package jmotley.com.jspades.data

data class OnlineLobbyState(
    val roomId: String,
    val inviteCode: String,
    val hostPlayerId: String,
    val localPlayerId: String,
    val localDisplayName: String,
    val isHost: Boolean,
    val seats: List<OnlineSeat> = List(4) { OnlineSeat(seatIndex = it) },
    val status: LobbyStatus = LobbyStatus.Waiting
) {
    /** Room seat index of this device's player (0=host/south…3). Derived from seats list. */
    val localSeatIndex: Int
        get() = seats.find { it.playerId == localPlayerId }?.seatIndex ?: if (isHost) 0 else -1
}

data class OnlineSeat(
    val seatIndex: Int,
    val playerId: String? = null,
    val displayName: String? = null,
    val isHost: Boolean = false,
    val kind: SeatKind = SeatKind.Open,
    val connection: ConnectionStatus = ConnectionStatus.Connected
)

data class ChatMessage(
    val senderId: String,
    val displayName: String,
    val text: String,
    val isLocal: Boolean
)

data class MockLogEntry(
    val direction: LogDirection,
    val type: String,
    val summary: String,
    val status: LogStatus = LogStatus.Ok
)

enum class LogDirection { Sent, Received }
enum class LogStatus { Ok, Error }
enum class SeatKind { Open, Human, Cpu }
enum class ConnectionStatus { Connected, GracePeriod, CpuSubstitute, Disconnected }
enum class LobbyStatus { Waiting, Starting, InGame }
