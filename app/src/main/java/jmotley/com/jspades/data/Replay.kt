package jmotley.com.jspades.data

/**
 * Event-sourced replay model for a single hand.
 *
 * Events are recorded during normal play and used to deterministically reconstruct
 * the hand state at any step for the replay viewer.
 */
sealed class ReplayEvent {
    /** Cards dealt to each player, stored as uid strings for compact encoding. */
    data class Deal(val playerHands: Map<String, List<String>>) : ReplayEvent()

    /** A bid placed by a player. */
    data class Bid(val playerId: String, val bid: Int, val isBlind: Boolean) : ReplayEvent()

    /** A single card played into the current trick. */
    data class CardPlay(val playerId: String, val cardUid: String) : ReplayEvent()

    /** The trick winner after all players have played. */
    data class TrickWon(val winnerId: String) : ReplayEvent()
}

/**
 * Finalized replay for one completed hand.
 * Stored as [GameState.lastHandReplay] after scoring.
 */
data class HandReplay(
    val players: List<Player>,
    val gameType: GameType,
    val events: List<ReplayEvent>
)
