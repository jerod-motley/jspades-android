package jmotley.com.jspades.networking

import jmotley.com.jspades.data.Suit
import jmotley.com.jspades.data.BidMessage
import jmotley.com.jspades.data.MPProtocol
import jmotley.com.jspades.data.MPRetentionResult
import jmotley.com.jspades.data.WireBidRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
	@Test fun `negotiated missing fields are rejected and never acknowledged`() {
		val caps = setOf(MPProtocol.CAP_GENERATION_SCOPED_ACTIONS, MPProtocol.CAP_EXPLICIT_BID_ROLE)
		val compliant = BidMessage("ok", 1, "p1", 1, 4, false,
			bidRole = WireBidRole.INDIVIDUAL, gameGeneration = 1)
		assertTrue(validateNegotiatedWireFields(compliant, caps))
		assertFalse(validateNegotiatedWireFields(compliant.copy(cmdId = "no-gen", gameGeneration = null), caps))
		assertFalse(validateNegotiatedWireFields(compliant.copy(cmdId = "no-role", bidRole = null), caps))
		assertFalse(isValidWireCommandId(""))
		assertFalse(shouldAcknowledgeRoutingResult(MPRetentionResult.REJECTED))
	}

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
