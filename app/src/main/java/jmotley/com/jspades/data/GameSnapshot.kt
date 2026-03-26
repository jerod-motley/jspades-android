package jmotley.com.jspades.data

/**
 * Point-in-time copy of all mutable GameState fields needed for a deterministic
 * rewind. Intentionally separate from GameState to keep the stored checkpoint
 * explicit — only fields that change during play are included.
 *
 * Fields excluded:
 *   - gameType, metadata, gameLength — set at lobby, never mutate during play
 *   - lastHandScore, replayEvents, lastHandReplay — display/logging artifacts
 */
data class GameSnapshot(
    // Phase and turn
    val phase: GamePhase,
    val leaderIndex: Int,
    val handLeaderIndex: Int,
    val currentTrick: Trick,
    val spadesBroken: Boolean,

    // Players (includes per-player RuntimeFlags for void tracking)
    val players: List<Player>,

    // Card state
    val phaseHands: Map<GamePhase, List<Hand>>,
    val hands: Map<String, Hand>,
    val originalDealHands: Map<String, List<Card>>,
    val discard: List<Card>,
    val deck: List<Card>,
    val kitty: Hand?,
    val kittyWinnerId: String?,

    // Scoring
    val score: Score,

    // Rules flags (don't change mid-game but must survive restore)
    val twoOfSpadesJoker: Boolean,
    val spadesMustBreak: Boolean,
    val enableSandbagPenalty: Boolean,
    val enableDoubleBidBonus: Boolean,
    val minBidFive: Boolean,
    val allowNilBid: Boolean,
    val allowBlindExchange: Boolean,
)

/**
 * Capture a restorable checkpoint from the current state.
 */
fun GameState.toSnapshot(): GameSnapshot = GameSnapshot(
    phase               = phase,
    leaderIndex         = leaderIndex,
    handLeaderIndex     = handLeaderIndex,
    currentTrick        = currentTrick,
    spadesBroken        = spadesBroken,
    players             = players,
    phaseHands          = phaseHands,
    hands               = hands,
    originalDealHands   = originalDealHands,
    discard             = discard,
    deck                = deck,
    kitty               = kitty,
    kittyWinnerId       = kittyWinnerId,
    score               = score,
    twoOfSpadesJoker    = twoOfSpadesJoker,
    spadesMustBreak     = spadesMustBreak,
    enableSandbagPenalty  = enableSandbagPenalty,
    enableDoubleBidBonus  = enableDoubleBidBonus,
    minBidFive          = minBidFive,
    allowNilBid         = allowNilBid,
    allowBlindExchange  = allowBlindExchange,
)

/**
 * Restore all snapshot fields onto this state. Phase is restored from the snapshot.
 * Callers that want a different phase (e.g. replayLastHand targeting Bid) must call
 * advancePhase() explicitly after applySnapshot().
 *
 * Fields not in the snapshot — gameType, metadata, gameLength — are left unchanged
 * since they are set at lobby time and do not mutate during play.
 */
fun GameState.applySnapshot(snap: GameSnapshot): GameState = copy(
    phase               = snap.phase,
    leaderIndex         = snap.leaderIndex,
    handLeaderIndex     = snap.handLeaderIndex,
    currentTrick        = snap.currentTrick,
    spadesBroken        = snap.spadesBroken,
    players             = snap.players,
    phaseHands          = snap.phaseHands,
    hands               = snap.hands,
    originalDealHands   = snap.originalDealHands,
    discard             = snap.discard,
    deck                = snap.deck,
    kitty               = snap.kitty,
    kittyWinnerId       = snap.kittyWinnerId,
    score               = snap.score,
    twoOfSpadesJoker    = snap.twoOfSpadesJoker,
    spadesMustBreak     = snap.spadesMustBreak,
    enableSandbagPenalty  = snap.enableSandbagPenalty,
    enableDoubleBidBonus  = snap.enableDoubleBidBonus,
    minBidFive          = snap.minBidFive,
    allowNilBid         = snap.allowNilBid,
    allowBlindExchange  = snap.allowBlindExchange,
)
