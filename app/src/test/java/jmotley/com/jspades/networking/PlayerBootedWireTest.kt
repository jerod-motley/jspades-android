package jmotley.com.jspades.networking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the "idle disconnect freezes the room" bug: the relay's
 * `PlayerBooted` frame used to fall through to `SpadesMPMessage.Unknown` (Android logged
 * `parse failed: Serializer for subclass 'PlayerBooted' is not registered` then
 * `UNKNOWN message`), so a booted seat — including the host booting itself — was never
 * acted on.
 */
class PlayerBootedWireTest {

    @Test
    fun `PlayerBooted parses with playerId and reason`() {
        val json = """{"type":"PlayerBooted","roomId":"T34MQ6","playerId":"179bb96a-8571-4b70-936e-a59e24d9f14f","reason":"idle"}"""
        val msg = parseIncoming(json)
        check(msg is SpadesMPMessage.PlayerBooted) { "expected PlayerBooted, got ${msg::class.simpleName}" }
        assertEquals("179bb96a-8571-4b70-936e-a59e24d9f14f", msg.personId)
        assertEquals("idle", msg.reason)
    }

    @Test
    fun `PlayerBooted parses with a missing reason`() {
        val json = """{"type":"PlayerBooted","roomId":"T34MQ6","playerId":"abc"}"""
        val msg = parseIncoming(json)
        check(msg is SpadesMPMessage.PlayerBooted) { "expected PlayerBooted, got ${msg::class.simpleName}" }
        assertEquals("abc", msg.personId)
        assertEquals("", msg.reason)
    }
}
