package jmotley.com.jspades.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireCompatibilityTest {
	@Test fun protocolPolicyKeepsLegacyOptionalAndEnforcesNegotiatedFields() {
		assertFalse(MPProtocol.requiresGeneration(emptySet()))
		assertFalse(MPProtocol.requiresExplicitBidRole(emptySet()))
		assertTrue(MPProtocol.requiresGeneration(setOf(MPProtocol.CAP_GENERATION_SCOPED_ACTIONS)))
		assertTrue(MPProtocol.requiresExplicitBidRole(setOf(MPProtocol.CAP_EXPLICIT_BID_ROLE)))
	}
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

	/** todo.md fix #3: the host's authoritative trick winner. Must round-trip through the
	 *  sealed [WireMessage] discriminator and stay cross-platform-shaped with iOS's
	 *  `MPWireTrickResolved` (flat `handNum` / `trickNum` / `winnerSeat` / optional `gameGeneration`). */
	@Test
	fun trickResolvedRoundTripsThroughTheSealedDiscriminatorAndKeepsGenerationOptional() {
		val legacy = json.decodeFromString<TrickResolvedMessage>(
			"""{"cmdId":"tr-1","seat":0,"playerId":"host","handNum":1,"trickNum":12,"winnerSeat":2}"""
		)
		assertNull(legacy.gameGeneration)
		assertEquals(12, legacy.trickNum)
		assertEquals(2, legacy.winnerSeat)

		val msg = TrickResolvedMessage(
			cmdId = "tr-2", seat = 0, playerId = "host",
			handNum = 3, trickNum = 5, winnerSeat = 1, gameGeneration = 7
		)
		val encoded = json.encodeToString(WireMessage.serializer(), msg)
		assertTrue("expected \"type\":\"trickResolved\" in $encoded", encoded.contains("\"type\":\"trickResolved\""))

		val decoded = json.decodeFromString(WireMessage.serializer(), encoded)
		assertTrue(decoded is TrickResolvedMessage)
		assertEquals(msg, decoded)
		assertEquals(7, (decoded as TrickResolvedMessage).gameGeneration)
	}

	/** todo.md fix #4: the host's authoritative in-progress trick state, used to unfreeze a
	 *  trick-phase desync. Nested map/list fields must survive the sealed-discriminator
	 *  round-trip and the relay-envelope rebuild (JSON-string inflation). */
	@Test
	fun trickStateRoundTripsNestedCollectionsThroughTheSealedDiscriminator() {
		val msg = TrickStateMessage(
			cmdId = "ts-1", seat = 0, playerId = "host", handNum = 1, trickNum = 12,
			leaderSeat = 2, spadesBroken = true,
			plays = listOf(TrickStatePlay(2, "8_3"), TrickStatePlay(3, "4_1")),
			handsBySeat = mapOf("2" to listOf("10_3", "11_3"), "3" to listOf("2_1")),
			booksBySeat = mapOf("0" to 4, "1" to 3, "2" to 4, "3" to 1),
			targetSeat = 1, responseToRequestId = "req-9", gameGeneration = 5,
		)
		val encoded = json.encodeToString(WireMessage.serializer(), msg)
		assertTrue("expected \"type\":\"trickState\" in $encoded", encoded.contains("\"type\":\"trickState\""))

		val decoded = json.decodeFromString(WireMessage.serializer(), encoded)
		assertTrue(decoded is TrickStateMessage)
		assertEquals(msg, decoded)
	}

	@Test
	fun trickStateRebuildsFromARelayEnvelope() {
		val plays = """[{\"seat\":2,\"cardId\":\"8_3\"},{\"seat\":3,\"cardId\":\"4_1\"}]"""
		val hands = """{\"2\":[\"10_3\"],\"3\":[\"2_1\"]}"""
		val books = """{\"0\":4,\"1\":3,\"2\":4,\"3\":1}"""
		val relayFrame = """{"type":"trickState","cmdId":"ts-2","roomId":"LBHLLD","fromPlayerId":"host",""" +
			""""payload":{"seat":"0","playerId":"host","handNum":"1","trickNum":"12","leaderSeat":"2",""" +
			""""spadesBroken":"true","plays":"$plays","handsBySeat":"$hands","booksBySeat":"$books",""" +
			""""targetSeat":"1","responseToRequestId":"req-9","gameGeneration":"5"}}"""

		val rebuilt = jmotley.com.jspades.networking.rebuildRelayEnvelopeForDecoding(relayFrame)
		assertTrue("relay frame should be recognised as a game action", rebuilt != null)
		val decoded = json.decodeFromString(WireMessage.serializer(), rebuilt!!)
		assertTrue(decoded is TrickStateMessage)
		decoded as TrickStateMessage
		assertEquals(12, decoded.trickNum)
		assertEquals(2, decoded.leaderSeat)
		assertTrue(decoded.spadesBroken)
		assertEquals(listOf(TrickStatePlay(2, "8_3"), TrickStatePlay(3, "4_1")), decoded.plays)
		assertEquals(listOf("10_3"), decoded.handsBySeat["2"])
		assertEquals(4, decoded.booksBySeat["2"])
		assertEquals(1, decoded.targetSeat)
	}

	/** The relay strips every top-level field except type/cmdId/payload, so a relay-forwarded
	 *  `trickResolved` frame must still rebuild into a decodable [TrickResolvedMessage] — i.e.
	 *  the type has to be in `rebuildRelayEnvelopeForDecoding`'s allowlist. */
	@Test
	fun trickResolvedRebuildsFromARelayEnvelope() {
		val relayFrame = """{"type":"trickResolved","cmdId":"tr-3","roomId":"LBHLLD","fromPlayerId":"host",""" +
			""""payload":{"seat":"0","playerId":"host","handNum":"4","trickNum":"9","winnerSeat":"3","gameGeneration":"2"}}"""

		val rebuilt = jmotley.com.jspades.networking.rebuildRelayEnvelopeForDecoding(relayFrame)
		assertTrue("relay frame should be recognised as a game action", rebuilt != null)

		val decoded = json.decodeFromString(WireMessage.serializer(), rebuilt!!)
		assertTrue(decoded is TrickResolvedMessage)
		decoded as TrickResolvedMessage
		assertEquals("tr-3", decoded.cmdId)
		assertEquals(4, decoded.handNum)
		assertEquals(9, decoded.trickNum)
		assertEquals(3, decoded.winnerSeat)
		assertEquals(2, decoded.gameGeneration)
	}
}
