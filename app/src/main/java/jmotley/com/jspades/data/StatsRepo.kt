package jmotley.com.jspades.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Stats(
    val gamesPlayed: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val bostons: Int = 0,
    val quits: Int = 0,
    val consecutiveWins: Int = 0,
    val booksWon: Int = 0,
    val booksAgainst: Int = 0,
    val bidsMade: Int = 0,
    val bidsSet: Int = 0,
    val totalBidLevel: Int = 0,
    val handsAsDeclarerWon: Int = 0,
    val handsAsDeclarerLost: Int = 0
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

    fun toServerMap(stats: Stats): Map<String, Int> = mapOf(
        "games_played" to stats.gamesPlayed,
        "games_won" to stats.wins,
        "games_lost" to stats.losses,
        "bostons" to stats.bostons,
        "quits" to stats.quits,
        "consecutive_wins" to stats.consecutiveWins,
        "books_won" to stats.booksWon,
        "books_against" to stats.booksAgainst,
        "bids_made" to stats.bidsMade,
        "bids_set" to stats.bidsSet,
        "total_bid_level" to stats.totalBidLevel,
        "hands_as_declarer_won" to stats.handsAsDeclarerWon,
        "hands_as_declarer_lost" to stats.handsAsDeclarerLost
    )

    fun applyServerStats(ctx: Context, serverMap: Map<String, Int>) {
        val current = load(ctx)
        save(ctx, current.copy(
            gamesPlayed = serverMap["games_played"] ?: current.gamesPlayed,
            wins = serverMap["games_won"] ?: current.wins,
            losses = serverMap["games_lost"] ?: current.losses,
            bostons = serverMap["bostons"] ?: current.bostons,
            quits = serverMap["quits"] ?: current.quits,
            consecutiveWins = serverMap["consecutive_wins"] ?: current.consecutiveWins,
            booksWon = serverMap["books_won"] ?: current.booksWon,
            booksAgainst = serverMap["books_against"] ?: current.booksAgainst,
            bidsMade = serverMap["bids_made"] ?: current.bidsMade,
            bidsSet = serverMap["bids_set"] ?: current.bidsSet,
            totalBidLevel = serverMap["total_bid_level"] ?: current.totalBidLevel,
            handsAsDeclarerWon = serverMap["hands_as_declarer_won"] ?: current.handsAsDeclarerWon,
            handsAsDeclarerLost = serverMap["hands_as_declarer_lost"] ?: current.handsAsDeclarerLost
        ))
    }

    fun clearPendingDeltas(ctx: Context) {
        prefs(ctx).edit().remove("stats_deltas_json").apply()
    }
}
