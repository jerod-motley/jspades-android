package jmotley.com.jspades.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

// ── Primitive wire types ──────────────────────────────────────────────────────

/**
 * A single card as transmitted over the wire.
 *
 * Both platforms agree on 0-based rank and suit for [id]. The adapter on each
 * platform converts between the wire format and the local card representation.
 */
@Serializable
data class WireCard(
    /**
     * Cross-platform card identity: `{rank}_{suit}`, both 0-based.
     * Rank: TWO=0 … ACE=12. Specials above 12: Deuce=13, LittleJoker=14, BigJoker=15, WildDeuce=16.
     * Suit: HEARTS=0, CLUBS=1, DIAMONDS=2, SPADES=3.
     * Example: 2♣ = "0_1", Ace♠ = "12_3".
     * Android adapter: wire rank = rank.value − 2; wire suit via HEARTS=0/CLUBS=1/DIAMONDS=2/SPADES=3.
     */
    val id: String,
    /**
     * Shared asset key used for rendering: `c{rank+2}_{suit+1}` for standard cards
     * (e.g. "c2_1" for 2♣, "c14_4" for Ace♠). Special cards use their own asset name.
     * Each platform's adapter maps this to the local asset file as needed.
     */
    val image: String
)

/** One seat's player identity inside a `gameConfig` message. */
@Serializable
data class WireSeatPlayer(
    val playerId: String,
    val displayName: String,
    /** Roster identity, independent of which device executes the seat. */
    val kind: String = ""
)

/**
 * All host-controlled game settings sent once before the first deal.
 * Remote clients apply these exactly and do not prompt the user for options.
 * `gameType` uses the shared iOS/Android camelCase wire string (e.g. "houseRules", "kitty", "classic",
 * "fourManSolo", "threeManSolo", "twoManSolo"). The adapter maps these to/from [GameType]. `gameLength` matches [GameLength.name].
 */
@Serializable
data class WireGameConfig(
    val gameType: String,
    val twoOfSpadesJoker: Boolean = false,
    val twoOfDiamondsJoker: Boolean = false,
    val enableDoubleBidBonus: Boolean = false,
    val spadesMustBreak: Boolean = false,
    /** Effective minimum bid for this hand (already accounts for the minBidFive setting). */
    val minimumBid: Int = 4,
    val enableSandbagPenalty: Boolean = true,
    val allowNilBid: Boolean = false,
    val blindNilExchangeEnabled: Boolean = false,
    val gameLength: String = "MEDIUM"
)

// ── Wire messages (discriminated by the "type" field) ────────────────────────
// "type" matches the relay envelope field used by both iOS and the WSS relay,
// so incoming relay envelopes decode directly without stripping the outer wrapper.

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class WireMessage {
    abstract val cmdId: String
    abstract val seat: Int
    abstract val playerId: String
}

@Serializable
enum class WireRecoveryReason {
    CONFLICTING_FACT, IMPOSSIBLE_PROGRESS, DELIVERY_TIMEOUT, RECONNECT_DIVERGENCE
}

@Serializable
@SerialName("resyncRequest")
data class ResyncRequestMessage(
    override val cmdId: String,
    override val seat: Int,
    override val playerId: String,
    val requestId: String,
    val requesterSeat: Int,
    val gameGeneration: Int,
    val handNum: Int? = null,
    val reason: WireRecoveryReason,
    val semanticKey: String? = null,
    val conflictingCmdIds: List<String> = emptyList(),
    val lastSnapshotVersion: Long? = null,
    val logicalStateDigest: String? = null
) : WireMessage()

@Serializable
@SerialName("stateSnapshot")
data class StateSnapshotMessage(
    override val cmdId: String,
    override val seat: Int,
    override val playerId: String,
    val snapshotId: String,
    val responseToRequestId: String? = null,
    val targetSeat: Int,
    val gameGeneration: Int,
    val handNum: Int? = null,
    val snapshotVersion: Long,
    /** Canonical JSON owned and validated by the reducer, never UI state. */
    val logicalState: String,
    val retainedSemanticFacts: List<String> = emptyList(),
    val retainedCmdIds: List<String> = emptyList()
) : WireMessage()

