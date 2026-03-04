package jmotley.com.jspades.engine

import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.DealMode
import jmotley.com.jspades.data.GamePhase
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.data.Hand
import jmotley.com.jspades.data.Play
import jmotley.com.jspades.data.PlayerHandState
import jmotley.com.jspades.data.Rank
import jmotley.com.jspades.data.Suit
import jmotley.com.jspades.data.AnimationEvent
import jmotley.com.jspades.data.Score
import jmotley.com.jspades.models.GameViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

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
    private val scope: CoroutineScope
) {
    /** Dispatch on the current phase. May launch coroutines for background work. */
    fun execute() {
        scope.launch {
            when (viewModel.state.value.phase) {
                GamePhase.Lobby       -> handleLobby()
                GamePhase.Deal        -> handleDeal()
                GamePhase.DealHuman   -> handleDealHuman()
                GamePhase.Bid         -> handleBid()
                GamePhase.BidHuman    -> { /* UI owns this phase — waits for submitBid() */ }
                GamePhase.KittyReveal -> handleKittyReveal()
                GamePhase.Kitty       -> handleKitty()
                GamePhase.KittyHuman  -> { /* UI owns this phase — waits for kitty exchange */ }
                GamePhase.Trick       -> handleTrick()
                GamePhase.TrickHuman  -> { /* UI owns this phase — waits for playCard() */ }
                GamePhase.TrickResolve-> handleTrickResolve()
                GamePhase.Score       -> handleScore()
                GamePhase.EndHand     -> handleEndHand()
                GamePhase.Finished    -> handleFinished()
            }
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
        val deck: MutableList<Card> = Suit.entries
            .flatMap { suit -> standardRanks.map { rank -> Card(suit = suit, rank = rank) } }
            .toMutableList()

        // 2. Optionally append jokers
        if (gt.includeJokers) {
            deck += Card(suit = Suit.SPADES, rank = Rank.LITTLEJOKER)
            deck += Card(suit = Suit.SPADES, rank = Rank.BIGJOKER)
        }

        // 3. Optionally strip 2♥ and/or 2♣
        if (gt.removeTwoOfHearts) deck.removeIf { it.suit == Suit.HEARTS && it.rank == Rank.TWO }
        if (gt.removeTwoOfClubs)  deck.removeIf { it.suit == Suit.CLUBS  && it.rank == Rank.TWO }

        // 4. Shuffle
        val shuffled = deck.shuffled().toMutableList()

        // 5. Dispatch to the correct deal algorithm
        when (gt.dealMode) {
            DealMode.STANDARD            -> dealStandard(shuffled, ids, gt)
            DealMode.KITTY_TWO_OF_SPADES -> dealWithKitty(shuffled, ids, gt)
            DealMode.TWO_MAN_ALTERNATE   -> dealTwoManAlternate(shuffled, ids, gt)
        }

        delay(400)
        viewModel.advancePhase(GamePhase.DealHuman)
        execute()
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

        // Ensure 2♠ is in a player's hand
        val kittyIdx = kittySlice.indexOfFirst { it.suit == Suit.SPADES && it.rank == Rank.TWO }
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
            cards.any { it.suit == Suit.SPADES && it.rank == Rank.TWO }
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
    private fun dealTwoManAlternate(shuffled: MutableList<Card>, ids: List<String>, gt: GameType) {
        val deck    = shuffled.toMutableList()
        val cpuHand = mutableListOf<Card>()

        // CPU (non-dealer) takes round-1 pick automatically
        pickOneCard(deck, cpuHand)

        val perPlayer = mapOf(
            ids[0] to PlayerHandState(),   // human — empty until pick UI runs
            ids[1] to PlayerHandState(
                hand = cpuHand.sortedWith(compareBy({ it.suit.ordinal }, { it.rank.ordinal }))
            )
        )
        viewModel.applyDeal(Hand(playerOrder = ids, perPlayer = perPlayer))
        viewModel.storeDeck(deck)
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
     * - [DealMode.TWO_MAN_ALTERNATE]: UI-driven — [GameViewModel.applyDealPick] advances
     *   to Bid once the human's hand is full; no action needed here.
     * - All other modes: auto-advance after animations complete.
     */
    private suspend fun handleDealHuman() {
        if (viewModel.state.value.gameType.dealMode == DealMode.TWO_MAN_ALTERNATE) return
        // cards × 50ms stagger + 320ms animation ≈ 970ms; 1200ms gives a pause after last card
        delay(1200)
        viewModel.advancePhase(GamePhase.Bid)
        execute()
    }

    /**
     * Bid: dispatch to the correct bid flow based on game type.
     *
     * House Rules uses a team-based flow (non-dealer team first, dealer's team second).
     * All other game types use a per-player clockwise flow starting from leaderIndex.
     */
    private suspend fun handleBid() {
        if (viewModel.state.value.gameType == GameType.HOUSE_RULES) {
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
                if (player.id == "south") {
                    viewModel.advancePhase(GamePhase.BidHuman)
                    execute()
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
                // Fire-and-forget: emit event, return; animation completion calls execute()
                viewModel.emitAnimation(AnimationEvent.BidPlaced(player.id, result.bid))
                return
            }
        }
        // All players have bid — compute team totals for team games
        if (s.gameType.useTeams) finalizeTeamBids()
        advanceAfterBidding()
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
            val humanOnTeam    = teamPlayers.any { it.id == "south" }
            val humanAlreadyBid = s.players.find { it.id == "south" }?.runtimeFlags?.didBid ?: false

            // Find the next unbidd CPU player on this team
            val nextCpu = teamPlayers.firstOrNull { !it.runtimeFlags.didBid && it.id != "south" }
            if (nextCpu != null) {
                val hand   = getPlayerHand(s, nextCpu.id)
                val result = BidEngine.computeCpuBid(hand, nextCpu, s)
                viewModel.submitBid(nextCpu.id, result.bid, result.isBlind)
                // Fire-and-forget: one CPU bid per execute() call
                viewModel.emitAnimation(AnimationEvent.BidPlaced(nextCpu.id, result.bid))
                return
            }

            // All CPUs on this team have bid
            if (humanOnTeam && !humanAlreadyBid) {
                viewModel.advancePhase(GamePhase.BidHuman)
                execute()
                return
            }

            if (!humanOnTeam) {
                // CPU-only team — sum individual bids and store team total (idempotent)
                val teamBid = teamPlayers.sumOf { player ->
                    s.phaseHands[GamePhase.Deal]?.lastOrNull()
                        ?.perPlayer?.get(player.id)?.bid ?: 0
                }.coerceAtLeast(s.gameType.minimumBid)
                viewModel.setTeamBid(teamId, teamBid)
            }
            // fall through to next team
        }

        advanceAfterBidding()
    }

    /**
     * Compute and store team bids from individual player bids (Classic / Kitty).
     * For Kitty, caps the team total at 12 and floors at minimumBid (5).
     */
    private fun finalizeTeamBids() {
        val s    = viewModel.state.value
        val hand = s.phaseHands[GamePhase.Deal]?.lastOrNull() ?: return
        val isKitty = s.gameType == GameType.TEAM_KITTY

        for (teamId in listOf(0, 1)) {
            val teamPlayerIds = s.players.filter { it.team == teamId }.map { it.id }
            var teamTotal = teamPlayerIds.sumOf { id -> hand.perPlayer[id]?.bid ?: 0 }
            if (isKitty) teamTotal = teamTotal.coerceIn(s.gameType.minimumBid, 12)
            viewModel.setTeamBid(teamId, teamTotal)
        }
    }

    /** Advance from bidding to Kitty or straight to Trick depending on game type. */
    private fun advanceAfterBidding() {
        val hasKitty = viewModel.state.value.kitty != null
        val next = if (hasKitty) GamePhase.KittyReveal else GamePhase.Trick
        viewModel.advancePhase(next)
        execute()
    }

    // ── Bid utility ───────────────────────────────────────────────────────────

    /** Returns the dealt cards for [playerId] from the current Deal-phase hand. */
    private fun getPlayerHand(s: GameState, playerId: String): List<Card> =
        s.phaseHands[GamePhase.Deal]?.lastOrNull()?.perPlayer?.get(playerId)?.hand
            ?: emptyList()

    /**
     * KittyReveal: show the kitty cards briefly, then hand to the winning bidder.
     * TODO: determine winning bidder; for now advances to Trick.
     */
    private suspend fun handleKittyReveal() {
        delay(1500) // let the UI show the kitty reveal animation
        viewModel.advancePhase(GamePhase.KittyHuman)
        execute()
    }

    /**
     * Kitty (CPU kitty exchange): CPU player swaps cards with the kitty.
     * TODO: implement CPU swap logic.
     */
    private suspend fun handleKitty() {
        delay(800)
        viewModel.advancePhase(GamePhase.Trick)
        execute()
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
        val nextPlayer = nextPlayerInTrick(s.currentTrick.plays, s.players.map { it.id }, s.leaderIndex)

        if (nextPlayer == "south") {
            viewModel.advancePhase(GamePhase.TrickHuman)
            execute()
            return
        }

        val card = PlayEngine.selectCard(nextPlayer, s)
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
        if (plays.isEmpty()) { execute(); return }

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
    private suspend fun handleScore() {
        val s    = viewModel.state.value
        val hand = s.phaseHands[GamePhase.Deal]?.lastOrNull()
        if (hand != null) {
            viewModel.applyScore(scoreHand(s, hand))
        }
        viewModel.advancePhase(GamePhase.EndHand)
        execute()
    }

    /**
     * EndHand: check win condition; if game continues, reset per-hand state and re-deal.
     *
     * Win conditions (target = 500, losing floor = −200):
     *   - Any team/player reaches ≥ 500 AND leads all others → winner.
     *   - Any team/player drops to ≤ −200 AND trails all others → eliminated, others win.
     * Tie at exactly 500 → continue until the tie is broken.
     *
     * Displayed score = score.points[key] + score.bags[key].
     */
    private suspend fun handleEndHand() {
        val s = viewModel.state.value

        // Build displayed totals (points + bags) per team or player
        val totals: Map<String, Int> = if (s.gameType.useTeams) {
            mapOf(
                "0" to ((s.score.points["0"] ?: 0) + (s.score.bags["0"] ?: 0)),
                "1" to ((s.score.points["1"] ?: 0) + (s.score.bags["1"] ?: 0))
            )
        } else {
            s.players.associate { p ->
                p.id to ((s.score.points[p.id] ?: 0) + (s.score.bags[p.id] ?: 0))
            }
        }

        val highScore = totals.values.maxOrNull() ?: 0
        val lowScore  = totals.values.minOrNull() ?: 0

        val gameOver = highScore >= TARGET_SCORE || lowScore <= LOSING_SCORE

        if (gameOver) {
            viewModel.advancePhase(GamePhase.Finished)
            execute()
            return
        }

        // Continue: rotate dealer, wipe per-hand state, re-deal
        viewModel.resetForNextHand()
        viewModel.advancePhase(GamePhase.Deal)
        execute()
    }

    // ── Scoring helpers ───────────────────────────────────────────────────────

    /** First team/player to reach this score wins (if leading all others). */
    private val TARGET_SCORE = 500

    /** A team/player that falls to this score (while behind) is eliminated. */
    private val LOSING_SCORE = -200

    /**
     * Compute the point and bag deltas for a completed hand.
     * Returns a [Score] whose values are signed deltas to be added to the running total.
     */
    private fun scoreHand(s: GameState, hand: Hand): Score {
        val pointsDelta = mutableMapOf<String, Int>()
        val bagsDelta   = mutableMapOf<String, Int>()

        if (s.gameType.useTeams) {
            for (teamId in listOf(0, 1)) {
                val key        = teamId.toString()
                val bid        = hand.teamBids.getOrNull(teamId) ?: 0
                val isBlind    = hand.teamBlind.getOrNull(teamId) == true
                val tricksWon  = s.players
                    .filter { it.team == teamId }
                    .sumOf { p -> hand.perPlayer[p.id]?.tricksWon ?: 0 }
                val currentBags = s.score.bags[key] ?: 0
                val (pts, bags) = scoreEntry(bid, tricksWon, isBlind, currentBags, s)
                pointsDelta[key] = pts
                bagsDelta[key]   = bags
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

        return Score(points = pointsDelta, bags = bagsDelta)
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

    /** Finished: game is over — UI shows result overlay. */
    private suspend fun handleFinished() {
        // no-op — UI takes over
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
