package jmotley.com.jspades.data

import android.content.Context
import android.util.Log
import jmotley.com.jspades.networking.Announcement

object AnnouncementsRepo {
    private const val PREF_LAST_SHOWN = "announcement_last_shown_date"

    fun fetchNextUnshown(context: Context, announcements: List<Announcement>): String? {
        if (announcements.isEmpty()) {
            Log.i("TICKER", "AnnouncementsRepo: list is empty")
            return null
        }

        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val lastShown = prefs.getString(PREF_LAST_SHOWN, null)
        Log.i("TICKER", "AnnouncementsRepo: lastShown=$lastShown total=${announcements.size}")

        val next = if (lastShown == null) {
            announcements.maxByOrNull { it.createdate }
        } else {
            val candidates = announcements.filter { it.createdate > lastShown }
            Log.i("TICKER", "AnnouncementsRepo: candidates newer than lastShown=${candidates.size}")
            candidates.minByOrNull { it.createdate }
        }

        if (next == null) {
            Log.i("TICKER", "AnnouncementsRepo: no eligible announcement found")
            return null
        }
        if (next.announcement.isBlank()) {
            Log.w("TICKER", "AnnouncementsRepo: selected announcement has blank message sk=${next.sk}")
            return null
        }

        Log.i("TICKER", "AnnouncementsRepo: selected sk=${next.sk} createdate=${next.createdate} announcement=${next.announcement}")
        prefs.edit().putString(PREF_LAST_SHOWN, next.createdate).apply()
        return next.announcement
    }
}
