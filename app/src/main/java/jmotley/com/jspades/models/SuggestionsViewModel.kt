package jmotley.com.jspades.models

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jmotley.com.jspades.networking.OnlineApi
import jmotley.com.jspades.networking.Suggestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class SuggestionsUiState(
    val items: List<Suggestion> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class SuggestionsViewModel(application: Application) : AndroidViewModel(application) {
    private val ctx: Context get() = getApplication<Application>().applicationContext

    private val _state = MutableStateFlow(SuggestionsUiState())
    val state: StateFlow<SuggestionsUiState> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching { OnlineApi.getSuggestions() }
                .onSuccess { items -> _state.update { it.copy(items = items, isLoading = false) } }
                .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    fun create(description: String) {
        if (description.isBlank()) return
        val prefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val user = prefs.getString("player_username", "Anonymous") ?: "Anonymous"
        viewModelScope.launch {
            runCatching {
                OnlineApi.createSuggestion(
                    Suggestion(
                        sk = "${user}_${System.currentTimeMillis()}",
                        description = description.trim(),
                        user = user,
                        createDate = Instant.now().toString()
                    )
                )
            }.onSuccess { load() }
        }
    }

    fun like(suggestion: Suggestion) {
        val prefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val user = prefs.getString("player_username", "Anonymous") ?: "Anonymous"
        viewModelScope.launch {
            runCatching { OnlineApi.likeSuggestion(suggestion, user) }
                .onSuccess { load() }
        }
    }
}
