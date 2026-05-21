package jmotley.com.jspades.engine

import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.data.effectiveMinBid
import jmotley.com.jspades.data.Player
import jmotley.com.jspades.data.Rank
import jmotley.com.jspades.data.Suit

/**
 * BidEngine — pure, stateless card strength rating and CPU bid calculation.
 *
 * All functions operate on hand/state snapshots; no side-effects.
 * PhaseManager calls [computeCpuBid] for each CPU player during the Bid phase.
 */
object BidEngine {

    /**
     * Trump card hierarchy, highest → lowest ordinal.
     * The strength-5 chain starts at BIGJOKER and extends downward as long as
     * every consecutive trump rank is also present in the hand.
     */
    private val TRUMP_CHAIN: List<Rank> = listOf(
        Rank.BIGJOKER, Rank.LITTLEJOKER, Rank.WILDDEUCE, Rank.DEUCE,
        Rank.ACE, Rank.KING, Rank.QUEEN, Rank.JACK,
        Rank.TEN, Rank.NINE, Rank.EIGHT, Rank.SEVEN,
        Rank.SIX, Rank.FIVE, Rank.FOUR, Rank.THREE, Rank.TWO
    )

    /** True when [card] is a trump (spade suit or either joker). */
    private fun isTrump(card: Card): Boolean =
        card.suit == Suit.SPADES || card.rank == Rank.LITTLEJOKER || card.rank == Rank.BIGJOKER

    /**
     * Returns the subset of [hand] that forms the guaranteed-winner chain.
     *
     * Starting from Big Joker, a card is strength-5 if every rank above it in
     * the trump hierarchy is also held. The chain breaks at the first gap.
     *
     * Example: BJ + LJ + 2♠(DEUCE) + A♠ → all four are strength-5.
     *          BJ + LJ + K♠ (no DEUCE, no A♠) → only BJ + LJ are strength-5.
     */
    private fun strength5Cards(hand: List<Card>): Set<Card> {
        val result = mutableSetOf<Card>()
        for (rank in TRUMP_CHAIN) {
            val card = hand.firstOrNull { it.rank == rank && isTrump(it) } ?: break
            result.add(card)
        }
        return result
    }

    /**
     * Rate a single card 1–5 given the full hand context.
     *
     * | Rating | Condition |
     * |--------|-----------|
     * | 5 | Member of the strength-5 trump chain |
     * | 4 | Ace in a non-trump suit; player holds < 7 cards of that suit |
     * | 3 | Any other trump; Ace in non-trump with 7+ of suit; King with < 4 of suit |
     * | 2 | King in a suit where the player holds 4+ of that suit |
     * | 1 | Everything else |
     */
    fun rateCard(card: Card, hand: List<Card>): Int {
        if (card in strength5Cards(hand)) return 5
        val isSpade   = isTrump(card)
        val suitCount = hand.count { it.suit == card.suit }
        return when {
            card.rank == Rank.ACE && !isSpade && suitCount < 7 -> 4
            isSpade                                             -> 3
            card.rank == Rank.ACE && !isSpade                   -> 3  // suitCount >= 7
            card.rank == Rank.KING && suitCount < 4             -> 3
            card.rank == Rank.KING                              -> 2
            else                                                -> 1
        }
    }

    /**
     * Base bid from the rated hand:
     * ```
     * bid  = count(strength > 3)
     * bid += floor(count(strength == 3) / 2)
     * ```
     */
    fun computeBaseBid(hand: List<Card>): Int {
        val ratings = hand.map { rateCard(it, hand) }
        return ratings.count { it > 3 } + ratings.count { it == 3 } / 2
    }

    /** Result of a CPU bid. [isBlind] is only ever true for a Classic blind-nil. */
    data class BidResult(val bid: Int, val isBlind: Boolean = false)

    /**
     * Determines whether a CPU player should go blind this hand.
     * Returns a [BidResult] with [isBlind]=true if they should, null if not.
     * Eligible when 100+ points behind; goes blind 33% of the time when eligible.
     * Double blind is allowed.
     */
    fun shouldCpuBidBlind(hand: List<Card>, player: Player, state: GameState): BidResult? {
        val eligible = if (state.gameType.useTeams) {
            teamScore(state, 1 - player.team) - teamScore(state, player.team) >= 100
        } else {
            val myScore = playerScore(state, player.id)
            val topScore = state.players.filter { it.id != player.id }
                .maxOfOrNull { p -> playerScore(state, p.id) } ?: 0
            topScore - myScore >= 100
        }
        if (!eligible) return null
        // 33% chance
        if (kotlin.random.Random.nextInt(3) != 0) return null
        return when (state.gameType) {
            GameType.TEAM_CLASSIC, GameType.SOLO_FOUR_MAN -> BidResult(bid = 0, isBlind = true)
            else -> BidResult(bid = 7, isBlind = true)
        }
    }

    /**
     * Full CPU bid for [player] in [state], with all game-type adjustments applied.
     *
     * @param hand          The player's dealt hand.
     * @param isKittyWinner True when this player holds the 2♠ (Kitty variant only).
     */
    fun computeCpuBid(
        hand: List<Card>,
        player: Player,
        state: GameState,
        isKittyWinner: Boolean = false
    ): BidResult {
        val base = computeBaseBid(hand)
        return when (state.gameType) {
            GameType.HOUSE_RULES ->
                BidResult(base.coerceIn(0, 13))

            GameType.TEAM_CLASSIC ->
                BidResult(base.coerceIn(0, 13))

            GameType.TEAM_KITTY ->
                BidResult((base + if (isKittyWinner) 1 else 0).coerceIn(0, 13))

            GameType.SOLO_FOUR_MAN ->
                BidResult(base.coerceIn(0, 13))

            GameType.SOLO_THREE_MAN -> {
                val walking = hand.count {
                    (it.rank == Rank.QUEEN || it.rank == Rank.JACK) && it.suit != Suit.SPADES
                }
                BidResult((base + walking / 2).coerceAtLeast(state.effectiveMinBid).coerceAtMost(18))
            }

            GameType.SOLO_TWO_MAN ->
                BidResult(base.coerceAtLeast(state.effectiveMinBid).coerceAtMost(13))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun teamScore(state: GameState, team: Int): Int =
        (state.score.points[team.toString()] ?: 0) + (state.score.bags[team.toString()] ?: 0)

    private fun playerScore(state: GameState, playerId: String): Int =
        state.score.points[playerId] ?: 0
}
