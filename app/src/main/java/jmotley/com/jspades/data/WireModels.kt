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
    val displayName: String
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
    val minBidFive: Boolean = false,
    val enableSandbagPenalty: Boolean = true,
    val allowNilBid: Boolean = false,
    val allowBlindExchange: Boolean = false,
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

/**
 * host → all, once before the first deal.
 * Establishes rules and seat→player identity for the game.
 */
@Serializable
@SerialName("gameConfig")
data class GameConfigMessage(
    override val cmdId: String,
    override val seat: Int,
    override val playerId: String,
    val config: WireGameConfig,
    /** Seat index (as string key) → player info. */
    val players: Map<String, WireSeatPlayer>
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
    /** PlayerId of the player who won the kitty (holds 2♠); null for non-kitty game types. */
    val kittyWinnerId: String? = null
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
    val decidingSeats: List<Int>
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
    val accepted: Boolean
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
    val isBlind: Boolean
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
    val cardId: String
) : WireMessage()
