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
    val seatIndex: Int = 0,
    // Void-tracking: true once the player has failed to follow a suit (cutting = void)
    val cuttingSpades: Boolean = false,
    val cuttingHearts: Boolean = false,
    val cuttingClubs: Boolean = false,
    val cuttingDiamonds: Boolean = false,
    /** Suit of the first card this player discarded (throwoff signal to partner). */
    val suitFirstThrowOff: Suit? = null
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

// ── Deal mode ─────────────────────────────────────────────────────────────────

/**
 * Controls how cards are physically distributed to players.
 * Used by PhaseManager.handleDeal() to select the correct algorithm.
 */
enum class DealMode {
    /** Shuffle the deck, then deal sequentially (subList per player). */
    STANDARD,
    /** Kitty variant: set aside [GameType.kittySize] cards first; guarantee 2♠ in a player's hand. */
    KITTY_TWO_OF_SPADES,
    /** Two Man Solo: players alternate keep/skip picks from the top of the face-down deck. */
    TWO_MAN_ALTERNATE
}

// ── Game variant ───────────────────────────────────────────────────────────────

/**
 * All selectable game variants. Each entry fully describes the deck and deal rules
 * so that PhaseManager and defaultPlayers() need only read these properties.
 *
 * Deck construction order:
 *  1. Start with 52 standard face cards (TWO–ACE × all 4 suits).
 *  2. If [includeJokers] → append Little Joker + Big Joker (2 cards).
 *  3. If [removeTwoOfHearts] → remove 2♥.
 *  4. If [removeTwoOfClubs]  → remove 2♣.
 *  5. Shuffle; set aside [kittySize] cards for the kitty.
 *  6. Deal [cardsPerPlayer] to each of [playerCount] players.
 */
enum class GameType(
    /** Display name matching the menu button label. */
    val label: String,
    /** Number of players at the table (2–4). */
    val playerCount: Int,
    /** Whether players are split into two opposing teams. */
    val useTeams: Boolean,
    /** Whether the Little Joker and Big Joker are included. */
    val includeJokers: Boolean,
    /** Whether the 2 of Hearts is removed from the deck. */
    val removeTwoOfHearts: Boolean,
    /** Whether the 2 of Clubs is removed from the deck. */
    val removeTwoOfClubs: Boolean,
    /** Cards reserved for the kitty before player hands are dealt (0 = no kitty). */
    val kittySize: Int,
    /** Algorithm used to distribute cards to players. */
    val dealMode: DealMode,
    /** Lowest legal bid value for this variant. */
    val minimumBid: Int
) {
    // Team Play
    /** 4-player team game. Standard 52-card deck (jokers − 2♥ − 2♣). No kitty. */
    HOUSE_RULES ("House Rules",    4, true,  true,  true,  true,  0, DealMode.STANDARD,            4),
    /** 4-player team game. All 54 cards; 6-card kitty; 2♠ must go to a player. */
    TEAM_KITTY  ("Kitty",          4, true,  true,  false, false, 6, DealMode.KITTY_TWO_OF_SPADES, 5),
    /** 4-player team game. Pure 52 face cards (no jokers, no removals). No kitty. */
    TEAM_CLASSIC("Classic",        4, true,  false, false, false, 0, DealMode.STANDARD,            0),

    // Solo Play
    /** 4-player solo (no teams). Standard 52-card deck. No kitty. */
    SOLO_FOUR_MAN ("Four Man Solo",  4, false, true,  true,  true,  0, DealMode.STANDARD,            0),
    /** 3-player solo (no teams). All 54 cards; 18 per player. No kitty. */
    SOLO_THREE_MAN("Three Man Solo", 3, false, true,  false, false, 0, DealMode.STANDARD,            0),
    /** 2-player solo (no teams). Standard 52-card deck; alternate keep/skip deal. */
    SOLO_TWO_MAN  ("Two Man Solo",   2, false, true,  true,  true,  0, DealMode.TWO_MAN_ALTERNATE,   0);

    /**
     * Cards each player receives after the kitty is set aside.
     * Derived from deck size minus kitty size divided by player count.
     */
    val cardsPerPlayer: Int get() {
        val deckSize = 52 +
            (if (includeJokers) 2 else 0) -
            (if (removeTwoOfHearts) 1 else 0) -
            (if (removeTwoOfClubs) 1 else 0)
        return (deckSize - kittySize) / playerCount
    }

    companion object {
        /** Find the [GameType] whose [label] matches [label], defaulting to [TEAM_CLASSIC]. */
        fun fromLabel(label: String): GameType =
            entries.find { it.label == label } ?: TEAM_CLASSIC
    }
}

