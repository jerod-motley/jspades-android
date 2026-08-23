package jmotley.com.jspades.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format regression coverage for the "Play Again retains scores" bug fix: a
 * non-host client sends [RequestPlayAgainMessage] instead of resetting itself to a
 * bogus local game. Pins the sealed [WireMessage] discriminator round-trip, mirroring
 * [ReadyForNextHandMessageTest] — the part of the fix that doesn't depend on
 * `GameViewModel`/`AndroidViewModel`, which this project's plain JUnit setup can't
 * instantiate (no Robolectric).
 */
class RequestPlayAgainMessageTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Test
    fun `serializes with the requestPlayAgain type discriminator and round-trips`() {
        val msg = RequestPlayAgainMessage(cmdId = "abc-123", seat = 3, playerId = "player-3")

        val encoded = json.encodeToString(WireMessage.serializer(), msg)
        assertTrue("expected \"type\":\"requestPlayAgain\" in $encoded", encoded.contains("\"type\":\"requestPlayAgain\""))

        val decoded = json.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(msg, decoded)
        assertTrue(decoded is RequestPlayAgainMessage)
    }

    @Test
    fun `distinct seats do not collide after round-trip`() {
        val seat1 = RequestPlayAgainMessage(cmdId = "c1", seat = 1, playerId = "p1")
        val seat2 = RequestPlayAgainMessage(cmdId = "c2", seat = 2, playerId = "p2")

        val decoded1 = json.decodeFromString(WireMessage.serializer(), json.encodeToString(WireMessage.serializer(), seat1))
        val decoded2 = json.decodeFromString(WireMessage.serializer(), json.encodeToString(WireMessage.serializer(), seat2))

        assertEquals(1, decoded1.seat)
        assertEquals(2, decoded2.seat)
    }

    /**
     * Regression coverage for the code-review finding "Play Again lacks the planned game
     * identity": [RequestPlayAgainMessage.gameGeneration] lets the host reject a delayed
     * request tagged for a game generation that has already been superseded.
     */
    @Test
    fun `gameGeneration round-trips distinctly from the default`() {
        val msg = RequestPlayAgainMessage(cmdId = "c1", seat = 0, playerId = "p0", gameGeneration = 3)

        val decoded = json.decodeFromString(WireMessage.serializer(), json.encodeToString(WireMessage.serializer(), msg))

        assertTrue(decoded is RequestPlayAgainMessage)
        assertEquals(3, (decoded as RequestPlayAgainMessage).gameGeneration)
    }
}
