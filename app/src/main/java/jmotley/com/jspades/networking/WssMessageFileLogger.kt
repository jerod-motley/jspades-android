package jmotley.com.jspades.networking

import android.content.Context
import android.os.Build
import jmotley.com.jspades.data.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class WssTraceContext(
    val roomId: String = "",
    val localPlayerId: String = "",
    val hostPlayerId: String = "",
    val role: String = "unresolved"
)

/**
 * Serialized, raw WSS trace writer. One record is emitted at the transport boundary.
 *
 * A single app-lifetime object, not a per-connection instance: [GameSocketClient] is
 * recreated on every reconnect and every room join, so owning this per-instance would
 * leak a coroutine + channel each time and split record numbering across independent
 * writers appending to the same file.
 */
object WssMessageFileLogger {
    private var file: File? = null
    private val runId = UUID.randomUUID().toString().take(8)
    private val counter = AtomicLong(0)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Idempotent — call before recording. No-ops (and never touches disk or launches the
     * writer coroutine) unless [AppConfig.ENABLE_WSS_MESSAGE_FILE_LOGGING] is on.
     */
    @Synchronized
    fun ensureStarted(context: Context) {
        if (!AppConfig.ENABLE_WSS_MESSAGE_FILE_LOGGING || file != null) return
        file = File(context.applicationContext.filesDir, "mplogs.md")
        scope.launch {
            for (record in queue) file?.appendText(record, Charsets.UTF_8)
        }
    }

    fun record(direction: String, raw: String, context: WssTraceContext) {
        if (!AppConfig.ENABLE_WSS_MESSAGE_FILE_LOGGING || file == null) return
        val obj = runCatching { JSONObject(raw) }.getOrNull()
        val payload = obj?.optJSONObject("payload")
        fun value(key: String): String? = obj?.optString(key)?.takeIf { it.isNotBlank() }
            ?: payload?.optString(key)?.takeIf { it.isNotBlank() }
        val fromPlayerId = value("fromPlayerId")
        // The payload's own claimed playerId, kept separate from the resolved `sender`
        // below — folding it into a fallback chain would hide exactly the class of bug
        // (valid relay identity, blank/wrong payload identity) this trace exists to catch.
        val claimedPlayerId = value("playerId")
        val sender = if (direction == "RECV") {
            fromPlayerId ?: claimedPlayerId ?: value("sessionId")
        } else claimedPlayerId ?: context.localPlayerId
        val type = value("type") ?: value("action") ?: "unknown"
        val seq = obj?.opt("sequence")?.toString() ?: "-"
        val cmd = value("cmdId") ?: "-"
        val seat = value("seat") ?: value("seatIndex") ?: "-"
        val number = counter.incrementAndGet()
        val device = "${Build.MANUFACTURER}-${Build.MODEL}".replace(" ", "-")
        val header = "### ${Instant.now()} | #$number | $direction | Android | $device | run=$runId | room=${context.roomId} | role=${context.role} | local=${context.localPlayerId} | host=${context.hostPlayerId} | from=${sender.orEmpty()} | claimedPlayerId=${claimedPlayerId.orEmpty()} | type=$type | seq=$seq | cmd=$cmd | seat=$seat\n\n"
        queue.trySend(header + "```json\n" + raw + "\n```\n\n")
    }
}
