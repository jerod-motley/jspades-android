package jmotley.com.jspades.models

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jmotley.com.jspades.data.AppConfig
import jmotley.com.jspades.data.ChatMessage
import jmotley.com.jspades.data.MockLogEntry
import jmotley.com.jspades.data.OnlineLobbyState
import jmotley.com.jspades.data.SeatKind
import jmotley.com.jspades.networking.OnlineSession
import jmotley.com.jspades.networking.SocketState
import jmotley.com.jspades.data.MPGameConfig
import jmotley.com.jspades.data.MPSession
import jmotley.com.jspades.data.MPStateHolder
import android.util.Log
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

sealed class LobbyUiState {
    object RoleChoice : LobbyUiState()
    data class JoinEntry(val codeInput: String = "", val error: String? = null) : LobbyUiState()
    object Connecting : LobbyUiState()
    data class InLobby(
        val lobby: OnlineLobbyState,
        val chat: List<ChatMessage>,
        val mockRunning: Boolean,
        val mockLog: List<MockLogEntry>,
        val countdownSeconds: Int = 0
    ) : LobbyUiState()
}

class OnlineLobbyViewModel(app: Application) : AndroidViewModel(app) {

    private val session = OnlineSession(viewModelScope, AppConfig.GAME_SOCKET_URL)

    private val _uiState = MutableStateFlow<LobbyUiState>(LobbyUiState.RoleChoice)
    val uiState: StateFlow<LobbyUiState> = _uiState

    /** Fires when a real game should start — UI collects this and navigates to PlayScreen. */
    private val _navigateToPlay = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateToPlay: SharedFlow<Unit> = _navigateToPlay

    private var playTransitionStarted = false

    val personId: String = getOrCreatePersonId(app)
    val displayName: String = "Player ${personId.takeLast(4).uppercase()}"

    init {
        // Keep InLobby state in sync with session flows
        viewModelScope.launch {
            session.socketState.collect { socketState ->
                val lobby = session.lobby.value ?: return@collect
                when (socketState) {
                    SocketState.Connected -> pushInLobby(lobby)
                    SocketState.Connecting -> if (_uiState.value !is LobbyUiState.InLobby) {
                        _uiState.value = LobbyUiState.Connecting
                    }
                    SocketState.Disconnected -> if (_uiState.value is LobbyUiState.Connecting) {
                        _uiState.value = LobbyUiState.RoleChoice
                    }
                }
            }
        }
        viewModelScope.launch {
            session.lobby.collect { lobby ->
                if (lobby != null && session.socketState.value == SocketState.Connected) {
                    pushInLobby(lobby)
                }
            }
        }
        viewModelScope.launch {
            session.chat.collect { chat ->
                val current = _uiState.value as? LobbyUiState.InLobby ?: return@collect
                _uiState.value = current.copy(chat = chat)
            }
        }
        viewModelScope.launch {
            session.mockRunning.collect { running ->
                val current = _uiState.value as? LobbyUiState.InLobby ?: return@collect
                _uiState.value = current.copy(mockRunning = running)
            }
        }
        viewModelScope.launch {
            session.mockLog.collect { log ->
                val current = _uiState.value as? LobbyUiState.InLobby ?: return@collect
                _uiState.value = current.copy(mockLog = log)
            }
        }
        viewModelScope.launch {
            session.countdown.collect { secs ->
                val current = _uiState.value as? LobbyUiState.InLobby ?: return@collect
                _uiState.value = current.copy(countdownSeconds = secs)
            }
        }
        viewModelScope.launch {
            session.gameStartSignal.collect {
                try {
                    maybeNavigateToPlay("countdownComplete")
                } catch (t: Throwable) {
                    Log.e("LobbyVM", "gameStartSignal handling failed", t)
                    throw t
                }
            }
        }
    }

    private suspend fun maybeNavigateToPlay(reason: String) {
        if (playTransitionStarted) return

        val lobby = session.lobby.value ?: return
        if (!lobby.isHost && !session.startGameReady.value) return

        val seatIndex = lobby.localSeatIndex.coerceAtLeast(0)
        playTransitionStarted = true
        Log.i("LobbyVM", "maybeNavigateToPlay reason=$reason room=${lobby.roomId} localSeat=$seatIndex")

        val rawNames = (0..3).map { i ->
            val seat = lobby.seats.find { it.seatIndex == i }
            when {
                seat == null || seat.kind == SeatKind.Open -> "CPU"
                seat.playerId == lobby.localPlayerId -> lobby.localDisplayName
                else -> seat.displayName ?: "Player"
            }
        }
        val rotated = (0..3).map { offset -> rawNames[(seatIndex + offset) % 4] }
        val remoteHumanSeats = (0..3).filter { i ->
            i != seatIndex &&
            lobby.seats.find { it.seatIndex == i }?.kind == SeatKind.Human
        }.toSet()
        val config = MPGameConfig(
            playerNames      = rotated,
            localSeatIndex   = seatIndex,
            roomId           = lobby.roomId,
            remoteHumanSeats = remoteHumanSeats
        )
        Log.i("LobbyVM", "navigateToPlay reason=$reason players=$rotated")
        MPStateHolder.set(config)
        _navigateToPlay.emit(Unit)
    }

    private fun pushInLobby(lobby: OnlineLobbyState) {
        val current = _uiState.value as? LobbyUiState.InLobby
        _uiState.value = LobbyUiState.InLobby(
            lobby           = lobby,
            chat            = current?.chat ?: session.chat.value,
            mockRunning     = current?.mockRunning ?: session.mockRunning.value,
            mockLog         = current?.mockLog ?: session.mockLog.value,
            countdownSeconds = current?.countdownSeconds ?: session.countdown.value
        )
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun chooseHost() {
        playTransitionStarted = false
        _uiState.value = LobbyUiState.Connecting
        MPSession.session = session
        MPSession.isHost = true
        session.createRoom(personId, displayName)
    }

    fun chooseJoin() {
        _uiState.value = LobbyUiState.JoinEntry()
    }

    fun updateJoinCode(code: String) {
        val current = _uiState.value as? LobbyUiState.JoinEntry ?: return
        _uiState.value = current.copy(codeInput = code.uppercase(), error = null)
    }

    fun submitJoin() {
        val current = _uiState.value as? LobbyUiState.JoinEntry ?: return
        val code = current.codeInput.trim()
        if (code.length < 4) {
            _uiState.value = current.copy(error = "Enter a valid room code")
            return
        }
        playTransitionStarted = false
        _uiState.value = LobbyUiState.Connecting
        MPSession.session = session
        MPSession.isHost = false
        session.joinRoom(personId, displayName, code)
    }

    fun sendChat(text: String) {
        if (text.isBlank()) return
        session.sendChat(text)
    }

    fun startMockTest() {
        session.startMockProtocol()
    }

    fun startGame() {
        session.startRealGame()
    }

    fun swapSeats(seatA: Int, seatB: Int) {
        session.swapSeats(seatA, seatB)
    }

    fun disconnect() {
        playTransitionStarted = false
        session.disconnect()
        MPSession.clear()
        _uiState.value = LobbyUiState.RoleChoice
    }

    override fun onCleared() {
        super.onCleared()
        session.disconnect()
    }

    // ── Identity ──────────────────────────────────────────────────────────────

    private fun getOrCreatePersonId(context: Context): String {
        val prefs = context.getSharedPreferences("jspades_mp", Context.MODE_PRIVATE)
        return prefs.getString("player_id", null) ?: UUID.randomUUID().toString().lowercase().also {
            prefs.edit().putString("player_id", it).apply()
        }
    }
}
