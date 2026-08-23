package jmotley.com.jspades.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format regression coverage for the "Next Hand" bug fix: a non-host client sends
 * [ReadyForNextHandMessage] instead of dealing itself a bogus local hand. This pins the
 * sealed [WireMessage] discriminator round-trip — the part of the fix that doesn't depend
 * on `GameViewModel`/`AndroidViewModel`, which this project's plain JUnit setup (no
 * Robolectric, no `returnDefaultValues`) can't instantiate.
 */
class ReadyForNextHandMessageTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Test
    fun `serializes with the readyForNextHand type discriminator and round-trips`() {
        val msg = ReadyForNextHandMessage(cmdId = "abc-123", seat = 2, playerId = "player-2", handNum = 5)

        val encoded = json.encodeToString(WireMessage.serializer(), msg)
        assertTrue("expected \"type\":\"readyForNextHand\" in $encoded", encoded.contains("\"type\":\"readyForNextHand\""))

        val decoded = json.decodeFromString(WireMessage.serializer(), encoded)
        assertEquals(msg, decoded)
        assertTrue(decoded is ReadyForNextHandMessage)
    }

    @Test
    fun `distinct hand numbers do not collide after round-trip`() {
        val first  = ReadyForNextHandMessage(cmdId = "c1", seat = 1, playerId = "p1", handNum = 3)
        val second = ReadyForNextHandMessage(cmdId = "c2", seat = 1, playerId = "p1", handNum = 4)

        val decodedFirst  = json.decodeFromString(WireMessage.serializer(), json.encodeToString(WireMessage.serializer(), first))
        val decodedSecond = json.decodeFromString(WireMessage.serializer(), json.encodeToString(WireMessage.serializer(), second))

        assertEquals(3, (decodedFirst as ReadyForNextHandMessage).handNum)
        assertEquals(4, (decodedSecond as ReadyForNextHandMessage).handNum)
    }
}
