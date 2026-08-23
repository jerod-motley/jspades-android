package jmotley.com.jspades.networking

import jmotley.com.jspades.data.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The wire suit encoding (H=0, C=1, D=2, S=3) happens to match [Suit.displaySortOrder]
 * today, but the two are defined independently — this pins the wire format so a future
 * change to display sort order (or vice versa) can't silently change card IDs on the
 * network without a test failing here.
 */
class MPAdapterTest {

    @Test
    fun `suit wire round-trip`() {
        for (suit in Suit.entries) {
            val wire = suitToWireSuit(suit)
            assertEquals(suit, wireSuitToSuit(wire))
        }
    }

    @Test
    fun `wire suit values match the documented H,C,D,S encoding`() {
        assertEquals(0, suitToWireSuit(Suit.HEARTS))
        assertEquals(1, suitToWireSuit(Suit.CLUBS))
        assertEquals(2, suitToWireSuit(Suit.DIAMONDS))
        assertEquals(3, suitToWireSuit(Suit.SPADES))
    }

    @Test
    fun `wireSuitToSuit rejects out-of-range values`() {
        assertNull(wireSuitToSuit(-1))
        assertNull(wireSuitToSuit(4))
    }
}
