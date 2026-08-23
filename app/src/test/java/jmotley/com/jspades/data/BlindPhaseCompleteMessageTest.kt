package jmotley.com.jspades.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format regression coverage for the code-review finding "BlindBid is still not
 * host-authoritative": [BlindPhaseCompleteMessage] is what a non-host client actually
 * waits on to leave `GamePhase.BlindBid`, since it never independently decides blind
 * eligibility itself anymore (see `PhaseManager.handleBlindBid`'s non-host early return).
 * Pins the sealed [WireMessage] discriminator round-trip.
 */
class BlindPhaseCompleteMessageTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Test
    fun `serializes with the blindPhaseComplete type discriminator and round-trips`() {
        val msg = BlindPhaseCompleteMessage(cmdId = "abc-123", seat = 0, playerId = "host-player", handNum = 4)

        val encoded = json.encodeToString(WireMessage.serializer(), msg)
        assertTrue("expected \"type\":\"blindPhaseComplete\" in $encoded", encoded.contains("\"type\":\"blindPhaseComplete\""))

        val decoded = json.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(msg, decoded)
        assertTrue(decoded is BlindPhaseCompleteMessage)
        assertEquals(4, (decoded as BlindPhaseCompleteMessage).handNum)
    }

    @Test
    fun `distinct hand numbers do not collide after round-trip`() {
        val first  = BlindPhaseCompleteMessage(cmdId = "c1", seat = 0, playerId = "p0", handNum = 5)
        val second = BlindPhaseCompleteMessage(cmdId = "c2", seat = 0, playerId = "p0", handNum = 6)

        val decodedFirst  = json.decodeFromString(WireMessage.serializer(), json.encodeToString(WireMessage.serializer(), first))
        val decodedSecond = json.decodeFromString(WireMessage.serializer(), json.encodeToString(WireMessage.serializer(), second))

        assertEquals(5, (decodedFirst as BlindPhaseCompleteMessage).handNum)
        assertEquals(6, (decodedSecond as BlindPhaseCompleteMessage).handNum)
    }
}
