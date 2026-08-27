package jmotley.com.jspades.networking

import android.util.Log
import jmotley.com.jspades.data.BidMessage
import jmotley.com.jspades.data.BlindOfferMessage
import jmotley.com.jspades.data.BlindPhaseCompleteMessage
import jmotley.com.jspades.data.BlindResponseMessage
import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.DealMessage
import jmotley.com.jspades.data.GameConfigMessage
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.data.MPProtocol
import jmotley.com.jspades.data.MPActionType
import jmotley.com.jspades.data.MPNormalizedAction
import jmotley.com.jspades.data.MPNormalizedPayload
import jmotley.com.jspades.data.MPRetentionResult
import jmotley.com.jspades.data.MPSemanticKey
import jmotley.com.jspades.data.effectiveMinBid
import jmotley.com.jspades.data.PlayCardMessage
import jmotley.com.jspades.data.Rank
import jmotley.com.jspades.data.ReadyForNextHandMessage
import jmotley.com.jspades.data.RequestPlayAgainMessage
import jmotley.com.jspades.data.ReceiptAckMessage
import jmotley.com.jspades.data.ResyncRequestMessage
import jmotley.com.jspades.data.StateSnapshotMessage
import jmotley.com.jspades.data.WireRecoveryReason
import jmotley.com.jspades.data.Suit
import jmotley.com.jspades.data.WireCard
import jmotley.com.jspades.data.WireGameConfig
import jmotley.com.jspades.data.WireMessage
import jmotley.com.jspades.data.WireBidRole
import jmotley.com.jspades.data.WireSeatPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
//import kotlinx.serialization.json.parseToJsonElement
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "WSSMP"
private val relayRebuildJson = Json { ignoreUnknownKeys = true }

/** Internal for shared protocol-fixture tests; production receive routing uses this exact path. */
internal fun rebuildRelayEnvelopeForDecoding(raw: String): String? = runCatching {
    val obj = relayRebuildJson.parseToJsonElement(raw).jsonObject
    val type = (obj["type"] as? JsonPrimitive)?.content ?: return@runCatching null
    if (type !in setOf("gameConfig", "deal", "blindOffer", "blindResponse", "bid", "playCard",
            "readyForNextHand", "requestPlayAgain", "blindPhaseComplete", "receiptAck",
            "resyncRequest", "stateSnapshot")) return@runCatching null
    val payloadObj = obj["payload"] as? JsonObject ?: return@runCatching null

    buildJsonObject {
        put("type", type)
        (obj["cmdId"] as? JsonPrimitive)?.content?.let { put("cmdId", it) }
        payloadObj.forEach { (key, payloadElement) ->
            val stringValue = (payloadElement as? JsonPrimitive)?.content ?: return@forEach
            val inflated = when {
                stringValue.startsWith("{") || stringValue.startsWith("[") ->
                    runCatching { relayRebuildJson.parseToJsonElement(stringValue) }
                        .getOrElse { JsonPrimitive(stringValue) }
                stringValue == "true" -> JsonPrimitive(true)
                stringValue == "false" -> JsonPrimitive(false)
                stringValue.toLongOrNull() != null -> JsonPrimitive(stringValue.toLong())
                stringValue.toDoubleOrNull() != null -> JsonPrimitive(stringValue.toDouble())
                else -> JsonPrimitive(stringValue)
            }
            put(key, inflated)
        }
        val payloadPlayerId = (payloadObj["playerId"] as? JsonPrimitive)?.content
        if (payloadPlayerId.isNullOrEmpty()) {
            (obj["fromPlayerId"] as? JsonPrimitive)?.content?.let { put("playerId", it) }
        }
    }.toString()
}.getOrNull()

// ── Card format conversion ────────────────────────────────────────────────────
// Wire: rank 0-based (TWO=0…ACE=12, specials above 12), suit ♥=0/♣=1/♦=2/♠=3
// Android: rank.value (TWO=2…ACE=14, specials≥15), Suit enum ordinal (♣=0/♦=1/♥=2/♠=3)
// Wire rank = rank.value − 2.  Wire suit uses the ♥-first convention, not Suit.ordinal.

// internal (not private) so MPAdapterTest can assert the wire encoding directly —
// this must stay independent of Suit.displaySortOrder even though the numbers coincide today.
internal fun wireSuitToSuit(w: Int): Suit? = when (w) {
    0 -> Suit.HEARTS; 1 -> Suit.CLUBS; 2 -> Suit.DIAMONDS; 3 -> Suit.SPADES; else -> null
}

internal fun suitToWireSuit(s: Suit): Int = when (s) {
    Suit.HEARTS -> 0; Suit.CLUBS -> 1; Suit.DIAMONDS -> 2; Suit.SPADES -> 3
}

internal fun wireIdToCard(id: String): Card? {
    val sep = id.indexOf('_')
    if (sep < 0) return null
    val wireRank = id.substring(0, sep).toIntOrNull() ?: return null
    val wireSuit = id.substring(sep + 1).toIntOrNull() ?: return null
    val rank = Rank.entries.find { it.value == wireRank + 2 } ?: return null
    val suit = wireSuitToSuit(wireSuit) ?: return null
    return Card(suit, rank)
}

