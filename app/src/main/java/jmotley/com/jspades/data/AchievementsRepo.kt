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

@Serializable
data class CompletedChallengeItem(
    val sk: String = "",
    val gameType: String = "",
    val achieved: Boolean = false,
    val date: String? = null,
    val name: String? = null
) {
    fun challengeSk(): String = if (sk.isNotBlank()) sk else (name ?: "")
}

object AchievementsRepo {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO)

    // ── Achievements ──────────────────────────────────────────────────────────

    private val _achievementsFlow = MutableStateFlow<List<AchievementItem>>(emptyList())
    val achievementsFlow: StateFlow<List<AchievementItem>> = _achievementsFlow

    // ── Challenges ────────────────────────────────────────────────────────────

    private val _completedChallengesFlow = MutableStateFlow<List<CompletedChallengeItem>>(emptyList())
    val completedChallengesFlow: StateFlow<List<CompletedChallengeItem>> = _completedChallengesFlow

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

    fun applyServerAchievements(ctx: Context, serverItems: List<AchievementItem>) {
        if (serverItems.isEmpty()) return

        val merged = (loadAchievements(ctx) + serverItems)
            .filter { it.achieved }
            .groupBy { it.name }
            .map { (_, items) ->
                items.maxByOrNull { it.date ?: "" } ?: items.first()
            }
            .sortedBy { it.name }

        saveAchievements(ctx, merged)
        _achievementsFlow.value = merged
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
     * On failure: removes from pending.
     */
    fun finalizeChallenge(ctx: Context, sk: String, gameType: String, succeeded: Boolean) {
        val pending = loadPending(ctx).toMutableSet()
        pending.remove(sk)
        savePending(ctx, pending)
        _pendingFlow.value = pending

        if (succeeded) {
            val current = loadCompletedChallenges(ctx).toMutableList()
            if (current.none { it.challengeSk() == sk && it.gameType == gameType && it.achieved }) {
                val entry = CompletedChallengeItem(
                    sk = sk,
                    gameType = gameType,
                    achieved = true,
                    date = Instant.now().toString(),
                    name = sk
                )
                val idx = current.indexOfFirst { it.challengeSk() == sk && it.gameType == gameType }
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

    fun loadCompletedChallenges(ctx: Context): List<CompletedChallengeItem> {
        val raw = prefs(ctx).getString("challenges_json", null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<CompletedChallengeItem>>(raw) }.getOrElse {
            runCatching {
                json.decodeFromString<List<AchievementItem>>(raw).map {
                    CompletedChallengeItem(
                        sk = it.name,
                        gameType = "",
                        achieved = it.achieved,
                        date = it.date,
                        name = it.name
                    )
                }
            }.getOrDefault(emptyList())
        }
    }

    fun applyServerCompletedChallenges(ctx: Context, serverItems: List<CompletedChallengeItem>) {
        if (serverItems.isEmpty()) return

        val merged = (loadCompletedChallenges(ctx) + serverItems)
            .filter { it.achieved }
            .groupBy { it.challengeSk() to it.gameType }
            .map { (_, items) ->
                items.maxByOrNull { it.date ?: "" } ?: items.first()
            }
            .sortedBy { it.challengeSk() }

        saveCompletedChallenges(ctx, merged)
        _completedChallengesFlow.value = merged
    }

    private fun saveCompletedChallenges(ctx: Context, items: List<CompletedChallengeItem>) {
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

        // ── Stats ─────────────────────────────────────────────────────────────

        // times_met_bid / times_did_not_meet_bid — based on human's result this hand
        if (state.gameType.useTeams) {
            val humanBid    = hand.teamBids.getOrNull(humanTeam) ?: 0
            val humanTricks = humanTeamTricks
            if (humanBid > 0) {
                if (humanTricks >= humanBid) StatsRepo.increment(ctx) { copy(timesMetBid = timesMetBid + 1) }
                else                         StatsRepo.increment(ctx) { copy(timesDidNotMeetBid = timesDidNotMeetBid + 1) }
            }
        } else {
            if (southPhs.bid > 0) {
                if (southPhs.tricksWon >= southPhs.bid) StatsRepo.increment(ctx) { copy(timesMetBid = timesMetBid + 1) }
                else                                    StatsRepo.increment(ctx) { copy(timesDidNotMeetBid = timesDidNotMeetBid + 1) }
            }
        }

        // times_set_opponents — human set the opposing side this hand
        val humanSetOpponents = if (state.gameType.useTeams) {
            val oppTeam   = 1 - humanTeam
            val oppTricks = state.players.filter { it.team == oppTeam }
                .sumOf { hand.perPlayer[it.id]?.tricksWon ?: 0 }
            val oppBid    = hand.teamBids.getOrNull(oppTeam) ?: 0
            oppBid > 0 && oppTricks < oppBid
        } else {
            state.players.filter { it.id != "south" }.any { p ->
                val phs = hand.perPlayer[p.id] ?: return@any false
                phs.bid > 0 && phs.tricksWon < phs.bid
            }
        }
        if (humanSetOpponents) StatsRepo.increment(ctx) { copy(timesSetOpponents = timesSetOpponents + 1) }

        // times_been_set — human was set this hand
        val humanWasSet = if (state.gameType.useTeams) {
            val humanBid = hand.teamBids.getOrNull(humanTeam) ?: 0
            humanBid > 0 && humanTeamTricks < humanBid
        } else {
            southPhs.bid > 0 && southPhs.tricksWon < southPhs.bid
        }
        if (humanWasSet) StatsRepo.increment(ctx) { copy(timesBeenSet = timesBeenSet + 1) }
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
            StatsRepo.increment(ctx) { copy(gamesWon = gamesWon + 1) }
        } else {
            mark(ctx, AchievementIds.LOSSES)
            StatsRepo.increment(ctx) { copy(gamesLost = gamesLost + 1) }
        }

        // games_played + exactly one played_* for the game type
        val gameTypeStat: Stats.() -> Stats = when (state.gameType) {
            GameType.HOUSE_RULES    -> { { copy(gamesPlayed = gamesPlayed + 1, playedHouseRules  = playedHouseRules  + 1) } }
            GameType.TEAM_KITTY    -> { { copy(gamesPlayed = gamesPlayed + 1, playedKitty        = playedKitty        + 1) } }
            GameType.TEAM_CLASSIC  -> { { copy(gamesPlayed = gamesPlayed + 1, playedClassic      = playedClassic      + 1) } }
            GameType.SOLO_TWO_MAN  -> { { copy(gamesPlayed = gamesPlayed + 1, playedTwoMan       = playedTwoMan       + 1) } }
            GameType.SOLO_THREE_MAN-> { { copy(gamesPlayed = gamesPlayed + 1, playedThreeMan     = playedThreeMan     + 1) } }
            GameType.SOLO_FOUR_MAN -> { { copy(gamesPlayed = gamesPlayed + 1, playedFourMan      = playedFourMan      + 1) } }
        }
        StatsRepo.increment(ctx, gameTypeStat)
    }

    suspend fun sendIfVerified(ctx: Context) {
        val appPrefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val registered = appPrefs.getBoolean("player_registered", false)
        val email = appPrefs.getString("player_email", "") ?: ""
        if (!registered || email.isBlank()) return
        val username = appPrefs.getString("player_username", "") ?: ""
        val stats = StatsRepo.load(ctx)
        val deltas = StatsRepo.loadDeltas(ctx)
        val statsMap = StatsRepo.toServerMap(stats) + StatsRepo.deltasToServerMap(deltas)
        val achievements = loadAchievements(ctx)
        val challenges = loadCompletedChallenges(ctx)
        runCatching {
            val success = jmotley.com.jspades.networking.ProfileApi.updateProfile(
                email, username, statsMap, achievements, challenges, add = true
            )
            if (success) StatsRepo.clearPendingDeltas(ctx)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("jspades_prefs", Context.MODE_PRIVATE)
}
