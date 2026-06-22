package jmotley.com.jspades.engine

import android.content.Context
import jmotley.com.jspades.logging.PlayLogger
import jmotley.com.jspades.data.AchievementsRepo
import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.DealMode
import jmotley.com.jspades.data.GamePhase
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.data.Hand
import jmotley.com.jspades.data.Play
import jmotley.com.jspades.data.PlayerHandState
import jmotley.com.jspades.data.PlayerType
import jmotley.com.jspades.data.playerTypeById
import jmotley.com.jspades.data.Rank
import jmotley.com.jspades.data.Suit
import jmotley.com.jspades.data.AnimationEvent
import jmotley.com.jspades.data.toSnapshot
import jmotley.com.jspades.data.ReplayEvent
import jmotley.com.jspades.data.Score
import jmotley.com.jspades.data.effectiveMinBid
import jmotley.com.jspades.data.targetScore
import jmotley.com.jspades.models.GameViewModel
import jmotley.com.jspades.networking.PlayLogApi
import jmotley.com.jspades.networking.PlayLogGame
import jmotley.com.jspades.networking.PlayLogPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PhaseManager — the single entry point for all game-phase transitions.
 *
 * Only [execute] is public. Call it whenever the [GameState.phase] changes and
 * you want the engine to take over. CPU logic, background sleeping, and
 * fire-and-forget animations all originate here.
 *
 * Human-interactive phases (e.g. [GamePhase.BidHuman]) return immediately and
 * let the UI drive the next call to [execute] via a ViewModel action.
 *
 * @param viewModel Shared game state owner.
 * @param scope     Coroutine scope tied to the ViewModel's lifetime (viewModelScope).
 */
