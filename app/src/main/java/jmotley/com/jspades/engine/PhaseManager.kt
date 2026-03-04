package jmotley.com.jspades.engine

import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.DealMode
import jmotley.com.jspades.data.GamePhase
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.data.Hand
import jmotley.com.jspades.data.PlayerHandState
import jmotley.com.jspades.data.Rank
import jmotley.com.jspades.data.Suit
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
     * Walks players clockwise from leaderIndex. CPU players bid immediately;
     * when the human is reached, switches to BidHuman and returns.
     * After all players have bid, team bids are finalized and the phase advances.
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
                delay(800)
                val hand   = getPlayerHand(s, player.id)
                val result = BidEngine.computeCpuBid(
                    hand           = hand,
                    player         = player,
                    state          = s,
                    isKittyWinner  = s.kittyWinnerId == player.id
                )
                viewModel.submitBid(player.id, result.bid, result.isBlind)
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
     * CPU-only teams: sum individual bids, apply minimumBid floor, store as team bid.
     * When the human's team is up: CPU partner bids first, then BidHuman waits for the
     * human to enter the team total via [GameViewModel.submitHumanTeamBid].
     *
     * This function is re-entered after the human submits (via execute()); the fresh
     * state snapshot will show all players as didBid = true, so it falls through to
     * [advanceAfterBidding].
     */
    private suspend fun handleBidHouseRules() {
        val s          = viewModel.state.value
        val n          = s.players.size
        // leaderIndex is the first-to-act (left of dealer); dealer is one seat clockwise.
        val dealerIdx  = (s.leaderIndex - 1 + n) % n
        val dealerTeam = s.players[dealerIdx].team
        val teamOrder  = listOf(1 - dealerTeam, dealerTeam) // non-dealer first

        for (teamId in teamOrder) {
            val teamPlayers = s.players.filter { it.team == teamId }
            if (teamPlayers.all { it.runtimeFlags.didBid }) continue // already done

            // CPU players on this team bid first
            for (player in teamPlayers) {
                if (!player.runtimeFlags.didBid && player.id != "south") {
                    delay(600)
                    val hand   = getPlayerHand(s, player.id)
                    val result = BidEngine.computeCpuBid(hand, player, s)
                    viewModel.submitBid(player.id, result.bid, result.isBlind)
                }
            }

            val humanOnTeam    = teamPlayers.any { it.id == "south" }
            val humanAlreadyBid = s.players.find { it.id == "south" }?.runtimeFlags?.didBid ?: false

            if (humanOnTeam && !humanAlreadyBid) {
                // Hand off to BidView — human sees CPU partner's bid and enters team total
                viewModel.advancePhase(GamePhase.BidHuman)
                execute()
                return
            }

            if (!humanOnTeam) {
                // All CPU team — sum individual bids and store team total
                val fresh    = viewModel.state.value
                val teamBid  = teamPlayers.sumOf { player ->
                    fresh.phaseHands[GamePhase.Deal]?.lastOrNull()
                        ?.perPlayer?.get(player.id)?.bid ?: 0
                }.coerceAtLeast(s.gameType.minimumBid)
                viewModel.setTeamBid(teamId, teamBid)
            }
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
     * Trick: CPU plays a card into the current trick.
     * When it is the human's turn, switches to TrickHuman.
     * TODO: implement CPU card-selection logic.
     */
    private suspend fun handleTrick() {
        val s = viewModel.state.value
        val nextPlayer = nextPlayerInTrick(s.currentTrick.plays, s.players.map { it.id }, s.leaderIndex)

        if (nextPlayer == "south") {
            viewModel.advancePhase(GamePhase.TrickHuman)
            execute()
            return
        }

        delay(900) // simulate CPU thinking
        // TODO: select card via CPU AI; placeholder plays first card in hand
        val hand = s.phaseHands[GamePhase.Deal]?.lastOrNull()
            ?.perPlayer?.get(nextPlayer)?.hand
        val card = hand?.firstOrNull()
        if (card != null) {
            viewModel.playCard(nextPlayer, card)
        }

        if (viewModel.state.value.currentTrick.isComplete) {
            viewModel.advancePhase(GamePhase.TrickResolve)
        }
        execute()
    }

    /**
     * TrickResolve: determine trick winner, update tricksWon, clear trick,
     * then loop back to Trick or move to Score if the hand is over.
     * TODO: implement full trick-resolution logic (spades trump, lead suit, etc.).
     */
    private suspend fun handleTrickResolve() {
        delay(1000) // pause so the UI can show completed trick
        // TODO: calculate winner from currentTrick
        viewModel.collectTrick(winnerId = "south" /* placeholder */)

        val totalTricksPlayed = viewModel.state.value.discard.size / 4
        if (totalTricksPlayed >= 13) {
            viewModel.advancePhase(GamePhase.Score)
        } else {
            viewModel.advancePhase(GamePhase.Trick)
        }
        execute()
    }

    /**
     * Score: tally points/bags for each team, then advance to EndHand or Finished.
     * TODO: implement full scoring (made/set/bags/blind/nil).
     */
    private suspend fun handleScore() {
        delay(500)
        viewModel.advancePhase(GamePhase.EndHand)
        execute()
    }

    /**
     * EndHand: reset per-hand state for the next round.
     * TODO: check win condition; rotate leader; deal next hand.
     */
    private suspend fun handleEndHand() {
        delay(300)
        // TODO: check if any team reached 500 (or target score)
        viewModel.advancePhase(GamePhase.Finished)
        execute()
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
