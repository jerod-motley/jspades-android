package jmotley.com.jspades.data

data class MPGameConfig(
    /** [south, west, north, east] display names */
    val playerNames: List<String>,
    /** Canonical IDs used by GameViewModel ("south","west","north","east") */
    val playerIds: List<String> = listOf("south", "west", "north", "east"),
    /** This device's room seat index (0 = host/south, 1 = west, 2 = north, 3 = east) */
    val localSeatIndex: Int = 0,
    val roomId: String = "",
    /**
     * Pre-dealt hands keyed by canonical player ID ("south","west","north","east"), already
     * rotated for this device's perspective. When set, PlayScreen skips the local shuffle
     * so every device plays the same deck broadcast by the host.
     */
    val dealtHands: Map<String, List<String>>? = null,
    /**
     * Room seat index of the player who leads the first trick (computed by the host from the
     * actual seat layout). Used to set the game engine's leaderIndex for trick play.
     */
    val firstLeadRoomSeat: Int = 1,
    /** Canonical IDs of non-south human players whose bids arrive via WebSocket. */
    val remoteHumanIds: Set<String> = emptySet()
)

/** App-scoped one-shot holder for passing MP game config from the lobby to PlayScreen. */
object MPStateHolder {
    @Volatile private var _config: MPGameConfig? = null

    fun set(config: MPGameConfig) { _config = config }

    /** Consume and clear — returns null if nothing pending. */
    fun consume(): MPGameConfig? = _config?.also { _config = null }

    fun peek(): MPGameConfig? = _config
}
