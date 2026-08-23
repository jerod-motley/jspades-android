package jmotley.com.jspades.networking

import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.data.OnlineSeat
import jmotley.com.jspades.data.WireGameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression coverage for the "lobby doesn't show the host's settings" bug: the host's
 * [WireGameConfig] must survive the flat string-map wire format buildLobbySnapshot/
 * buildStartGame use, so a joining client's lobby view reflects the host's real settings.
 */
class LobbySettingsWireTest {

    private val settings = WireGameConfig(
        gameType = gameTypeToWireString(GameType.HOUSE_RULES),
        twoOfSpadesJoker = true,
        twoOfDiamondsJoker = false,
        enableDoubleBidBonus = true,
        spadesMustBreak = true,
        minimumBid = 5,
        enableSandbagPenalty = false,
        allowNilBid = false,
        blindNilExchangeEnabled = false,
        gameLength = "LONG"
    )

    @Test
    fun `toLobbyFields round-trips through parseLobbySettings`() {
        val decoded = parseLobbySettings(settings.toLobbyFields(), fallback = WireGameConfig(gameType = "houseRules"))
        assertEquals(settings, decoded)
    }

    @Test
    fun `buildLobbySnapshot carries settings through the real wire encoding to onSnapShot-equivalent parsing`() {
        val json = buildLobbySnapshot("host-id", "ROOM1", seats = emptyList(), settings = settings)
        val msg = parseIncoming(json)
        check(msg is SpadesMPMessage.SnapShot) { "expected SnapShot, got $msg" }

        val decoded = parseLobbySettings(msg.payload, fallback = WireGameConfig(gameType = "houseRules"))
        assertEquals(settings, decoded)
    }

    @Test
    fun `buildStartGame carries settings through the real wire encoding`() {
        val json = buildStartGame("host-id", "ROOM1", seats = emptyList(), settings = settings)
        val msg = parseIncoming(json)
        check(msg is SpadesMPMessage.StartGame) { "expected StartGame, got $msg" }

        val decoded = parseLobbySettings(msg.payload, fallback = WireGameConfig(gameType = "houseRules"))
        assertEquals(settings, decoded)
    }

    @Test
    fun `buildStartGame without settings does not overwrite the client's already-known settings`() {
        val json = buildStartGame("host-id", "ROOM1", seats = listOf(OnlineSeat(0)))
        val msg = parseIncoming(json)
        check(msg is SpadesMPMessage.StartGame) { "expected StartGame, got $msg" }

        val decoded = parseLobbySettings(msg.payload, fallback = settings)
        assertEquals(settings, decoded)
    }

    @Test
    fun `parseLobbySettings falls back whole, never half-populated, when settings fields are absent`() {
        val fallback = settings
        val decoded = parseLobbySettings(mapOf("phase" to "lobby", "seat0Id" to "abc"), fallback = fallback)
        assertEquals(fallback, decoded)
    }

    @Test
    fun `hostWireGameConfig gameType mapping stays consistent with the wire converter`() {
        for (gameType in GameType.entries) {
            val wire = gameTypeToWireString(gameType)
            assertEquals(gameType, wireStringToGameType(wire))
        }
        assertNull(wireStringToGameType("not-a-real-game-type"))
    }
}
