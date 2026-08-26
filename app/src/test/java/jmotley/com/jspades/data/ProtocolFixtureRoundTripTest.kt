package jmotley.com.jspades.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import jmotley.com.jspades.networking.rebuildRelayEnvelopeForDecoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Phase 0 of MD/auto-mp.md ("Automated Multiplayer Testing" plan, see also
 * /Users/jerodmotley/Documents/Dev/VS Code/jspades-mp-testing). Loads the shared
 * protocol-fixtures/ set vendored under app/src/test/resources/protocol-fixtures/
 * (standard Gradle JVM test classpath resource location — no build.gradle.kts
 * changes needed) and, for each fixture declared in manifest.json, checks that
 * its declared outcome ("accept" or "reject") matches what direct decode into
 * the concrete WireMessage subtype actually does — plus, for "accept" fixtures,
 * that decode -> encode -> decode round-trips to an equal value.
 *
 * Fixtures decode directly into a concrete data class (e.g. BidMessage), not
 * through the WireMessage sealed class's "type"-discriminated polymorphic
 * decode — the fixtures use "action" as their top-level key (matching iOS's
 * native, same-platform frame shape) rather than the relay envelope's "type" +
 * "payload" wrapper. Direct concrete decode ignores that extra field via
 * ignoreUnknownKeys, exactly mirroring how the iOS-side counterpart
 * (ProtocolFixtureRoundTripTests.swift) decodes straight into MPWire* types.
 *
 * jspades-mp-testing/scripts/run-phase0-cross-roundtrip.sh collects Android's
 * encoded artifacts through PHASE0_OUTPUT_DIR and supplies iOS output through
 * PHASE0_INPUT_DIR for the literal Android -> iOS -> Android pipeline.
 */
class ProtocolFixtureRoundTripTest {

    @Serializable
    private data class FixtureManifestEntry(
        val path: String,
        val messageType: String,
        val outcome: String,
        val reasonCategory: String? = null
    )

