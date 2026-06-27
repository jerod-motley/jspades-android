package jmotley.com.jspades.networking

import android.util.Log
import jmotley.com.jspades.data.BidMessage
import jmotley.com.jspades.data.BlindOfferMessage
import jmotley.com.jspades.data.BlindResponseMessage
import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.DealMessage
import jmotley.com.jspades.data.GameConfigMessage
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.data.PlayCardMessage
import jmotley.com.jspades.data.Rank
import jmotley.com.jspades.data.Suit
import jmotley.com.jspades.data.WireCard
import jmotley.com.jspades.data.WireGameConfig
import jmotley.com.jspades.data.WireMessage
import jmotley.com.jspades.data.WireSeatPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

// ── Card format conversion ────────────────────────────────────────────────────
// Wire: rank 0-based (TWO=0…ACE=12, specials above 12), suit ♥=0/♣=1/♦=2/♠=3
// Android: rank.value (TWO=2…ACE=14, specials≥15), Suit enum ordinal (♣=0/♦=1/♥=2/♠=3)
// Wire rank = rank.value − 2.  Wire suit uses the ♥-first convention, not Suit.ordinal.

private fun wireSuitToSuit(w: Int): Suit? = when (w) {
    0 -> Suit.HEARTS; 1 -> Suit.CLUBS; 2 -> Suit.DIAMONDS; 3 -> Suit.SPADES; else -> null
}

private fun suitToWireSuit(s: Suit): Int = when (s) {
    Suit.HEARTS -> 0; Suit.CLUBS -> 1; Suit.DIAMONDS -> 2; Suit.SPADES -> 3
}

