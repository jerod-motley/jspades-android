package jmotley.com.jspades.models

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jmotley.com.jspades.networking.Message
import jmotley.com.jspades.networking.OnlineApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class MessagesUiState(
    val items: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class MessagesViewModel(application: Application) : AndroidViewModel(application) {
    private val ctx: Context get() = getApplication<Application>().applicationContext

    private val _state = MutableStateFlow(MessagesUiState())
    val state: StateFlow<MessagesUiState> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching { OnlineApi.getMessages() }
                .onSuccess { items -> _state.update { it.copy(items = items, isLoading = false) } }
                .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    fun create(text: String) {
        if (text.isBlank()) return
        val prefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val author = prefs.getString("player_username", "Anonymous") ?: "Anonymous"
        viewModelScope.launch {
            runCatching {
                OnlineApi.createMessage(
                    Message(
                        sk = "${author}_${System.currentTimeMillis()}",
                        author = author,
                        text = text.trim(),
                        createDate = Instant.now().toString()
                    )
                )
            }.onSuccess { load() }
        }
    }
}
