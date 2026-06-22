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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MPAdapter"

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
    private val scope: CoroutineScope
) {
    private val seenCmdIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var seatPlayerMap: Map<String, WireSeatPlayer> = emptyMap()

    private val wireJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ── Receive ───────────────────────────────────────────────────────────────

    /** Call from the socket's onMessage callback; safe to call from any thread. */
    fun receive(raw: String) {
        val msg = runCatching {
            wireJson.decodeFromString<WireMessage>(raw)
        }.getOrElse {
            Log.w(TAG, "parse failed: ${it.message?.take(80)}")
            return
        }

        if (!seenCmdIds.add(msg.cmdId)) {
            Log.d(TAG, "dup cmdId=${msg.cmdId.take(8)}")
            return
        }

        val knownSeats = seatPlayerMap
        if (knownSeats.isNotEmpty() && msg !is GameConfigMessage) {
            val expected = knownSeats[msg.seat.toString()]
            if (expected == null) {
                Log.w(TAG, "dropping message for unknown seat=${msg.seat}")
                return
            }
            if (expected.playerId != msg.playerId) {
                Log.w(TAG, "identity mismatch seat=${msg.seat} expected=${expected.playerId} got=${msg.playerId}")
                return
            }
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
        delegate.onDeal(msg.handNum, msg.dealerSeat, msg.seatOrder, handsBySeat, kitty, msg.kittyWinnerId)
    }

    private fun handleBlindOffer(msg: BlindOfferMessage) {
        delegate.onBlindOffer(msg.handNum, msg.teamSeats, msg.decidingSeats)
    }

    private fun handleBlindResponse(msg: BlindResponseMessage) {
        delegate.onBlindResponse(msg.seat, msg.accepted, msg.handNum)
    }

    private fun handleBid(msg: BidMessage) {
        delegate.onBid(msg.seat, msg.amount, msg.isBlind, msg.handNum)
    }

    private fun handlePlayCard(msg: PlayCardMessage) {
        val card = wireIdToCard(msg.cardId)
        if (card == null) {
            Log.w(TAG, "handlePlayCard: unparseable cardId=${msg.cardId}")
            return
        }
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

    private fun dispatch(msg: WireMessage) {
        seenCmdIds.add(msg.cmdId)
        socket.send(wireJson.encodeToString(WireMessage.serializer(), msg))
    }

    private fun nextCmdId() = UUID.randomUUID().toString()
}
