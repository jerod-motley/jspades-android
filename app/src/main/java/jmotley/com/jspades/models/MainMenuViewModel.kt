package jmotley.com.jspades.models

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jmotley.com.jspades.data.AnnouncementsRepo
import jmotley.com.jspades.networking.OnlineApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainMenuViewModel(application: Application) : AndroidViewModel(application) {
    private val ctx: Context get() = getApplication<Application>().applicationContext

    private val _tickerText = MutableStateFlow<String?>(null)
    val tickerText: StateFlow<String?> = _tickerText

    init { loadTicker() }

    private fun loadTicker() {
        viewModelScope.launch {
            Log.i("TICKER", "loadTicker: fetching announcements")
            runCatching { OnlineApi.getAnnouncements() }
                .onSuccess { items ->
                    Log.i("TICKER", "loadTicker: fetch succeeded count=${items.size}")
                    val text = AnnouncementsRepo.fetchNextUnshown(ctx, items)
                    Log.i("TICKER", "loadTicker: tickerText=${text ?: "<null — no ticker>"}")
                    _tickerText.value = text
                }
                .onFailure { e ->
                    Log.w("TICKER", "loadTicker: fetch failed err=${e.message}")
                }
        }
    }
}
