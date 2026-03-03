package jmotley.com.jspades.engine

import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.DealMode
import jmotley.com.jspades.data.GamePhase
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
    }

    /**
     * Two Man Solo alternate deal:
     * Players take turns picking from the top of the face-down deck.
     * The dealer (ids[0]) picks second; the non-dealer (ids[1]) picks first.
     *
     * Each player's turn:
     *  - Peek at the top card.
     *  - Keep it → take the top card, discard the next card unseen.
     *  - Skip it → discard the top card, must take the next card.
     * One turn consumes exactly 2 cards and grants the player exactly 1 card.
     * After [GameType.cardsPerPlayer] rounds, both players hold 13 cards.
     *
     * TODO: for a human dealer or non-dealer, surface a UI chooser in DealHuman.
     *       Currently the decision is simulated 50/50 for both players.
     */
    private fun dealTwoManAlternate(shuffled: MutableList<Card>, ids: List<String>, gt: GameType) {
        // ids[0] = dealer (picks second), ids[1] = non-dealer (picks first)
        val hands = ids.associateWith { mutableListOf<Card>() }
        val deck  = shuffled.toMutableList()

        repeat(gt.cardsPerPlayer) {
            // Non-dealer turn
            pickOneCard(deck, hands.getValue(ids[1]))
            // Dealer turn
            pickOneCard(deck, hands.getValue(ids[0]))
        }

        val perPlayer = hands.mapValues { (_, cards) ->
            PlayerHandState(hand = cards.sortedWith(compareBy({ it.suit.ordinal }, { it.rank.ordinal })))
        }
        viewModel.applyDeal(Hand(playerOrder = ids, perPlayer = perPlayer))
    }

    /**
     * Pick exactly one card for [hand] from the top of [deck], consuming two cards.
     * Decision is currently random (50/50 keep-top vs keep-second).
     */
    private fun pickOneCard(deck: MutableList<Card>, hand: MutableList<Card>) {
        if (deck.size < 2) return
        val top  = deck.removeAt(0)
        val next = deck.removeAt(0)
        if (kotlin.random.Random.nextBoolean()) hand.add(top) else hand.add(next)
    }

    /**
     * DealHuman: the player sees their hand.
     * For now auto-advances to Bid after a short pause.
     * TODO: wait for an explicit "I'm ready" tap from the human player.
     */
    private suspend fun handleDealHuman() {
        delay(600)
        viewModel.advancePhase(GamePhase.Bid)
        execute()
    }

    /**
     * Bid: CPU players bid in clockwise order starting from the leader.
     * When the next bidder is the human, switches to BidHuman.
     * TODO: wire real CPU bidding AI.
     */
    private suspend fun handleBid() {
        val s = viewModel.state.value
        val n = s.players.size
        for (offset in 0 until n) {
            val player = s.players[(s.leaderIndex + offset) % n]
            if (!player.runtimeFlags.didBid) {
                if (player.id == "south") {
                    // Human's turn — hand off to UI
                    viewModel.advancePhase(GamePhase.BidHuman)
                    execute()
                    return
                }
                // TODO: CPU bid logic (strategy / AI)
                delay(800) // simulate CPU thinking
                viewModel.submitBid(playerId = player.id, bid = 3 /* placeholder */)
                // Loop continues to next bidder
            }
        }
        // All bids submitted — move on
        advanceAfterBidding()
    }

    /** Advance from bidding to Kitty or straight to Trick depending on game type. */
    private fun advanceAfterBidding() {
        val hasKitty = viewModel.state.value.kitty != null
        val next = if (hasKitty) GamePhase.KittyReveal else GamePhase.Trick
        viewModel.advancePhase(next)
        execute()
    }

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
