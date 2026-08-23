package jmotley.com.jspades.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format regression coverage for the code-review finding "Phase is not a reliable
 * new-game identity": [GameConfigMessage.gameGeneration] is what actually distinguishes a
 * genuine new-game start from same-game configuration recovery in `GameViewModel
 * .onGameConfig`, replacing a local-phase heuristic (`.Lobby`/`.Finished`) that a stray
 * recovery message could fool. Pins the sealed [WireMessage] discriminator round-trip.
 */
class GameConfigMessageTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val sampleConfig = WireGameConfig(gameType = "houseRules")
    private val samplePlayers = mapOf(
        "0" to WireSeatPlayer(playerId = "p0", displayName = "South", kind = "human"),
        "1" to WireSeatPlayer(playerId = "p1", displayName = "West", kind = "cpu")
    )

    @Test
    fun `gameGeneration round-trips through the real wire encoding`() {
        val msg = GameConfigMessage(
            cmdId = "abc-123", seat = 0, playerId = "p0",
            config = sampleConfig, players = samplePlayers, gameGeneration = 2
        )

        val encoded = json.encodeToString(WireMessage.serializer(), msg)
        assertTrue("expected \"type\":\"gameConfig\" in $encoded", encoded.contains("\"type\":\"gameConfig\""))

        val decoded = json.decodeFromString(WireMessage.serializer(), encoded)
        assertTrue(decoded is GameConfigMessage)
        assertEquals(2, (decoded as GameConfigMessage).gameGeneration)
    }

    @Test
    fun `distinct generations do not collide after round-trip`() {
        val first  = GameConfigMessage(cmdId = "c1", seat = 0, playerId = "p0", config = sampleConfig, players = samplePlayers, gameGeneration = 1)
        val second = GameConfigMessage(cmdId = "c2", seat = 0, playerId = "p0", config = sampleConfig, players = samplePlayers, gameGeneration = 2)

        val decodedFirst  = json.decodeFromString(WireMessage.serializer(), json.encodeToString(WireMessage.serializer(), first))
        val decodedSecond = json.decodeFromString(WireMessage.serializer(), json.encodeToString(WireMessage.serializer(), second))

        assertEquals(1, (decodedFirst as GameConfigMessage).gameGeneration)
        assertEquals(2, (decodedSecond as GameConfigMessage).gameGeneration)
    }

    @Test
    fun `gameGeneration defaults to 1 when omitted`() {
        val msg = GameConfigMessage(cmdId = "c1", seat = 0, playerId = "p0", config = sampleConfig, players = samplePlayers)
        assertEquals(1, msg.gameGeneration)
    }
}
