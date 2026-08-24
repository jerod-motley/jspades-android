package jmotley.com.jspades.views

import jmotley.com.jspades.data.formatBidForDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

class GameInfoBidFormatTest {
    @Test fun `numbered blind bid has B prefix`() {
		assertEquals("B7", formatBidForDisplay(7, isBlind = true))
    }

    @Test fun `ordinary numbered bid has no prefix`() {
		assertEquals("7", formatBidForDisplay(7, isBlind = false))
    }

    @Test fun `blind nil is not rendered as B0`() {
		assertEquals("B Nil", formatBidForDisplay(0, isBlind = true))
    }
}
