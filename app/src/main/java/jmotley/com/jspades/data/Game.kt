package jmotley.com.jspades.data

import kotlin.jvm.JvmInline

/**
 * Canonical, platform-agnostic game model for jSpades.
 * This file defines immutable-ish data classes representing the canonical
 * GameState snapshot that should be owned by a single controller/ViewModel
 * and emitted to UI subscribers.
 */

/** Basic card model. */
enum class Suit { CLUBS, DIAMONDS, HEARTS, SPADES }

enum class Rank(val value: Int) {
    TWO(2), THREE(3), FOUR(4), FIVE(5), SIX(6), SEVEN(7), EIGHT(8), NINE(9), TEN(10),
    JACK(11), QUEEN(12), KING(13), ACE(14), DEUCE(15), LITTLEJOKER(16), BIGJOKER(17)
}

data class Card(
    val suit: Suit,
    val rank: Rank,
    /**
     * Lightweight unique-ish id for diffs. Uses rank value and suit ordinal so it
     * maps cleanly to asset names like `c2_1.png` when using `assetFileName()`.
     */
    val uid: String = run {
        val si = if (rank == Rank.LITTLEJOKER || rank == Rank.BIGJOKER) Suit.SPADES.ordinal + 1 else suit.ordinal + 1
        "${rank.value}_$si"
    }
) {
    /** Return the expected asset filename for this card (e.g. `c2_1.png`).
     * Jokers are treated as spades (suit index 4) for asset lookup.
     * Suit index mapping: CLUBS=1, DIAMONDS=2, HEARTS=3, SPADES=4
     */
    fun assetFileName(): String {
        val suitIndex = if (rank == Rank.LITTLEJOKER || rank == Rank.BIGJOKER) {
            Suit.SPADES.ordinal + 1
        } else {
            suit.ordinal + 1
        }
        return "c${rank.value}_$suitIndex.png"
    }
}

/** Player and runtime flags. */
data class RuntimeFlags(
    val didBid: Boolean = false,
    val currentCard: Card? = null,
    val seatIndex: Int = 0
)

/** Player includes team assignment and runtime flags. */
data class Player(
    val id: String,
    val name: String,
    val displayName: String = name,
    /** Team id: 0 or 1 for team games; use 0 for non-team games. */
    val team: Int = 0,
    val runtimeFlags: RuntimeFlags = RuntimeFlags()
)

/** Represents a hand (cards held by a player or the kitty). */
/** Per-player state for a single hand/round (keyed by player id). */
data class PlayerHandState(
    val hand: List<Card> = emptyList(),
    val bid: Int = 0,
    val tricksWon: Int = 0,
    val isBlind: Boolean = false
)

/** Hand model used for a single hand/round. Uses player id keys so GameState.players
 * can remain canonical and stable while per-hand data is stored separately.
 */
data class Hand(
    /** Trick plays: an array of card arrays (groups of plays, usually groups of 4) */
    val trickPlays: List<List<Card>> = emptyList(),
    /** Player order for this hand (may differ from canonical game player order) */
    val playerOrder: List<String> = emptyList(),
    /** Per-player state keyed by player id (dealt cards, bids, tricksWon, blind) */
    val perPlayer: Map<String, PlayerHandState> = emptyMap(),
    /** Per-team bids (2 entries for team 0 and team 1) */
    val teamBids: List<Int?> = listOf(0, 0),
    /** Team blind flags (2 entries) */
    val teamBlind: List<Boolean> = listOf(false, false),
    /** Player blind flags keyed by player id */
    val playerBlind: Map<String, Boolean> = emptyMap()
) {
    fun contains(card: Card) = perPlayer.values.any { list -> list.hand.any { it.uid == card.uid } }
}

/** A single card play in a trick. */
data class Play(val playerId: String, val card: Card)

/** Current trick: ordered by play order (may contain nulls for not-played slots). */
data class Trick(val plays: List<Play?> = listOf(null, null, null, null)) {
    val isComplete: Boolean get() = plays.all { it != null }
}

/** Scoring representation. Team or player keyed by id. */
data class Score(
    val points: Map<String, Int> = emptyMap(),
    val bags: Map<String, Int> = emptyMap()
)

/** High-level phases used by the engine to drive UI and side-effects. */
enum class GamePhase { Lobby, Deal, Kitty, Bid, Play, TrickResolve, Score, Finished }

/** Lightweight metadata for snapshots. */
data class Metadata(val id: String? = null, val timestampMs: Long? = null)

/** The canonical snapshot representing the entire game state. */
data class GameState(
    /** Canonical player array (never re-ordered). */
    val players: List<Player> = emptyList(),
    val leaderIndex: Int = 0, // index into `players` indicating who leads this hand/trick
    val currentTrick: Trick = Trick(),
    val hands: Map<String, Hand> = emptyMap(), // playerId -> Hand
    val kitty: Hand? = null,
    /** Cards that have been played / collected (discard pile). */
    val discard: List<Card> = emptyList(),
    val trump: Suit? = null,
    val score: Score = Score(),
    val phase: GamePhase = GamePhase.Lobby,
    /** Map of phase -> hands (array of Hand objects tied to phases) */
    val phaseHands: Map<GamePhase, List<Hand>> = emptyMap(),
    val metadata: Metadata? = null
)

/** Helpers */
fun GameState.playerById(id: String): Player? = players.find { it.id == id }
fun GameState.handForPlayer(id: String): Hand = hands[id] ?: Hand()

/**
 * Convenience: returns the local player's current card list.
 * Prefers `hands[localPlayerId]` (live state), falls back to the last
 * Deal-phase snapshot so the Hand view always has something to show.
 */
fun GameState.localHand(localPlayerId: String): List<Card> =
    hands[localPlayerId]?.perPlayer?.get(localPlayerId)?.hand
        ?: phaseHands[GamePhase.Deal]?.lastOrNull()?.perPlayer?.get(localPlayerId)?.hand
        ?: emptyList()

/** Helpers to create default player states with teams 0/1 assigned to seats 0..3. */
fun defaultFourPlayers(ids: List<String>, names: List<String>): List<Player> {
    require(ids.size == 4 && names.size == 4)
    return List(4) { i ->
        val team = if (i % 2 == 0) 0 else 1
        Player(id = ids[i], name = names[i], team = team, runtimeFlags = RuntimeFlags(seatIndex = i))
    }
}