class PhaseManager(
    private val viewModel: GameViewModel,
    private val scope: CoroutineScope,
    private val context: Context
) {
    /**
     * Re-entrancy guard: ensures only one phase-handler coroutine is active at a time.
     * Concurrent execute() calls are dropped — the running handler chains to the next
     * phase when it finishes. Set to false in the finally block so the UI's subsequent
     * execute() call (after an animation delay) always finds it clear.
     */
    private val busy = AtomicBoolean(false)

    /**
     * Public entry point. Acquires the guard and dispatches the current phase.
     * Dropped if a handler coroutine is already running — only one is ever active.
     * External callers (ViewModel, UI) always use this.
     */
    fun execute() {
        if (!busy.compareAndSet(false, true)) return
        scope.launch {
            try {
                dispatch()
            } finally {
                busy.set(false)
            }
        }
    }

    /**
     * Called when the human confirms the bid review summary (OK button).
     * Bypasses handleBid and goes directly to the post-bid phase.
     */
    fun executeBidConfirmed() {
        if (!busy.compareAndSet(false, true)) return
        scope.launch {
            try {
                advanceAfterBidding()
            } finally {
                busy.set(false)
            }
        }
    }

    /**
     * Internal dispatch — calls the handler for the current phase.
     * Handlers that chain synchronously call this directly instead of [execute]
     * so they stay within the same coroutine and don't trip the re-entrancy guard.
     * Handlers that fire-and-forget simply return; the guard clears in [execute]'s
     * finally block and the UI's subsequent [execute] call resumes the engine.
     */
    private suspend fun dispatch() {
        when (viewModel.state.value.phase) {
            GamePhase.Lobby       -> handleLobby()
            GamePhase.Deal        -> handleDeal()
            GamePhase.DealHuman   -> handleDealHuman()
            GamePhase.DeuceReveal -> handleDeuceReveal()
            GamePhase.Bid         -> handleBid()
            GamePhase.BidHuman    -> { /* UI owns this phase — waits for submitBid() */ }
            GamePhase.BidMP       -> { /* adapter owns this phase — waits for Phase 5 applyRemoteBid() */ }
            GamePhase.BidReview   -> { /* UI owns this phase — waits for submitBidReview() */ }
            GamePhase.KittyReveal -> handleKittyReveal()
            GamePhase.Kitty       -> handleKitty()
            GamePhase.KittyHuman  -> { /* UI owns this phase — waits for kitty exchange */ }
            GamePhase.BlindBid          -> handleBlindBid()
            GamePhase.BlindBidHuman     -> { /* UI owns this phase — waits for submitHumanBlindBid() */ }
            GamePhase.BlindExchange     -> handleBlindExchange()
            GamePhase.BlindExchangeHuman -> { /* UI owns this phase — waits for submitHumanBlindExchange() */ }
            GamePhase.Trick       -> handleTrick()
            GamePhase.TrickHuman  -> { /* UI owns this phase — waits for playCard() */ }
            GamePhase.TrickMP     -> { /* adapter owns this phase — waits for Phase 5 applyRemotePlay() */ }
            GamePhase.TrickResolve-> handleTrickResolve()
            GamePhase.Score       -> handleScore()
            GamePhase.EndHand     -> handleEndHand()
            GamePhase.Finished    -> handleFinished()
        }
    }

    // ── Phase handlers ────────────────────────────────────────────────────────

    /** Lobby: entirely UI-driven. Engine waits for onLobbyComplete(). */
    private suspend fun handleLobby() {
        // no-op — LobbyView drives the reveal and calls viewModel.onLobbyComplete()
    }

    /**
     * Deal: build the deck according to [GameType], shuffle, distribute cards to players
     * (and kitty if applicable), store in phaseHands, then advance to DealHuman.
     */
    private suspend fun handleDeal() {
        val state = viewModel.state.value
        val gt    = state.gameType
        val ids   = state.players.map { it.id }

        // 1. Build base deck: TWO–ACE × all 4 suits
        val standardRanks = listOf(
            Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.SIX, Rank.SEVEN,
            Rank.EIGHT, Rank.NINE, Rank.TEN, Rank.JACK, Rank.QUEEN, Rank.KING, Rank.ACE
        )
        val deck: MutableList<Card> = Suit.entries.flatMap { suit ->
            standardRanks.map { rank -> Card(suit = suit, rank = rank) }
        }.toMutableList()

        // 2. Optionally append jokers
        if (gt.includeJokers) {
            deck += Card(suit = Suit.SPADES, rank = Rank.LITTLEJOKER)
            deck += Card(suit = Suit.SPADES, rank = Rank.BIGJOKER)
        }

        // 3. Optionally strip 2♥ and/or 2♣
        if (gt.removeTwoOfHearts) deck.removeIf { it.suit == Suit.HEARTS && it.rank == Rank.TWO }
        if (gt.removeTwoOfClubs)  deck.removeIf { it.suit == Suit.CLUBS  && it.rank == Rank.TWO }

        // 3b. Promote 2♠ to DEUCE rank (3rd/4th joker). Implied by twoOfDiamondsJoker as well.
        if (state.twoOfSpadesJoker || state.twoOfDiamondsJoker) {
            val idx = deck.indexOfFirst { it.suit == Suit.SPADES && it.rank == Rank.TWO }
            if (idx >= 0) deck[idx] = Card(suit = Suit.SPADES, rank = Rank.DEUCE)
        }

        // 3c. Promote 2♦ to WILDDEUCE rank (3rd joker, above 2♠). Internal suit becomes SPADES
        // so the card sorts into the trump group; assetFileName() still returns the 2♦ image.
        if (state.twoOfDiamondsJoker) {
            val idx = deck.indexOfFirst { it.suit == Suit.DIAMONDS && it.rank == Rank.TWO }
            if (idx >= 0) deck[idx] = Card(suit = Suit.SPADES, rank = Rank.WILDDEUCE)
        }

        // 4. Shuffle
        val shuffled = deck.shuffled().toMutableList()

        // 4b. Apply challenge-specific deal bias (if a challenge is active)
        val activeSk = AchievementsRepo.getActiveChallenge(context) ?: ""
        if (activeSk.isNotEmpty()) applyChallengeBias(shuffled, activeSk, ids, gt)

        // 5. Dispatch to the correct deal algorithm
        when (gt.dealMode) {
            DealMode.STANDARD            -> dealStandard(shuffled, ids, gt)
            DealMode.KITTY_TWO_OF_SPADES -> dealWithKitty(shuffled, ids, gt)
            DealMode.TWO_MAN_ALTERNATE   -> dealTwoManAlternate(shuffled, ids, gt)
        }

        // 6. Host broadcasts the deal to remote clients
        viewModel.broadcastDeal()

        // Log game type and dealt hands for debugging
        PlayLogger.logGameType(viewModel.state.value)
        PlayLogger.logDealtHands(viewModel.state.value)

        // Capture hand-start checkpoint and reset per-hand cheat flags
        viewModel.handStartSnapshot = viewModel.state.value.toSnapshot()

        viewModel.advancePhase(GamePhase.DealHuman)
        dispatch()
    }

    // ── Deal algorithms ───────────────────────────────────────────────────────

    /**
     * Sequential deal: each player gets [GameType.cardsPerPlayer] consecutive cards
     * from the shuffled deck, sorted by suit then rank.
     */
    private fun dealStandard(shuffled: List<Card>, ids: List<String>, gt: GameType) {
        val cpp = gt.cardsPerPlayer
        val dealt = ids.mapIndexed { i, id ->
            id to shuffled.subList(i * cpp, (i + 1) * cpp)
                .sortedWith(compareBy({ it.suit.ordinal }, { it.rank.ordinal }))
        }.toMap()
        val perPlayer = dealt.mapValues { (_, cards) -> PlayerHandState(hand = cards) }
        viewModel.applyDeal(Hand(playerOrder = ids, perPlayer = perPlayer))
    }

    /**
     * Kitty deal (TEAM_KITTY):
     * - Set aside the first [GameType.kittySize] cards as the kitty.
     * - Guarantee 2♠ is in a player's hand (not the kitty): if 2♠ ends up in the
     *   kitty slice, swap it with a random card from the remaining player cards.
     * - Deal [GameType.cardsPerPlayer] sorted cards to each player.
     * - Store the kitty hand via [GameViewModel.applyKitty].
     */
    private fun dealWithKitty(shuffled: MutableList<Card>, ids: List<String>, gt: GameType) {
        val kittySlice    = shuffled.take(gt.kittySize).toMutableList()
        val playerCards   = shuffled.drop(gt.kittySize).toMutableList()

        // Ensure 2♠ (Rank.TWO or Rank.DEUCE when twoOfSpadesJoker is on) is in a player's hand
        val kittyIdx = kittySlice.indexOfFirst {
            it.suit == Suit.SPADES && (it.rank == Rank.TWO || it.rank == Rank.DEUCE)
        }
        if (kittyIdx >= 0) {
            val swapIdx = playerCards.indices.random()
            val displaced = playerCards[swapIdx]
            playerCards[swapIdx] = kittySlice[kittyIdx]
            kittySlice[kittyIdx] = displaced
        }

        val cpp = gt.cardsPerPlayer
        val dealt = ids.mapIndexed { i, id ->
            id to playerCards.subList(i * cpp, (i + 1) * cpp)
                .sortedWith(compareBy({ it.suit.ordinal }, { it.rank.ordinal }))
        }.toMap()
        val perPlayer = dealt.mapValues { (_, cards) -> PlayerHandState(hand = cards) }

        viewModel.applyDeal(Hand(playerOrder = ids, perPlayer = perPlayer))
        viewModel.applyKitty(Hand(perPlayer = mapOf("kitty" to PlayerHandState(hand = kittySlice))))

        // Record which player holds the 2♠ (kitty winner)
        val kittyWinnerId = dealt.entries.firstOrNull { (_, cards) ->
            cards.any { it.suit == Suit.SPADES && (it.rank == Rank.TWO || it.rank == Rank.DEUCE) }
        }?.key
        if (kittyWinnerId != null) viewModel.setKittyWinner(kittyWinnerId)
    }

    /**
     * Two Man Solo alternate deal (interactive version):
     * ids[0] = dealer (south / human, picks second).
     * ids[1] = non-dealer (north / CPU, picks first).
     *
     * CPU takes its first pick automatically so the human sees the correct
     * top card when DealHuman begins. Subsequent picks are handled by
     * [GameViewModel.applyDealPick] as the human taps Keep / Skip in the UI.
     */
    /**
     * Two Man Solo alternate deal (interactive version).
     *
     * The non-dealer (leaderIndex player) picks first each round.
     * If the CPU is the non-dealer, give it one automatic pick so the human sees
     * the correct top card when DealHuman begins.
     * If the human is the non-dealer, both hands start empty — the UI drives
     * the first pick and [GameViewModel.applyDealPick] handles the CPU's reply pick.
     */
    private fun dealTwoManAlternate(shuffled: MutableList<Card>, ids: List<String>, gt: GameType) {
        val deck = shuffled.toMutableList()
        val s    = viewModel.state.value
        val nonDealerIsHuman = s.players.getOrNull(s.leaderIndex)?.playerType == PlayerType.HUMAN

        val cpuHand = mutableListOf<Card>()
        if (!nonDealerIsHuman) pickOneCard(deck, cpuHand) // CPU is non-dealer: pre-pick

        val perPlayer = mapOf(
            ids[0] to PlayerHandState(),   // south — empty until pick UI runs
            ids[1] to PlayerHandState(
                hand = cpuHand.sortedWith(compareBy({ it.suit.ordinal }, { it.rank.ordinal }))
            )
        )
        viewModel.applyDeal(Hand(playerOrder = ids, perPlayer = perPlayer))
        viewModel.storeDeck(deck)
    }

    /**
     * Applies a challenge-specific deal bias to the shuffled deck in-place.
     *
     * For STANDARD / KITTY deals, moves target cards into the section owned by the
     * intended player. Section layout: kittyOffset + playerIndex × cardsPerPlayer.
     *
     * For TWO_MAN_ALTERNATE, places target cards at the human's first viewing positions
     * (indices 2, 6, 10 … in the original shuffled list, since the CPU auto-consumes
     * positions 0–1 before the human's first pick).
     *
     * No-ops silently if a target card is not present in the deck (e.g. no jokers in Classic).
     */
    private fun applyChallengeBias(
        shuffled: MutableList<Card>,
        activeSk: String,
        ids: List<String>,
        gt: GameType
    ) {
        val cpp      = gt.cardsPerPlayer
        val kOff     = gt.kittySize                                       // 0 for non-kitty
        val humanIdx = ids.indexOf("south").takeIf { it >= 0 } ?: 0
        val partnerIdx = ids.indexOf("north").takeIf { it >= 0 && gt.useTeams } ?: -1
        val opp1Idx  = ids.indices.firstOrNull { it != humanIdx && it != partnerIdx } ?: -1
        val opp2Idx  = ids.indices.lastOrNull  { it != humanIdx && it != partnerIdx && it != opp1Idx } ?: -1

        // Move a card into a player's section. Avoids displacing any card with the
        // same rank that was already placed (critical when placing all 4 of a rank).
        fun moveToSection(rank: Rank, suit: Suit, sectionIdx: Int) {
            if (sectionIdx < 0) return
            val sStart = kOff + sectionIdx * cpp
            val sEnd   = kOff + (sectionIdx + 1) * cpp
            if (sEnd > shuffled.size) return
            val from = shuffled.indexOfFirst { it.rank == rank && it.suit == suit }
            if (from < 0 || from in sStart until sEnd) return
            val to = (sStart until sEnd).firstOrNull { i -> i != from && shuffled[i].rank != rank } ?: return
            val tmp = shuffled[from]; shuffled[from] = shuffled[to]; shuffled[to] = tmp
        }

        // Move a card away from the human's section into opp1's section.
        fun moveFromHuman(rank: Rank, suit: Suit) {
            val from = shuffled.indexOfFirst { it.rank == rank && it.suit == suit }
            if (from < 0) return
            val humanStart = kOff + humanIdx * cpp
            if (from !in humanStart until humanStart + cpp) return
            if (opp1Idx < 0) return
            val opp1Start = kOff + opp1Idx * cpp
            val opp1End   = opp1Start + cpp
            if (opp1End > shuffled.size) return
            val to = (opp1Start until opp1End).firstOrNull { it != from } ?: return
            val tmp = shuffled[from]; shuffled[from] = shuffled[to]; shuffled[to] = tmp
        }

        // TWO_MAN_ALTERNATE: put target cards at human viewing slots (2, 6, 10 …).
        if (gt.dealMode == DealMode.TWO_MAN_ALTERNATE) {
            val targets: List<Pair<Rank, Suit>> = when (activeSk) {
                "two_man_boston", "get_all_two_solo" ->
                    listOf(Rank.ACE to Suit.SPADES, Rank.KING to Suit.SPADES)
                "walk_4_ladies" ->
                    Suit.values().map { Rank.QUEEN to it }
                "get_5_no_trump_two_solo" ->
                    listOf(Rank.ACE to Suit.HEARTS, Rank.ACE to Suit.DIAMONDS, Rank.ACE to Suit.CLUBS)
                else -> emptyList()
            }
            var slot = 2
            for ((rank, suit) in targets) {
                if (slot >= shuffled.size) break
                val from = shuffled.indexOfFirst { it.rank == rank && it.suit == suit }
                if (from >= 0 && from != slot) {
                    val tmp = shuffled[from]; shuffled[from] = shuffled[slot]; shuffled[slot] = tmp
                }
                slot += 4
            }
            return
        }

        val hasBigJoker = shuffled.any { it.rank == Rank.BIGJOKER }

        when (activeSk) {
            "walk_all_kings", "all_kings_walk" ->
                Suit.values().forEach { moveToSection(Rank.KING, it, humanIdx) }

            "no_aces_team", "all_aces_walk" ->
                Suit.values().forEach { moveToSection(Rank.ACE, it, humanIdx) }

            "run_boston", "get_all_books", "kitty_boston",
            "big_joker_leads", "run_boston_alone", "by_yourself_kitty",
            "three_man_boston" -> {
                if (hasBigJoker) moveToSection(Rank.BIGJOKER, Suit.SPADES, humanIdx)
                else moveToSection(Rank.ACE, Suit.SPADES, humanIdx)
            }

            "run_boston_partner_bid" -> {
                if (hasBigJoker) moveToSection(Rank.BIGJOKER, Suit.SPADES, humanIdx)
                else moveToSection(Rank.ACE, Suit.SPADES, humanIdx)
            }

            "boston_no_jokers" -> {
                // Big Joker → partner, Little Joker → opp1, neither stays with human
                if (partnerIdx >= 0) moveToSection(Rank.BIGJOKER, Suit.SPADES, partnerIdx)
                if (opp1Idx >= 0)    moveToSection(Rank.LITTLEJOKER, Suit.SPADES, opp1Idx)
            }

            "nil_success", "blind_nil", "nil_partner_boston" -> {
                // Remove high trump from human's hand so nil is achievable
                moveFromHuman(Rank.BIGJOKER, Suit.SPADES)
                moveFromHuman(Rank.LITTLEJOKER, Suit.SPADES)
                moveFromHuman(Rank.DEUCE, Suit.SPADES)
                moveFromHuman(Rank.ACE, Suit.SPADES)
            }

            "no_jokers" -> {
                if (gt.kittySize > 0) {
                    // Move both jokers into the kitty section [0..kittySize-1]
                    for (rank in listOf(Rank.BIGJOKER, Rank.LITTLEJOKER)) {
                        val from = shuffled.indexOfFirst { it.rank == rank }
                        if (from < 0 || from < gt.kittySize) continue
                        val to = (0 until gt.kittySize).firstOrNull() ?: continue
                        val tmp = shuffled[from]; shuffled[from] = shuffled[to]; shuffled[to] = tmp
                    }
                } else {
                    if (opp1Idx >= 0) moveToSection(Rank.BIGJOKER, Suit.SPADES, opp1Idx)
                    if (opp2Idx >= 0) moveToSection(Rank.LITTLEJOKER, Suit.SPADES, opp2Idx)
                    else if (opp1Idx >= 0) moveToSection(Rank.LITTLEJOKER, Suit.SPADES, opp1Idx)
                }
            }

            "no_red_wins" -> {
                if (hasBigJoker) {
                    moveToSection(Rank.BIGJOKER, Suit.SPADES, humanIdx)
                    moveToSection(Rank.LITTLEJOKER, Suit.SPADES, humanIdx)
                }
            }

            // All other challenges use a random deal — no bias needed.
        }
    }

    /**
     * Pick exactly one card for [hand] from the top of [deck], consuming two cards.
     * Decision is random (50/50 keep-top vs keep-second).
     */
    private fun pickOneCard(deck: MutableList<Card>, hand: MutableList<Card>) {
        if (deck.size < 2) return
        val top  = deck.removeAt(0)
        val next = deck.removeAt(0)
        if (kotlin.random.Random.nextBoolean()) hand.add(top) else hand.add(next)
    }

    /**
     * DealHuman:
     * - All modes: advance to BlindBid if blind decisions haven't been made yet.
     *   For TWO_MAN_ALTERNATE, after blind decisions are made, the UI shows DealPickView.
     * - Non-TWO_MAN_ALTERNATE: after all blind decisions are made, emit DealComplete.
     */
    private suspend fun handleDealHuman() {
        val s = viewModel.state.value
        val allBlindDecided = s.players.all { it.runtimeFlags.didBlindDecide }

        if (s.gameType.dealMode == DealMode.TWO_MAN_ALTERNATE) {
            if (!allBlindDecided) {
                viewModel.advancePhase(GamePhase.BlindBid)
                dispatch()
            }
            // else: UI shows DealPickView; applyDealPick() advances to Bid when done
            return
        }

        if (!allBlindDecided) {
            viewModel.advancePhase(GamePhase.BlindBid)
            dispatch()
            return
        }

        // All blind decisions made — emit DealComplete to reveal cards
        val cardCount = s.phaseHands[GamePhase.Deal]?.lastOrNull()
            ?.perPlayer?.values?.firstOrNull()?.hand?.size ?: 13
        viewModel.emitAnimation(AnimationEvent.DealComplete(cardCount))
    }

    /**
     * BlindBid: each player (in clockwise order from leaderIndex) decides whether to bid blind.
     * CPUs that are 100+ points behind decide randomly (33% chance).
     * Human player: advance to BlindBidHuman if eligible; auto-skip if not.
     * After all players have decided: emit DealComplete (or return to DealHuman for TWO_MAN_ALTERNATE).
     */
    private suspend fun handleBlindBid() {
        val s = viewModel.state.value
        val n = s.players.size

        // Host broadcasts blind offer once, before any per-player decisions are processed.
        val noneDecidedYet = s.players.none { it.runtimeFlags.didBlindDecide }
        if (noneDecidedYet && viewModel.isMPHost && s.gameType.useTeams) {
            val canonicalIds = listOf("south", "west", "north", "east")
            for (teamId in listOf(0, 1)) {
                val myScore  = (s.score.points[teamId.toString()] ?: 0) + (s.score.bags[teamId.toString()] ?: 0)
                val oppScore = (s.score.points[(1 - teamId).toString()] ?: 0) + (s.score.bags[(1 - teamId).toString()] ?: 0)
                if (oppScore - myScore < 100) continue
                val teamPlayers = s.players.filter { it.team == teamId }
                val teamRoomSeats = teamPlayers.mapNotNull { p ->
                    val idx = canonicalIds.indexOf(p.id)
                    if (idx >= 0) viewModel.canonicalIdxToRoomSeat(idx) else null
                }
                val decidingRoomSeats = teamPlayers
                    .filter { it.playerType == PlayerType.HUMAN || it.playerType == PlayerType.MP }
                    .mapNotNull { p ->
                        val idx = canonicalIds.indexOf(p.id)
                        if (idx >= 0) viewModel.canonicalIdxToRoomSeat(idx) else null
                    }
                viewModel.broadcastBlindOffer(teamRoomSeats, decidingRoomSeats)
                break  // at most one team can be 100 behind at a time
            }
        }

        for (offset in 0 until n) {
            val player = s.players[(s.leaderIndex + offset) % n]
            if (player.runtimeFlags.didBlindDecide) continue

            if (player.playerType == PlayerType.HUMAN) {
                // Check eligibility before showing UI
                val eligible = if (s.gameType.useTeams) {
                    val myScore  = (s.score.points[player.team.toString()] ?: 0) + (s.score.bags[player.team.toString()] ?: 0)
                    val oppScore = (s.score.points[(1 - player.team).toString()] ?: 0) + (s.score.bags[(1 - player.team).toString()] ?: 0)
                    oppScore - myScore >= 100
                } else {
                    val myScore  = s.score.points[player.id] ?: 0
                    val topScore = s.players.filter { it.id != player.id }
                        .maxOfOrNull { p -> s.score.points[p.id] ?: 0 } ?: 0
                    topScore - myScore >= 100
                }
                if (eligible) {
                    viewModel.advancePhase(GamePhase.BlindBidHuman)
                    dispatch()
                    return
                } else {
                    viewModel.markBlindDecision(player.id)
                    continue
                }
            }

            // CPU player
            val hand = getPlayerHand(s, player.id)
            val blindResult = BidEngine.shouldCpuBidBlind(hand, player, s)
            if (blindResult != null) {
                viewModel.submitBid(player.id, blindResult.bid, blindResult.isBlind)
            }
            viewModel.broadcastCPUBlindResponse(player.id, blindResult != null)
            viewModel.markBlindDecision(player.id)
        }

        // All players have decided — reveal cards
        if (s.gameType.dealMode == DealMode.TWO_MAN_ALTERNATE) {
            viewModel.advancePhase(GamePhase.DealHuman)
            dispatch()
        } else {
            val cardCount = s.phaseHands[GamePhase.Deal]?.lastOrNull()
                ?.perPlayer?.values?.firstOrNull()?.hand?.size ?: 13
            viewModel.emitAnimation(AnimationEvent.DealComplete(cardCount))
        }
    }

    /**
     * BlindExchange: for TEAM_CLASSIC with allowBlindExchange enabled.
     * Teams with at least one blind bidder exchange 2 cards.
     * CPU-only teams: auto-compute and apply exchange.
     * Human-involved teams: advance to BlindExchangeHuman and wait.
     * After all teams exchanged (or no exchange needed): advance to Bid.
     */
    private suspend fun handleBlindExchange() {
        val s = viewModel.state.value
        if (!s.allowBlindExchange || s.gameType != GameType.TEAM_CLASSIC) {
            viewModel.advancePhase(GamePhase.Bid)
            dispatch()
            return
        }

        val dealHand = s.phaseHands[GamePhase.Deal]?.lastOrNull()

        for (teamId in listOf(0, 1)) {
            val teamPlayers = s.players.filter { it.team == teamId }
            if (teamPlayers.size != 2) continue

            val p1 = teamPlayers[0]
            val p2 = teamPlayers[1]
            val p1Phs = dealHand?.perPlayer?.get(p1.id)
            val p2Phs = dealHand?.perPlayer?.get(p2.id)

            val anyBlind = (p1Phs?.isBlind == true) || (p2Phs?.isBlind == true)
            if (!anyBlind) continue

            // Skip if already exchanged
            if (p1.runtimeFlags.didBlindExchange && p2.runtimeFlags.didBlindExchange) continue

            val humanOnTeam = teamPlayers.any { it.playerType == PlayerType.HUMAN }
            if (humanOnTeam) {
                viewModel.advancePhase(GamePhase.BlindExchangeHuman)
                dispatch()
                return
            }

            // CPU-only team: auto-compute exchange
            val p1Hand = p1Phs?.hand ?: emptyList()
            val p2Hand = p2Phs?.hand ?: emptyList()
            val p1IsBlind = p1Phs?.isBlind == true
            val p2IsBlind = p2Phs?.isBlind == true

            val cardsFrom1 = PlayEngine.selectBlindExchangeCards(p1Hand, p1IsBlind, p2IsBlind)
            val cardsFrom2 = PlayEngine.selectBlindExchangeCards(p2Hand, p2IsBlind, p1IsBlind)

            viewModel.applyBlindExchange(p1.id, p2.id, cardsFrom1, cardsFrom2)
            viewModel.markBlindExchange(p1.id)
            viewModel.markBlindExchange(p2.id)
        }

        viewModel.advancePhase(GamePhase.Bid)
        dispatch()
    }

    private suspend fun handleDeuceReveal() {
        val winnerId = viewModel.state.value.kittyWinnerId ?: return
        // Stay in DeuceReveal — emit event; animation collector delays 2 seconds then advances to Bid.
        viewModel.emitAnimation(AnimationEvent.DeuceRevealed(winnerId))
    }

    /**
     * Bid: dispatch to the correct bid flow based on game type.
     *
     * House Rules uses a team-based flow (non-dealer team first, dealer's team second).
     * All other game types use a per-player clockwise flow starting from leaderIndex.
     */
    private suspend fun handleBid() {
        val gt = viewModel.state.value.gameType
        if (gt == GameType.HOUSE_RULES || gt == GameType.TEAM_KITTY) {
            handleBidHouseRules()
        } else {
            handleBidIndividual()
        }
    }

    /**
     * Individual bid flow (Classic, Kitty, Solo variants).
     *
     * Fire-and-forget: one player bids per execute() call.
     * CPU → compute bid → emit BidPlaced → return.
     * Human → advance to BidHuman → execute() → return.
     * All done → finalize teams → advanceAfterBidding.
     */
    private suspend fun handleBidIndividual() {
        val s = viewModel.state.value
        val n = s.players.size
        for (offset in 0 until n) {
            val player = s.players[(s.leaderIndex + offset) % n]
            if (!player.runtimeFlags.didBid) {
                if (player.playerType == PlayerType.HUMAN) {
                    // Challenge may lock south out of bidding (e.g. run_boston_partner_bid)
                    val allowBid = AchievementsRepo.getActiveChallengeAllowBid(context)
                    if (allowBid != null && allowBid != "south") {
                        viewModel.submitBid("south", 0, false)
                        viewModel.emitAnimation(AnimationEvent.BidPlaced("south", 0))
                        return
                    }
                    viewModel.advancePhase(GamePhase.BidHuman)
                    dispatch()
                    return
                }
                if (player.playerType == PlayerType.MP) {
                    viewModel.advancePhase(GamePhase.BidMP)
                    dispatch()
                    return
                }
                val hand   = getPlayerHand(s, player.id)
                val result = BidEngine.computeCpuBid(
                    hand          = hand,
                    player        = player,
                    state         = s,
                    isKittyWinner = s.kittyWinnerId == player.id
                )
                viewModel.submitBid(player.id, result.bid, result.isBlind)
                viewModel.broadcastCPUBid(player.id, result.bid, result.isBlind)
                // Fire-and-forget: emit event, return; animation completion calls execute()
                viewModel.emitAnimation(AnimationEvent.BidPlaced(player.id, result.bid))
                return
            }
        }
        // All players have bid — compute team totals, then wait for human OK
        if (s.gameType.useTeams) finalizeTeamBids()
        viewModel.advancePhase(GamePhase.BidReview)
    }

    /**
     * House Rules team bid flow.
     *
     * Non-dealer team bids first; dealer's team bids second.
     * Fire-and-forget: one CPU bid per execute() call.
     * CPU-only teams: sum individual bids after all have bid (idempotent on re-entry).
     * Human's team: hand off to BidHuman when all CPU partners have bid.
     */
    private suspend fun handleBidHouseRules() {
        val s          = viewModel.state.value
        val n          = s.players.size
        val dealerIdx  = (s.leaderIndex - 1 + n) % n
        val dealerTeam = s.players[dealerIdx].team
        val teamOrder  = listOf(1 - dealerTeam, dealerTeam) // non-dealer first

        for (teamId in teamOrder) {
            val teamPlayers    = s.players.filter { it.team == teamId }
            val humanOnTeam    = teamPlayers.any { it.playerType == PlayerType.HUMAN }
            val humanAlreadyBid = s.players.find { it.playerType == PlayerType.HUMAN }?.runtimeFlags?.didBid ?: false

            // Check if this team is already committed to a blind bid (set by a previous CPU on the team)
            val teamAlreadyBlind = s.phaseHands[GamePhase.Deal]?.lastOrNull()
                ?.teamBlind?.getOrNull(teamId) ?: false

            if (teamAlreadyBlind) {
                // Blind already committed — auto-fill any remaining unbidd players and skip BidHuman
                val remaining = teamPlayers.filter { !it.runtimeFlags.didBid }
                if (remaining.isNotEmpty()) {
                    remaining.forEach { p -> viewModel.submitBid(p.id, 0, false) }
                    viewModel.emitAnimation(AnimationEvent.BidPlaced(remaining.first().id, 0))
                    return
                }
                // fall through to next team
            } else {
                // Find the next unbidd CPU player on this team
                val nextCpu = teamPlayers.firstOrNull { !it.runtimeFlags.didBid && it.playerType == PlayerType.CPU }
                if (nextCpu != null) {
                    val hand   = getPlayerHand(s, nextCpu.id)
                    val result = BidEngine.computeCpuBid(hand, nextCpu, s)

                    if (result.isBlind) {
                        // Team goes blind: commit combined bid of 7, auto-fill all teammates
                        viewModel.submitBid(nextCpu.id, 0, false)
                        viewModel.broadcastCPUBid(nextCpu.id, 7, isBlind = true)
                        teamPlayers.filter { it.id != nextCpu.id }.forEach { p ->
                            viewModel.submitBid(p.id, 0, false)
                            viewModel.broadcastCPUBid(p.id, 0, isBlind = false)
                        }
                        viewModel.setTeamBid(teamId, 7)
                        viewModel.setTeamBlind(teamId, true)
                        viewModel.emitAnimation(AnimationEvent.BidPlaced(nextCpu.id, 7))
                        return
                    }

                    viewModel.submitBid(nextCpu.id, result.bid, result.isBlind)
                    viewModel.broadcastCPUBid(nextCpu.id, result.bid, result.isBlind)
                    // Fire-and-forget: one CPU bid per execute() call
                    viewModel.emitAnimation(AnimationEvent.BidPlaced(nextCpu.id, result.bid))
                    return
                }

                // Yield for the next unbidd MP player on this team
                val nextMp = teamPlayers.firstOrNull { !it.runtimeFlags.didBid && it.playerType == PlayerType.MP }
                if (nextMp != null) {
                    viewModel.advancePhase(GamePhase.BidMP)
                    dispatch()
                    return
                }

                // All CPUs and MP players on this team have bid
                if (humanOnTeam && !humanAlreadyBid) {
                    val allowBid = AchievementsRepo.getActiveChallengeAllowBid(context)
                    if (allowBid != null && allowBid != "south") {
                        viewModel.submitBid("south", 0, false)
                        viewModel.emitAnimation(AnimationEvent.BidPlaced("south", 0))
                        return
                    }
                    viewModel.advancePhase(GamePhase.BidHuman)
                    dispatch()
                    return
                }

                if (!humanOnTeam) {
                    // CPU-only team — sum individual bids and store team total (idempotent)
                    val teamBid = teamPlayers.sumOf { player ->
                        s.phaseHands[GamePhase.Deal]?.lastOrNull()
                            ?.perPlayer?.get(player.id)?.bid ?: 0
                    }.coerceAtLeast(s.effectiveMinBid)
                    viewModel.setTeamBid(teamId, teamBid)
                }
                // fall through to next team
            }
        }

        viewModel.advancePhase(GamePhase.BidReview)
    }

    /**
     * Compute and store team bids from individual player bids (Classic / Kitty).
     * For Kitty, caps the team total at 12 and floors at minimumBid (5).
     * For TEAM_KITTY, the human's team bid is already set via submitHumanTeamBid —
     * skip that team so we don't double-count by summing individual bids.
     */
    private fun finalizeTeamBids() {
        val s    = viewModel.state.value
        val hand = s.phaseHands[GamePhase.Deal]?.lastOrNull() ?: return
        val isKitty = s.gameType == GameType.TEAM_KITTY

        for (teamId in listOf(0, 1)) {
            // TEAM_KITTY: skip teams whose bid was already entered as a team total
            if (isKitty && (hand.teamBids.getOrNull(teamId) ?: 0) != 0) continue
            val teamPlayerIds = s.players.filter { it.team == teamId }.map { it.id }
            var teamTotal = teamPlayerIds.sumOf { id -> hand.perPlayer[id]?.bid ?: 0 }
            if (isKitty) teamTotal = teamTotal.coerceIn(s.effectiveMinBid, 12)
            viewModel.setTeamBid(teamId, teamTotal)
        }
        // Log finalized team/player bids
        PlayLogger.logBids(viewModel.state.value)
    }

    /** Advance from bidding to Trick — kitty is always resolved before bidding. */
    private suspend fun advanceAfterBidding() {
        viewModel.advancePhase(GamePhase.Trick)
        dispatch()
    }

    // ── Bid utility ───────────────────────────────────────────────────────────

    /** Returns the dealt cards for [playerId] from the current Deal-phase hand. */
    private fun getPlayerHand(s: GameState, playerId: String): List<Card> =
        s.phaseHands[GamePhase.Deal]?.lastOrNull()?.perPlayer?.get(playerId)?.hand
            ?: emptyList()

    /**
     * KittyReveal: show the kitty cards briefly, then route to the correct exchange phase.
     * Human kitty winner → KittyHuman (UI takes over).
     * CPU kitty winner  → Kitty (engine handles the discard automatically).
     */
    private suspend fun handleKittyReveal() {
        val winnerId = viewModel.state.value.kittyWinnerId ?: return
        // Stay in KittyReveal — emit the event and let the animation collector
        // delay 2 seconds, then advance to KittyHuman (human) or Kitty (CPU).
        viewModel.emitAnimation(AnimationEvent.KittyRevealed(winnerId))
    }

    /**
     * Kitty (CPU kitty exchange): merge kitty cards into the winner's hand,
     * compute the optimal discard via PlayEngine, apply it, then advance to Trick.
     */
    private suspend fun handleKitty() {
        val s        = viewModel.state.value
        val winnerId = s.kittyWinnerId
        if (winnerId != null) {
            val kittyCards = s.kitty?.perPlayer?.values?.flatMap { it.hand }.orEmpty()
            val playerHand = getPlayerHand(s, winnerId)
            val discards   = PlayEngine.computeKittyDiscard(playerHand, kittyCards, kittyCards.size)
            viewModel.applyKittyExchange(winnerId, discards)
            // Kitty winner is awarded one free trick before play begins
            viewModel.awardTrick(winnerId)
        }
        // Exchange is pure computation — advance directly, no delay needed
        viewModel.advancePhase(GamePhase.Bid)
        dispatch()
    }

    /**
     * Trick: advance the trick one play at a time.
     *
     * Fire-and-forget: one CPU card play per execute() call.
     * Human → switch to TrickHuman → execute() → return (UI takes over).
     * CPU  → select card → play → emit CardPlayed → return.
     * Trick complete → advance to TrickResolve before emitting so the
     * animation callback re-enters handleTrickResolve.
     */
    private suspend fun handleTrick() {
        val s          = viewModel.state.value
        val n          = s.players.size

        // If the human just played (South's card is the most recent play), pause so
        // South's card animation has time to land before the next CPU reacts.
        // plays[] is filled in sequence order, so plays[playedCount-1] = last play.
        val playedCount = s.currentTrick.plays.count { it != null }

        // Shift trick checkpoints only at the start of a new trick (before anyone has played)
        if (playedCount == 0) {
            viewModel.previousTrickStartSnapshot = viewModel.currentTrickStartSnapshot
            viewModel.currentTrickStartSnapshot = viewModel.state.value.toSnapshot()
        }

        if (playedCount > 0 && s.playerTypeById(s.currentTrick.plays[playedCount - 1]?.playerId ?: "") == PlayerType.HUMAN) {
            delay(1000)
        }

        // Guard: if the trick is already complete (e.g. human played last),
        // go straight to TrickResolve instead of selecting another card.
        if (s.currentTrick.plays.count { it != null } == n) {
            viewModel.advancePhase(GamePhase.TrickResolve)
            dispatch()
            return
        }

        val nextPlayer = nextPlayerInTrick(s.currentTrick.plays, s.players.map { it.id }, s.leaderIndex)

        if (s.playerTypeById(nextPlayer) == PlayerType.HUMAN) {
            viewModel.advancePhase(GamePhase.TrickHuman)
            dispatch()
            return
        }

        if (s.playerTypeById(nextPlayer) == PlayerType.MP) {
            viewModel.advancePhase(GamePhase.TrickMP)
            dispatch()
            return
        }

        val card = PlayEngine.selectCard(nextPlayer, s)
        viewModel.broadcastCPUPlay(nextPlayer, card)  // before playCard: trickPlayNum uses pre-play slot count
        viewModel.playCard(nextPlayer, card)
        viewModel.removeCardFromHand(nextPlayer, card)

        val trickComplete = viewModel.state.value.currentTrick.plays.count { it != null } == n
        if (trickComplete) {
            viewModel.advancePhase(GamePhase.TrickResolve)
        }
        // Fire-and-forget: emit card play event; animation completion calls execute()
        viewModel.emitAnimation(AnimationEvent.CardPlayed(nextPlayer, card))
    }

    /**
     * TrickResolve: compute the winner, update void/cutting flags, award trick,
     * collect cards, advance to the next phase, then fire TrickWon (fire-and-forget).
     *
     * Phase is advanced BEFORE emitting so the animation callback re-enters
     * handleTrick or handleScore directly.
     * [plays] snapshot is captured before collectTrick clears the trick.
     */
    private suspend fun handleTrickResolve() {
        val s     = viewModel.state.value
        val plays = s.currentTrick.plays.filterNotNull()
        if (plays.isEmpty()) { dispatch(); return }

        // Log completed trick plays
        PlayLogger.logTrick(plays)

        val leadPlay   = plays.first()
        val leadCard   = leadPlay.card
        val leaderId   = leadPlay.playerId
        val winnerPlay = PlayEngine.computeTrickWinner(s.currentTrick.plays)
        val winnerId   = winnerPlay.playerId

        // Break spades if trump was played on a non-trump lead (Classic gate)
        if (!isTrump(leadCard) && !s.spadesBroken) {
            val anyTrumpCut = plays.any { it.playerId != leaderId && isTrump(it.card) }
            if (anyTrumpCut) viewModel.breakSpades()
        }

        // Update void-tracking and first-throwoff signal for non-leader plays
        for (play in plays) {
            if (play.playerId == leaderId) continue
            val followedSuit = if (isTrump(leadCard)) isTrump(play.card)
                               else play.card.suit == leadCard.suit && !isTrump(play.card)
            if (!followedSuit) {
                if (!isTrump(leadCard)) viewModel.markCutting(play.playerId, leadCard.suit)
                if (!isTrump(play.card)) viewModel.markSuitFirstThrowOff(play.playerId, play.card.suit)
            }
        }

        viewModel.awardTrick(winnerId)

        // Evaluate per-trick challenge constraints before collecting (currentTrick still intact)
        for (result in ChallengeEvaluation.evaluatePerTrick(viewModel.state.value, context)) {
            viewModel.emitChallengeResult(result)
        }

        // Check video triggers after awarding but before collecting (plays still available)
        checkVideoTriggers(plays, winnerId)

        val winnerIndex = s.players.indexOfFirst { it.id == winnerId }.takeIf { it >= 0 } ?: s.leaderIndex
        viewModel.collectTrick(winnerIndex)

        // Determine next phase and advance before emitting (so callback re-enters cleanly)
        val fresh      = viewModel.state.value
        val handsEmpty = fresh.phaseHands[GamePhase.Deal]?.lastOrNull()
            ?.perPlayer?.values?.all { phs -> phs.hand.isEmpty() } == true
        viewModel.advancePhase(if (handsEmpty) GamePhase.Score else GamePhase.Trick)

        // Fire-and-forget: emit winner event with play snapshot; animation completion calls execute()
        viewModel.emitAnimation(AnimationEvent.TrickWon(winnerId, plays))
    }

    private fun isTrump(card: Card): Boolean =
        card.suit == Suit.SPADES || card.rank == Rank.LITTLEJOKER || card.rank == Rank.BIGJOKER

    /**
     * Score: compute and apply this hand's points and bags, then advance to EndHand.
     *
     * Team games:  scored per team (keys "0"/"1" in [Score]).
     * Solo games:  scored per player (keys = player ids).
     * Nil bid (0): +100 if no tricks taken, −100 otherwise (blind doubles to ±200).
     * Regular bid: made → +bid×10; set → −bid×10. Blind or bid≥10 (optional) → ×20.
     * Bags:        each over-trick = 1 bag; every 10 bags (optional) = −100 penalty.
     */
    /**
     * Score: compute and apply this hand's points and bags, check the win condition,
     * then advance to [GamePhase.Finished] (game over) or [GamePhase.EndHand] (continue).
     *
     * Win conditions (losing floor = −targetScore):
     *   - Any team/player reaches ≥ targetScore AND leads all others → winner.
     *   - Any team/player drops to ≤ −targetScore AND trails all others → eliminated, others win.
     * Tie at exactly targetScore → continue until the tie is broken.
     */
    private suspend fun handleScore() {
        val s    = viewModel.state.value
        val hand = s.phaseHands[GamePhase.Deal]?.lastOrNull()

        // Evaluate per-hand challenge constraints before finalizing replay
        // (replayEvents must still be intact for trick history reconstruction)
        for (result in ChallengeEvaluation.evaluatePerHand(s, context)) {
            viewModel.emitChallengeResult(result)
        }

        viewModel.finalizeHandReplay()
        if (hand != null) {
            // Bag forgiveness: offer before scoring when human team is already at >= 8 bags.
            // Only offered once per game, and never during challenge mode.
            if (!viewModel.usedBagForgivenessThisGame && AchievementsRepo.getActiveChallenge(context) == null) {
                val humanBagKey = s.players.firstOrNull { it.playerType == PlayerType.HUMAN }?.let { p ->
                    if (s.gameType.useTeams) p.team.toString() else p.id
                }
                val currentBags = if (humanBagKey != null) s.score.bags[humanBagKey] ?: 0 else 0
                if (currentBags >= 8) {
                    viewModel.emitBagForgivenessOffer()
                    if (viewModel.awaitBagForgivenessResponse()) {
                        viewModel.applyBagForgiveness("south")
                    }
                }
            }
            // Score using the (possibly bag-reduced) state
            val scoringState = viewModel.state.value
            val delta = scoreHand(scoringState, hand)
            viewModel.applyScore(delta)
            // Log hand score delta and final totals
            PlayLogger.logHandScore(hand, delta, viewModel.state.value.score)
            AchievementsRepo.checkAndAwardPerHand(context, s, hand)
        }

        // Win check on updated totals
        val u = viewModel.state.value
        val totals: Map<String, Int> = if (u.gameType.useTeams) {
            mapOf(
                "0" to ((u.score.points["0"] ?: 0) + (u.score.bags["0"] ?: 0)),
                "1" to ((u.score.points["1"] ?: 0) + (u.score.bags["1"] ?: 0))
            )
        } else {
            u.players.associate { p ->
                p.id to ((u.score.points[p.id] ?: 0) + (u.score.bags[p.id] ?: 0))
            }
        }
        val high = totals.values.maxOrNull() ?: 0
        val low  = totals.values.minOrNull() ?: 0
        viewModel.advancePhase(if (high >= u.targetScore || low <= -u.targetScore) GamePhase.Finished else GamePhase.EndHand)
        dispatch()
    }

    /** EndHand: no-op — UI shows EndHandView and waits for [GameViewModel.onNextHand]. */
    private suspend fun handleEndHand() { /* UI owns this phase */ }

    /**
     * Compute the point and bag deltas for a completed hand.
     * Returns a [Score] whose values are signed deltas to be added to the running total.
     */
    private fun scoreHand(s: GameState, hand: Hand): Score {
        val pointsDelta = mutableMapOf<String, Int>()
        val bagsDelta   = mutableMapOf<String, Int>()
        val breakdown   = mutableMapOf<String, Int>()

        if (s.gameType.useTeams) {
            for (teamId in listOf(0, 1)) {
                val key         = teamId.toString()
                val teamPlayers = s.players.filter { it.team == teamId }
                val currentBags = s.score.bags[key] ?: 0

                // Nil+partner case: one player bid nil, partner has a positive bid.
                // Only applies when nil bids are allowed (TEAM_CLASSIC) and the team has 2 players.
                val nilPlayer = if (s.allowNilBid && teamPlayers.size == 2)
                    teamPlayers.find { p -> hand.perPlayer[p.id]?.bid == 0 }
                else null

                if (nilPlayer != null) {
                    val partner    = teamPlayers.first { it.id != nilPlayer.id }
                    val nilPhs     = hand.perPlayer[nilPlayer.id] ?: PlayerHandState()
                    val partnerPhs = hand.perPlayer[partner.id]   ?: PlayerHandState()
                    // Team trick total: partner's bid is measured against the full team count.
                    // Nil player's stolen tricks count toward the team total but score them -100.
                    val teamTricks = teamPlayers.sumOf { p -> hand.perPlayer[p.id]?.tricksWon ?: 0 }

                    val (nilPts, _)            = scoreEntry(0, nilPhs.tricksWon, nilPhs.isBlind, currentBags, s)
                    val (partnerPts, partnerBags) = scoreEntry(partnerPhs.bid, teamTricks, partnerPhs.isBlind, currentBags, s)

                    pointsDelta[key] = nilPts + partnerPts
                    bagsDelta[key]   = partnerBags   // bags accrue only from partner's overs
                    breakdown[nilPlayer.id] = nilPts
                    breakdown[partner.id]   = partnerPts
                } else {
                    val bid       = hand.teamBids.getOrNull(teamId) ?: 0
                    val isBlind   = hand.teamBlind.getOrNull(teamId) == true
                    val tricksWon = teamPlayers.sumOf { p -> hand.perPlayer[p.id]?.tricksWon ?: 0 }
                    val (pts, bags) = scoreEntry(bid, tricksWon, isBlind, currentBags, s)
                    pointsDelta[key] = pts
                    bagsDelta[key]   = bags
                }
            }
        } else {
            for (player in s.players) {
                val phs         = hand.perPlayer[player.id] ?: continue
                val currentBags = s.score.bags[player.id] ?: 0
                val (pts, bags) = scoreEntry(phs.bid, phs.tricksWon, phs.isBlind, currentBags, s)
                pointsDelta[player.id] = pts
                bagsDelta[player.id]   = bags
            }
        }

        return Score(points = pointsDelta, bags = bagsDelta, playerBreakdown = breakdown)
    }

    /**
     * Score a single bid entry.
     *
     * @param bid         The bid placed (0 = nil).
     * @param tricksWon   Tricks actually taken.
     * @param isBlind     True for a blind nil or blind 7.
     * @param currentBags Running bag total for this key before this hand.
     * @param s           Current game state (for optional rule flags).
     * @return Pair(pointsDelta, bagsDelta) to add to the running totals.
     */
    private fun scoreEntry(
        bid: Int,
        tricksWon: Int,
        isBlind: Boolean,
        currentBags: Int,
        s: GameState
    ): Pair<Int, Int> {

        // Nil bid — scored separately from regular bids
        if (bid == 0) {
            val base = if (isBlind) 200 else 100
            return Pair(if (tricksWon == 0) +base else -base, 0)
        }

        // Regular / blind bid
        val multiplier = when {
            isBlind                                    -> 20  // blind always doubles
            s.enableDoubleBidBonus && bid >= 10        -> 20  // optional: ≥10 doubles
            else                                       -> 10
        }

        return if (tricksWon >= bid) {
            // Made bid
            val overTricks = tricksWon - bid
            var bagDelta   = overTricks
            var penalty    = 0
            if (s.enableSandbagPenalty && overTricks > 0 && currentBags + overTricks >= 10) {
                penalty  = 100
                bagDelta -= 10   // reset 10 accumulated bags
            }
            Pair(bid * multiplier - penalty, bagDelta)
        } else {
            // Set (missed bid)
            Pair(-(bid * multiplier), 0)
        }
    }

    /** Finished: game is over — award win/loss achievements, post play log, then UI takes over. */
    private suspend fun handleFinished() {
        val s = viewModel.state.value
        AchievementsRepo.checkAndAwardGameOver(context, s)

        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val email = prefs.getString("player_email", null)
        val name  = prefs.getString("player_username", "") ?: ""
        if (!email.isNullOrBlank()) {
            val payload = PlayLogPayload(
                sk         = email,
                name       = name,
                createDate = Instant.now().toString(),
                games      = listOf(
                    PlayLogGame(
                        gameType    = s.gameType.label,
                        scores      = s.score.points,
                        handsPlayed = s.phaseHands[GamePhase.Deal]?.size ?: 0
                    )
                )
            )
            runCatching { PlayLogApi.post(payload) }
        }
        AchievementsRepo.sendIfVerified(context)
    }

    // ── Video trigger detection ───────────────────────────────────────────────

    /**
     * Entry point called after [awardTrick] but before [collectTrick].
     * [preState] is the snapshot captured at the top of [handleTrickResolve] (pre-mutation).
     * [plays] are the non-null plays of the completed trick.
     * [winnerId] is the player who won.
     */
    private suspend fun checkVideoTriggers(plays: List<Play>, winnerId: String) {
        val s    = viewModel.state.value  // post-award state
        val hand = s.phaseHands[GamePhase.Deal]?.lastOrNull() ?: return
        val totalTricksWon = hand.perPlayer.values.sumOf { it.tricksWon }
        val isFinalTrick   = totalTricksWon == s.gameType.cardsPerPlayer
        val trickIndex     = totalTricksWon - 1

        checkFrustrated(s, plays, winnerId, trickIndex)
        if (isFinalTrick) {
            checkCardhead(s, plays, winnerId, trickIndex)
            checkBoston(s, hand, trickIndex)
        }
    }

    /** Fires the frustrated video when south accidentally cuts north. */
    private suspend fun checkFrustrated(
        s: GameState, plays: List<Play>, winnerId: String, trickIndex: Int
    ) {
        if (viewModel.frustratedVideoFiredThisHand) return
        if (!s.gameType.useTeams) return
        if (plays.size != 4) return
        if (winnerId != "south") return
        if (plays.first().playerId != "west") return
        if (plays.last().playerId != "south") return

        val playsBeforeSouth = plays.dropLast(1)
        if (evaluateTrickWinnerAmong(playsBeforeSouth) != "north") return

        // Reconstruct south's hand before they played this trick
        val southCurrentHand = s.phaseHands[GamePhase.Deal]?.lastOrNull()
            ?.perPlayer?.get("south")?.hand ?: emptyList()
        val southPlayedCard  = plays.last().card
        val southHandBefore  = southCurrentHand + southPlayedCard

        // Legal alternatives south could have played instead
        val ledCard    = plays.first().card
        val legalAlts  = legalCards(southHandBefore, ledCard).filter { it.uid != southPlayedCard.uid }
        val foundSave  = legalAlts.any { alt ->
            evaluateTrickWinnerAmong(playsBeforeSouth + Play("south", alt)) == "north"
        }
        if (!foundSave) return

        viewModel.frustratedVideoFiredThisHand = true
        val asset = listOf("frustratedgirl", "frustrateddad", "frustratedmom").random()
        viewModel.emitFrustratedVideo(asset)
        viewModel.recordReplayEvent(ReplayEvent.VideoTrigger("frustrated", asset, trickIndex, plays.size - 1))
    }

    /** Fires when south drops the Big Joker to beat an opponent's Little Joker on the final trick. */
    private suspend fun checkCardhead(
        s: GameState, plays: List<Play>, winnerId: String, trickIndex: Int
    ) {
        if (plays.last().playerId != "south") return
        if (plays.last().card.rank != Rank.BIGJOKER) return
        if (winnerId != "south") return
        if (plays.none { it.card.rank == Rank.LITTLEJOKER }) return
        if (plays.none { it.card.rank == Rank.LITTLEJOKER && it.playerId != "south" && it.playerId != "north" }) return

        val asset = listOf("cardheadlady", "cardheaddad", "cardheadboy", "cardheadgirl", "cardheadmom").random()
        viewModel.emitCardheadEvent(asset)
        viewModel.recordReplayEvent(ReplayEvent.VideoTrigger("cardhead", asset, trickIndex, plays.size - 1))
    }

    /** Fires when south's team (or south alone in solo) won every trick in the hand. */
    private suspend fun checkBoston(s: GameState, hand: Hand, trickIndex: Int) {
        val southTricks = hand.perPlayer["south"]?.tricksWon ?: 0
        val northTricks = hand.perPlayer["north"]?.tricksWon ?: 0
        val isBoston = if (s.gameType.useTeams) {
            (southTricks + northTricks) == s.gameType.cardsPerPlayer
        } else {
            southTricks == s.gameType.cardsPerPlayer
        }
        if (!isBoston) return

        val asset = listOf("bostonboy", "bostongirl").random()
        viewModel.emitBostonVideo(asset)
        viewModel.recordReplayEvent(ReplayEvent.VideoTrigger("boston", asset, -1, -1))
    }

    /**
     * Returns the subset of [hand] that is legal to play when [ledCard] was led.
     * If the player is void in the led suit (or trump if trump was led), anything goes.
     */
    private fun legalCards(hand: List<Card>, ledCard: Card): List<Card> {
        return if (isTrump(ledCard)) {
            val hasTrump = hand.any { isTrump(it) }
            if (hasTrump) hand.filter { isTrump(it) } else hand
        } else {
            val hasLedSuit = hand.any { it.suit == ledCard.suit && !isTrump(it) }
            if (hasLedSuit) hand.filter { it.suit == ledCard.suit && !isTrump(it) } else hand
        }
    }

    /**
     * Returns the player id of the winner among [plays] using standard Spades ranking:
     * Big Joker > Little Joker > Spades (by rank) > led suit (by rank) > off-suit (loses).
     */
    private fun evaluateTrickWinnerAmong(plays: List<Play>): String? {
        if (plays.isEmpty()) return null
        val ledCard = plays.first().card
        var best      = plays.first()
        var bestScore = trickCardScore(best.card, ledCard)
        for (play in plays.drop(1)) {
            val score = trickCardScore(play.card, ledCard)
            if (score > bestScore) { best = play; bestScore = score }
        }
        return best.playerId
    }

    private fun trickCardScore(card: Card, ledCard: Card): Int = when {
        card.rank == Rank.BIGJOKER    -> 1000
        card.rank == Rank.LITTLEJOKER -> 999
        card.suit == Suit.SPADES      -> 100 + card.rank.ordinal
        isTrump(ledCard)              -> -1   // trump led; non-trump can't win
        card.suit == ledCard.suit     -> card.rank.ordinal
        else                          -> -1   // off-suit can't win
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /**
     * Given [plays] for the current trick and the full [playerIds] list in seat order,
     * returns the id of the next player who has not yet played this trick.
     * Wraps from [leaderIndex] clockwise.
     */
    private fun nextPlayerInTrick(
        plays: List<jmotley.com.jspades.data.Play?>,
        playerIds: List<String>,
        leaderIndex: Int
    ): String {
        val n = playerIds.size
        val playedCount = plays.count { it != null }
        return playerIds[(leaderIndex + playedCount) % n]
    }
}
