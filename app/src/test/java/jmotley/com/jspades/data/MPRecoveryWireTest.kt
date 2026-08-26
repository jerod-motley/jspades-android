package jmotley.com.jspades.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MPRecoveryWireTest {
    private val json = Json { classDiscriminator = "type"; encodeDefaults = true }

    @Test fun recoveryMessagesRoundTripThroughWireMessage() {
        val request: WireMessage = ResyncRequestMessage("c1", 1, "p1", "r1", 1, 3, 8,
            WireRecoveryReason.CONFLICTING_FACT, "BID:3:8:1", listOf("old", "new"), 4, "digest")
        assertEquals(request, json.decodeFromString(WireMessage.serializer(),
            json.encodeToString(WireMessage.serializer(), request)))

        val snapshot: WireMessage = StateSnapshotMessage("c2", 0, "host", "s5", "r1", 1,
            3, 8, 5, "{\"phase\":\"bid\"}", listOf("fact"), listOf("c1"))
        assertEquals(snapshot, json.decodeFromString(WireMessage.serializer(),
            json.encodeToString(WireMessage.serializer(), snapshot)))
    }
}
