package jmotley.com.jspades.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Stats(
    val gamesPlayed: Int = 0,
    val gamesWon: Int = 0,
    val gamesLost: Int = 0,
    val playedHouseRules: Int = 0,
    val playedKitty: Int = 0,
    val playedClassic: Int = 0,
    val playedTwoMan: Int = 0,
    val playedThreeMan: Int = 0,
    val playedFourMan: Int = 0,
    val timesMetBid: Int = 0,
    val timesDidNotMeetBid: Int = 0,
    val timesSetOpponents: Int = 0,
    val timesBeenSet: Int = 0
)

object StatsRepo {
    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    fun load(ctx: Context): Stats {
        val raw = prefs(ctx).getString("stats_json", null) ?: return Stats()
        return runCatching { json.decodeFromString<Stats>(raw) }.getOrDefault(Stats())
    }

    fun save(ctx: Context, stats: Stats) {
        prefs(ctx).edit().putString("stats_json", json.encodeToString(stats)).apply()
    }

    fun increment(ctx: Context, update: Stats.() -> Stats) {
        save(ctx, update(load(ctx)))
    }

    fun toServerMap(stats: Stats): Map<String, Int> = mapOf(
        "games_played"            to stats.gamesPlayed,
        "games_won"               to stats.gamesWon,
        "games_lost"              to stats.gamesLost,
        "played_house_rules"      to stats.playedHouseRules,
        "played_kitty"            to stats.playedKitty,
        "played_classic"          to stats.playedClassic,
        "played_two_man"          to stats.playedTwoMan,
        "played_three_man"        to stats.playedThreeMan,
        "played_four_man"         to stats.playedFourMan,
        "times_met_bid"           to stats.timesMetBid,
        "times_did_not_meet_bid"  to stats.timesDidNotMeetBid,
        "times_set_opponents"     to stats.timesSetOpponents,
        "times_been_set"          to stats.timesBeenSet
    )

    fun applyServerStats(ctx: Context, serverMap: Map<String, Int>) {
        val current = load(ctx)
        save(ctx, current.copy(
            gamesPlayed           = serverMap["games_played"]           ?: current.gamesPlayed,
            gamesWon              = serverMap["games_won"]              ?: current.gamesWon,
            gamesLost             = serverMap["games_lost"]             ?: current.gamesLost,
            playedHouseRules      = serverMap["played_house_rules"]     ?: current.playedHouseRules,
            playedKitty           = serverMap["played_kitty"]           ?: current.playedKitty,
            playedClassic         = serverMap["played_classic"]         ?: current.playedClassic,
            playedTwoMan          = serverMap["played_two_man"]         ?: current.playedTwoMan,
            playedThreeMan        = serverMap["played_three_man"]       ?: current.playedThreeMan,
            playedFourMan         = serverMap["played_four_man"]        ?: current.playedFourMan,
            timesMetBid           = serverMap["times_met_bid"]          ?: current.timesMetBid,
            timesDidNotMeetBid    = serverMap["times_did_not_meet_bid"] ?: current.timesDidNotMeetBid,
            timesSetOpponents     = serverMap["times_set_opponents"]    ?: current.timesSetOpponents,
            timesBeenSet          = serverMap["times_been_set"]         ?: current.timesBeenSet
        ))
    }

    fun clearPendingDeltas(ctx: Context) {
        prefs(ctx).edit().remove("stats_deltas_json").apply()
    }
}
