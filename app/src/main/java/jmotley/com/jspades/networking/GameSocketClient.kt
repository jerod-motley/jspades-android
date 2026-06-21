package jmotley.com.jspades.networking

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException

private const val TAG = "WSSMP"
private val socketLogJson = Json { ignoreUnknownKeys = true }

enum class SocketState { Disconnected, Connecting, Connected }

class GameSocketClient(
    private val url: String,
    private val scope: CoroutineScope,
    private val onMessage: (String) -> Unit,
    private val onStateChange: (SocketState) -> Unit,
    private val isLoggingEnabled: () -> Boolean = { true }
) {
    private val httpClient = HttpClient(OkHttp) {
        install(WebSockets) {
            pingInterval = 25_000L
        }
    }

    private val sendQueue = Channel<String>(Channel.UNLIMITED)
    private var connectJob: Job? = null

    fun connect() {
        logI("connect() → $url")
        connectJob?.cancel()
        connectJob = scope.launch {
            var backoffMs = 1_000L
            while (isActive) {
                onStateChange(SocketState.Connecting)
                logD("socket CONNECTING (backoff=${backoffMs}ms)")
                try {
                    httpClient.webSocket(url) {
                        onStateChange(SocketState.Connected)
                        backoffMs = 1_000L
                        logI("socket CONNECTED")

                        val sendJob = launch {
                            for (msg in sendQueue) {
                                try {
                                    logD("SEND → ${summarizeWsFrame(msg)} bytes=${msg.length}")
                                    send(Frame.Text(msg))
                                } catch (e: Exception) {
                                    logW("send failed: ${e.message}")
                                    break
                                }
                            }
                        }

                        try {
                            for (frame in incoming) {
                                when (frame) {
                                    is Frame.Text -> {
                                        val text = frame.readText()
                                        logD("RECV ← ${summarizeWsFrame(text)} bytes=${text.length}")
                                        onMessage(text)
                                    }
                                    is Frame.Close -> {
                                        logI("socket CLOSE frame received")
                                        break
                                    }
                                    else -> Unit
                                }
                            }
                        } catch (_: ClosedReceiveChannelException) {
                            logI("socket channel closed normally")
                        } finally {
                            sendJob.cancel()
                        }
                    }
                } catch (e: Exception) {
                    logW("socket error: ${e.javaClass.simpleName} — ${e.message}")
                }

                onStateChange(SocketState.Disconnected)
                logI("socket DISCONNECTED — retry in ${backoffMs}ms")
                if (!isActive) break
                delay(backoffMs)
                backoffMs = minOf(backoffMs * 2, 30_000L)
            }
            logI("connect loop exited")
        }
    }

    fun send(json: String) {
        sendQueue.trySend(json)
    }

    fun disconnect() {
        logI("disconnect() called")
        connectJob?.cancel()
        connectJob = null
        onStateChange(SocketState.Disconnected)
    }

    private fun logD(message: String) {
        if (isLoggingEnabled()) Log.d(TAG, message)
    }

    private fun logI(message: String) {
        if (isLoggingEnabled()) Log.i(TAG, message)
    }

    private fun logW(message: String) {
        if (isLoggingEnabled()) Log.w(TAG, message)
    }
}

private fun summarizeWsFrame(raw: String): String = runCatching {
    val obj = socketLogJson.parseToJsonElement(raw).jsonObject
    val type = obj.string("type") ?: obj.string("action") ?: "unknown"
    val room = obj.string("roomId")
    val player = obj.string("playerId") ?: obj.string("fromPlayerId") ?: obj.string("personId")
    val cmd = obj.string("cmdId")
    val sequence = obj["sequence"]?.jsonPrimitive?.intOrNull

    buildList {
        add("type=$type")
        room?.let { add("room=$it") }
        player?.let { add("from=${it.takeLast(8)}") }
        cmd?.takeIf { it.isNotBlank() }?.let { add("cmd=${it.take(8)}") }
        sequence?.let { add("seq=$it") }
        addAll(summarizePayload(type, obj))
    }.joinToString(" ")
}.getOrElse {
    "unparsed"
}

