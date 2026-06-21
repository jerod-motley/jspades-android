package jmotley.com.jspades.networking

import android.util.Log
import jmotley.com.jspades.data.OnlineSeat
import jmotley.com.jspades.data.SeatKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

// ── Game object data model ────────────────────────────────────────────────────

@Serializable
data class MpTrick(
    val trickNum: Int,
    val leader: Int,
    val plays: List<String>,  // indexed by room seat; "" = not played yet
    val winner: Int           // -1 if trick not yet complete
)

@Serializable
data class MpHandDeal(
    val seat0: List<String>,
    val seat1: List<String>,
    val seat2: List<String>,
    val seat3: List<String>
) {
    fun forSeat(seat: Int): List<String> = when (seat) {
        0    -> seat0
        1    -> seat1
        2    -> seat2
        else -> seat3
    }
}

@Serializable
data class MpHand(
    val handNum: Int,
    val dealerSeat: Int,           // room seat index of the dealer for this hand
    val seatBids: List<Int>,       // indexed by room seat
    val team0Bid: Int,
    val team1Bid: Int,
    val team0Tricks: Int,
    val team1Tricks: Int,
    val deal: MpHandDeal,
    val tricks: List<MpTrick>,
    val seatDidBid: List<Boolean> = emptyList(),     // indexed by room seat; empty = unknown
    val seatCurrentHands: List<List<String>> = emptyList() // indexed by room seat; empty = not from iOS state
)

@Serializable
data class MpSeatInfo(
    val seatIndex: Int,
    val playerId: String,
    val playerName: String,
    val team: Int,
    val isHuman: Boolean = false
)

@Serializable
data class GameObj(
    val phase: String,         // "bid" | "trick" | "score" | "endHand" | "finished"
    val waitingSeat: Int,      // room seat index of who must act next; -1 if nobody
    val gameOver: Boolean,
    val team0Score: Int,
    val team1Score: Int,
    val team0Bags: Int,
    val team1Bags: Int,
    val seats: List<MpSeatInfo>,
    val hands: List<MpHand>
) {
    val currentHand: MpHand? get() = hands.lastOrNull()
    val currentTrick: MpTrick? get() = currentHand?.tricks?.lastOrNull()
}

data class PendingGameState(
    val obj: GameObj,
    val sequence: Int
)

/** Wire envelope for the `gameState` message (nested data, not flat payload map). */
@Serializable
data class GameStateEnvelope(
    val action: String = "sendMessage",
    val type: String = "gameState",
    val playerId: String,
    val sessionId: String = playerId,
    val gm_type: String = "spades",
    val gm_sub_type: String = "houseRules",
    val roomId: String,
    val cmdId: String,
    val data: GameObj
)

// ── Wire envelope ─────────────────────────────────────────────────────────────

