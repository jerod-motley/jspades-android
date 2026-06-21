package jmotley.com.jspades.data

data class MPGameConfig(
    /** [south, west, north, east] display names rotated for this device's perspective */
    val playerNames: List<String>,
    /** Canonical IDs used by GameViewModel ("south","west","north","east") */
    val playerIds: List<String> = listOf("south", "west", "north", "east"),
    /** This device's room seat index (0 = host/south, 1..3 = guests) */
    val localSeatIndex: Int = 0,
    val roomId: String = "",
    val handNum: Int = 1,
    /** Room seat indices of human players who are NOT this device (host only). */
    val remoteHumanSeats: Set<Int> = emptySet()
)

/** App-scoped one-shot holder for passing MP game config from the lobby to PlayScreen. */
object MPStateHolder {
    @Volatile private var _config: MPGameConfig? = null

    fun set(config: MPGameConfig) { _config = config }

    /** Consume and clear — returns null if nothing pending. */
    fun consume(): MPGameConfig? = _config?.also { _config = null }

    fun peek(): MPGameConfig? = _config
}
