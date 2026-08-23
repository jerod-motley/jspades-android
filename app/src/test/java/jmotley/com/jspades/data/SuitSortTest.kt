package jmotley.com.jspades.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Hands must sort Hearts, Clubs, Diamonds, Spades — not by [Suit.ordinal]. */
class SuitSortTest {

    @Test
    fun `displaySortOrder is Hearts, Clubs, Diamonds, Spades`() {
        assertEquals(0, Suit.HEARTS.displaySortOrder)
        assertEquals(1, Suit.CLUBS.displaySortOrder)
        assertEquals(2, Suit.DIAMONDS.displaySortOrder)
        assertEquals(3, Suit.SPADES.displaySortOrder)
    }

    @Test
    fun `hand sorts to Hearts, Clubs, Diamonds, Spades with rank ascending within suit`() {
        val hand = listOf(
            Card(Suit.SPADES, Rank.KING),
            Card(Suit.DIAMONDS, Rank.FIVE),
            Card(Suit.HEARTS, Rank.ACE),
            Card(Suit.CLUBS, Rank.TWO),
            Card(Suit.DIAMONDS, Rank.THREE),
            Card(Suit.HEARTS, Rank.SEVEN),
            Card(Suit.SPADES, Rank.TWO)
        )

        val sorted = hand.sortedWith(compareBy({ it.suit.displaySortOrder }, { it.rank.ordinal }))

        val expected = listOf(
            Card(Suit.HEARTS, Rank.SEVEN),
            Card(Suit.HEARTS, Rank.ACE),
            Card(Suit.CLUBS, Rank.TWO),
            Card(Suit.DIAMONDS, Rank.THREE),
            Card(Suit.DIAMONDS, Rank.FIVE),
            Card(Suit.SPADES, Rank.TWO),
            Card(Suit.SPADES, Rank.KING)
        )
        assertEquals(expected, sorted)
    }
}