object MPProtocol {
    const val CURRENT_VERSION: Int = 2
    const val CAP_GENERATION_SCOPED_ACTIONS = "generationScopedActions"
    const val CAP_EXPLICIT_BID_ROLE = "explicitBidRole"
    const val CAP_SEMANTIC_FACTS = "semanticFacts"
    const val CAP_ORDERED_PLAY_INBOX = "orderedPlayInbox"
    const val CAP_STATE_RESYNC = "stateResync"

    val advertisedCapabilities: Set<String> = setOf(
        CAP_GENERATION_SCOPED_ACTIONS,
        CAP_EXPLICIT_BID_ROLE
    )

	fun requiresGeneration(capabilities: Set<String>): Boolean = CAP_GENERATION_SCOPED_ACTIONS in capabilities
	fun requiresExplicitBidRole(capabilities: Set<String>): Boolean = CAP_EXPLICIT_BID_ROLE in capabilities
}

@Serializable
enum class WireBidRole {
    @SerialName("individual") INDIVIDUAL,
    @SerialName("teamTotal") TEAM_TOTAL
}

/**
 * host → all, once before the first deal.
 * Establishes rules and seat→player identity for the game.
 *
 * [gameGeneration] distinguishes a genuine new-game start (the very first game, or a
 * Play Again restart — [gameGeneration] strictly greater than any value the receiver
 * has already applied) from a same-game configuration recovery (a reconnect resend, a
 * buffered replay, a newly created adapter — [gameGeneration] equal to or less than the
 * receiver's current value). Only a genuine new-game start may reset players, turn
 * order, trick state, or score; recovery of an already-active generation must leave all
 * of that untouched, since a client's own phase alone (e.g. being at `.Finished`) isn't
 * a reliable signal — a stray recovery message delivered while still at `.Finished`,
 * before the user has actually chosen Play Again, would otherwise be indistinguishable
 * from a real restart.
 */
@Serializable
@SerialName("gameConfig")
data class GameConfigMessage(
    override val cmdId: String,
    override val seat: Int,
    override val playerId: String,
    val config: WireGameConfig,
    /** Seat index (as string key) → player info. */
    val players: Map<String, WireSeatPlayer>,
    val gameGeneration: Int = 1,
    val protocolVersion: Int = 1,
    val capabilities: Set<String> = emptySet()
) : WireMessage()

/**
 * host → all, once per hand.
 * All hands are broadcast because the relay has no per-seat targeting;
 * each client reads only its own seat's hand.
 */
@Serializable
@SerialName("deal")
data class DealMessage(
    override val cmdId: String,
    override val seat: Int,
    override val playerId: String,
    val handNum: Int,
    val dealerSeat: Int,
    /** Fixed seat-position array of playerIds. Index = seat number. Same every hand. */
    val seatOrder: List<String>,
    /** Seat index (as string key) → ordered list of cards. */
    val hands: Map<String, List<WireCard>>,
    /** Kitty cards — non-null only for game types with a kitty (e.g. TEAM_KITTY). */
    val kitty: List<WireCard>? = null,
    /** Room seat of the player who won the kitty (holds 2♠); null for non-kitty game types. */
    val kittyOwnerSeat: Int? = null,
    val gameGeneration: Int? = null
) : WireMessage()

/**
 * host → all, once per hand, only when a team is eligible for a blind bid.
 * Clients whose seat is not in [decidingSeats] display a waiting message.
 * An empty [decidingSeats] means both seats are CPU; no client sends a blindResponse.
 */
@Serializable
@SerialName("blindOffer")
data class BlindOfferMessage(
    override val cmdId: String,
    override val seat: Int,
    override val playerId: String,
    val handNum: Int,
    val teamSeats: List<Int>,
    val decidingSeats: List<Int>,
    val gameGeneration: Int? = null
) : WireMessage()

/**
 * player → all.
 * Any single `accepted: false` declines the blind bid for the whole team.
 */
@Serializable
@SerialName("blindResponse")
data class BlindResponseMessage(
    override val cmdId: String,
    override val seat: Int,
    override val playerId: String,
    val handNum: Int,
    val accepted: Boolean,
    val gameGeneration: Int? = null
) : WireMessage()