private fun wireIdToCard(id: String): Card? {
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

// ── Delegate interface ────────────────────────────────────────────────────────

/**
 * Callbacks invoked by [MPAdapter] on the main thread after each received action.
 * All parameters are in Android types — no wire format knowledge needed here.
 * Implemented by the ViewModel in Phase 5.
 */
interface MPAdapterDelegate {
    fun onGameConfig(config: WireGameConfig, seatPlayers: Map<String, WireSeatPlayer>)
    fun onDeal(
        handNum: Int,
        dealerSeat: Int,
        seatOrder: List<String>,
        handsBySeat: Map<String, List<Card>>,
        kitty: List<Card>?,
        kittyWinnerId: String?
    )
    fun onBlindOffer(handNum: Int, teamSeats: List<Int>, decidingSeats: List<Int>)
    fun onBlindResponse(seat: Int, accepted: Boolean, handNum: Int)
    fun onBid(seat: Int, amount: Int, isBlind: Boolean, handNum: Int)
    fun onPlayCard(seat: Int, cardUid: String, handNum: Int, trickNum: Int, trickPlayNum: Int)
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

    @Volatile
    private var seatPlayerMap: Map<String, WireSeatPlayer> = emptyMap()

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

        Log.d(TAG, "receive action=${msg::class.simpleName} cmdId=${msg.cmdId.take(8)} seat=${msg.seat} playerId=${msg.playerId.take(8)}")

        if (!seenCmdIds.add(msg.cmdId)) {
            Log.d(TAG, "dup suppressed cmdId=${msg.cmdId.take(8)}")
            return
        }

        // Defer non-gameConfig messages until seatPlayerMap is populated (i.e. gameConfig
        // has been processed). Buffered replays arrive in wire order (bid seq=4 before
        // gameConfig seq=7), so without this guard pre-game CPU bids are dropped because
        // mpCurrentHandNum is still -1 when they arrive before onDeal.
        if (msg !is GameConfigMessage && seatPlayerMap.isEmpty()) {
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

        scope.launch(Dispatchers.Main) { route(msg) }
    }

    private fun route(msg: WireMessage) {
        when (msg) {
            is GameConfigMessage    -> handleGameConfig(msg)
            is DealMessage          -> handleDeal(msg)
            is BlindOfferMessage    -> handleBlindOffer(msg)
            is BlindResponseMessage -> handleBlindResponse(msg)
            is BidMessage           -> handleBid(msg)
            is PlayCardMessage      -> handlePlayCard(msg)
        }
    }

    private fun handleGameConfig(msg: GameConfigMessage) {
        seatPlayerMap = msg.players
        delegate.onGameConfig(msg.config, msg.players)
        // Flush messages that arrived before gameConfig was processed.
        // Identity-validate each one now that seatPlayerMap is populated.
        // Sort deal before bids/playCards — the queue holds messages in arrival order, but deal
        // must be processed first so mpCurrentHandNum is set before the staleness guard in onBid.
        val queued = preConfigQueue.sortedWith(compareBy { if (it is DealMessage) 0 else 1 })
        preConfigQueue.clear()
        for (qMsg in queued) {
            val expected = seatPlayerMap[qMsg.seat.toString()]
            if (expected != null && expected.playerId != qMsg.playerId) {
                Log.w(TAG, "preConfigQueue identity FAIL seat=${qMsg.seat} cmdId=${qMsg.cmdId.take(8)}")
                continue
            }
            Log.d(TAG, "preConfigQueue flush cmdId=${qMsg.cmdId.take(8)} type=${qMsg::class.simpleName}")
            scope.launch(Dispatchers.Main) { route(qMsg) }
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
        delegate.onDeal(msg.handNum, msg.dealerSeat, msg.seatOrder, handsBySeat, kitty, msg.kittyWinnerId)
    }

    private fun handleBlindOffer(msg: BlindOfferMessage) {
        delegate.onBlindOffer(msg.handNum, msg.teamSeats, msg.decidingSeats)
    }

    private fun handleBlindResponse(msg: BlindResponseMessage) {
        delegate.onBlindResponse(msg.seat, msg.accepted, msg.handNum)
    }

    private fun handleBid(msg: BidMessage) {
        Log.d(TAG, "handleBid seat=${msg.seat} amount=${msg.amount} isBlind=${msg.isBlind} handNum=${msg.handNum}")
        delegate.onBid(msg.seat, msg.amount, msg.isBlind, msg.handNum)
    }

    private fun handlePlayCard(msg: PlayCardMessage) {
        val card = wireIdToCard(msg.cardId)
        if (card == null) {
            Log.w(TAG, "handlePlayCard: unparseable cardId=${msg.cardId}")
            return
        }
        Log.d(TAG, "handlePlayCard wireId=${msg.cardId} → uid=${card.uid} seat=${msg.seat} hand=${msg.handNum} trick=${msg.trickNum} play=${msg.trickPlayNum}")
        delegate.onPlayCard(msg.seat, card.uid, msg.handNum, msg.trickNum, msg.trickPlayNum)
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    fun sendGameConfig(config: WireGameConfig, players: Map<String, WireSeatPlayer>) {
        seatPlayerMap = players  // seed locally; host suppresses its own echo so handleGameConfig never fires
        dispatch(GameConfigMessage(cmdId = nextCmdId(), seat = localSeat, playerId = localPlayerId,
            config = config, players = players))
    }

    fun sendDeal(
        handNum: Int,
        dealerSeat: Int,
        seatOrder: List<String>,
        handsBySeat: Map<String, List<Card>>,
        kitty: List<Card>? = null,
        kittyWinnerId: String? = null
    ) {
        val wireHands = handsBySeat.mapValues { (_, cards) -> cards.map(::cardToWireCard) }
        val wireKitty = kitty?.map(::cardToWireCard)
        dispatch(DealMessage(cmdId = nextCmdId(), seat = localSeat, playerId = localPlayerId,
            handNum = handNum, dealerSeat = dealerSeat, seatOrder = seatOrder,
            hands = wireHands, kitty = wireKitty, kittyWinnerId = kittyWinnerId))
    }

    fun sendBlindOffer(handNum: Int, teamSeats: List<Int>, decidingSeats: List<Int>) {
        dispatch(BlindOfferMessage(cmdId = nextCmdId(), seat = localSeat, playerId = localPlayerId,
            handNum = handNum, teamSeats = teamSeats, decidingSeats = decidingSeats))
    }

    /** Send the local human's blind response. */
    fun sendBlindResponse(accepted: Boolean, handNum: Int) {
        dispatch(BlindResponseMessage(cmdId = nextCmdId(), seat = localSeat, playerId = localPlayerId,
            handNum = handNum, accepted = accepted))
    }

    /** Send a blind response on behalf of a CPU seat (host-proxied). */
    fun sendBlindResponse(actingSeat: Int, actingPlayerId: String, accepted: Boolean, handNum: Int) {
        dispatch(BlindResponseMessage(cmdId = nextCmdId(), seat = actingSeat, playerId = actingPlayerId,
            handNum = handNum, accepted = accepted))
    }

    /**
     * Send a bid. [actingSeat] and [actingPlayerId] identify the seat that placed the bid —
     * the local human seat for human bids, or the CPU seat for host-proxied CPU bids.
     */
    fun sendBid(actingSeat: Int, actingPlayerId: String, amount: Int, isBlind: Boolean, handNum: Int) {
        dispatch(BidMessage(cmdId = nextCmdId(), seat = actingSeat, playerId = actingPlayerId,
            handNum = handNum, amount = amount, isBlind = isBlind))
    }

    /**
     * Send a card play. [actingSeat] and [actingPlayerId] identify the seat that played the card —
     * the local human seat for human plays, or the CPU seat for host-proxied CPU plays.
     */
    fun sendPlayCard(
        actingSeat: Int, actingPlayerId: String,
        card: Card, handNum: Int, trickNum: Int, trickPlayNum: Int
    ) {
        dispatch(PlayCardMessage(cmdId = nextCmdId(), seat = actingSeat, playerId = actingPlayerId,
            handNum = handNum, trickNum = trickNum, trickPlayNum = trickPlayNum,
            cardId = cardToWireId(card)))
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
    private fun rebuildFromRelayEnvelope(raw: String): String? = runCatching {
        val obj = wireJson.parseToJsonElement(raw).jsonObject
        val type = (obj["type"] as? JsonPrimitive)?.content ?: return@runCatching null
        if (type !in setOf("gameConfig", "deal", "blindOffer", "blindResponse", "bid", "playCard")) {
            return@runCatching null
        }
        val payloadObj = obj["payload"] as? JsonObject ?: return@runCatching null

        buildJsonObject {
            put("type", type)
            (obj["cmdId"] as? JsonPrimitive)?.content?.let { put("cmdId", it) }
            // Relay renames "playerId" to "fromPlayerId"; map it back.
            (obj["fromPlayerId"] as? JsonPrimitive)?.content?.let { put("playerId", it) }
            // Re-inflate payload string values to their proper JSON types.
            // Only attempt JSON parsing for values that are unambiguously JSON structures or
            // pure primitives — never for arbitrary strings such as UUIDs, which a permissive
            // parser may partially decode (e.g. "51D61897-..." → 51), leaving cmdId unquoted.
            payloadObj.forEach { (k, payloadElem) ->
                runCatching {
                    val strVal = (payloadElem as JsonPrimitive).content
                    val elem = when {
                        strVal.startsWith("{") || strVal.startsWith("[") ->
                            runCatching { wireJson.parseToJsonElement(strVal) }.getOrElse { JsonPrimitive(strVal) }
                        strVal == "true"  -> JsonPrimitive(true)
                        strVal == "false" -> JsonPrimitive(false)
                        strVal.toLongOrNull() != null   -> JsonPrimitive(strVal.toLong())
                        strVal.toDoubleOrNull() != null -> JsonPrimitive(strVal.toDouble())
                        else -> JsonPrimitive(strVal)
                    }
                    put(k, elem)
                }
            }
        }.toString()
    }.getOrNull()

    private fun dispatch(msg: WireMessage) {
        seenCmdIds.add(msg.cmdId)
        val extra = when (msg) {
            is BidMessage           -> "hand=${msg.handNum} amount=${msg.amount} blind=${msg.isBlind}"
            is PlayCardMessage      -> "hand=${msg.handNum} trick=${msg.trickNum} play=${msg.trickPlayNum} card=${msg.cardId}"
            is DealMessage          -> "hand=${msg.handNum} dealer=${msg.dealerSeat}"
            is BlindResponseMessage -> "hand=${msg.handNum} accepted=${msg.accepted}"
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
        socket.send(MpEnvelope(
            type    = msgType,
            playerId = localPlayerId,
            roomId  = roomId,
            cmdId   = msg.cmdId,
            payload = payload
        ).toJson())
    }

    private fun nextCmdId() = UUID.randomUUID().toString()
}
