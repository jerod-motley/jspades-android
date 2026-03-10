package jmotley.com.jspades.data

import jmotley.com.jspades.BuildConfig

object AppConfig {
    val BASE_API_URL: String = BuildConfig.BASE_API_URL
    val WEB_URL: String = BuildConfig.WEB_URL
    const val PARTITION_KEY = "JSPADES"

    /** Full leaderboard URL with partition key query param, or empty string if WEB_URL is not set. */
    val LEADERBOARD_URL: String get() = if (WEB_URL.isBlank()) "" else "$WEB_URL" + "scoressp"
}