private fun cardToWireId(card: Card): String =
    "${card.rank.value - 2}_${suitToWireSuit(card.suit)}"

private fun wireCardToCard(wc: WireCard): Card? = wireIdToCard(wc.id)

private fun cardToWireCard(card: Card): WireCard = WireCard(
    id    = cardToWireId(card),
    image = card.assetFileName().substringBeforeLast('.')
)

// ── GameType wire string conversion ───────────────────────────────────────────

fun gameTypeToWireString(gt: GameType): String = when (gt) {
    GameType.HOUSE_RULES    -> "houseRules"
    GameType.TEAM_KITTY     -> "kitty"
    GameType.TEAM_CLASSIC   -> "classic"
    GameType.SOLO_FOUR_MAN  -> "fourManSolo"
    GameType.SOLO_THREE_MAN -> "threeManSolo"
    GameType.SOLO_TWO_MAN   -> "twoManSolo"
}

fun wireStringToGameType(wire: String): GameType? = when (wire) {
    "houseRules"   -> GameType.HOUSE_RULES
    "kitty"        -> GameType.TEAM_KITTY
    "classic"      -> GameType.TEAM_CLASSIC
    "fourManSolo"  -> GameType.SOLO_FOUR_MAN
    "threeManSolo" -> GameType.SOLO_THREE_MAN
    "twoManSolo"   -> GameType.SOLO_TWO_MAN
    else           -> null
}

/**
 * Converts this state's currently-applied settings into a [WireGameConfig], for
 * re-broadcasting them verbatim — e.g. [GameViewModel.playAgain] re-announcing the
 * same rules for a new game, rather than a second, possibly-drifted source (like
 * re-reading [android.content.SharedPreferences], which is what [hostWireGameConfig]
 * is for at *initial* game start, before any [GameState] carrying real settings exists
 * yet).
 */
fun GameState.toWireGameConfig(): WireGameConfig = WireGameConfig(
    gameType                = gameTypeToWireString(gameType),
    twoOfSpadesJoker        = twoOfSpadesJoker,
    twoOfDiamondsJoker      = twoOfDiamondsJoker,
    enableDoubleBidBonus    = enableDoubleBidBonus,
    spadesMustBreak         = spadesMustBreak,
    minimumBid              = effectiveMinBid,
    enableSandbagPenalty    = enableSandbagPenalty,
    allowNilBid             = allowNilBid,
    blindNilExchangeEnabled = allowBlindExchange,
    gameLength              = gameLength.name
)

// ── Delegate interface ────────────────────────────────────────────────────────

/**
 * Callbacks invoked by [MPAdapter] on the main thread after each received action.
 * All parameters are in Android types — no wire format knowledge needed here.
 * Implemented by the ViewModel in Phase 5.
 */
interface MPAdapterDelegate {
    /** The generation accepted by the game-state owner; adapters must not cache a second copy. */
    fun currentGameGeneration(): Int
    fun onGameConfig(config: WireGameConfig, seatPlayers: Map<String, WireSeatPlayer>, gameGeneration: Int)
    fun onGameConfig(config: WireGameConfig, seatPlayers: Map<String, WireSeatPlayer>, gameGeneration: Int,
                     capabilities: Set<String>) = onGameConfig(config, seatPlayers, gameGeneration)
    fun onDeal(
        handNum: Int,
        dealerSeat: Int,
        seatOrder: List<String>,
        handsBySeat: Map<String, List<Card>>,
        kitty: List<Card>?,
        kittyOwnerSeat: Int?
    )
    fun onBlindOffer(action: MPNormalizedAction): MPRetentionResult
    fun onBlindResponse(action: MPNormalizedAction): MPRetentionResult
    fun onBlindPhaseComplete(action: MPNormalizedAction): MPRetentionResult
    fun onBid(action: MPNormalizedAction): MPRetentionResult
    fun onPlayCard(action: MPNormalizedAction): MPRetentionResult
    fun onReadyForNextHand(action: MPNormalizedAction): MPRetentionResult
    fun onRequestPlayAgain(action: MPNormalizedAction): MPRetentionResult
    fun onResyncRequest(message: ResyncRequestMessage): MPRetentionResult = MPRetentionResult.RETAINED
    fun onStateSnapshot(message: StateSnapshotMessage): MPRetentionResult = MPRetentionResult.RETAINED
    fun onTerminalDeliveryFailure(cmdId: String, missingSeats: Set<Int>, gameGeneration: Int?, handNum: Int?) {}
}

internal fun shouldAcknowledgeRoutingResult(result: MPRetentionResult): Boolean =
	result == MPRetentionResult.RETAINED || result == MPRetentionResult.DUPLICATE || result == MPRetentionResult.STALE

internal fun isValidWireCommandId(cmdId: String): Boolean = cmdId.isNotBlank()

