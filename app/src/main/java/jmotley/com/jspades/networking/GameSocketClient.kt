package jmotley.com.jspades.networking

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException

private const val TAG = "WSSMP"

enum class SocketState { Disconnected, Connecting, Connected }

class GameSocketClient(
    private val url: String,
    private val scope: CoroutineScope,
    private val onMessage: (String) -> Unit,
    private val onStateChange: (SocketState) -> Unit
) {
    private val httpClient = HttpClient(OkHttp) {
        install(WebSockets) {
            pingInterval = 25_000L
        }
    }

    private val sendQueue = Channel<String>(Channel.UNLIMITED)
    private var connectJob: Job? = null

    fun connect() {
        Log.i(TAG, "connect() → $url")
        connectJob?.cancel()
        connectJob = scope.launch {
            var backoffMs = 1_000L
            while (isActive) {
                onStateChange(SocketState.Connecting)
                Log.d(TAG, "socket CONNECTING (backoff=${backoffMs}ms)")
                try {
                    httpClient.webSocket(url) {
                        onStateChange(SocketState.Connected)
                        backoffMs = 1_000L
                        Log.i(TAG, "socket CONNECTED")

                        val sendJob = launch {
                            for (msg in sendQueue) {
                                try {
                                    Log.d(TAG, "SEND → $msg")
                                    send(Frame.Text(msg))
                                } catch (e: Exception) {
                                    Log.w(TAG, "send failed: ${e.message}")
                                    break
                                }
                            }
                        }

                        try {
                            for (frame in incoming) {
                                when (frame) {
                                    is Frame.Text -> {
                                        val text = frame.readText()
                                        Log.d(TAG, "RECV ← $text")
                                        onMessage(text)
                                    }
                                    is Frame.Close -> {
                                        Log.i(TAG, "socket CLOSE frame received")
                                        break
                                    }
                                    else -> Unit
                                }
                            }
                        } catch (_: ClosedReceiveChannelException) {
                            Log.i(TAG, "socket channel closed normally")
                        } finally {
                            sendJob.cancel()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "socket error: ${e.javaClass.simpleName} — ${e.message}")
                }

                onStateChange(SocketState.Disconnected)
                Log.i(TAG, "socket DISCONNECTED — retry in ${backoffMs}ms")
                if (!isActive) break
                delay(backoffMs)
                backoffMs = minOf(backoffMs * 2, 30_000L)
            }
            Log.i(TAG, "connect loop exited")
        }
    }

    fun send(json: String) {
        sendQueue.trySend(json)
    }

    fun disconnect() {
        Log.i(TAG, "disconnect() called")
        connectJob?.cancel()
        connectJob = null
        onStateChange(SocketState.Disconnected)
    }
}
