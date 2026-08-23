package jmotley.com.jspades.networking

import jmotley.com.jspades.data.GameLength
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.GameType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the "Play Again retains scores" bug fix: `playAgain()` now
 * re-broadcasts the host's currently-applied settings via [GameState.toWireGameConfig]
 * instead of the previous version, which named only 8 of [GameState]'s rule fields and
 * silently reset the rest (sandbag penalty, minimum-bid override, blind exchange,
 * double-bid bonus, game length) to their class defaults.
 */
class GameStateToWireGameConfigTest {

    @Test
    fun `carries every settings field through unchanged`() {
        val state = GameState(
            gameType             = GameType.HOUSE_RULES,
            twoOfSpadesJoker     = true,
            twoOfDiamondsJoker   = true,
            enableDoubleBidBonus = true,
            spadesMustBreak      = true,
            enableSandbagPenalty = false,
            allowNilBid          = true,
            allowBlindExchange   = true,
            gameLength           = GameLength.LONG
        )

        val config = state.toWireGameConfig()

        assertEquals("houseRules", config.gameType)
        assertTrue(config.twoOfSpadesJoker)
        assertTrue(config.twoOfDiamondsJoker)
        assertTrue(config.enableDoubleBidBonus)
        assertTrue(config.spadesMustBreak)
        assertFalse(config.enableSandbagPenalty)
        assertTrue(config.allowNilBid)
        assertTrue(config.blindNilExchangeEnabled)
        assertEquals("LONG", config.gameLength)
    }

    @Test
    fun `minimumBid reflects minBidFive when no explicit override is set`() {
        val withoutMinBidFive = GameState(gameType = GameType.HOUSE_RULES, minBidFive = false)
        val withMinBidFive    = GameState(gameType = GameType.HOUSE_RULES, minBidFive = true)

        assertEquals(4, withoutMinBidFive.toWireGameConfig().minimumBid)
        assertEquals(5, withMinBidFive.toWireGameConfig().minimumBid)
    }

    @Test
    fun `minimumBid uses the explicit override regardless of minBidFive`() {
        val state = GameState(gameType = GameType.HOUSE_RULES, minBidFive = true, minimumBidOverride = 7)
        assertEquals(7, state.toWireGameConfig().minimumBid)
    }

    @Test
    fun `round trips through wireStringToGameType`() {
        for (gameType in GameType.entries) {
            val state = GameState(gameType = gameType)
            assertEquals(gameType, wireStringToGameType(state.toWireGameConfig().gameType))
        }
    }
}
