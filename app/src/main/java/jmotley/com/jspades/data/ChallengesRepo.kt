package jmotley.com.jspades.data

import android.content.Context
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
    const val SEED_VERSION = 1
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

    fun forGameType(gameTypeLabel: String): List<Challenge> =
        _challengesFlow.value.filter { it.applicableGameTypes.contains(gameTypeLabel) }
}