@Serializable
data class MpEnvelope(
    val action: String = "sendMessage",
    val type: String,
    val playerId: String,
    val sessionId: String = playerId,
    val gm_type: String = "spades",
    val gm_sub_type: String = "houseRules",
    val roomId: String,
    val cmdId: String? = null,
    val payload: Map<String, String>? = null
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

fun MpEnvelope.toJson(): String = json.encodeToString(this)

// ── Parsed message types ──────────────────────────────────────────────────────

sealed class SpadesMPMessage {
    data class SnapShot(val personId: String, val roomId: String, val cmdId: String, val sequence: Int, val phase: String, val payload: Map<String, String>) : SpadesMPMessage()
    data class Start(val personId: String, val roomId: String, val cmdId: String) : SpadesMPMessage()
    /** iOS sends the final seat map + gameType inside the startGame payload. */
    data class StartGame(val personId: String, val roomId: String, val cmdId: String, val payload: Map<String, String>) : SpadesMPMessage()
    /** Sent by the host to begin the countdown. Clients show the timer then navigate together. */
    data class StartCountdown(val personId: String, val roomId: String, val cmdId: String, val seconds: Int) : SpadesMPMessage()
    /** A human player's individual bid (first on team) or team-total bid (second on team). */
    data class PlayerBid(val personId: String, val roomId: String, val cmdId: String, val seatIndex: Int, val bidAmount: Int) : SpadesMPMessage()
    data class BlindOffer(val personId: String, val roomId: String, val cmdId: String, val handNum: Int, val eligibleSeats: List<Int>) : SpadesMPMessage()
    data class BlindResponse(val personId: String, val roomId: String, val cmdId: String, val handNum: Int, val seatIndex: Int, val accepted: Boolean) : SpadesMPMessage()
    data class Deal(val personId: String, val roomId: String, val cmdId: String, val handNum: Int, val dealerSeat: Int, val firstLead: Int, val hands: Map<Int, List<String>>) : SpadesMPMessage()
    data class Bid(val personId: String, val roomId: String, val cmdId: String, val handNum: Int, val teamIndex: Int, val seatIndex: Int, val bidAmount: Int, val isBlind: Boolean) : SpadesMPMessage()
    data class PlayCard(val personId: String, val roomId: String, val cmdId: String, val handNum: Int, val trickNum: Int, val leadSeat: Int, val seatIndex: Int, val card: String) : SpadesMPMessage()
    data class TrickResult(val personId: String, val roomId: String, val cmdId: String, val handNum: Int, val trickNum: Int, val winnerSeat: Int) : SpadesMPMessage()
    data class HandScore(val personId: String, val roomId: String, val cmdId: String, val handNum: Int, val team0Score: Int, val team1Score: Int, val gameOver: Boolean) : SpadesMPMessage()
    data class GameFinished(val personId: String, val roomId: String, val cmdId: String, val handNum: Int, val winnerTeam: Int) : SpadesMPMessage()
    data class Chat(val personId: String, val roomId: String, val cmdId: String, val displayName: String, val message: String) : SpadesMPMessage()
    data class RoomJoined(val roomId: String, val myPlayerId: String) : SpadesMPMessage()
    data class PlayerJoined(val roomId: String, val playerId: String, val seatIndex: Int) : SpadesMPMessage()
    data class PlayerInfo(val personId: String, val roomId: String, val displayName: String) : SpadesMPMessage()
    data class PlayerDisconnected(val personId: String) : SpadesMPMessage()
    data class PlayerReconnected(val personId: String) : SpadesMPMessage()
    data class RoomFull(val roomId: String) : SpadesMPMessage()
    data class GameState(val personId: String, val roomId: String, val cmdId: String, val sequence: Int, val data: GameObj) : SpadesMPMessage()
    data class Unknown(val raw: String) : SpadesMPMessage()
}

private data class IosPlayerInfo(val uuid: String, val seat: Int, val team: Int, val name: String, val isHuman: Boolean)

/**
 * Parses an iOS-format `gameState` message (payload: {json: "..."}) and converts it to
 * a [GameObj] that Android's client pipeline understands.
 *
 * iOS wire format differences from Android's GameStateEnvelope:
 *  - Game data is in payload.json (a JSON string), not in a top-level "data" object.
 *  - perPlayer is an alternating [uuid, state, uuid, state, …] JSON array, not a map.
 *  - dealtHands is an alternating [uuid, [cards], uuid, [cards], …] JSON array.
 *  - Card rank/suit ordinals are the same 0-based integers Android uses in "rank_suit" tokens.
 *
 * Returns null if the message is not in iOS format or the parse fails for any reason.
 */
private fun tryParseIosLegacyGameState(obj: JsonObject): GameObj? = runCatching {
    val payloadJson = obj["payload"]?.jsonObject?.get("json")?.jsonPrimitive?.content
        ?: return null
    val ios = json.parseToJsonElement(payloadJson).jsonObject

    // ── Players ───────────────────────────────────────────────────────────────
    val playersArr = ios["players"]?.jsonArray ?: return null
    val players = playersArr.mapNotNull { p ->
        runCatching {
            val po = p.jsonObject
            IosPlayerInfo(
                uuid    = po["id"]!!.jsonPrimitive.content,
                seat    = po["seatIndex"]!!.jsonPrimitive.intOrNull!!,
                team    = po["team"]!!.jsonPrimitive.intOrNull!!,
                name    = po["name"]?.jsonPrimitive?.content ?: "",
                isHuman = po["isHuman"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }.getOrNull()
    }
    if (players.isEmpty()) return null

    val uuidToSeat = players.associate { it.uuid to it.seat }
    val uuidToTeam = players.associate { it.uuid to it.team }
    val seatToUuid = players.associate { it.seat to it.uuid }
    val canonIds   = listOf("south", "west", "north", "east")
    val seats = players.map { p ->
        MpSeatInfo(p.seat, canonIds.getOrElse(p.seat) { "seat${p.seat}" }, p.name, p.team, p.isHuman)
    }

    // ── Phase ─────────────────────────────────────────────────────────────────
    val iosPhase = ios["phase"]?.jsonPrimitive?.content ?: "bid"
    val androidPhase = when (iosPhase) {
        "bid", "bidHuman", "bidReview"        -> "bid"
        "trick", "trickHuman"                 -> "trick"
        "trickResolve"                        -> "trickResolve"
        "score", "endHand", "handScore"       -> "score"
        "finished", "gameFinished"            -> "finished"
        "blindOffer", "blindResponse"         -> "blindOffer"
        else                                  -> "bid"
    }

    // ── Helper: parse perPlayer as alternating array or plain object ───────────
    fun parsePerPlayer(node: JsonElement?): Map<String, JsonObject> {
        val out = mutableMapOf<String, JsonObject>()
        when (node) {
            is JsonArray -> {
                var i = 0
                while (i + 1 < node.size) {
                    val uuid  = runCatching { node[i].jsonPrimitive.content }.getOrNull() ?: break
                    val state = runCatching { node[i + 1].jsonObject }.getOrNull() ?: break
                    out[uuid] = state; i += 2
                }
            }
            is JsonObject -> node.entries.forEach { (k, v) ->
                runCatching { out[k] = v.jsonObject }
            }
            else -> {}
        }
        return out
    }

    // ── Hands ─────────────────────────────────────────────────────────────────
    val handsArr = ios["hands"]?.jsonArray ?: return null
    val mpHands  = handsArr.mapIndexedNotNull { handIdx, handEl ->
        runCatching {
            val hand      = handEl.jsonObject
            val perPlayer = parsePerPlayer(hand["perPlayer"])

            val seatBids = (0..3).map { seat ->
                perPlayer[seatToUuid[seat] ?: ""]?.get("bid")?.jsonPrimitive?.intOrNull ?: 0
            }
            val seatDidBid = (0..3).map { seat ->
                perPlayer[seatToUuid[seat] ?: ""]?.get("didBid")?.jsonPrimitive?.booleanOrNull ?: false
            }
            val teamBidsArr = hand["teamBids"]?.jsonArray
            val team0Bid    = teamBidsArr?.getOrNull(0)?.jsonPrimitive?.intOrNull ?: 0
            val team1Bid    = teamBidsArr?.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 0
            val team0Tricks = players.filter { it.team == 0 }
                .sumOf { p -> perPlayer[p.uuid]?.get("tricksTaken")?.jsonPrimitive?.intOrNull ?: 0 }
            val team1Tricks = players.filter { it.team == 1 }
                .sumOf { p -> perPlayer[p.uuid]?.get("tricksTaken")?.jsonPrimitive?.intOrNull ?: 0 }

            // dealtHands alternating array → "rank_suit" tokens by seat
            val dealBySeat = mutableMapOf<Int, List<String>>()
            val dhEl = hand["dealtHands"]
            if (dhEl is JsonArray) {
                var j = 0
                while (j + 1 < dhEl.size) {
                    val uuid  = runCatching { dhEl[j].jsonPrimitive.content }.getOrNull() ?: break
                    val cards = runCatching { dhEl[j + 1].jsonArray }.getOrNull() ?: break
                    val seat  = uuidToSeat[uuid]
                    if (seat != null) {
                        dealBySeat[seat] = cards.mapNotNull { c ->
                            runCatching {
                                val co   = c.jsonObject
                                val rank = co["rank"]!!.jsonPrimitive.intOrNull!!
                                val suit = co["suit"]!!.jsonPrimitive.intOrNull!!
                                "${rank}_${suit}"
                            }.getOrNull()
                        }
                    }
                    j += 2
                }
            }
            val deal = MpHandDeal(
                seat0 = dealBySeat[0] ?: emptyList(),
                seat1 = dealBySeat[1] ?: emptyList(),
                seat2 = dealBySeat[2] ?: emptyList(),
                seat3 = dealBySeat[3] ?: emptyList()
            )

            // Past completed tricks from hands[].tricks array
            val pastTricks = hand["tricks"]?.jsonArray?.mapIndexedNotNull { tIdx, tEl ->
                runCatching {
                    val t      = tEl.jsonObject
                    val leader = t["leaderSeat"]?.jsonPrimitive?.intOrNull
                        ?: t["currentLeaderSeat"]?.jsonPrimitive?.intOrNull ?: 0
                    val plays  = MutableList(4) { "" }
                    t["plays"]?.jsonArray?.forEach { pEl ->
                        runCatching {
                            val pObj = pEl.jsonObject
                            val uuid = pObj["playerId"]!!.jsonPrimitive.content
                            val c    = pObj["card"]!!.jsonObject
                            val seat = uuidToSeat[uuid]!!
                            plays[seat] = "${c["rank"]!!.jsonPrimitive.intOrNull}_${c["suit"]!!.jsonPrimitive.intOrNull}"
                        }
                    }
                    MpTrick(tIdx, leader, plays, t["winnerSeat"]?.jsonPrimitive?.intOrNull ?: -1)
                }.getOrNull()
            } ?: emptyList()

            // Current hands from perPlayer[uuid].hand — iOS removes played cards, making this authoritative
            val seatCurrentHands = (0..3).map { seat ->
                val uuid = seatToUuid[seat] ?: return@map emptyList()
                perPlayer[uuid]?.get("hand")?.jsonArray?.mapNotNull { c ->
                    runCatching {
                        val co = c.jsonObject
                        "${co["rank"]!!.jsonPrimitive.intOrNull!!}_${co["suit"]!!.jsonPrimitive.intOrNull!!}"
                    }.getOrNull()
                } ?: emptyList()
            }

            // Current in-progress trick reconstructed from perPlayer[uuid].currentPlay
            val leaderSeat = hand["currentLeaderSeat"]?.jsonPrimitive?.intOrNull ?: 0
            val curPlays   = MutableList(4) { "" }
            perPlayer.forEach { (uuid, state) ->
                runCatching {
                    val cp   = state["currentPlay"]?.jsonObject ?: return@runCatching
                    val seat = uuidToSeat[uuid]!!
                    curPlays[seat] = "${cp["rank"]!!.jsonPrimitive.intOrNull}_${cp["suit"]!!.jsonPrimitive.intOrNull}"
                }
            }
            // In trick phase always append a current-trick sentinel so currentTrick.trickNum
            // reflects the actual trick index (pastTricks.size), even before anyone has played.
            val allTricks = if (androidPhase == "trick" || androidPhase == "trickResolve")
                pastTricks + MpTrick(pastTricks.size, leaderSeat, curPlays, -1)
            else pastTricks

            MpHand(
                handNum          = hand["number"]?.jsonPrimitive?.intOrNull ?: handIdx,
                dealerSeat       = hand["dealerSeatIndex"]?.jsonPrimitive?.intOrNull ?: 0,
                seatBids         = seatBids,
                seatDidBid       = seatDidBid,
                team0Bid         = team0Bid,
                team1Bid         = team1Bid,
                team0Tricks      = team0Tricks,
                team1Tricks      = team1Tricks,
                deal             = deal,
                tricks           = allTricks,
                seatCurrentHands = seatCurrentHands
            )
        }.getOrNull()
    }

    // ── waitingSeat ───────────────────────────────────────────────────────────
    val lastHand = handsArr.lastOrNull()?.jsonObject
    val lastPP   = parsePerPlayer(lastHand?.get("perPlayer"))
    // Android-generated iOS-format messages embed waitingSeat directly at the root
    val waitingSeat = ios["waitingSeat"]?.jsonPrimitive?.intOrNull
        ?: when (androidPhase) {
            "bid" -> {
                val nextUuid = lastHand?.get("bidOrder")?.jsonArray
                    ?.firstOrNull { el ->
                        val u = runCatching { el.jsonPrimitive.content }.getOrNull()
                        u != null && lastPP[u]?.get("didBid")?.jsonPrimitive?.booleanOrNull != true
                    }?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                uuidToSeat[nextUuid] ?: -1
            }
            "trick" -> {
                val leader = lastHand?.get("currentLeaderSeat")?.jsonPrimitive?.intOrNull ?: 0
                (0..3).map { off -> (leader + off) % 4 }.firstOrNull { seat ->
                    lastPP[seatToUuid[seat] ?: ""]?.get("hasPlayedThisTrick")
                        ?.jsonPrimitive?.booleanOrNull != true
                } ?: -1
            }
            "blindOffer" -> {
                // Seats that have already decided (accepted or declined blind bid)
                val parseUuidSet: (String) -> Set<String> = { key ->
                    ios[key]?.jsonArray
                        ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                        ?.toSet() ?: emptySet()
                }
                val decided = parseUuidSet("pendingBlindBids") + parseUuidSet("pendingBlindDeclines")
                // First human guest who hasn't decided yet is the waiting seat
                players.filter { p -> p.isHuman && !decided.contains(p.uuid) }
                    .mapNotNull { p -> uuidToSeat[p.uuid] }
                    .firstOrNull() ?: -1
            }
            else -> -1
        }

    // ── Scores ────────────────────────────────────────────────────────────────
    // Android-generated messages embed scores directly; fall back to scoreboard or perPlayer sums
    val team0Score = ios["team0Score"]?.jsonPrimitive?.intOrNull
        ?: run {
            var t = 0
            val scoresObj = ios["scoreboard"]?.jsonObject?.get("scores")?.jsonObject
            if (!scoresObj.isNullOrEmpty()) {
                scoresObj.forEach { (key, v) ->
                    // iOS keys scores by team index ("0"/"1"); Android keys by UUID — handle both
                    val team = uuidToTeam[key] ?: key.toIntOrNull()
                    if (team == 0) t += v.jsonPrimitive.intOrNull ?: 0
                }
            } else {
                players.filter { it.team == 0 }.forEach { p ->
                    t += lastPP[p.uuid]?.get("totalScore")?.jsonPrimitive?.intOrNull ?: 0
                }
            }
            t
        }
    val team1Score = ios["team1Score"]?.jsonPrimitive?.intOrNull
        ?: run {
            var t = 0
            val scoresObj = ios["scoreboard"]?.jsonObject?.get("scores")?.jsonObject
            if (!scoresObj.isNullOrEmpty()) {
                scoresObj.forEach { (key, v) ->
                    val team = uuidToTeam[key] ?: key.toIntOrNull()
                    if (team == 1) t += v.jsonPrimitive.intOrNull ?: 0
                }
            } else {
                players.filter { it.team == 1 }.forEach { p ->
                    t += lastPP[p.uuid]?.get("totalScore")?.jsonPrimitive?.intOrNull ?: 0
                }
            }
            t
        }
    val team0Bags = ios["team0Bags"]?.jsonPrimitive?.intOrNull ?: 0
    val team1Bags = ios["team1Bags"]?.jsonPrimitive?.intOrNull ?: 0

    Log.i("WSSMP", "iOS legacy gameState → phase=$androidPhase waitingSeat=$waitingSeat hands=${mpHands.size}")
    GameObj(
        phase       = androidPhase,
        waitingSeat = waitingSeat,
        gameOver    = iosPhase in listOf("finished", "gameFinished"),
        team0Score  = team0Score,
        team1Score  = team1Score,
        team0Bags   = team0Bags,
        team1Bags   = team1Bags,
        seats       = seats,
        hands       = mpHands
    )
}.getOrElse { e ->
    Log.w("WSSMP", "tryParseIosLegacyGameState failed: ${e.message?.take(120)}")
    null
}

fun parseIncoming(raw: String): SpadesMPMessage = try {
    val obj: JsonObject = json.parseToJsonElement(raw).jsonObject
    val type = obj["type"]?.jsonPrimitive?.content ?: return SpadesMPMessage.Unknown(raw)
    val personId = (obj["playerId"] ?: obj["fromPlayerId"] ?: obj["personId"])?.jsonPrimitive?.content ?: ""
    val roomId = obj["roomId"]?.jsonPrimitive?.content ?: ""
    val cmdId = obj["cmdId"]?.jsonPrimitive?.content ?: ""
    val payload: Map<String, String> = obj["payload"]?.jsonObject
        ?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()

    when (type) {
        "snapShot" -> SpadesMPMessage.SnapShot(personId, roomId, cmdId,
            sequence = obj["sequence"]?.jsonPrimitive?.intOrNull ?: 0,
            phase = payload["phase"] ?: "", payload)
        "start"     -> SpadesMPMessage.Start(personId, roomId, cmdId)
        "startGame"      -> SpadesMPMessage.StartGame(personId, roomId, cmdId, payload)
        "startCountdown" -> SpadesMPMessage.StartCountdown(personId, roomId, cmdId,
            seconds = payload["seconds"]?.toIntOrNull() ?: 5)
        "playerBid" -> SpadesMPMessage.PlayerBid(personId, roomId, cmdId,
            seatIndex = payload["seatIndex"]?.toIntOrNull() ?: 0,
            bidAmount = payload["bidAmount"]?.toIntOrNull() ?: 0)
        "blindOffer" -> SpadesMPMessage.BlindOffer(
            personId, roomId, cmdId,
            handNum = payload["handNum"]?.toIntOrNull() ?: 0,
            eligibleSeats = payload["eligibleSeats"]?.split(" ")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        )
        "blindResponse" -> SpadesMPMessage.BlindResponse(
            personId, roomId, cmdId,
            handNum = payload["handNum"]?.toIntOrNull() ?: 0,
            seatIndex = payload["seatIndex"]?.toIntOrNull() ?: 0,
            accepted = payload["accepted"] == "true"
        )
        "deal" -> SpadesMPMessage.Deal(
            personId, roomId, cmdId,
            handNum = payload["handNum"]?.toIntOrNull() ?: 0,
            dealerSeat = payload["dealerSeat"]?.toIntOrNull() ?: 0,
            firstLead = payload["firstLead"]?.toIntOrNull() ?: 1,
            hands = (0..3).associate { seat ->
                seat to (payload["seat${seat}Hand"]?.split(" ")
                    ?.map { normalizeCard(it) } ?: emptyList())
            }
        )
        "bid" -> SpadesMPMessage.Bid(
            personId, roomId, cmdId,
            handNum = payload["handNum"]?.toIntOrNull() ?: 0,
            teamIndex = payload["teamIndex"]?.toIntOrNull() ?: 0,
            seatIndex = payload["seatIndex"]?.toIntOrNull() ?: -1,
            bidAmount = payload["bidAmount"]?.toIntOrNull() ?: -1,
            isBlind = payload["isBlind"] == "true"
        )
        "playCard" -> SpadesMPMessage.PlayCard(
            personId, roomId, cmdId,
            handNum = payload["handNum"]?.toIntOrNull() ?: 0,
            trickNum = payload["trickNum"]?.toIntOrNull() ?: 0,
            leadSeat = payload["leadSeat"]?.toIntOrNull() ?: 0,
            seatIndex = payload["seatIndex"]?.toIntOrNull() ?: 0,
            card = payload["card"] ?: ""
        )
        "trickResult" -> SpadesMPMessage.TrickResult(
            personId, roomId, cmdId,
            handNum = payload["handNum"]?.toIntOrNull() ?: 0,
            trickNum = payload["trickNum"]?.toIntOrNull() ?: 0,
            winnerSeat = payload["winnerSeat"]?.toIntOrNull() ?: 0
        )
        "handScore" -> SpadesMPMessage.HandScore(
            personId, roomId, cmdId,
            handNum = payload["handNum"]?.toIntOrNull() ?: 0,
            team0Score = payload["team0TotalScore"]?.toIntOrNull() ?: 0,
            team1Score = payload["team1TotalScore"]?.toIntOrNull() ?: 0,
            gameOver = payload["gameOver"] == "true"
        )
        "gameFinished" -> SpadesMPMessage.GameFinished(
            personId, roomId, cmdId,
            handNum = payload["handNum"]?.toIntOrNull() ?: 0,
            winnerTeam = payload["winnerTeam"]?.toIntOrNull() ?: 0
        )
        "chat", "chatMessage" -> SpadesMPMessage.Chat(
            personId, roomId, cmdId,
            displayName = payload["displayName"] ?: personId,
            message = payload["message"] ?: ""
        )
        "gameState" -> {
            val sequence = obj["sequence"]?.jsonPrimitive?.intOrNull ?: 0
            if (obj.containsKey("data")) {
                // Android format: top-level "data" object with GameObj
                val envelope = json.decodeFromString<GameStateEnvelope>(raw)
                SpadesMPMessage.GameState(envelope.playerId, envelope.roomId, envelope.cmdId, sequence, envelope.data)
            } else {
                // iOS legacy format: payload: {json: "..."} — attempt conversion to GameObj
                val gameObj = tryParseIosLegacyGameState(obj)
                if (gameObj != null) SpadesMPMessage.GameState(personId, roomId, cmdId, sequence, gameObj)
                else SpadesMPMessage.Unknown(raw)
            }
        }
        "playerInfo" -> SpadesMPMessage.PlayerInfo(
            personId = personId,
            roomId = roomId,
            displayName = payload["displayName"] ?: personId
        )
        "RoomJoined" -> {
            val myPlayerId = obj["playerId"]?.jsonPrimitive?.content ?: ""
            SpadesMPMessage.RoomJoined(roomId = roomId, myPlayerId = myPlayerId)
        }
        "PlayerJoined" -> SpadesMPMessage.PlayerJoined(
            roomId = roomId,
            playerId = personId,
            seatIndex = obj["seatIndex"]?.jsonPrimitive?.intOrNull ?: -1
        )
        "PlayerDisconnected" -> SpadesMPMessage.PlayerDisconnected(personId)
        "PlayerReconnected" -> SpadesMPMessage.PlayerReconnected(personId)
        "RoomFull" -> SpadesMPMessage.RoomFull(roomId)
        else -> SpadesMPMessage.Unknown(raw)
    }
} catch (_: Exception) {
    SpadesMPMessage.Unknown(raw)
}

// ── Builders ──────────────────────────────────────────────────────────────────

private fun cmdId() = UUID.randomUUID().toString()

/**
 * Serializes [gameObj] to the canonical iOS GameState JSON (the value inside payload.json).
 *
 * [seatUuidMap]  room-seat-index → UUID string. Human seats use their WSS playerId;
 *                CPU seats use a UUID generated once at game-start. Populated by the host;
 *                callers that don't supply a map get random UUIDs (non-cross-platform safe).
 * [gameId]       stable UUID for this game session, used to generate deterministic hand IDs.
 *
 * tryParseIosLegacyGameState reads [waitingSeat] from the root, so that field is kept as
 * an Android extension alongside the canonical iOS schema.
 */
fun gameObjToIosJson(
    gameObj: GameObj,
    seatUuidMap: Map<Int, String> = emptyMap(),
    gameId: String = "",
    seq: Int = 1
): String {
    fun seatUuid(seat: Int): String = seatUuidMap[seat] ?: UUID.randomUUID().toString()
    fun handUuid(handNum: Int): String =
        UUID.nameUUIDFromBytes("$gameId/$handNum".toByteArray()).toString()

    val waitingIsHuman = gameObj.seats.find { it.seatIndex == gameObj.waitingSeat }?.isHuman == true
    // Seat 0 is always the Android host in host-originated broadcasts. The host handles its own
    // human turns locally and never needs to signal remote clients to act. Only promote to
    // bidHuman/trickHuman for remote human seats (waitingSeat > 0).
    val waitingIsRemoteHuman = waitingIsHuman && gameObj.waitingSeat > 0
    val iosPhase = when (gameObj.phase) {
        "bid"              -> if (gameObj.waitingSeat >= 0 && waitingIsRemoteHuman) "bidHuman" else "bid"
        "trick"            -> if (gameObj.waitingSeat >= 0 && waitingIsRemoteHuman) "trickHuman" else "trick"
        "score", "endHand" -> "endHand"
        "finished"         -> "gameFinished"
        else               -> gameObj.phase
    }

    fun tokenToCard(token: String): JsonObject? {
        val parts = token.split("_"); if (parts.size != 2) return null
        val rank = parts[0].toIntOrNull() ?: return null
        val suit = parts[1].toIntOrNull() ?: return null
        return buildJsonObject { put("id", "c${rank + 2}_${suit + 1}"); put("rank", rank); put("suit", suit) }
    }

    val root = buildJsonObject {
        // Android extension field — read by tryParseIosLegacyGameState on the receiving side.
        put("waitingSeat", gameObj.waitingSeat)

        // Required top-level iOS fields
        put("id",                      gameId.ifEmpty { UUID.randomUUID().toString() })
        put("seq",                     seq)
        put("phase",                   iosPhase)
        put("gameType",                "houseRules")
        put("currentHandIndex",        (gameObj.hands.size - 1).coerceAtLeast(0))
        put("trump",                   3)
        put("minimumBid",              4)
        put("maximumBid",              13)
        put("maxScore",                250)
        put("losingScore",             -250)
        put("deckCount",               0)
        put("deuceIsWild",             false)
        put("deuceOfDiamondsIsWild",   false)
        put("boardFive",               false)
        put("spadesMustBreak",         false)
        put("sandbagsEnabled",         false)
        put("blindNilExchangeEnabled", false)
        put("challengeMode",           false)
        putJsonArray("discard")               {}
        putJsonArray("pendingBlindBids")      {}
        putJsonArray("pendingBlindDeclines")  {}
        putJsonObject("metadata")             {}

        putJsonArray("players") {
            gameObj.seats.sortedBy { it.seatIndex }.forEach { seat ->
                addJsonObject {
                    put("id",        seatUuid(seat.seatIndex))
                    put("seatIndex", seat.seatIndex)
                    put("team",      seat.team)
                    put("name",      seat.playerName)
                    put("isHuman",   seat.isHuman)
                }
            }
        }

        putJsonArray("hands") {
            gameObj.hands.forEach { hand ->
                addJsonObject {
                    // Compute trick state up front so currentLeaderSeat can use it
                    val completedTricks = hand.tricks.filter { it.winner >= 0 }
                    val inProgress      = hand.tricks.lastOrNull()?.takeIf { it.winner < 0 }
                    // currentLeaderSeat: use in-progress trick's leader if one exists;
                    // otherwise the new trick hasn't started yet and waitingSeat IS the leader.
                    val currentLeader   = inProgress?.leader
                        ?: if (gameObj.waitingSeat >= 0) gameObj.waitingSeat else 0

                    // Derive dealer team and bid order from the actual dealer seat.
                    val dealerTeam    = gameObj.seats.find { it.seatIndex == hand.dealerSeat }?.team ?: 0
                    val nonDealerTeam = 1 - dealerTeam
                    // teamBidOrder: non-dealer team always bids first.
                    // currentTeamBidIndex must reflect who is actually waiting to bid:
                    //   0 → nonDealerTeam is bidding, 1 → dealerTeam is bidding, 2 → done.
                    val waitingTeam = if (gameObj.phase == "bid" && gameObj.waitingSeat >= 0)
                        gameObj.seats.find { it.seatIndex == gameObj.waitingSeat }?.team
                    else null
                    val currentTeamBidIndex = when (waitingTeam) {
                        nonDealerTeam -> 0; dealerTeam -> 1; else -> 2
                    }

                    put("id",                  handUuid(hand.handNum))
                    put("number",              hand.handNum)
                    put("dealerSeatIndex",     hand.dealerSeat)
                    put("currentLeaderSeat",   currentLeader)
                    put("currentBidderIndex",  0)
                    put("currentTeamBidIndex", currentTeamBidIndex)
                    put("spadesBroken",        false)
                    put("numOfTrumpPlayed",    0)
                    put("cardOnHeadPlayed",    false)
                    put("bostonPlayed",        false)
                    put("frustratedPlayed",    false)
                    put("pickCurrentSeat",     0)
                    putJsonArray("pickDeck")  {}
                    putJsonArray("teamBlind") { add(false); add(false) }
                    putJsonArray("teamBids")  { add(hand.team0Bid); add(hand.team1Bid) }
                    putJsonArray("teamBidOrder") { add(nonDealerTeam); add(dealerTeam) }
                    putJsonObject("highestRemainingRank") {
                        put("0", 12); put("1", 12); put("2", 12); put("3", 15)
                    }
                    putJsonArray("bidOrder") { for (s in 0..3) add(seatUuid(s)) }

                    val playedBySeat = Array(4) { mutableSetOf<String>() }
                    completedTricks.forEach { trick ->
                        trick.plays.forEachIndexed { seat, token ->
                            if (token.isNotBlank()) playedBySeat[seat].add(token)
                        }
                    }

                    // dealtHands: interleaved [uuid, [Card...], uuid, [Card...], ...]
                    putJsonArray("dealtHands") {
                        for (seat in 0..3) {
                            add(seatUuid(seat))
                            addJsonArray {
                                hand.deal.forSeat(seat).forEach { token ->
                                    tokenToCard(token)?.let { add(it) }
                                }
                            }
                        }
                    }

                    // perPlayer: interleaved [uuid, {state}, uuid, {state}, ...]
                    putJsonArray("perPlayer") {
                        for (seat in 0..3) {
                            add(seatUuid(seat))
                            addJsonObject {
                                val hasBid = hand.seatDidBid.getOrElse(seat) {
                                    hand.handNum < (gameObj.hands.lastOrNull()?.handNum ?: 0)
                                }
                                put("bid",    if (hasBid) hand.seatBids.getOrElse(seat) { 0 } else -1)
                                val teamIdx = gameObj.seats.find { it.seatIndex == seat }?.team ?: 0
                                put("teamBid", if (teamIdx == 0) hand.team0Bid else hand.team1Bid)
                                put("didBid", hasBid)
                                put("tricksTaken", if (teamIdx == 0) hand.team0Tricks else hand.team1Tricks)
                                put("totalScore", if (teamIdx == 0) gameObj.team0Score else gameObj.team1Score)
                                val curToken = inProgress?.plays?.getOrElse(seat) { "" } ?: ""
                                put("hasPlayedThisTrick", curToken.isNotBlank())
                                if (curToken.isNotBlank()) {
                                    tokenToCard(curToken)?.let { put("currentPlay", it) }
                                }
                                val remaining = hand.deal.forSeat(seat)
                                    .filter { it !in playedBySeat[seat] }
                                putJsonArray("hand") {
                                    remaining.forEach { token -> tokenToCard(token)?.let { add(it) } }
                                }
                                put("cuttingSpades",   false)
                                put("cuttingHearts",   false)
                                put("cuttingClubs",    false)
                                put("cuttingDiamonds", false)
                                put("sandBags", 0)
                                put("blind",    false)
                            }
                        }
                    }

                    // Completed tricks — iOS format: plays keyed by seat-index string
                    putJsonArray("tricks") {
                        completedTricks.forEach { trick ->
                            addJsonObject {
                                put("number",       trick.trickNum)
                                put("leadSeatIndex", trick.leader)
                                putJsonObject("plays") {
                                    trick.plays.forEachIndexed { seat, token ->
                                        if (token.isNotBlank()) {
                                            tokenToCard(token)?.let { put("$seat", it) }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // In-progress trick as a separate field (omitted when null)
                    if (inProgress != null) {
                        putJsonObject("currentTrick") {
                            put("number",        inProgress.trickNum)
                            put("leadSeatIndex", inProgress.leader)
                            putJsonObject("plays") {
                                inProgress.plays.forEachIndexed { seat, token ->
                                    if (token.isNotBlank()) {
                                        tokenToCard(token)?.let { put("$seat", it) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Scoreboard keyed by team index as string
        putJsonObject("scoreboard") {
            putJsonObject("scores") {
                put("0", gameObj.team0Score)
                put("1", gameObj.team1Score)
            }
            putJsonObject("sandbagCount") {
                put("0", gameObj.team0Bags)
                put("1", gameObj.team1Bags)
            }
            putJsonObject("playerAdjustments") {}
        }
    }
    return root.toString()
}

/** Builds a gameState wire message using the iOS payload.json envelope (understood by both platforms). */
fun buildGameState(
    personId: String,
    roomId: String,
    gameObj: GameObj,
    seatUuidMap: Map<Int, String> = emptyMap(),
    gameId: String = "",
    seq: Int = 1
): String =
    MpEnvelope(
        type     = "gameState",
        playerId = personId,
        roomId   = roomId,
        cmdId    = cmdId(),
        payload  = mapOf("json" to gameObjToIosJson(gameObj, seatUuidMap, gameId, seq))
    ).toJson()

fun buildJoinRoom(roomId: String, playerId: String): String =
    """{"action":"joinRoom","playerId":"$playerId","roomId":"$roomId","gameType":"spades"}"""

fun buildLobbySnapshot(personId: String, roomId: String, seats: List<OnlineSeat>): String {
    val payload = mutableMapOf("phase" to "lobby")
    seats.forEach { seat ->
        payload["seat${seat.seatIndex}Id"] = seat.playerId ?: ""
        payload["seat${seat.seatIndex}Name"] = seat.displayName ?: ""
        payload["seat${seat.seatIndex}Kind"] = seat.kind.name.lowercase()
    }
    return MpEnvelope(type = "snapShot", playerId = personId, roomId = roomId, cmdId = cmdId(), payload = payload).toJson()
}

fun buildStart(personId: String, roomId: String) =
    MpEnvelope(type = "start", playerId = personId, roomId = roomId, cmdId = cmdId(), payload = mapOf("countdown" to "3")).toJson()

fun buildPlayerBid(personId: String, roomId: String, seatIndex: Int, bidAmount: Int) =
    MpEnvelope(type = "playerBid", playerId = personId, roomId = roomId, cmdId = cmdId(),
        payload = mapOf("seatIndex" to seatIndex.toString(), "bidAmount" to bidAmount.toString())).toJson()

fun buildBlindResponse(personId: String, roomId: String, seatIndex: Int, accepted: Boolean) =
    MpEnvelope(type = "blindResponse", playerId = personId, roomId = roomId, cmdId = cmdId(),
        payload = mapOf("seatIndex" to seatIndex.toString(), "accepted" to accepted.toString())).toJson()

fun buildStartCountdown(personId: String, roomId: String, seconds: Int) =
    MpEnvelope(type = "startCountdown", playerId = personId, roomId = roomId,
        cmdId = cmdId(), payload = mapOf("seconds" to seconds.toString())).toJson()

fun buildStartGame(personId: String, roomId: String, seats: List<OnlineSeat> = emptyList(), countdownSeconds: Int = 5): String {
    val payload = buildMap<String, String> {
        put("gameType", "houseRules")
        put("countdownSeconds", countdownSeconds.toString())
        seats.forEach { s ->
            put("seat${s.seatIndex}Id",   s.playerId   ?: "")
            put("seat${s.seatIndex}Name", s.displayName ?: "")
            put("seat${s.seatIndex}Kind", s.kind.name.lowercase())
        }
    }
    return MpEnvelope(type = "startGame", playerId = personId, roomId = roomId,
        cmdId = cmdId(), payload = payload.ifEmpty { null }).toJson()
}

fun buildDeal(personId: String, roomId: String, handNum: Int, dealerSeat: Int, firstLead: Int, hands: Map<Int, List<String>>): String {
    val payload = mutableMapOf(
        "handNum" to handNum.toString(),
        "dealerSeat" to dealerSeat.toString(),
        "firstLead" to firstLead.toString()
    )
    hands.forEach { (seat, cards) -> payload["seat${seat}Hand"] = cards.joinToString(" ") }
    return MpEnvelope(type = "deal", playerId = personId, roomId = roomId, cmdId = cmdId(), payload = payload).toJson()
}

fun buildBid(personId: String, roomId: String, handNum: Int, teamIndex: Int, bidAmount: Int, isBlind: Boolean = false) =
    MpEnvelope(type = "bid", playerId = personId, roomId = roomId, cmdId = cmdId(), payload = mapOf(
        "handNum" to handNum.toString(),
        "teamIndex" to teamIndex.toString(),
        "bidAmount" to bidAmount.toString(),
        "isBlind" to isBlind.toString()
    )).toJson()

fun buildPlayCard(personId: String, roomId: String, handNum: Int, trickNum: Int, leadSeat: Int, seatIndex: Int, card: String) =
    MpEnvelope(type = "playCard", playerId = personId, roomId = roomId, cmdId = cmdId(), payload = mapOf(
        "handNum" to handNum.toString(),
        "trickNum" to trickNum.toString(),
        "leadSeat" to leadSeat.toString(),
        "seatIndex" to seatIndex.toString(),
        "card" to card
    )).toJson()

fun buildTrickResult(personId: String, roomId: String, handNum: Int, trickNum: Int, leadSeat: Int, winnerSeat: Int, cards: Map<Int, String>) =
    MpEnvelope(type = "trickResult", playerId = personId, roomId = roomId, cmdId = cmdId(), payload = buildMap {
        put("handNum", handNum.toString())
        put("trickNum", trickNum.toString())
        put("leadSeat", leadSeat.toString())
        put("winnerSeat", winnerSeat.toString())
        cards.forEach { (seat, card) -> put("card$seat", card) }
    }).toJson()

fun buildHandScore(personId: String, roomId: String, handNum: Int, team0Bid: Int, team0Tricks: Int, team0Total: Int, team0Bags: Int, team1Bid: Int, team1Tricks: Int, team1Total: Int, team1Bags: Int, gameOver: Boolean): String {
    val t0Round = if (team0Tricks >= team0Bid) team0Bid * 10 + (team0Tricks - team0Bid) else -(team0Bid * 10)
    val t1Round = if (team1Tricks >= team1Bid) team1Bid * 10 + (team1Tricks - team1Bid) else -(team1Bid * 10)
    return MpEnvelope(type = "handScore", playerId = personId, roomId = roomId, cmdId = cmdId(), payload = mapOf(
        "handNum" to handNum.toString(),
        "team0Bid" to team0Bid.toString(),
        "team0Tricks" to team0Tricks.toString(),
        "team0RoundScore" to t0Round.toString(),
        "team0TotalScore" to team0Total.toString(),
        "team0Bags" to team0Bags.toString(),
        "team1Bid" to team1Bid.toString(),
        "team1Tricks" to team1Tricks.toString(),
        "team1RoundScore" to t1Round.toString(),
        "team1TotalScore" to team1Total.toString(),
        "team1Bags" to team1Bags.toString(),
        "gameOver" to gameOver.toString()
    )).toJson()
}

fun buildGameFinished(personId: String, roomId: String, handNum: Int, winnerTeam: Int, score0: Int, score1: Int) =
    MpEnvelope(type = "gameFinished", playerId = personId, roomId = roomId, cmdId = cmdId(), payload = mapOf(
        "handNum" to handNum.toString(),
        "winnerTeam" to winnerTeam.toString(),
        "finalScoreTeam0" to score0.toString(),
        "finalScoreTeam1" to score1.toString()
    )).toJson()

fun buildPlayerInfo(personId: String, roomId: String, displayName: String) =
    MpEnvelope(type = "playerInfo", playerId = personId, roomId = roomId,
        payload = mapOf("displayName" to displayName)
    ).toJson()

/**
 * Translates an iOS card token to Android format.
 *
 * iOS: "c{iosRank}_{iosSuit}"  suits: 1=spades 2=diamonds 3=clubs 4=hearts; jokers rank 16/17 suit 4
 * Android: "{androidRank}_{androidSuit}"  suits: 0=hearts 1=clubs 2=diamonds 3=spades; jokers rank 14/15 suit 3
 *
 * If the card already lacks the 'c' prefix it is returned unchanged (Android native or unknown).
 */
fun normalizeCard(card: String): String {
    if (!card.startsWith("c")) return card
    val stripped = card.drop(1)
    val under = stripped.indexOf('_')
    if (under < 0) return card
    val iosRank = stripped.substring(0, under).toIntOrNull() ?: return card
    val iosSuit = stripped.substring(under + 1).toIntOrNull() ?: return card
    if (iosSuit == 4 && iosRank >= 16) return if (iosRank == 16) "14_3" else "15_3"
    val androidRank = iosRank - 2
    val androidSuit = when (iosSuit) { 1 -> 3; 2 -> 2; 3 -> 1; 4 -> 0; else -> return card }
    return "${androidRank}_${androidSuit}"
}

/**
 * Converts an Android-format card token to the iOS/wire format used by all clients.
 * This is the inverse of [normalizeCard] and must be applied before sending deal payloads.
 *
 * Android: "{rank}_{suit}"   rank 0-12=TWO-ACE, 14=LittleJoker, 15=BigJoker; suit 0=hearts 1=clubs 2=diamonds 3=spades
 * Wire:    "c{rank}_{suit}"  rank 2-14=TWO-ACE, 16=LittleJoker, 17=BigJoker; suit 1=spades 2=diamonds 3=clubs 4=hearts
 */
fun androidToWireCard(token: String): String {
    val under = token.indexOf('_')
    if (under < 0) return token
    val androidRank = token.substring(0, under).toIntOrNull() ?: return token
    val androidSuit = token.substring(under + 1).toIntOrNull() ?: return token
    if (androidRank == 14 && androidSuit == 3) return "c16_4"   // Little Joker
    if (androidRank == 15 && androidSuit == 3) return "c17_4"   // Big Joker
    val wireRank = androidRank + 2
    val wireSuit = when (androidSuit) { 0 -> 4; 1 -> 3; 2 -> 2; 3 -> 1; else -> return token }
    return "c${wireRank}_${wireSuit}"
}

fun buildChat(personId: String, roomId: String, displayName: String, message: String) =
    MpEnvelope(type = "chatMessage", playerId = personId, roomId = roomId, cmdId = cmdId(), payload = mapOf(
        "displayName" to displayName,
        "message" to message
    )).toJson()

// ── Card encoding helpers ─────────────────────────────────────────────────────

fun buildHouseRulesDeck(): List<String> {
    val cards = mutableListOf<String>()
    for (r in 0..12) cards.add("${r}_3")       // all spades
    for (r in 1..12) cards.add("${r}_0")       // hearts without 2♥ (rank 0)
    for (r in 0..12) cards.add("${r}_2")       // all diamonds
    for (r in 1..12) cards.add("${r}_1")       // clubs without 2♣ (rank 0)
    cards.add("14_3")                           // Little Joker
    cards.add("15_3")                           // Big Joker
    return cards.shuffled()
}