private fun summarizePayload(type: String, obj: JsonObject): List<String> {
    if (type == "gameState") return summarizeGameState(obj)

    val payload = obj["payload"]?.jsonObject ?: return emptyList()
    return when (type) {
        "playerBid" -> listOfNotNull(
            payload.string("seatIndex")?.let { "seat=$it" },
            payload.string("bidAmount")?.let { "bid=$it" }
        )
        "playCard" -> listOfNotNull(
            payload.string("seatIndex")?.let { "seat=$it" },
            payload.string("card")?.let { "card=$it" },
            payload.string("trickNum")?.let { "trick=$it" },
            payload.string("leadSeat")?.let { "lead=$it" }
        )
        "startCountdown" -> listOfNotNull(payload.string("seconds")?.let { "seconds=$it" })
        "startGame", "snapShot" -> listOf("payloadKeys=${payload.keys.sorted().joinToString(",")}")
        "chat", "chatMessage" -> listOf("messageLen=${payload.string("message")?.length ?: 0}")
        else -> if (payload.isEmpty()) emptyList() else listOf("payloadKeys=${payload.keys.sorted().joinToString(",")}")
    }
}

private fun summarizeGameState(obj: JsonObject): List<String> {
    obj["data"]?.jsonObject?.let { data ->
        val hands = data["hands"]?.jsonArray
        return listOfNotNull(
            data.string("phase")?.let { "phase=$it" },
            data["waitingSeat"]?.jsonPrimitive?.intOrNull?.let { "waitingSeat=$it" },
            hands?.size?.let { "hands=$it" },
            data["team0Score"]?.jsonPrimitive?.intOrNull?.let { "t0=$it" },
            data["team1Score"]?.jsonPrimitive?.intOrNull?.let { "t1=$it" },
            summarizeAndroidTrick(hands?.lastOrNull()?.jsonObject)
        )
    }

    val payloadJson = obj["payload"]?.jsonObject?.string("json") ?: return emptyList()
    return runCatching {
        val game = socketLogJson.parseToJsonElement(payloadJson).jsonObject
        val hands = game["hands"]?.jsonArray
        listOfNotNull(
            game.string("phase")?.let { "phase=$it" },
            game["waitingSeat"]?.jsonPrimitive?.intOrNull?.let { "waitingSeat=$it" },
            hands?.size?.let { "hands=$it" },
            summarizeIosTrick(hands?.lastOrNull()?.jsonObject)
        )
    }.getOrElse {
        listOf("payload=json")
    }
}

private fun summarizeAndroidTrick(hand: JsonObject?): String? {
    val trick = hand?.get("tricks")?.jsonArray?.lastOrNull()?.jsonObject ?: return null
    val trickNum = trick["trickNum"]?.jsonPrimitive?.intOrNull
    val leader = trick["leader"]?.jsonPrimitive?.intOrNull
    val winner = trick["winner"]?.jsonPrimitive?.intOrNull
    val plays = trick["plays"]?.jsonArray?.count { it.jsonPrimitive.content.isNotBlank() }
    return "trick=${trickNum ?: "?"} leader=${leader ?: "?"} plays=${plays ?: 0} winner=${winner ?: "?"}"
}

private fun summarizeIosTrick(hand: JsonObject?): String? {
    hand ?: return null
    val current = hand["currentTrick"]?.jsonObject
    val trickNum = current?.get("number")?.jsonPrimitive?.intOrNull
    val leader = current?.get("leadSeatIndex")?.jsonPrimitive?.intOrNull
        ?: hand["currentLeaderSeat"]?.jsonPrimitive?.intOrNull
    val plays = current?.get("plays")?.jsonObject?.size
    return "trick=${trickNum ?: "?"} leader=${leader ?: "?"} plays=${plays ?: 0}"
}

private fun JsonObject.string(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()
