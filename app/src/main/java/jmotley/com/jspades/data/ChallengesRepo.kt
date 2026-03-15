package jmotley.com.jspades.data

import android.content.Context
import io.ktor.client.request.*
import io.ktor.client.statement.*
import jmotley.com.jspades.networking.NetworkClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Challenge(
    val sk: String,
    val shortText: String,
    val longDesc: String,
    val winCriteria: String? = null,
    val allowBid: String? = null,
    val applicableGameTypes: List<String> = emptyList()
)

object ChallengesRepo {
    const val SEED_VERSION = 2
    private val json = Json { ignoreUnknownKeys = true }

    private val _challengesFlow = MutableStateFlow<List<Challenge>>(emptyList())
    val challengesFlow: StateFlow<List<Challenge>> = _challengesFlow

    fun init(ctx: Context) {
        ensureSeeded(ctx)
        loadChallenges(ctx)
    }

    private fun ensureSeeded(ctx: Context) {
        val prefs = ctx.getSharedPreferences("jspades_prefs", Context.MODE_PRIVATE)
        val currentVersion = prefs.getInt("challenges_seed_version", 0)
        if (currentVersion >= SEED_VERSION) return
        val raw = ctx.assets.open("built_in_challenges.json").bufferedReader().readText()
        ctx.openFileOutput("challenges.json", Context.MODE_PRIVATE).use { it.write(raw.toByteArray()) }
        prefs.edit().putInt("challenges_seed_version", SEED_VERSION).apply()
    }

    private fun loadChallenges(ctx: Context) {
        val file = ctx.getFileStreamPath("challenges.json")
        if (!file.exists()) { _challengesFlow.value = emptyList(); return }
        val raw = runCatching { file.readText() }.getOrNull() ?: return
        _challengesFlow.value = runCatching {
            json.decodeFromString<List<Challenge>>(raw)
        }.getOrDefault(emptyList())
    }

    /**
     * Fetches remote challenges from the server and merges them with the local list.
     * Remote items with the same [Challenge.sk] replace local ones; new items are appended.
     * The merged list is persisted to disk and pushed into [challengesFlow].
     * Call from a coroutine scope (e.g. viewModelScope or App.onCreate coroutine).
     */
    suspend fun fetchAndMergeRemote(ctx: Context) {
        runCatching {
            val response = NetworkClient.client.get("${AppConfig.BASE_API_URL}/challenge") {
                parameter("type", AppConfig.PARTITION_KEY)
            }
            val remote = json.decodeFromString<List<Challenge>>(response.bodyAsText())
            if (remote.isEmpty()) return
            val merged = (_challengesFlow.value.associateBy { it.sk } + remote.associateBy { it.sk }).values.toList()
            _challengesFlow.value = merged
            ctx.openFileOutput("challenges.json", Context.MODE_PRIVATE)
                .use { it.write(json.encodeToString(merged).toByteArray()) }
        }
    }

    fun forGameType(gameTypeLabel: String): List<Challenge> =
        _challengesFlow.value.filter { it.applicableGameTypes.contains(gameTypeLabel) }
}
