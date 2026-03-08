package jmotley.com.jspades.models

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jmotley.com.jspades.data.AchievementItem
import jmotley.com.jspades.data.AchievementsRepo
import jmotley.com.jspades.data.Stats
import jmotley.com.jspades.data.StatsRepo
import jmotley.com.jspades.networking.ProfileApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.regex.Pattern

enum class ProfilePhase { FORM, CHECKING, SENT, NOT_VERIFIED, REGISTERED }

data class ProfileUiState(
    val phase: ProfilePhase = ProfilePhase.FORM,
    val email: String = "",
    val username: String = "",
    val errorMessage: String? = null,
    val stats: Stats? = null,
    val achievements: List<AchievementItem> = emptyList()
)

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx: Context get() = getApplication<Application>().applicationContext

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private val _navigateBack = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateBack: SharedFlow<Unit> = _navigateBack.asSharedFlow()

    private val emailPattern = Pattern.compile(
        "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$",
        Pattern.CASE_INSENSITIVE
    )

    fun onEnter() {
        val prefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val email = prefs.getString("player_email", "") ?: ""
        val username = prefs.getString("player_username", "") ?: ""
        val stats = StatsRepo.load(ctx)
        val achievements = AchievementsRepo.loadAchievements(ctx)
        _state.update { it.copy(email = email, username = username, stats = stats, achievements = achievements) }
        if (email.isNotBlank()) checkStatus()
    }

    fun updateEmail(v: String) = _state.update { it.copy(email = v, errorMessage = null) }
    fun updateUsername(v: String) = _state.update { it.copy(username = v, errorMessage = null) }

    fun submit() {
        val s = _state.value
        if (s.username.isBlank()) {
            _state.update { it.copy(errorMessage = "Please enter a username.") }
            return
        }
        if (!emailPattern.matcher(s.email.trim()).matches()) {
            _state.update { it.copy(errorMessage = "Please enter a valid email address.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(phase = ProfilePhase.CHECKING, errorMessage = null) }
            runCatching {
                ProfileApi.register(s.email.trim(), s.username.trim())
                ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
                    .putString("player_email", s.email.trim())
                    .putString("player_username", s.username.trim())
                    .apply()
                _state.update { it.copy(phase = ProfilePhase.SENT) }
                delay(2000)
                _navigateBack.emit(Unit)
            }.onFailure { e ->
                _state.update { it.copy(phase = ProfilePhase.FORM, errorMessage = "Registration failed. Try again.") }
            }
        }
    }

    fun checkStatus() {
        val email = _state.value.email.ifBlank {
            ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getString("player_email", "") ?: ""
        }
        if (email.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(phase = ProfilePhase.CHECKING) }
            runCatching {
                val response = ProfileApi.fetch(email)
                if (response.verified == true) {
                    ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
                        .putBoolean("player_registered", true).apply()
                    response.stats?.let { StatsRepo.applyServerStats(ctx, it) }
                    val updatedStats = StatsRepo.load(ctx)
                    val achievements = AchievementsRepo.loadAchievements(ctx)
                    _state.update {
                        it.copy(phase = ProfilePhase.REGISTERED, stats = updatedStats, achievements = achievements)
                    }
                    AchievementsRepo.sendIfVerified(ctx)
                } else {
                    _state.update { it.copy(phase = ProfilePhase.NOT_VERIFIED) }
                }
            }.onFailure {
                _state.update { it.copy(phase = ProfilePhase.NOT_VERIFIED, errorMessage = "Could not connect. Check your internet.") }
            }
        }
    }
}
