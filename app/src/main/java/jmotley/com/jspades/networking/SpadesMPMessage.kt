package jmotley.com.jspades.networking

import jmotley.com.jspades.data.OnlineSeat
import jmotley.com.jspades.data.SeatKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.UUID

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
    /** Host broadcasts final team bids so all clients can sync before trick play begins. */
    data class AllBids(val personId: String, val roomId: String, val cmdId: String, val team0Bid: Int, val team1Bid: Int, val team0IsBlind: Boolean = false, val team1IsBlind: Boolean = false) : SpadesMPMessage()
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
    data class Unknown(val raw: String) : SpadesMPMessage()
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
        "allBids" -> SpadesMPMessage.AllBids(personId, roomId, cmdId,
            team0Bid = payload["team0Bid"]?.toIntOrNull() ?: 0,
            team1Bid = payload["team1Bid"]?.toIntOrNull() ?: 0,
            team0IsBlind = payload["team0Blind"] == "true",
            team1IsBlind = payload["team1Blind"] == "true")
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
        "chat" -> SpadesMPMessage.Chat(
            personId, roomId, cmdId,
            displayName = payload["displayName"] ?: personId,
            message = payload["message"] ?: ""
        )
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

fun buildAllBids(personId: String, roomId: String, team0Bid: Int, team1Bid: Int, team0IsBlind: Boolean = false, team1IsBlind: Boolean = false) =
    MpEnvelope(type = "allBids", playerId = personId, roomId = roomId, cmdId = cmdId(),
        payload = mapOf(
            "team0Bid" to team0Bid.toString(), "team0Blind" to team0IsBlind.toString(),
            "team1Bid" to team1Bid.toString(), "team1Blind" to team1IsBlind.toString()
        )).toJson()

fun buildStartCountdown(personId: String, roomId: String, seconds: Int) =
    MpEnvelope(type = "startCountdown", playerId = personId, roomId = roomId,
        cmdId = cmdId(), payload = mapOf("seconds" to seconds.toString())).toJson()

fun buildStartGame(personId: String, roomId: String, seats: List<OnlineSeat> = emptyList()): String {
    val payload = buildMap<String, String> {
        put("gameType", "houseRules")
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
    MpEnvelope(type = "chat", playerId = personId, roomId = roomId, cmdId = cmdId(), payload = mapOf(
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
