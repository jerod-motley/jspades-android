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
import kotlinx.coroutines.withTimeoutOrNull
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
                val lobby = session.lobby.value ?: return@collect
                val seatIndex = lobby.localSeatIndex.coerceAtLeast(0)
                // Build player name list rotated so this device's seat is always "south".
                // Room seats: 0=host/south, 1=west, 2=north, 3=east (from host's view).
                // Rotation: seat at seatIndex becomes south, the rest wrap clockwise.
                val rawNames = (0..3).map { i ->
                    val seat = lobby.seats.find { it.seatIndex == i }
                    when {
                        seat == null || seat.kind == SeatKind.Open -> "CPU"
                        seat.playerId == lobby.localPlayerId -> lobby.localDisplayName
                        else -> seat.displayName ?: "Player"
                    }
                }
                val rotated = (0..3).map { offset -> rawNames[(seatIndex + offset) % 4] }
                // Rotate the server-dealt hands to canonical IDs ("south","west","north","east")
                // using the same seatIndex rotation as the player names.
                val canonicalIds = listOf("south", "west", "north", "east")
                // If the deal hasn't arrived yet (e.g. slow relay), wait up to 3 s before
                // proceeding. The deal is sent by the host during the 5-second countdown so
                // it should always be buffered before we get here.
                val deal = session.pendingDeal.value
                    ?: withTimeoutOrNull(3_000L) { session.pendingDeal.first { it != null } }
                if (deal == null) Log.w("LobbyVM", "gameStartSignal fired but deal is still null — starting without pre-dealt hands")
                val rotatedHands = deal?.let {
                    canonicalIds.indices.associate { offset ->
                        canonicalIds[offset] to (it.hands[(seatIndex + offset) % 4] ?: emptyList())
                    }
                }
                // Correct firstLead if it points to an open seat (iOS hardcodes firstLead=1).
                // Walk clockwise from the raw value until we hit an occupied seat.
                val rawFirstLead = deal?.firstLead ?: 1
                val firstLeadRoomSeat = if (lobby.seats.find { it.seatIndex == rawFirstLead }?.kind != SeatKind.Open) {
                    rawFirstLead
                } else {
                    (1..3).map { (rawFirstLead + it) % 4 }
                        .firstOrNull { idx -> lobby.seats.find { s -> s.seatIndex == idx }?.kind != SeatKind.Open }
                        ?: rawFirstLead
                }
                // Canonical IDs of non-south players who are human (not open/CPU).
                // PhaseManager waits for their individual bid via WebSocket instead of auto-bidding.
                val remoteHumanIds = canonicalIds.indices
                    .filter { offset ->
                        offset > 0 &&
                        lobby.seats.find { s -> s.seatIndex == (seatIndex + offset) % 4 }?.kind == SeatKind.Human
                    }
                    .map { canonicalIds[it] }
                    .toSet()
                // Guests await ALL non-south plays from the host (CPU + human).
                // The host only awaits guest human plays.
                val remotePlayerIds: Set<String> = if (MPSession.isHost) {
                    remoteHumanIds
                } else {
                    canonicalIds.drop(1).toSet()  // west, north, east
                }
                val config = MPGameConfig(
                    playerNames       = rotated,
                    localSeatIndex    = seatIndex,
                    roomId            = lobby.roomId,
                    dealtHands        = rotatedHands,
                    firstLeadRoomSeat = firstLeadRoomSeat,
                    remoteHumanIds    = remoteHumanIds,
                    remotePlayerIds   = remotePlayerIds
                )
                MPStateHolder.set(config)
                session.consumePendingDeal()
                _navigateToPlay.emit(Unit)
            }
        }
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
