package jmotley.com.jspades.data

/**
 * One-shot events fired by [PhaseManager] when the engine performs a CPU action.
 *
 * The engine emits one event per step and returns immediately (fire-and-forget).
 * The UI observes these via [GameViewModel.animationEvents], plays the corresponding
 * animation, and calls [GameViewModel.phaseManager].execute() when done so the
 * engine can advance to the next step.
 */
sealed class AnimationEvent {
    /** A player placed their bid (CPU-driven). */
    data class BidPlaced(val playerId: String, val bid: Int) : AnimationEvent()

    /** A CPU player played a card into the current trick. */
    data class CardPlayed(val playerId: String, val card: Card) : AnimationEvent()

    /**
     * All players have played; [winnerId] takes the trick.
     * [plays] is the snapshot of trick plays captured before they were cleared,
     * so the UI can animate cards flying to the winner's seat.
     */
    data class TrickWon(val winnerId: String, val plays: List<Play>) : AnimationEvent()
}