/** High-level phases used by the engine to drive UI and side-effects. */
enum class GamePhase { Lobby, Deal, DealHuman, Bid, BidHuman, KittyReveal, Kitty, KittyHuman, Trick, TrickHuman, TrickResolve, Score, EndHand, Finished }

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
    /** Active game variant — drives deck construction, deal mode, and player/team setup. */
    val gameType: GameType = GameType.TEAM_CLASSIC,
    /** Cards that have been played / collected (discard pile). */
    val discard: List<Card> = emptyList(),
    /** Remaining deal deck; only populated during [GamePhase.DealHuman] for [DealMode.TWO_MAN_ALTERNATE]. */
    val deck: List<Card> = emptyList(),
    /** Player who holds the 2♠ after dealing; only set for [DealMode.KITTY_TWO_OF_SPADES]. */
    val kittyWinnerId: String? = null,
    val score: Score = Score(),
    val phase: GamePhase = GamePhase.Lobby,
    /** True once a spade has been played on a non-spade lead (Classic only gate). */
    val spadesBroken: Boolean = false,
    /** Map of phase -> hands (array of Hand objects tied to phases) */
    val phaseHands: Map<GamePhase, List<Hand>> = emptyMap(),
    val metadata: Metadata? = null,
    /**
     * Optional rule: a bid of 10 or more earns double points (bid × 20).
     * Defaults off; toggled from the settings screen before the game ships.
     */
    val enableDoubleBidBonus: Boolean = false,
    /**
     * Optional rule: every 10 accumulated sandbags costs −100 points.
     * Defaults on per standard Spades rules; toggled from the settings screen.
     */
    val enableSandbagPenalty: Boolean = true,
    /**
     * Signed point/bag deltas from the most recently scored hand.
     * Populated by [GameViewModel.applyScore]; used by EndHandView to show
     * per-hand results. Cleared on the next [GameViewModel.applyScore] call.
     */
    val lastHandScore: Score = Score(),
    /**
     * Snapshot of each player's hand at deal time. Never modified after dealing.
     * Used by HandView as a fixed layout template so the hand position stays stable
     * even as cards are removed during play.
     */
    val originalDealHands: Map<String, List<Card>> = emptyMap(),
    /**
     * Replay events accumulated during the current hand.
     * Cleared by [GameViewModel.resetForNextHand] and [GameViewModel.finalizeHandReplay].
     */
    val replayEvents: List<ReplayEvent> = emptyList(),
    /**
     * Finalized replay for the most recently completed hand.
     * Set by [GameViewModel.finalizeHandReplay] in [GamePhase.Score].
     * Passed to ReplayHandView from EndHandView.
     */
    val lastHandReplay: HandReplay? = null
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

/**
 * Create players for any [GameType].
 *
 * - Team games (4 players): seat index 0 & 2 → team 0; seats 1 & 3 → team 1.
 * - Solo games: all players are on team 0 (no opposing teams).
 *
 * [ids] and [names] must each contain exactly [gameType.playerCount] entries,
 * ordered by seat (South first, then West, North, East for 4-player games).
 */
fun defaultPlayers(ids: List<String>, names: List<String>, gameType: GameType): List<Player> {
    require(ids.size == gameType.playerCount && names.size == gameType.playerCount)
    return ids.mapIndexed { i, id ->
        val team = if (gameType.useTeams && i % 2 == 1) 1 else 0
        Player(id = id, name = names[i], team = team, runtimeFlags = RuntimeFlags(seatIndex = i))
    }
}

/** Back-compat alias: 4-player team game with default Classic rules. */
fun defaultFourPlayers(ids: List<String>, names: List<String>): List<Player> =
    defaultPlayers(ids, names, GameType.TEAM_CLASSIC)