/**
 * host → all, once per hand, always — whether or not any team was eligible for a blind
 * offer. The host is the sole authority on when the blind phase is over: a non-host
 * client never independently decides this (it does not run its own eligibility loop at
 * all — see `PhaseManager.handleBlindBid`'s non-host early return) and must wait for
 * either a [BlindOfferMessage] (if its own seat is one of the deciding seats) or this
 * message (once every required response is in, or immediately if no one was eligible)
 * before leaving `GamePhase.BlindBid`.
 */
@Serializable
@SerialName("blindPhaseComplete")
data class BlindPhaseCompleteMessage(
    override val cmdId: String,
    override val seat: Int,
    override val playerId: String,
    val handNum: Int,
    val gameGeneration: Int? = null
) : WireMessage()

/**
 * non-host client → all (host included).
 * Sent once when a guest presses "Next Hand" before the host has advanced past
 * `EndHand`. Purely informational — the host still deals on its own timeline via
 * its own "Next Hand" press — but gives the host a per-seat readiness signal to
 * track and gives the guest a real, idempotent action to take instead of dealing
 * a bogus local hand (see `GameViewModel.onNextHand`'s host-only deal path).
 * [handNum] is the hand the sender believes just ended, so a late/duplicate
 * message for an already-superseded hand can be told apart from a current one.
 */
@Serializable
@SerialName("readyForNextHand")
data class ReadyForNextHandMessage(
    override val cmdId: String,
    override val seat: Int,
    override val playerId: String,
    val handNum: Int,
    val gameGeneration: Int? = null
) : WireMessage()

/**
 * non-host client → all (host included).
 * Sent once when a guest presses "Play Again" at `.Finished` before the host has
 * started a new game. Purely informational, the same way [ReadyForNextHandMessage]
 * is for hand transitions — the host still restarts on its own timeline via its own
 * "Play Again" press, which re-sends [GameConfigMessage] (score/settings reset) and
 * then a fresh [DealMessage]. [gameGeneration] is the generation the sender currently
 * has applied — a request tagged for an older generation than the host's current one
 * is a delayed message from an already-completed game and must be rejected, the same
 * way [handNum] lets [ReadyForNextHandMessage] tell a current request from a stale one.
 */
@Serializable
@SerialName("requestPlayAgain")
data class RequestPlayAgainMessage(
    override val cmdId: String,
    override val seat: Int,
    override val playerId: String,
    val gameGeneration: Int = 1
) : WireMessage()

/**
 * player → all; host sends on behalf of CPU seats.
 * `amount: 0` = nil. `isBlind: true` + `amount: 0` = blind nil.
 */
@Serializable
@SerialName("bid")
data class BidMessage(
    override val cmdId: String,
    override val seat: Int,
    override val playerId: String,
    val handNum: Int,
    val amount: Int,
    val isBlind: Boolean,
    /** True only when this bid commits the complete team contract. */
    val isTeamTotal: Boolean? = null,
    /** Required by protocol v2; null only for legacy peers during rollout. */
    val bidRole: WireBidRole? = null,
    val gameGeneration: Int? = null
) : WireMessage()

/**
 * player → all; host sends on behalf of CPU seats.
 * [trickNum] and [trickPlayNum] are 1-based and used for validation only —
 * not for ordering (see §4 of mp-new.md).
 */
@Serializable
@SerialName("playCard")
data class PlayCardMessage(
    override val cmdId: String,
    override val seat: Int,
    override val playerId: String,
    val handNum: Int,
    val trickNum: Int,
    val trickPlayNum: Int,
    /** Matches [WireCard.id] — 0-based `{rank}_{suit}` (e.g. "12_3" for Ace♠). */
    val cardId: String,
    val gameGeneration: Int? = null
) : WireMessage()

/** Receipt-only acknowledgement. Duplicate commands are acknowledged again. */
@Serializable
@SerialName("receiptAck")
data class ReceiptAckMessage(
    override val cmdId: String,
    override val seat: Int,
    override val playerId: String,
    val ackedCmdId: String
) : WireMessage()
