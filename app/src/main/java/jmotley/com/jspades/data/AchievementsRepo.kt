package jmotley.com.jspades.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

@Serializable
data class AchievementItem(
    val name: String,
    val achieved: Boolean,
    val date: String? = null
)

object AchievementsRepo {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO)

    // ── Achievements ──────────────────────────────────────────────────────────

    private val _achievementsFlow = MutableStateFlow<List<AchievementItem>>(emptyList())
    val achievementsFlow: StateFlow<List<AchievementItem>> = _achievementsFlow

    // ── Challenges ────────────────────────────────────────────────────────────

    private val _completedChallengesFlow = MutableStateFlow<List<AchievementItem>>(emptyList())
    val completedChallengesFlow: StateFlow<List<AchievementItem>> = _completedChallengesFlow

    private val _pendingFlow = MutableStateFlow<Set<String>>(emptySet())
    val pendingFlow: StateFlow<Set<String>> = _pendingFlow

    private val _activeChallengeFlow = MutableStateFlow<String?>(null)
    val activeChallengeFlow: StateFlow<String?> = _activeChallengeFlow

    // ── Initialization ────────────────────────────────────────────────────────

    fun init(ctx: Context) {
        _achievementsFlow.value = loadAchievements(ctx)
        _completedChallengesFlow.value = loadCompletedChallenges(ctx)
        _pendingFlow.value = loadPending(ctx)
        _activeChallengeFlow.value = prefs(ctx).getString("active_challenge_sk", null)
    }

    // ── Achievement management ─────────────────────────────────────────────────

    fun mark(ctx: Context, id: String) {
        val current = loadAchievements(ctx).toMutableList()
        if (current.any { it.name == id && it.achieved }) return
        val idx = current.indexOfFirst { it.name == id }
        val entry = AchievementItem(name = id, achieved = true, date = Instant.now().toString())
        if (idx >= 0) current[idx] = entry else current.add(entry)
        saveAchievements(ctx, current)
        _achievementsFlow.value = current
    }

    fun markAsync(ctx: Context, id: String) {
        scope.launch { mark(ctx, id) }
    }

    fun loadAchievements(ctx: Context): List<AchievementItem> {
        val raw = prefs(ctx).getString("achievements_json", null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<AchievementItem>>(raw) }.getOrDefault(emptyList())
    }

    private fun saveAchievements(ctx: Context, items: List<AchievementItem>) {
        prefs(ctx).edit().putString("achievements_json", json.encodeToString(items)).apply()
    }

    // ── Challenge active-challenge prefs ──────────────────────────────────────

    fun setActiveChallenge(ctx: Context, sk: String, winCriteria: String?, allowBid: String?, shortText: String? = null) {
        prefs(ctx).edit()
            .putString("active_challenge_sk", sk)
            .putString("active_challenge_bidrule", winCriteria ?: "")
            .putString("active_challenge_allow_bid", allowBid ?: "south")
            .putString("active_challenge_short_text", shortText ?: "")
            .apply()
        _activeChallengeFlow.value = sk
    }

    fun clearActiveChallenge(ctx: Context) {
        prefs(ctx).edit()
            .remove("active_challenge_sk")
            .remove("active_challenge_bidrule")
            .remove("active_challenge_allow_bid")
            .remove("active_challenge_short_text")
            .apply()
        _activeChallengeFlow.value = null
    }

    fun getActiveChallenge(ctx: Context): String? =
        prefs(ctx).getString("active_challenge_sk", null)

    fun getActiveChallengeAllowBid(ctx: Context): String? =
        prefs(ctx).getString("active_challenge_allow_bid", null)

    fun getActiveChallengeShortText(ctx: Context): String? {
        val v = prefs(ctx).getString("active_challenge_short_text", null)
        return if (v.isNullOrBlank()) null else v
    }

    fun getActiveChallengeBidRule(ctx: Context): String? {
        val v = prefs(ctx).getString("active_challenge_bidrule", null)
        return if (v.isNullOrBlank()) null else v
    }

    // ── Challenge pending tracking ────────────────────────────────────────────

    fun markChallengePending(ctx: Context, sk: String) {
        val current = loadPending(ctx).toMutableSet()
        current.add(sk)
        savePending(ctx, current)
        _pendingFlow.value = current
    }

    // ── Challenge finalization ─────────────────────────────────────────────────

    /**
     * Called by PlayScreen after a ChallengeResult is collected.
     * On success: records the challenge as completed and clears active prefs.
     * On failure: removes from pending but retains active prefs so the player can retry.
     */
    fun finalizeChallenge(ctx: Context, sk: String, succeeded: Boolean) {
        val pending = loadPending(ctx).toMutableSet()
        pending.remove(sk)
        savePending(ctx, pending)
        _pendingFlow.value = pending

        if (succeeded) {
            val current = loadCompletedChallenges(ctx).toMutableList()
            if (current.none { it.name == sk && it.achieved }) {
                val entry = AchievementItem(name = sk, achieved = true, date = Instant.now().toString())
                val idx = current.indexOfFirst { it.name == sk }
                if (idx >= 0) current[idx] = entry else current.add(entry)
                saveCompletedChallenges(ctx, current)
                _completedChallengesFlow.value = current
            }
        }
        // Always clear active challenge after a result — normal games must not inherit it.
        // To retry a failed challenge the player returns to the Challenges screen.
        clearActiveChallenge(ctx)
    }

    // ── Completed challenges persistence ──────────────────────────────────────

    fun loadCompletedChallenges(ctx: Context): List<AchievementItem> {
        val raw = prefs(ctx).getString("challenges_json", null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<AchievementItem>>(raw) }.getOrDefault(emptyList())
    }

    private fun saveCompletedChallenges(ctx: Context, items: List<AchievementItem>) {
        prefs(ctx).edit().putString("challenges_json", json.encodeToString(items)).apply()
    }

    private fun loadPending(ctx: Context): Set<String> {
        val raw = prefs(ctx).getString("challenges_pending_json", null) ?: return emptySet()
        return runCatching { json.decodeFromString<List<String>>(raw).toSet() }.getOrDefault(emptySet())
    }

    private fun savePending(ctx: Context, items: Set<String>) {
        prefs(ctx).edit().putString("challenges_pending_json", json.encodeToString(items.toList())).apply()
    }

    // ── In-game achievement evaluation ────────────────────────────────────────

    /** Called at GamePhase.Score after the hand is complete. Evaluates all per-hand achievements. */
    fun checkAndAwardPerHand(ctx: Context, state: GameState, hand: Hand) {
        val southPhs = hand.perPlayer["south"] ?: return
        val humanTeam = state.players.find { it.id == "south" }?.team ?: 0

        // GotAll (Boston) — human team took every trick
        val humanTeamTricks = if (state.gameType.useTeams) {
            state.players.filter { it.team == humanTeam }.sumOf { hand.perPlayer[it.id]?.tricksWon ?: 0 }
        } else {
            southPhs.tricksWon
        }
        if (humanTeamTricks == state.gameType.cardsPerPlayer) mark(ctx, AchievementIds.GOT_ALL)

        // GotNil — south bid nil and took zero tricks
        if (southPhs.bid == 0 && southPhs.tricksWon == 0) mark(ctx, AchievementIds.GOT_NIL)

        // GotBlindNil — south went blind-nil successfully
        if (southPhs.isBlind && southPhs.tricksWon == 0) mark(ctx, AchievementIds.GOT_BLIND_NIL)

        // GotTen — south took 10 or more tricks
        if (southPhs.tricksWon >= 10) mark(ctx, AchievementIds.GOT_TEN)

        // GotExactBid — south made exactly their bid (no overbid)
        if (southPhs.bid > 0 && southPhs.tricksWon == southPhs.bid) mark(ctx, AchievementIds.GOT_EXACT_BID)

        // SetOpponents — opposing team failed their bid (team games only)
        if (state.gameType.useTeams) {
            val oppTeam = 1 - humanTeam
            val oppTricks = state.players.filter { it.team == oppTeam }
                .sumOf { hand.perPlayer[it.id]?.tricksWon ?: 0 }
            val oppBid = hand.teamBids.getOrNull(oppTeam) ?: 0
            if (oppBid > 0 && oppTricks < oppBid) mark(ctx, AchievementIds.SET_OPPONENTS)
        }

        // SetMultipleOpponents — 2+ opponents individually set (solo games only)
        if (!state.gameType.useTeams) {
            val setCount = state.players.filter { it.id != "south" }.count { p ->
                val phs = hand.perPlayer[p.id] ?: return@count false
                phs.bid > 0 && phs.tricksWon < phs.bid
            }
            if (setCount >= 2) mark(ctx, AchievementIds.SET_MULTIPLE_OPPONENTS)
        }
    }

    /** Called at GamePhase.Finished. Evaluates Wins, Losses, and BlowoutWins. */
    fun checkAndAwardGameOver(ctx: Context, state: GameState) {
        val totals: Map<String, Int> = if (state.gameType.useTeams) {
            mapOf(
                "0" to ((state.score.points["0"] ?: 0) + (state.score.bags["0"] ?: 0)),
                "1" to ((state.score.points["1"] ?: 0) + (state.score.bags["1"] ?: 0))
            )
        } else {
            state.players.associate { p ->
                p.id to ((state.score.points[p.id] ?: 0) + (state.score.bags[p.id] ?: 0))
            }
        }
        val humanKey = if (state.gameType.useTeams) "0" else "south"
        val humanScore = totals[humanKey] ?: 0
        val maxScore = totals.values.maxOrNull() ?: 0

        if (humanScore >= maxScore) {
            mark(ctx, AchievementIds.WINS)
            val bestOpponent = totals.filterKeys { it != humanKey }.values.maxOrNull() ?: 0
            if (humanScore - bestOpponent >= 250) mark(ctx, AchievementIds.BLOWOUT_WINS)
        } else {
            mark(ctx, AchievementIds.LOSSES)
        }
    }

    suspend fun sendIfVerified(ctx: Context) {
        val appPrefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val registered = appPrefs.getBoolean("player_registered", false)
        val email = appPrefs.getString("player_email", "") ?: ""
        if (!registered || email.isBlank()) return
        val username = appPrefs.getString("player_username", "") ?: ""
        val stats = StatsRepo.load(ctx)
        val achievements = loadAchievements(ctx)
        runCatching {
            jmotley.com.jspades.networking.ProfileApi.updateProfile(
                email, username, StatsRepo.toServerMap(stats), achievements
            )
            StatsRepo.clearPendingDeltas(ctx)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("jspades_prefs", Context.MODE_PRIVATE)
}