    @Serializable
    private data class FixtureManifest(
        val fixtures: List<FixtureManifestEntry>
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun reasonCategory(error: SerializationException): String = when {
        error.message?.contains("required for type", ignoreCase = true) == true ->
            "decodeRejected:missingField"
        error.message?.contains("missing", ignoreCase = true) == true &&
            error.message?.contains("field", ignoreCase = true) == true ->
            "decodeRejected:missingField"
        else -> "decodeRejected:typeMismatch"
    }

    private fun relayEnvelope(nativeText: String, messageType: String): String {
        val body = json.parseToJsonElement(nativeText).jsonObject
        val payload = buildJsonObject {
            body.forEach { (key, value) ->
                if (key != "action") {
                    put(key, JsonPrimitive(if (value is JsonPrimitive) value.content else value.toString()))
                }
            }
        }
        return buildJsonObject {
            put("type", messageType)
            put("cmdId", body["cmdId"]?.jsonPrimitive?.content ?: "")
            put("fromPlayerId", body["playerId"]?.jsonPrimitive?.content ?: "")
            put("roomId", "PHASE0")
            put("payload", payload)
        }.toString()
    }

    private fun fixturesRoot(): File {
        val resource = ProtocolFixtureRoundTripTest::class.java.classLoader!!
            .getResource("protocol-fixtures/manifest.json")
            ?: error(
                "protocol-fixtures/manifest.json not found on the test classpath — " +
                    "run jspades-mp-testing/scripts/sync-fixtures.sh to vendor fixtures first"
            )
        return requireNotNull(File(resource.toURI()).parentFile)
    }

    private inline fun <reified T> assertFixture(entry: FixtureManifestEntry, root: File) {
        val text = File(root, entry.path).readText()
        when (entry.outcome) {
            "accept" -> {
                val decoded = json.decodeFromString<T>(text)
                val reencoded = json.encodeToString(serializer<T>(), decoded)
                val redecoded = json.decodeFromString<T>(reencoded)
                assertEquals("${entry.path} did not round-trip through re-encode/decode", decoded, redecoded)
                val relay = relayEnvelope(text, entry.messageType)
                val rebuilt = requireNotNull(rebuildRelayEnvelopeForDecoding(relay)) {
                    "${entry.path}: production relay-envelope rebuild returned null"
                }
                val relayDecoded = json.decodeFromString<T>(rebuilt)
                assertEquals("${entry.path} did not survive production relay-envelope rebuild", decoded, relayDecoded)
                System.getenv("PHASE0_OUTPUT_DIR")?.takeIf(String::isNotBlank)?.let { outputRoot ->
                    File(outputRoot, entry.path).apply {
                        parentFile.mkdirs()
                        writeText(reencoded)
                    }
                }
            }
            "reject" -> {
                try {
                    json.decodeFromString<T>(text)
                    fail("${entry.path}: expected reject but decode succeeded")
                } catch (expected: SerializationException) {
                    assertEquals(
                        "${entry.path}: rejection category mismatch for ${expected.message}",
                        entry.reasonCategory,
                        reasonCategory(expected)
                    )
                }
            }
            else -> fail("${entry.path}: unknown declared outcome \"${entry.outcome}\"")
        }
    }

    @Test
    fun allDeclaredFixturesMatchTheirManifestOutcome() {
        val root = fixturesRoot()
        val manifest = json.decodeFromString<FixtureManifest>(File(root, "manifest.json").readText())
        assertTrue("manifest.json declared no fixtures", manifest.fixtures.isNotEmpty())

        for (entry in manifest.fixtures) {
            when (entry.messageType) {
                "gameConfig" -> assertFixture<GameConfigMessage>(entry, root)
                "deal" -> assertFixture<DealMessage>(entry, root)
                "blindOffer" -> assertFixture<BlindOfferMessage>(entry, root)
                "blindResponse" -> assertFixture<BlindResponseMessage>(entry, root)
                "blindPhaseComplete" -> assertFixture<BlindPhaseCompleteMessage>(entry, root)
                "bid" -> assertFixture<BidMessage>(entry, root)
                "playCard" -> assertFixture<PlayCardMessage>(entry, root)
                "readyForNextHand" -> assertFixture<ReadyForNextHandMessage>(entry, root)
                "requestPlayAgain" -> assertFixture<RequestPlayAgainMessage>(entry, root)
                "receiptAck" -> assertFixture<ReceiptAckMessage>(entry, root)
                "resyncRequest" -> assertFixture<ResyncRequestMessage>(entry, root)
                "stateSnapshot" -> assertFixture<StateSnapshotMessage>(entry, root)
                else -> fail("${entry.path}: unknown messageType \"${entry.messageType}\" — add a case above")
            }
        }
    }

    @Test
    fun externalCrossToolchainOutputsDecode() {
        val inputRoot = System.getenv("PHASE0_INPUT_DIR")?.takeIf(String::isNotBlank) ?: return
        val fixtureRoot = fixturesRoot()
        val manifest = json.decodeFromString<FixtureManifest>(File(fixtureRoot, "manifest.json").readText())
        for (entry in manifest.fixtures.filter { it.outcome == "accept" }) {
            val text = File(inputRoot, entry.path).readText()
            when (entry.messageType) {
                "gameConfig" -> json.decodeFromString<GameConfigMessage>(text)
                "deal" -> json.decodeFromString<DealMessage>(text)
                "blindOffer" -> json.decodeFromString<BlindOfferMessage>(text)
                "blindResponse" -> json.decodeFromString<BlindResponseMessage>(text)
                "blindPhaseComplete" -> json.decodeFromString<BlindPhaseCompleteMessage>(text)
                "bid" -> json.decodeFromString<BidMessage>(text)
                "playCard" -> json.decodeFromString<PlayCardMessage>(text)
                "readyForNextHand" -> json.decodeFromString<ReadyForNextHandMessage>(text)
                "requestPlayAgain" -> json.decodeFromString<RequestPlayAgainMessage>(text)
                "receiptAck" -> json.decodeFromString<ReceiptAckMessage>(text)
                "resyncRequest" -> json.decodeFromString<ResyncRequestMessage>(text)
                "stateSnapshot" -> json.decodeFromString<StateSnapshotMessage>(text)
                else -> fail("${entry.path}: unknown messageType ${entry.messageType}")
            }
        }
    }
}
