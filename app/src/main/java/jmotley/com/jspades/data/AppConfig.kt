package jmotley.com.jspades.data

import jmotley.com.jspades.BuildConfig

object AppConfig {
    val BASE_API_URL: String = BuildConfig.BASE_API_URL
    val WEB_URL: String = BuildConfig.WEB_URL
    val GAME_SOCKET_URL: String = BuildConfig.GAME_SOCKET_URL
    val TEST_MODE: Boolean = false //BuildConfig.DEBUG
    /** Writes every raw WSS send/receive frame to the app's files/mplogs.md. */
    val ENABLE_WSS_MESSAGE_FILE_LOGGING: Boolean = BuildConfig.DEBUG
    /** Manually flip to true on BOTH devices' local debug builds to advertise `semanticFacts`/
     * `stateResync` and exercise Slice 3's bid-recovery path (order-independent bidding,
     * host self-heal, resync request/response, snapshot apply) end-to-end during manual
     * testing. Normal device testing never negotiates these capabilities, so that path never
     * runs otherwise. Must stay false outside a deliberate local test session — flip back
     * before committing/releasing. */
    val ENABLE_STRICT_BID_RECOVERY_TESTING: Boolean = true // TEMP: local MP test session — revert before commit/release
    const val PARTITION_KEY = "JSPADES"

    /** Full leaderboard URL with partition key query param, or empty string if WEB_URL is not set. */
    val LEADERBOARD_URL: String get() = if (WEB_URL.isBlank()) "" else "$WEB_URL" + "scoressp"
}
