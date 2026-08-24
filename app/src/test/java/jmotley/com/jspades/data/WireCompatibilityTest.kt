package jmotley.com.jspades.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WireCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun legacySeatPlayerWithoutKindDecodes() {
        val player = json.decodeFromString<WireSeatPlayer>(
            """{"playerId":"cpu-2","displayName":"CPU 2"}"""
        )

        assertEquals("", player.kind)
    }

    @Test
    fun legacyBidWithoutTeamTotalDecodesForTurnBasedInference() {
        val bid = json.decodeFromString<BidMessage>(
            """{"cmdId":"c1","seat":2,"playerId":"p2","handNum":1,"amount":7,"isBlind":false}"""
        )

        assertNull(bid.isTeamTotal)
    }

	@Test
	fun receiptAcknowledgementDecodesWithOriginalCommandIdentity() {
		val ack = json.decodeFromString<ReceiptAckMessage>(
			"""{"cmdId":"ack-1","seat":1,"playerId":"peer-1","ackedCmdId":"play-1"}"""
		)

		assertEquals("play-1", ack.ackedCmdId)
		assertEquals(1, ack.seat)
	}
}