internal fun validateNegotiatedWireFields(msg: WireMessage, capabilities: Set<String>): Boolean {
	if (msg is GameConfigMessage || msg is ReceiptAckMessage || msg is ResyncRequestMessage || msg is StateSnapshotMessage) return true
	if (MPProtocol.requiresGeneration(capabilities)) {
		val generation = when (msg) {
			is DealMessage -> msg.gameGeneration
			is BlindOfferMessage -> msg.gameGeneration
			is BlindResponseMessage -> msg.gameGeneration
			is BlindPhaseCompleteMessage -> msg.gameGeneration
			is BidMessage -> msg.gameGeneration
			is PlayCardMessage -> msg.gameGeneration
			is ReadyForNextHandMessage -> msg.gameGeneration
			is RequestPlayAgainMessage -> msg.gameGeneration
			else -> null
		}
		if (generation == null) return false
	}
	if (msg is BidMessage && MPProtocol.requiresExplicitBidRole(capabilities) && msg.bidRole == null) return false
	if (msg is PlayCardMessage && (msg.trickNum < 1 || msg.trickPlayNum < 1)) return false
	return true
}

// ── Adapter ───────────────────────────────────────────────────────────────────

/**
 * MPAdapter — the wire-protocol boundary between the WebSocket and the game engine.
 *
 * Responsibilities:
 *  - Decode incoming [WireMessage] JSON from [socket] and route to [delegate]
 *  - Deduplicate messages by cmdId (suppresses relay echoes and reconnect replays)
 *  - Validate sender seat/playerId against the received gameConfig player map
 *  - Convert card IDs and game-type strings between wire and Android formats
 *  - Serialize outgoing actions and send via [socket]
 *  - Invoke all [delegate] callbacks on the main thread
 *
 * Invariant: never advances phase, never computes turn order, never derives
 * payload fields, never sends or consumes local UI phases.
 */
