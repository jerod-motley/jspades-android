package jmotley.com.jspades.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertNull(bid.bidRole)
        assertNull(bid.gameGeneration)
    }

    @Test
    fun versionTwoBidRoundTripsExplicitRoleAndGeneration() {
        val original = BidMessage(
            cmdId = "bid-2", seat = 2, playerId = "p2", handNum = 4,
            amount = 5, isBlind = false, isTeamTotal = true,
            bidRole = WireBidRole.TEAM_TOTAL, gameGeneration = 3
        )

        val encoded = json.encodeToString(BidMessage.serializer(), original)
        val decoded = json.decodeFromString<BidMessage>(encoded)

        assertEquals(WireBidRole.TEAM_TOTAL, decoded.bidRole)
        assertEquals(3, decoded.gameGeneration)
        assertTrue(encoded.contains("\"bidRole\":\"teamTotal\""))
    }

    @Test
    fun legacyHandScopedMessagesDecodeWithoutGeneration() {
        val deal = json.decodeFromString<DealMessage>(
            """{"cmdId":"d1","seat":0,"playerId":"p0","handNum":1,"dealerSeat":0,"seatOrder":[],"hands":{}}"""
        )
        val offer = json.decodeFromString<BlindOfferMessage>(
            """{"cmdId":"o1","seat":0,"playerId":"p0","handNum":1,"teamSeats":[0,2],"decidingSeats":[0]}"""
        )
        val response = json.decodeFromString<BlindResponseMessage>(
            """{"cmdId":"r1","seat":0,"playerId":"p0","handNum":1,"accepted":true}"""
        )
        val complete = json.decodeFromString<BlindPhaseCompleteMessage>(
            """{"cmdId":"c1","seat":0,"playerId":"p0","handNum":1}"""
        )
        val ready = json.decodeFromString<ReadyForNextHandMessage>(
            """{"cmdId":"n1","seat":1,"playerId":"p1","handNum":1}"""
        )
        val play = json.decodeFromString<PlayCardMessage>(
            """{"cmdId":"p1","seat":1,"playerId":"p1","handNum":1,"trickNum":1,"trickPlayNum":2,"cardId":"12_3"}"""
        )

        assertNull(deal.gameGeneration)
        assertNull(offer.gameGeneration)
        assertNull(response.gameGeneration)
        assertNull(complete.gameGeneration)
        assertNull(ready.gameGeneration)
        assertNull(play.gameGeneration)
    }

    @Test
    fun versionTwoHandScopedMessagesRoundTripGeneration() {
        val generation = 8
        val deal = DealMessage("d2", 0, "p0", 2, 0, listOf("p0"), emptyMap(), gameGeneration = generation)
        val offer = BlindOfferMessage("o2", 0, "p0", 2, listOf(0, 2), listOf(0), generation)
        val response = BlindResponseMessage("r2", 0, "p0", 2, true, generation)
        val complete = BlindPhaseCompleteMessage("c2", 0, "p0", 2, generation)
        val ready = ReadyForNextHandMessage("n2", 1, "p1", 2, generation)
        val play = PlayCardMessage("p2", 1, "p1", 2, 3, 2, "12_3", generation)

        assertEquals(generation, json.decodeFromString<DealMessage>(json.encodeToString(DealMessage.serializer(), deal)).gameGeneration)
        assertEquals(generation, json.decodeFromString<BlindOfferMessage>(json.encodeToString(BlindOfferMessage.serializer(), offer)).gameGeneration)
        assertEquals(generation, json.decodeFromString<BlindResponseMessage>(json.encodeToString(BlindResponseMessage.serializer(), response)).gameGeneration)
        assertEquals(generation, json.decodeFromString<BlindPhaseCompleteMessage>(json.encodeToString(BlindPhaseCompleteMessage.serializer(), complete)).gameGeneration)
        assertEquals(generation, json.decodeFromString<ReadyForNextHandMessage>(json.encodeToString(ReadyForNextHandMessage.serializer(), ready)).gameGeneration)
        assertEquals(generation, json.decodeFromString<PlayCardMessage>(json.encodeToString(PlayCardMessage.serializer(), play)).gameGeneration)
    }

    @Test
    fun legacyGameConfigDefaultsToVersionOneWithoutCapabilities() {
        val config = json.decodeFromString<GameConfigMessage>(
            """{"cmdId":"g1","seat":0,"playerId":"host","config":{"gameType":"HOUSE_RULES","twoOfSpadesJoker":false,"twoOfDiamondsJoker":false,"enableDoubleBidBonus":false,"spadesMustBreak":false,"minimumBid":4,"enableSandbagPenalty":true,"allowNilBid":false,"blindNilExchangeEnabled":false,"gameLength":"MEDIUM"},"players":{},"gameGeneration":1}"""
        )

        assertEquals(1, config.protocolVersion)
        assertTrue(config.capabilities.isEmpty())
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
