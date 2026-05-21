package jmotley.com.jspades.engine

import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.data.Rank
import jmotley.com.jspades.data.Suit

/**
 * Builds the canonical full deck for a given [GameType] without shuffling.
 * Used by [PhaseManager] (via handleDeal) and [ReplayHandView] (for uid → Card lookup).
 *
 * @param twoOfSpadesJoker  When true, the 2♠ is given [Rank.DEUCE] so it ranks as
 *   the third joker (below Little Joker, above Ace of Spades).
 * @param twoOfDiamondsJoker When true, the 2♦ is given [Rank.WILDDEUCE] and suit SPADES
 *   so it ranks as the third joker (above 2♠, below Little Joker). Also implies 2♠
 *   is promoted to [Rank.DEUCE] regardless of [twoOfSpadesJoker].
 */
fun buildFullDeck(gt: GameType, twoOfSpadesJoker: Boolean = false, twoOfDiamondsJoker: Boolean = false): List<Card> {
    val standardRanks = listOf(
        Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.SIX, Rank.SEVEN,
        Rank.EIGHT, Rank.NINE, Rank.TEN, Rank.JACK, Rank.QUEEN, Rank.KING, Rank.ACE
    )
    val deck: MutableList<Card> = Suit.entries
        .flatMap { suit -> standardRanks.map { rank -> Card(suit = suit, rank = rank) } }
        .toMutableList()

    if (gt.includeJokers) {
        deck += Card(suit = Suit.SPADES, rank = Rank.LITTLEJOKER)
        deck += Card(suit = Suit.SPADES, rank = Rank.BIGJOKER)
    }
    if (gt.removeTwoOfHearts) deck.removeIf { it.suit == Suit.HEARTS && it.rank == Rank.TWO }
    if (gt.removeTwoOfClubs)  deck.removeIf { it.suit == Suit.CLUBS  && it.rank == Rank.TWO }

    // Promote 2♠ to DEUCE rank (3rd/4th joker). Implied by twoOfDiamondsJoker as well.
    if (twoOfSpadesJoker || twoOfDiamondsJoker) {
        val idx = deck.indexOfFirst { it.suit == Suit.SPADES && it.rank == Rank.TWO }
        if (idx >= 0) deck[idx] = Card(suit = Suit.SPADES, rank = Rank.DEUCE)
    }

    // Promote 2♦ to WILDDEUCE rank (3rd joker, above 2♠). Internal suit becomes SPADES
    // so the card sorts into the trump group; assetFileName() still returns the 2♦ image.
    if (twoOfDiamondsJoker) {
        val idx = deck.indexOfFirst { it.suit == Suit.DIAMONDS && it.rank == Rank.TWO }
        if (idx >= 0) deck[idx] = Card(suit = Suit.SPADES, rank = Rank.WILDDEUCE)
    }

    return deck
}

/** Builds a uid → Card lookup map for the given [GameType]. */
fun buildCardLookup(gt: GameType, twoOfSpadesJoker: Boolean = false, twoOfDiamondsJoker: Boolean = false): Map<String, Card> =
    buildFullDeck(gt, twoOfSpadesJoker, twoOfDiamondsJoker).associateBy { it.uid }