class MPAdapter(
    private val socket: GameSocketClient,
    private val delegate: MPAdapterDelegate,
    private val localSeat: Int,
    private val localPlayerId: String,
    private val roomId: String,
    private val scope: CoroutineScope
) {
    private val seenCmdIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
	private val locallyOriginatedCmdIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
	private data class PendingReceipt(
		val raw: String,
		val expectedSeats: Set<Int>,
		val gameGeneration: Int?,
		val handNum: Int?,
		val acknowledgedSeats: MutableSet<Int> = ConcurrentHashMap.newKeySet()
	)
	private val pendingReceipts = ConcurrentHashMap<String, PendingReceipt>()

	/** Callbacks registered via [sendStateSnapshot]'s `onReceiptComplete` param, fired exactly once
	 * when the matching cmdId's outstanding receipt is fully acknowledged, and discarded (never
	 * fired) if delivery times out instead — see [trackForReceipt]. */
	private val receiptCompletionCallbacks = ConcurrentHashMap<String, () -> Unit>()

    @Volatile
    private var seatPlayerMap: Map<String, WireSeatPlayer> = emptyMap()
	@Volatile
	private var negotiatedCapabilities: Set<String> = emptySet()
	private var negotiatedGeneration: Int? = null

    // Messages that arrive before gameConfig is processed are held here and flushed
    // inside handleGameConfig once seatPlayerMap is populated. This ensures deal and
    // pre-game CPU bids are always applied in the correct order (gameConfig → deal → bid).
    private val preConfigQueue = mutableListOf<WireMessage>()

    private val wireJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ── Receive ───────────────────────────────────────────────────────────────

    /** Call from the socket's onMessage callback; safe to call from any thread. */
    fun receive(raw: String) {
        // The relay only forwards type, fromPlayerId, cmdId, and payload.
        // Try to reconstruct a decodable WireMessage JSON from the relay envelope fields.
        val inner = rebuildFromRelayEnvelope(raw) ?: raw
        val msg = runCatching {
            wireJson.decodeFromString<WireMessage>(inner)
        }.getOrElse {
            Log.w(TAG, "parse failed: ${it.message?.take(80)}")
            return
        }
		if (!isValidWireCommandId(msg.cmdId)) {
			Log.w(TAG, "receive rejected: blank cmdId")
			return
		}

        Log.d(TAG, "receive action=${msg::class.simpleName} cmdId=${msg.cmdId.take(8)} seat=${msg.seat} playerId=${msg.playerId.take(8)}")

        if (!seenCmdIds.add(msg.cmdId)) {
            Log.d(TAG, "dup suppressed cmdId=${msg.cmdId.take(8)}")
			if (msg !is ReceiptAckMessage && msg.cmdId !in locallyOriginatedCmdIds) {
				dispatchAck(msg.cmdId)
			}
            return
        }

        // Defer non-gameConfig messages until seatPlayerMap is populated (i.e. gameConfig
        // has been processed). Buffered replays arrive in wire order (bid seq=4 before
        // gameConfig seq=7), so without this guard pre-game CPU bids are dropped because
        // mpCurrentHandNum is still -1 when they arrive before onDeal.
        if (msg !is GameConfigMessage && seatPlayerMap.isEmpty()) {
			if (preConfigQueue.size >= 256) {
				val evicted = preConfigQueue.removeAt(0)
				seenCmdIds.remove(evicted.cmdId)
				Log.w(TAG, "preConfigQueue bounded eviction cmdId=${evicted.cmdId.take(8)}")
			}
            Log.d(TAG, "preConfigQueue enqueue cmdId=${msg.cmdId.take(8)} type=${msg::class.simpleName}")
            preConfigQueue.add(msg)
            return
        }

        val knownSeats = seatPlayerMap
        if (knownSeats.isNotEmpty() && msg !is GameConfigMessage) {
            val expected = knownSeats[msg.seat.toString()]
            if (expected == null) {
                Log.w(TAG, "identity FAIL unknown seat=${msg.seat}")
                return
            }
            if (expected.playerId != msg.playerId) {
                Log.w(TAG, "identity FAIL seat=${msg.seat} expected=${expected.playerId.take(8)} got=${msg.playerId.take(8)}")
                return
            }
            Log.d(TAG, "identity PASS seat=${msg.seat}")
        }

        scope.launch(Dispatchers.Main) {
            val result = route(msg)
			logRoutingTelemetry(msg, result)
            if (result == MPRetentionResult.REJECTED || result == MPRetentionResult.CONFLICT) {
                seenCmdIds.remove(msg.cmdId)
            }
            if (msg !is ReceiptAckMessage && msg.cmdId !in locallyOriginatedCmdIds && result.isAcknowledged()) {
                dispatchAck(msg.cmdId)
            }
        }
    }

	private fun logRoutingTelemetry(msg: WireMessage, result: MPRetentionResult) {
		val scope = when (msg) {
			is GameConfigMessage -> msg.gameGeneration to null
			is DealMessage -> msg.gameGeneration to msg.handNum
			is BlindOfferMessage -> msg.gameGeneration to msg.handNum
			is BlindResponseMessage -> msg.gameGeneration to msg.handNum
			is BlindPhaseCompleteMessage -> msg.gameGeneration to msg.handNum
			is BidMessage -> msg.gameGeneration to msg.handNum
			is PlayCardMessage -> msg.gameGeneration to msg.handNum
			is ReadyForNextHandMessage -> msg.gameGeneration to msg.handNum
			is RequestPlayAgainMessage -> msg.gameGeneration to null
			is ResyncRequestMessage -> msg.gameGeneration to msg.handNum
			is StateSnapshotMessage -> msg.gameGeneration to msg.handNum
			else -> null to null
		}
		Log.i(TAG, "telemetry event=mp_route action=${msg::class.simpleName} result=$result cmdId=${msg.cmdId.take(8)} generation=${scope.first} hand=${scope.second} capabilities=${negotiatedCapabilities.sorted()}")
	}

    private fun MPRetentionResult.isAcknowledged(): Boolean = shouldAcknowledgeRoutingResult(this)

    private fun route(msg: WireMessage): MPRetentionResult {
        if (!validateNegotiatedWireFields(msg, negotiatedCapabilities)) return MPRetentionResult.REJECTED
        return when (msg) {
            is GameConfigMessage    -> { handleGameConfig(msg); MPRetentionResult.RETAINED }
            is DealMessage          -> { handleDeal(msg); MPRetentionResult.RETAINED }
            is BlindOfferMessage    -> handleBlindOffer(msg)
            is BlindResponseMessage -> handleBlindResponse(msg)
            is BidMessage           -> handleBid(msg)
            is PlayCardMessage      -> handlePlayCard(msg)
            is ReadyForNextHandMessage -> handleReadyForNextHand(msg)
            is RequestPlayAgainMessage -> handleRequestPlayAgain(msg)
            is BlindPhaseCompleteMessage -> handleBlindPhaseComplete(msg)
            is ReceiptAckMessage -> { handleReceiptAck(msg); MPRetentionResult.RETAINED }
            is ResyncRequestMessage -> delegate.onResyncRequest(msg)
            is StateSnapshotMessage -> delegate.onStateSnapshot(msg)
        }
    }
	private fun handleReceiptAck(msg: ReceiptAckMessage) {
		val pending = pendingReceipts[msg.ackedCmdId] ?: run {
			Log.w(TAG, "receipt ack UNMATCHED ackedCmdId=${msg.ackedCmdId.take(8)} fromSeat=${msg.seat} — no pending receipt (already completed/timed out, or cmdId mismatch)")
			return
		}
		pending.acknowledgedSeats.add(msg.seat)
		if (pending.acknowledgedSeats.containsAll(pending.expectedSeats)) {
			pendingReceipts.remove(msg.ackedCmdId)
			Log.d(TAG, "receipt complete cmdId=${msg.ackedCmdId.take(8)}")
			receiptCompletionCallbacks.remove(msg.ackedCmdId)?.invoke()
		}
	}

    private fun handleGameConfig(msg: GameConfigMessage) {
        seatPlayerMap = msg.players
		val previousGeneration = negotiatedGeneration
		if ((previousGeneration == null && msg.gameGeneration >= delegate.currentGameGeneration()) ||
			(previousGeneration != null && msg.gameGeneration > previousGeneration)) {
			negotiatedGeneration = msg.gameGeneration
			negotiatedCapabilities = msg.capabilities
		}
		Log.i(TAG, "telemetry event=mp_capabilities generation=${msg.gameGeneration} protocolVersion=${msg.protocolVersion} acceptedGeneration=$negotiatedGeneration capabilities=${negotiatedCapabilities.sorted()}")
        delegate.onGameConfig(msg.config, msg.players, msg.gameGeneration, msg.capabilities)
        // Flush messages that arrived before gameConfig was processed.
        // Identity-validate each one now that seatPlayerMap is populated.
        // Sort deal before bids/playCards — the queue holds messages in arrival order, but deal
        // must be processed first so mpCurrentHandNum is set before the staleness guard in onBid.
        val queued = preConfigQueue.sortedWith(compareBy { if (it is DealMessage) 0 else 1 })
        preConfigQueue.clear()
        for (qMsg in queued) {
            val expected = seatPlayerMap[qMsg.seat.toString()]
            // Require a known seat that matches, not just "no mismatch" — an absent seat
            // must reject too, matching the live-message check in receive() (see mp-fix.md P1).
            if (expected == null || expected.playerId != qMsg.playerId) {
                Log.w(TAG, "preConfigQueue identity FAIL seat=${qMsg.seat} cmdId=${qMsg.cmdId.take(8)}")
                continue
            }
            Log.d(TAG, "preConfigQueue flush cmdId=${qMsg.cmdId.take(8)} type=${qMsg::class.simpleName}")
            val result = route(qMsg)
			logRoutingTelemetry(qMsg, result)
			if (result == MPRetentionResult.REJECTED || result == MPRetentionResult.CONFLICT) {
				seenCmdIds.remove(qMsg.cmdId)
			}
			if (qMsg !is ReceiptAckMessage && qMsg.cmdId !in locallyOriginatedCmdIds && result.isAcknowledged()) {
				dispatchAck(qMsg.cmdId)
			}
        }
    }

    private fun handleDeal(msg: DealMessage) {
        val handsBySeat = mutableMapOf<String, List<Card>>()
        for ((seatKey, wcs) in msg.hands) {
            val cards = wcs.map { wc ->
                wireCardToCard(wc) ?: run {
                    Log.w(TAG, "handleDeal: unparseable card id=${wc.id} in seat $seatKey — dropping deal")
                    return
                }
            }
            handsBySeat[seatKey] = cards
        }
        val kitty = msg.kitty?.map { wc ->
            wireCardToCard(wc) ?: run {
                Log.w(TAG, "handleDeal: unparseable kitty card id=${wc.id} — dropping deal")
                return
            }
        }
        Log.d(TAG, "handleDeal handNum=${msg.handNum} dealer=${msg.dealerSeat} handSizes=${handsBySeat.mapValues { it.value.size }} kittyCount=${kitty?.size ?: 0}")
        delegate.onDeal(msg.handNum, msg.dealerSeat, msg.seatOrder, handsBySeat, kitty, msg.kittyOwnerSeat)
    }

    private fun handleBlindOffer(msg: BlindOfferMessage): MPRetentionResult {
		val generation = msg.gameGeneration ?: delegate.currentGameGeneration()
		return delegate.onBlindOffer(normalized(msg, MPActionType.BLIND_OFFER, generation, msg.handNum,
			MPNormalizedPayload.BlindOffer(msg.teamSeats, msg.decidingSeats)))
    }

    private fun handleBlindResponse(msg: BlindResponseMessage): MPRetentionResult {
		val generation = msg.gameGeneration ?: delegate.currentGameGeneration()
		return delegate.onBlindResponse(normalized(msg, MPActionType.BLIND_RESPONSE, generation, msg.handNum,
			MPNormalizedPayload.BlindResponse(msg.accepted), seatKey = msg.seat))
    }

    private fun handleBid(msg: BidMessage): MPRetentionResult {
        Log.d(TAG, "handleBid seat=${msg.seat} amount=${msg.amount} isBlind=${msg.isBlind} handNum=${msg.handNum}")
        val generation = msg.gameGeneration ?: delegate.currentGameGeneration()
        val role = msg.bidRole ?: if (msg.isTeamTotal == true) WireBidRole.TEAM_TOTAL else WireBidRole.INDIVIDUAL
        return delegate.onBid(MPNormalizedAction(
            type = MPActionType.BID,
            semanticKey = MPSemanticKey(MPActionType.BID, generation, msg.handNum, msg.seat),
            cmdId = msg.cmdId, senderSeat = msg.seat, senderPlayerId = msg.playerId,
            gameGeneration = generation, handNum = msg.handNum,
            payload = MPNormalizedPayload.Bid(msg.amount, msg.isBlind, role)
        ))
    }

    private fun handlePlayCard(msg: PlayCardMessage): MPRetentionResult {
        val card = wireIdToCard(msg.cardId)
        if (card == null) {
            Log.w(TAG, "handlePlayCard: unparseable cardId=${msg.cardId}")
            return MPRetentionResult.REJECTED
        }
        Log.d(TAG, "handlePlayCard wireId=${msg.cardId} → uid=${card.uid} seat=${msg.seat} hand=${msg.handNum} trick=${msg.trickNum} play=${msg.trickPlayNum}")
        val generation = msg.gameGeneration ?: delegate.currentGameGeneration()
        return delegate.onPlayCard(MPNormalizedAction(
            type = MPActionType.CARD_PLAY,
            semanticKey = MPSemanticKey(MPActionType.CARD_PLAY, generation, msg.handNum, trickNum = msg.trickNum, trickPlayNum = msg.trickPlayNum),
            cmdId = msg.cmdId, senderSeat = msg.seat, senderPlayerId = msg.playerId,
            gameGeneration = generation, handNum = msg.handNum,
            trickNum = msg.trickNum, trickPlayNum = msg.trickPlayNum,
            payload = MPNormalizedPayload.CardPlay(msg.cardId)
        ))
    }

    private fun handleReadyForNextHand(msg: ReadyForNextHandMessage): MPRetentionResult {
        Log.d(TAG, "handleReadyForNextHand seat=${msg.seat} handNum=${msg.handNum}")
		return delegate.onReadyForNextHand(normalized(msg, MPActionType.READY_NEXT_HAND,
			msg.gameGeneration ?: delegate.currentGameGeneration(),
			msg.handNum, MPNormalizedPayload.ReadyNextHand, seatKey = msg.seat))
    }

    private fun handleRequestPlayAgain(msg: RequestPlayAgainMessage): MPRetentionResult {
        Log.d(TAG, "handleRequestPlayAgain seat=${msg.seat} gameGeneration=${msg.gameGeneration}")
		return delegate.onRequestPlayAgain(normalized(msg, MPActionType.PLAY_AGAIN_REQUEST, msg.gameGeneration,
			0, MPNormalizedPayload.PlayAgainRequest, seatKey = msg.seat))
    }

    private fun handleBlindPhaseComplete(msg: BlindPhaseCompleteMessage): MPRetentionResult {
        Log.d(TAG, "handleBlindPhaseComplete handNum=${msg.handNum}")
		val generation = msg.gameGeneration ?: delegate.currentGameGeneration()
		return delegate.onBlindPhaseComplete(normalized(msg, MPActionType.BLIND_PHASE_COMPLETE, generation,
			msg.handNum, MPNormalizedPayload.BlindPhaseComplete))
    }

	private fun normalized(msg: WireMessage, type: MPActionType, generation: Int, handNum: Int,
		payload: MPNormalizedPayload, seatKey: Int? = null) = MPNormalizedAction(type,
		MPSemanticKey(type, generation, handNum, seatKey), msg.cmdId, msg.seat, msg.playerId,
		generation, handNum, payload = payload)

    // ── Send ──────────────────────────────────────────────────────────────────

    fun sendGameConfig(config: WireGameConfig, players: Map<String, WireSeatPlayer>, gameGeneration: Int) {
        seatPlayerMap = players  // seed locally; host suppresses its own echo so handleGameConfig never fires
        dispatch(GameConfigMessage(cmdId = nextCmdId(), seat = localSeat, playerId = localPlayerId,
            config = config, players = players, gameGeneration = gameGeneration,
            protocolVersion = MPProtocol.CURRENT_VERSION,
            capabilities = MPProtocol.advertisedCapabilities))
    }

    fun sendResyncRequest(message: ResyncRequestMessage) = dispatch(message)

	/** Sends [message] and, if [onReceiptComplete] is given, registers it to fire exactly once
	 * the snapshot's cmdId is fully acknowledged by every expected target seat — proof the peer
	 * actually has the data, not merely that a send call was made (a synchronous "socket.send
	 * returned" cannot distinguish a live connection from a silently-dropped frame on a stale
	 * one). The callback is registered before [dispatch] runs so it can never race a fast or
	 * reentrant (e.g. loopback-test) acknowledgment that arrives before registration would
	 * otherwise have happened. If delivery times out instead of being acknowledged, the callback
	 * is discarded, never fired — the caller must treat "never fires" as "delivery did not
	 * succeed," not assume success after a timeout. */
	fun sendStateSnapshot(message: StateSnapshotMessage, onReceiptComplete: (() -> Unit)? = null) {
		onReceiptComplete?.let { receiptCompletionCallbacks[message.cmdId] = it }
		dispatch(message)
	}

    fun sendDeal(
        handNum: Int,
        dealerSeat: Int,
        seatOrder: List<String>,
        handsBySeat: Map<String, List<Card>>,
        kitty: List<Card>? = null,
        kittyOwnerSeat: Int? = null
    ) {
        val wireHands = handsBySeat.mapValues { (_, cards) -> cards.map(::cardToWireCard) }
        val wireKitty = kitty?.map(::cardToWireCard)
        dispatch(DealMessage(cmdId = nextCmdId(), seat = localSeat, playerId = localPlayerId,
            handNum = handNum, dealerSeat = dealerSeat, seatOrder = seatOrder,
            hands = wireHands, kitty = wireKitty, kittyOwnerSeat = kittyOwnerSeat,
            gameGeneration = delegate.currentGameGeneration()))
    }

    fun sendBlindOffer(handNum: Int, teamSeats: List<Int>, decidingSeats: List<Int>) {
        dispatch(BlindOfferMessage(cmdId = nextCmdId(), seat = localSeat, playerId = localPlayerId,
            handNum = handNum, teamSeats = teamSeats, decidingSeats = decidingSeats,
            gameGeneration = delegate.currentGameGeneration()))
    }

    /** Send the local human's blind response. */
    fun sendBlindResponse(accepted: Boolean, handNum: Int) {
        dispatch(BlindResponseMessage(cmdId = nextCmdId(), seat = localSeat, playerId = localPlayerId,
            handNum = handNum, accepted = accepted, gameGeneration = delegate.currentGameGeneration()))
    }

    /** Send a blind response on behalf of a CPU seat (host-proxied). */
    fun sendBlindResponse(actingSeat: Int, actingPlayerId: String, accepted: Boolean, handNum: Int) {
        dispatch(BlindResponseMessage(cmdId = nextCmdId(), seat = actingSeat, playerId = actingPlayerId,
            handNum = handNum, accepted = accepted, gameGeneration = delegate.currentGameGeneration()))
    }

    /**
     * Send a bid. [actingSeat] and [actingPlayerId] identify the seat that placed the bid —
     * the local human seat for human bids, or the CPU seat for host-proxied CPU bids.
     */
    fun newActionCmdId(): String = nextCmdId()

    fun sendBid(
        actingSeat: Int, actingPlayerId: String, amount: Int, isBlind: Boolean,
        handNum: Int, isTeamTotal: Boolean = false, cmdId: String = nextCmdId()
    ) {
        dispatch(BidMessage(cmdId = cmdId, seat = actingSeat, playerId = actingPlayerId,
            handNum = handNum, amount = amount, isBlind = isBlind, isTeamTotal = isTeamTotal,
            bidRole = if (isTeamTotal) WireBidRole.TEAM_TOTAL else WireBidRole.INDIVIDUAL,
            gameGeneration = delegate.currentGameGeneration()))
    }

    /**
     * Send a card play. [actingSeat] and [actingPlayerId] identify the seat that played the card —
     * the local human seat for human plays, or the CPU seat for host-proxied CPU plays.
     */
    fun sendPlayCard(
        actingSeat: Int, actingPlayerId: String,
        card: Card, handNum: Int, trickNum: Int, trickPlayNum: Int,
        cmdId: String = nextCmdId()
    ) {
        dispatch(PlayCardMessage(cmdId = cmdId, seat = actingSeat, playerId = actingPlayerId,
            handNum = handNum, trickNum = trickNum, trickPlayNum = trickPlayNum,
            cardId = cardToWireId(card), gameGeneration = delegate.currentGameGeneration()))
    }

    /** Non-host only: signal that the local player has pressed "Next Hand" and is waiting. */
    fun sendReadyForNextHand(handNum: Int) {
        dispatch(ReadyForNextHandMessage(cmdId = nextCmdId(), seat = localSeat, playerId = localPlayerId,
            handNum = handNum, gameGeneration = delegate.currentGameGeneration()))
    }

    /** Non-host only: signal that the local player has pressed "Play Again" and is waiting. */
    fun sendRequestPlayAgain(gameGeneration: Int) {
        dispatch(RequestPlayAgainMessage(cmdId = nextCmdId(), seat = localSeat, playerId = localPlayerId,
            gameGeneration = gameGeneration))
    }

    /**
     * Host only: signal that every required blind decision for [handNum] is in (or that no
     * team was eligible in the first place), so non-host clients — which never independently
     * decide this — can leave `GamePhase.BlindBid`.
     */
    fun sendBlindPhaseComplete(handNum: Int) {
        dispatch(BlindPhaseCompleteMessage(cmdId = nextCmdId(), seat = localSeat, playerId = localPlayerId,
            handNum = handNum, gameGeneration = delegate.currentGameGeneration()))
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * The relay only forwards these top-level fields verbatim: type, sequence, roomId,
     * fromPlayerId (renamed from playerId), cmdId, and payload (Map<String,String>).
     * Everything else is stripped. This function reconstructs a decodable WireMessage JSON
     * from those survivors, pulling game-specific fields out of the payload map.
     *
     * Payload string values are re-parsed: numeric strings become JSON numbers, "true"/"false"
     * become JSON booleans, and JSON-object/array strings are parsed back to their structure so
     * kotlinx-serialization can decode complex field types (WireGameConfig, List<WireCard>, etc.).
     *
     * Returns null for non-WireMessage envelope types (startGame, startCountdown, etc.) so
     * the caller can fall back to the legacy parseIncoming path.
     */
    private fun rebuildFromRelayEnvelope(raw: String): String? = rebuildRelayEnvelopeForDecoding(raw)

    private fun dispatch(msg: WireMessage) {
        seenCmdIds.add(msg.cmdId)
		locallyOriginatedCmdIds.add(msg.cmdId)
        val extra = when (msg) {
            is BidMessage           -> "hand=${msg.handNum} amount=${msg.amount} blind=${msg.isBlind}"
            is PlayCardMessage      -> "hand=${msg.handNum} trick=${msg.trickNum} play=${msg.trickPlayNum} card=${msg.cardId}"
            is DealMessage          -> "hand=${msg.handNum} dealer=${msg.dealerSeat}"
            is BlindResponseMessage -> "hand=${msg.handNum} accepted=${msg.accepted}"
            is ReadyForNextHandMessage -> "hand=${msg.handNum}"
            else -> ""
        }
        Log.d(TAG, "dispatch ${msg::class.simpleName} cmdId=${msg.cmdId.take(8)} seat=${msg.seat} player=${msg.playerId.take(8)} $extra".trimEnd())

        // Serialize WireMessage fields and encode them into the relay payload as Map<String,String>.
        // The relay strips every top-level field except type/roomId/fromPlayerId/cmdId/payload, so ALL
        // game-specific data (seat, amount, config, hands, …) must live in payload to survive transit.
        // Complex values (objects, arrays) become JSON-stringified strings; the receiver re-parses them.
        val wireObj = wireJson.parseToJsonElement(
            wireJson.encodeToString(WireMessage.serializer(), msg)
        ).jsonObject
        val msgType = (wireObj["type"] as? JsonPrimitive)?.content ?: return
        val payload = buildMap<String, String> {
            wireObj.forEach { (k, v) ->
                if (k != "type") {
                    put(k, if (v is JsonPrimitive) v.content else v.toString())
                }
            }
        }
		val raw = MpEnvelope(
            type    = msgType,
            playerId = localPlayerId,
            roomId  = roomId,
            cmdId   = msg.cmdId,
            payload = payload
        ).toJson()
		// Register receipt tracking BEFORE transmitting: a fast or reentrant (e.g. loopback-test)
		// acknowledgment must never be able to race ahead of pendingReceipts/receiptCompletionCallbacks
		// bookkeeping and be discarded as unmatched — see mp-fix.md's send-before-track finding.
		if (msg !is ReceiptAckMessage) {
			val targets = when (msg) {
				is StateSnapshotMessage -> setOf(msg.targetSeat)
				is ResyncRequestMessage -> setOf(0)
				else -> null
			}
			val actionScope = when (msg) {
				is BidMessage -> msg.gameGeneration to msg.handNum
				is PlayCardMessage -> msg.gameGeneration to msg.handNum
				is DealMessage -> msg.gameGeneration to msg.handNum
				is BlindOfferMessage -> msg.gameGeneration to msg.handNum
				is BlindResponseMessage -> msg.gameGeneration to msg.handNum
				is BlindPhaseCompleteMessage -> msg.gameGeneration to msg.handNum
				is ReadyForNextHandMessage -> msg.gameGeneration to msg.handNum
				is ResyncRequestMessage -> msg.gameGeneration to msg.handNum
				is StateSnapshotMessage -> msg.gameGeneration to msg.handNum
				is GameConfigMessage -> msg.gameGeneration to null
				is RequestPlayAgainMessage -> msg.gameGeneration to null
				is ReceiptAckMessage -> null to null
			}
			trackForReceipt(msg.cmdId, raw, targets, actionScope.first, actionScope.second)
		}
		socket.send(raw)
    }

	private fun dispatchAck(ackedCmdId: String) {
		dispatch(ReceiptAckMessage(nextCmdId(), localSeat, localPlayerId, ackedCmdId))
	}

	private fun trackForReceipt(cmdId: String, raw: String, targetSeats: Set<Int>? = null,
		gameGeneration: Int? = null, handNum: Int? = null) {
		val humans = seatPlayerMap.entries.mapNotNull { (seat, player) ->
			seat.toIntOrNull()?.takeIf {
				it != localSeat && player.kind != "cpu" && !player.playerId.startsWith("cpu-")
			}
		}.toSet()
		val expected = targetSeats?.intersect(humans) ?: humans
		if (expected.isEmpty()) return
		val pending = PendingReceipt(raw, expected, gameGeneration, handNum)
		pendingReceipts[cmdId] = pending
		scope.launch {
			repeat(4) {
				delay(1_500)
				if (pendingReceipts[cmdId] !== pending) return@launch
				Log.w(TAG, "receipt retry cmdId=${cmdId.take(8)} attempt=${it + 1}")
				socket.send(raw)
			}
			if (pendingReceipts[cmdId] === pending) {
				Log.e(TAG, "receipt timeout cmdId=${cmdId.take(8)} missing=${expected - pending.acknowledgedSeats}")
				pendingReceipts.remove(cmdId, pending)
				// Discard without firing — delivery did not succeed, so a registered
				// onReceiptComplete callback (e.g. bid-recovery gate completion) must never run.
				receiptCompletionCallbacks.remove(cmdId)
				delegate.onTerminalDeliveryFailure(cmdId, expected - pending.acknowledgedSeats,
					pending.gameGeneration, pending.handNum)
			}
		}
	}

    private fun nextCmdId() = UUID.randomUUID().toString()
}
