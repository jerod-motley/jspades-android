package jmotley.com.jspades.engine

import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.GamePhase
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.data.Play
import jmotley.com.jspades.data.Player
import jmotley.com.jspades.data.PlayerHandState
import jmotley.com.jspades.data.Rank
import jmotley.com.jspades.data.Suit

/**
 * PlayEngine — pure, stateless CPU card selection for trick play.
 *
 * [selectCard] is the single entry point. It determines the player's mode
 * (winner vs loser) from the current game state and dispatches to the
 * appropriate lead or follow strategy.
 *
 * [computeTrickWinner] is used by PhaseManager to resolve completed tricks.
 *
 * All functions operate on state snapshots — no side effects.
 */
object PlayEngine {

    // ── Public API ─────────────────────────────────────────────────────────

    /** Select the best card for [playerId] to play this trick. */
    fun selectCard(playerId: String, state: GameState): Card {
        val hand   = getHand(state, playerId)
        require(hand.isNotEmpty()) { "selectCard called for $playerId but hand is empty" }
        val player  = state.players.first { it.id == playerId }
        val leading = state.currentTrick.plays.all { it == null }
        return when (pickMode(player, state)) {
            Mode.WINNER_CLASSIC  -> if (leading) winnerLead(player, hand, state, classic = true)  else winnerFollow(player, hand, state, classic = true)
            Mode.WINNER_TEAM     -> if (leading) winnerLead(player, hand, state, classic = false) else winnerFollow(player, hand, state, classic = false)
            Mode.WINNER_SOLO     -> if (leading) winnerLeadSolo(player, hand, state)              else winnerFollowSolo(player, hand, state)
            Mode.WINNER_TWO      -> if (leading) winnerLeadSolo(player, hand, state)              else winnerFollowSolo(player, hand, state)
            Mode.LOSER,
            Mode.LOSER_TWO       -> if (leading) loserLead(hand, state)                           else throwOff(hand, state)
            Mode.LOSER_HIGH,
            Mode.LOSER_HIGH_TWO  -> if (leading) loserHighLead(hand, state)                       else loserHighFollow(hand, state)
        }
    }

    /**
     * Determine which play won the trick.
     * Trump always beats non-trump; among equal categories, highest rank (ordinal) wins.
     * Rank ordinal order: BIGJOKER(15) > LITTLEJOKER(14) > DEUCE(13) > ACE(12) > … > TWO(0).
     */
    fun computeTrickWinner(plays: List<Play?>): Play {
        val valid  = plays.filterNotNull()
        val lead   = valid.first().card
        val trumps = valid.filter { isTrump(it.card) }
        return if (trumps.isNotEmpty()) {
            trumps.maxBy { it.card.rank.ordinal }
        } else {
            valid.filter { it.card.suit == lead.suit }.maxBy { it.card.rank.ordinal }
        }
    }

    // ── Mode selection ─────────────────────────────────────────────────────

    private enum class Mode {
        WINNER_CLASSIC, WINNER_TEAM, WINNER_SOLO, WINNER_TWO,
        LOSER, LOSER_HIGH, LOSER_HIGH_TWO, LOSER_TWO
    }

    private fun pickMode(player: Player, state: GameState): Mode {
        val isTwoPlayer = state.gameType == GameType.SOLO_TWO_MAN
        val phs  = getHandState(state, player.id)
        val bid  = phs?.bid ?: 0

        // Nil bidder → always passive (never wants to win)
        if (bid == 0) return if (isTwoPlayer) Mode.LOSER_TWO else Mode.LOSER

        val stillNeeds = if (state.gameType.useTeams) {
            val hand    = state.phaseHands[GamePhase.Deal]?.lastOrNull()
            val teamBid = (hand?.teamBids?.getOrNull(player.team) ?: 0) ?: 0
            val teamWon = state.players.filter { it.team == player.team }
                .sumOf { p -> hand?.perPlayer?.get(p.id)?.tricksWon ?: 0 }
            teamWon < teamBid
        } else {
            (phs?.tricksWon ?: 0) < bid
        }

        return if (!stillNeeds) {
            if (isTwoPlayer) Mode.LOSER_HIGH_TWO else Mode.LOSER_HIGH
        } else when (state.gameType) {
            GameType.TEAM_CLASSIC                            -> Mode.WINNER_CLASSIC
            GameType.SOLO_TWO_MAN                            -> Mode.WINNER_TWO
            GameType.SOLO_FOUR_MAN, GameType.SOLO_THREE_MAN -> Mode.WINNER_SOLO
            else                                             -> Mode.WINNER_TEAM
        }
    }

    // ── Winner lead ────────────────────────────────────────────────────────

    /**
     * 13-step priority lead for Winner (team) and Classic variants.
     * [classic] gates trump leads on [GameState.spadesBroken].
     */
    private fun winnerLead(player: Player, hand: List<Card>, state: GameState, classic: Boolean): Card {
        val canLeadTrump = !classic || state.spadesBroken
        val numTrump     = hand.count { isTrump(it) }
        val trumpPlayed  = state.discard.count { isTrump(it) }
        val totalTrump   = 13 + (if (state.gameType.includeJokers) 2 else 0) // approx max trump
        val teammate     = teammate(player, state)
        val rightOpp     = rightOpponent(player, state)
        val leftOpp      = leftOpponent(player, state)

        // 1. All opponents void in spades → lead highest non-trump
        val opponents = listOfNotNull(rightOpp, leftOpp)
        if (opponents.isNotEmpty() && opponents.all { it.runtimeFlags.cuttingSpades }) {
            highestInAnyNonTrump(hand, state)?.let { return it }
        }

        // 2. Non-trump ace
        hand.firstOrNull { it.rank == Rank.ACE && !isTrump(it) }?.let { return it }

        // 3. Pull trump
        if (canLeadTrump) {
            if (numTrump > 2 || hand.size < 3) {
                highestTrump(hand, state)?.let { return it }
            }
            val half = (totalTrump - trumpPlayed) / 2
            if (numTrump >= half && numTrump > 2) {
                strength5(hand)?.let { return it }
                highestTrump(hand, state)?.let { return it }
                hand.firstOrNull { isTrump(it) && it.rank.ordinal < Rank.JACK.ordinal }?.let { return it }
                hand.firstOrNull { isTrump(it) && it.rank.ordinal < Rank.SIX.ordinal }?.let { return it }
            }
        }

        // 4. High non-trump that should walk (rated > 2, equals highest remaining)
        leadHighNonTrump(hand, state, minStrength = 3)?.let { return it }

        // 5. High non-trump that may not walk (rated > 1)
        leadHighNonTrump(hand, state, minStrength = 2)?.let { return it }

        // 6. Feed teammate (team games only)
        if (teammate != null) feedTeammate(teammate, hand, state)?.let { return it }

        // 7. Highest remaining non-trump card in hand
        hand.filter { !isTrump(it) }
            .firstOrNull { it.rank == highestRemainingNonTrump(it.suit, state) }
            ?.let { return it }

        // 8. Fish non-trump (lead lower card in best suit to draw out high cards)
        fishNonTrump(hand, state)?.let { return it }

        // 9. Secondary trump pull
        if (canLeadTrump && numTrump > 1) {
            strength5(hand)?.let { return it }
            highestTrump(hand, state)?.let { return it }
        }

        // 10. Guaranteed strength-5 winner
        strength5(hand)?.let { return it }

        // 11. Low-strength probe card
        hand.firstOrNull { rateCard(it, hand) < 3 }?.let { return it }

        // 12. Low trump (non-top)
        hand.firstOrNull { isTrump(it) && rateCard(it, hand) < 4 }?.let { return it }

        // 13. Anything
        return hand.first()
    }

    /** Solo lead: same priority but checks all remaining opponents (not just named seats). */
    private fun winnerLeadSolo(player: Player, hand: List<Card>, state: GameState): Card {
        val numTrump    = hand.count { isTrump(it) }
        val trumpPlayed = state.discard.count { isTrump(it) }
        val totalTrump  = 13 + (if (state.gameType.includeJokers) 2 else 0)
        val opps        = opponents(player, state)

        // 1. All opponents void in spades
        if (opps.isNotEmpty() && opps.all { it.runtimeFlags.cuttingSpades }) {
            highestInAnyNonTrump(hand, state)?.let { return it }
        }

        // 2. Non-trump ace
        hand.firstOrNull { it.rank == Rank.ACE && !isTrump(it) }?.let { return it }

        // 3. Pull trump
        if (numTrump > 2 || hand.size < 3) highestTrump(hand, state)?.let { return it }
        val half = (totalTrump - trumpPlayed) / 2
        if (numTrump >= half && numTrump > 2) {
            strength5(hand)?.let { return it }
            highestTrump(hand, state)?.let { return it }
            hand.firstOrNull { isTrump(it) && it.rank.ordinal < Rank.JACK.ordinal }?.let { return it }
        }

        // 4. High non-trump (no opponent after me is cutting that suit)
        hand.filter { !isTrump(it) && rateCard(it, hand) > 2 }
            .firstOrNull { c ->
                c.rank == highestRemainingNonTrump(c.suit, state) &&
                    opps.none { isCutting(it, c.suit) }
            }?.let { return it }

        // 5. High non-trump, lower threshold
        leadHighNonTrump(hand, state, minStrength = 2)?.let { return it }

        // 6. Lead highest remaining in a suit no opponent is cutting
        hand.filter { !isTrump(it) }
            .firstOrNull { c ->
                c.rank == highestRemainingNonTrump(c.suit, state) && opps.none { isCutting(it, c.suit) }
            }?.let { return it }

        // 7–13 fallback mirrors team winner
        hand.filter { !isTrump(it) }
            .firstOrNull { it.rank == highestRemainingNonTrump(it.suit, state) }?.let { return it }
        fishNonTrump(hand, state)?.let { return it }
        if (numTrump > 1) highestTrump(hand, state)?.let { return it }
        strength5(hand)?.let { return it }
        hand.firstOrNull { rateCard(it, hand) < 3 }?.let { return it }
        hand.firstOrNull { isTrump(it) && rateCard(it, hand) < 4 }?.let { return it }
        return hand.first()
    }

    // ── Loser leads ────────────────────────────────────────────────────────

    /** Lead the highest non-trump card that is below the highest remaining — likely to lose. */
    private fun loserLead(hand: List<Card>, state: GameState): Card {
        val nonTrump = hand.filter { !isTrump(it) }.sortedByDescending { it.rank.ordinal }
        nonTrump.firstOrNull { c ->
            val top = highestRemainingNonTrump(c.suit, state)
            top != null && c.rank.ordinal < top.ordinal
        }?.let { return it }
        val trumps = hand.filter { isTrump(it) }.sortedByDescending { it.rank.ordinal }
        trumps.firstOrNull { c ->
            val top = highestRemainingTrump(state)
            top != null && c.rank.ordinal < top.ordinal
        }?.let { return it }
        return hand.first()
    }

    /** LoserHigh lead: highest non-trump card that can still be beaten. */
    private fun loserHighLead(hand: List<Card>, state: GameState): Card {
        val nonTrump = hand.filter { !isTrump(it) }.sortedByDescending { it.rank.ordinal }
        nonTrump.firstOrNull { c ->
            val top = highestRemainingNonTrump(c.suit, state)
            top != null && c.rank.ordinal < top.ordinal
        }?.let { return it }
        val trumps = hand.filter { isTrump(it) }.sortedByDescending { it.rank.ordinal }
        trumps.firstOrNull { c ->
            val top = highestRemainingTrump(state)
            top != null && c.rank.ordinal < top.ordinal
        }?.let { return it }
        return hand.first() // all cards are highest remaining — play anything
    }

    // ── Winner follow ──────────────────────────────────────────────────────

    private fun winnerFollow(player: Player, hand: List<Card>, state: GameState, classic: Boolean): Card {
        val lead          = leadCard(state) ?: return hand.first()
        val hasLead       = hand.any { followsSuit(it, lead) }
        val winner        = currentWinner(state)
        val winnerCard    = winner?.card
        val teammate      = teammate(player, state)
        val isTeammateWin = winner?.playerId == teammate?.id
        val isLast        = state.currentTrick.plays.count { it != null } == state.players.size - 1

        // Classic: if current winner is a nil bidder and not teammate, throw off (don't set them)
        if (classic && isLast) {
            val winnerPhs   = winner?.let { getHandState(state, it.playerId) }
            val winnerIsNil = winnerPhs?.bid == 0 && winnerPhs.tricksWon == 0
                    && !isTeammateWin
            if (winnerIsNil) return throwOff(hand, state)
        }

        if (!hasLead) {
            // Void in lead suit
            val rightOpp = rightOpponent(player, state)
            return when {
                // Partner winning with King → throw off (King is already the top play)
                isTeammateWin && winnerCard?.rank == Rank.KING && !isTrump(winnerCard) -> throwOff(hand, state)
                // Partner winning with highest remaining in suit AND right opponent can't cut → throw off
                // (ObjC: 3rd-player only — skip if right opp is still cutting that suit)
                isTeammateWin && winnerCard != null && !isTrump(winnerCard)
                        && winnerCard.rank == highestRemainingNonTrump(winnerCard.suit, state)
                        && (rightOpp == null || !isCutting(rightOpp, winnerCard.suit)) -> throwOff(hand, state)
                else -> cut(player, hand, state)
            }
        }

        // Current winner is trump but lead was non-trump: can't beat, discard low
        if (winnerCard != null && isTrump(winnerCard) && !isTrump(lead)) {
            return hand.filter { followsSuit(it, lead) }.minBy { it.rank.ordinal }
        }

        // Trump was led: play strongest beating trump
        if (isTrump(lead)) {
            return hand.filter { isTrump(it) && it.rank.ordinal > (winnerCard?.rank?.ordinal ?: -1) }
                .minByOrNull { it.rank.ordinal }
                ?: hand.filter { isTrump(it) }.minBy { it.rank.ordinal }
        }

        // Non-trump lead, has cards in suit
        if (isLast && isTeammateWin) return throwOff(hand, state)
        val suitCards  = hand.filter { followsSuit(it, lead) }
        val winnerRank = winnerCard?.rank?.ordinal ?: -1
        val beating    = suitCards.filter { it.rank.ordinal > winnerRank }
        if (isLast) {
            return beating.minByOrNull { it.rank.ordinal } ?: suitCards.minBy { it.rank.ordinal }
        }
        // ObjC TrickPlayerWinner.followLead cascade (non-last, non-trump lead):
        // 1. Play the definite suit winner (highest remaining) if it beats current winner
        highestRemainingNonTrump(lead.suit, state)?.let { top ->
            beating.firstOrNull { it.rank == top }?.let { return it }
        }
        // 2. Beat with rank < QUEEN — conserve Queen
        beating.firstOrNull { it.rank.ordinal < Rank.QUEEN.ordinal }?.let { return it }
        // 3. Beat with rank < KING — conserve King
        beating.firstOrNull { it.rank.ordinal < Rank.KING.ordinal }?.let { return it }
        // 4. "Can't let a low card walk" — if winning card is below JACK, beat with anything
        if (winnerCard != null && winnerCard.rank.ordinal < Rank.JACK.ordinal) {
            beating.minByOrNull { it.rank.ordinal }?.let { return it }
        }
        // 5. Follow suit with lowest
        return suitCards.minBy { it.rank.ordinal }
    }

    private fun winnerFollowSolo(player: Player, hand: List<Card>, state: GameState): Card {
        val lead       = leadCard(state) ?: return hand.first()
        val hasLead    = hand.any { followsSuit(it, lead) }
        val winner     = currentWinner(state)
        val winnerCard = winner?.card
        val isLast     = state.currentTrick.plays.count { it != null } == state.players.size - 1

        if (!hasLead) return cut(player, hand, state)

        // Trump led, winner is trump: can't beat, play lowest in suit
        if (isTrump(lead) && winnerCard != null && isTrump(winnerCard)) {
            return hand.filter { followsSuit(it, lead) }.minBy { it.rank.ordinal }
        }

        // Last player: lowest winning card, or any lead-suit card
        if (isLast) {
            return hand.filter { followsSuit(it, lead) && it.rank.ordinal > (winnerCard?.rank?.ordinal ?: -1) }
                .minByOrNull { it.rank.ordinal }
                ?: hand.filter { followsSuit(it, lead) }.minBy { it.rank.ordinal }
        }

        // Trump led: beat with lowest trump that wins
        if (isTrump(lead)) {
            return hand.filter { isTrump(it) && it.rank.ordinal > (winnerCard?.rank?.ordinal ?: -1) }
                .minByOrNull { it.rank.ordinal }
                ?: hand.filter { isTrump(it) }.minBy { it.rank.ordinal }
        }

        // Non-trump lead, non-trump winner: ObjC TrickPlayerWinnerSolo.followLead cascade
        if (winnerCard != null && !isTrump(winnerCard)) {
            val suitCards  = hand.filter { followsSuit(it, lead) }
            val winnerRank = winnerCard.rank.ordinal
            val beating    = suitCards.filter { it.rank.ordinal > winnerRank }
            // 1. Definite suit winner (highest remaining) that beats current winner
            highestRemainingNonTrump(lead.suit, state)?.let { top ->
                beating.firstOrNull { it.rank == top }?.let { return it }
            }
            // 2. Beat with rank < QUEEN
            beating.firstOrNull { it.rank.ordinal < Rank.QUEEN.ordinal }?.let { return it }
            // 3. Beat with rank < KING
            beating.firstOrNull { it.rank.ordinal < Rank.KING.ordinal }?.let { return it }
            // 4. "Can't let a low card walk" (note: solo ObjC has this commented out, so omit)
            // 5. Follow suit with lowest
            return suitCards.minBy { it.rank.ordinal }
        }

        return hand.filter { followsSuit(it, lead) }.minBy { it.rank.ordinal }
    }

    // ── Loser follow ───────────────────────────────────────────────────────

    private fun loserHighFollow(hand: List<Card>, state: GameState): Card {
        val lead       = leadCard(state) ?: return hand.first()
        val hasLead    = hand.any { followsSuit(it, lead) }
        val winner     = currentWinner(state)
        val winnerCard = winner?.card

        if (!hasLead) return loserHighThrowOff(hand, lead)

        // Trump lead: highest trump below winner
        if (isTrump(lead)) {
            return hand.filter { isTrump(it) && it.rank.ordinal < (winnerCard?.rank?.ordinal ?: Int.MAX_VALUE) }
                .maxByOrNull { it.rank.ordinal }
                ?: hand.filter { isTrump(it) }.minBy { it.rank.ordinal }
        }

        // Non-trump led, winner is trump (cut): play highest lead-suit card
        if (winnerCard != null && isTrump(winnerCard)) {
            return hand.filter { followsSuit(it, lead) }.maxBy { it.rank.ordinal }
        }

        // Normal follow: highest lead-suit card below winner's rank
        return hand.filter { followsSuit(it, lead) && it.rank.ordinal < (winnerCard?.rank?.ordinal ?: Int.MAX_VALUE) }
            .maxByOrNull { it.rank.ordinal }
            ?: hand.filter { followsSuit(it, lead) }.minBy { it.rank.ordinal }
    }

    // ── Kitty discard ──────────────────────────────────────────────────────

    /**
     * CPU kitty exchange: select [discardCount] cards to throw away from
     * the combined [hand] (12 dealt) + [kittyCards] (6 picked up) = 18 cards.
     *
     * Strategy (mirrors ObjC GameFourKitty CPU logic):
     * 1. Void the shortest non-spade suit that has no Ace+King/Ace+Queen pair.
     * 2. Fill remaining discards with the lowest-rated cards (never 2♠).
     */
    fun computeKittyDiscard(hand: List<Card>, kittyCards: List<Card>, discardCount: Int): List<Card> {
        val fullHand  = (hand + kittyCards).toMutableList()
        val toDiscard = mutableListOf<Card>()
        val twoSpades = fullHand.firstOrNull { it.suit == Suit.SPADES && it.rank == Rank.TWO }

        fun hasPowerPair(cards: List<Card>): Boolean {
            val hasAce = cards.any { it.rank == Rank.ACE }
            return hasAce && (cards.any { it.rank == Rank.KING } || cards.any { it.rank == Rank.QUEEN })
        }

        // 1. Void the smallest non-spade suit with no power pair (entire suit fits in discard budget)
        fullHand.filter { !isTrump(it) }
            .groupBy { it.suit }
            .filterValues { !hasPowerPair(it) }
            .entries.sortedBy { it.value.size }
            .forEach { (_, suitCards) ->
                if (toDiscard.size < discardCount) {
                    val eligible = suitCards.filter { it != twoSpades }
                    if (toDiscard.size + eligible.size <= discardCount) {
                        toDiscard.addAll(eligible)
                        fullHand.removeAll(eligible.toSet())
                        return@forEach // void only one suit
                    }
                }
            }

        // 2. Fill remaining discard slots with lowest-rated cards (never 2♠)
        while (toDiscard.size < discardCount) {
            val candidate = fullHand
                .filter { it != twoSpades }
                .minByOrNull { rateCard(it, fullHand) }
                ?: break
            toDiscard.add(candidate)
            fullHand.remove(candidate)
        }

        return toDiscard
    }

    // ── Common helpers ─────────────────────────────────────────────────────

    private fun cut(player: Player, hand: List<Card>, state: GameState): Card {
        val lead       = leadCard(state)
        val winner     = currentWinner(state)
        val winnerCard = winner?.card
        val rightOpp   = rightOpponent(player, state)

        // Team game: right opponent also cutting led suit (not void in spades) → play any spade
        if (state.gameType.useTeams && rightOpp != null && lead != null) {
            val rightCuttingLed = isCutting(rightOpp, lead.suit)
            if (rightCuttingLed && !rightOpp.runtimeFlags.cuttingSpades) {
                return hand.filter { isTrump(it) }.minByOrNull { it.rank.ordinal } ?: throwOff(hand, state)
            }
        }

        // Current winner non-trump: play lowest trump
        if (winnerCard != null && !isTrump(winnerCard)) {
            return hand.filter { isTrump(it) }.minByOrNull { it.rank.ordinal } ?: throwOff(hand, state)
        }

        // Current winner is trump: play lowest beating trump, or throw off
        if (winnerCard != null) {
            return hand.filter { isTrump(it) && it.rank.ordinal > winnerCard.rank.ordinal }
                .minByOrNull { it.rank.ordinal }
                ?: throwOff(hand, state)
        }

        return hand.filter { isTrump(it) }.minByOrNull { it.rank.ordinal } ?: throwOff(hand, state)
    }

    /**
     * Graceful discard: give up the trick without wasting high cards.
     */
    private fun throwOff(hand: List<Card>, state: GameState): Card {
        val lead = leadCard(state)
        if (hand.all { isTrump(it) }) return hand.minBy { it.rank.ordinal }
        if (lead != null) {
            val inLead = hand.filter { followsSuit(it, lead) }
            if (inLead.isNotEmpty()) return inLead.minBy { it.rank.ordinal }
        }
        // Discard from over-stocked non-trump suit (rank < 8, count > 3)
        hand.filter { !isTrump(it) && it.rank.ordinal < Rank.EIGHT.ordinal }
            .groupBy { it.suit }
            .filter { (_, cards) -> cards.size > 3 }
            .values.flatten()
            .minByOrNull { it.rank.ordinal }?.let { return it }
        // Expand threshold (rank < 10, count > 2)
        hand.filter { !isTrump(it) && it.rank.ordinal < Rank.TEN.ordinal }
            .groupBy { it.suit }
            .filter { (_, cards) -> cards.size > 2 }
            .values.flatten()
            .minByOrNull { it.rank.ordinal }?.let { return it }
        // Lowest non-trump that has a protector in same suit
        val nonTrump = hand.filter { !isTrump(it) }.sortedBy { it.rank.ordinal }
        nonTrump.firstOrNull { c -> hand.count { it.suit == c.suit } > 1 }?.let { return it }
        nonTrump.firstOrNull { it.rank.ordinal < Rank.KING.ordinal }?.let { return it }
        return nonTrump.minByOrNull { it.rank.ordinal } ?: hand.minBy { it.rank.ordinal }
    }

    /** LoserHigh throwOff: discard the highest possible card. */
    private fun loserHighThrowOff(hand: List<Card>, lead: Card): Card =
        hand.filter { !isTrump(it) }.maxByOrNull { it.rank.ordinal }
            ?: hand.minBy { it.rank.ordinal }

    /** Feed teammate: lead into a suit the teammate can cut. */
    private fun feedTeammate(teammate: Player, hand: List<Card>, state: GameState): Card? {
        for (suit in listOf(Suit.HEARTS, Suit.CLUBS, Suit.DIAMONDS)) {
            if (isCutting(teammate, suit) && !teammate.runtimeFlags.cuttingSpades) {
                val top = highestRemainingNonTrump(suit, state)
                hand.filter { it.suit == suit && top != null && it.rank.ordinal < top.ordinal }
                    .maxByOrNull { it.rank.ordinal }?.let { return it }
            }
        }
        val throwSuit = teammate.runtimeFlags.suitFirstThrowOff
        if (throwSuit != null) {
            hand.firstOrNull { it.suit == throwSuit && rateCard(it, hand) < 3 }?.let { return it }
        }
        return null
    }

    // ── State derivation ───────────────────────────────────────────────────

    private val TRUMP_CHAIN = listOf(
        Rank.BIGJOKER, Rank.LITTLEJOKER, Rank.DEUCE,
        Rank.ACE, Rank.KING, Rank.QUEEN, Rank.JACK,
        Rank.TEN, Rank.NINE, Rank.EIGHT, Rank.SEVEN,
        Rank.SIX, Rank.FIVE, Rank.FOUR, Rank.THREE, Rank.TWO
    )

    private val NON_TRUMP_RANK_ORDER = listOf(
        Rank.ACE, Rank.KING, Rank.QUEEN, Rank.JACK, Rank.TEN,
        Rank.NINE, Rank.EIGHT, Rank.SEVEN, Rank.SIX, Rank.FIVE,
        Rank.FOUR, Rank.THREE, Rank.TWO
    )

    internal fun isTrump(card: Card): Boolean =
        card.suit == Suit.SPADES || card.rank == Rank.LITTLEJOKER || card.rank == Rank.BIGJOKER

    /** True if [card] must follow [lead] (same non-trump suit, or trump following trump lead). */
    private fun followsSuit(card: Card, lead: Card): Boolean =
        if (isTrump(lead)) isTrump(card)
        else card.suit == lead.suit && !isTrump(card)

    private fun getHand(state: GameState, playerId: String): List<Card> =
        state.phaseHands[GamePhase.Deal]?.lastOrNull()?.perPlayer?.get(playerId)?.hand ?: emptyList()

    private fun getHandState(state: GameState, playerId: String): PlayerHandState? =
        state.phaseHands[GamePhase.Deal]?.lastOrNull()?.perPlayer?.get(playerId)

    private fun leadCard(state: GameState): Card? =
        state.currentTrick.plays.firstOrNull { it != null }?.card

    private fun currentWinner(state: GameState): Play? {
        val plays = state.currentTrick.plays.filterNotNull()
        if (plays.isEmpty()) return null
        return computeTrickWinner(state.currentTrick.plays)
    }

    private fun highestRemainingNonTrump(suit: Suit, state: GameState): Rank? {
        val played = state.discard.filter { it.suit == suit && !isTrump(it) }.map { it.rank }.toSet()
        return NON_TRUMP_RANK_ORDER.firstOrNull { it !in played }
    }

    private fun highestRemainingTrump(state: GameState): Rank? {
        val played = state.discard.filter { isTrump(it) }.map { it.rank }.toSet()
        return TRUMP_CHAIN.firstOrNull { it !in played }
    }

    private fun highestTrump(hand: List<Card>, state: GameState): Card? {
        val topRank = highestRemainingTrump(state) ?: return null
        return hand.firstOrNull { isTrump(it) && it.rank == topRank }
    }

    private fun highestInAnyNonTrump(hand: List<Card>, state: GameState): Card? =
        hand.filter { !isTrump(it) }
            .firstOrNull { it.rank == highestRemainingNonTrump(it.suit, state) }

    private fun strength5(hand: List<Card>): Card? {
        for (rank in TRUMP_CHAIN) {
            val card = hand.firstOrNull { it.rank == rank && isTrump(it) } ?: return null
            if (rank == Rank.ACE) return card // whole chain confirmed up to ace
        }
        return null
    }

    private fun strength5Cards(hand: List<Card>): Set<Card> {
        val result = mutableSetOf<Card>()
        for (rank in TRUMP_CHAIN) {
            val card = hand.firstOrNull { it.rank == rank && isTrump(it) } ?: break
            result.add(card)
        }
        return result
    }

    private fun rateCard(card: Card, hand: List<Card>): Int {
        if (card in strength5Cards(hand)) return 5
        val isSpade   = isTrump(card)
        val suitCount = hand.count { it.suit == card.suit }
        return when {
            card.rank == Rank.ACE && !isSpade && suitCount < 7 -> 4
            isSpade                                             -> 3
            card.rank == Rank.ACE && !isSpade                   -> 3
            card.rank == Rank.KING && suitCount < 4             -> 3
            card.rank == Rank.KING                              -> 2
            else                                                -> 1
        }
    }

    private fun leadHighNonTrump(hand: List<Card>, state: GameState, minStrength: Int): Card? =
        hand.filter { !isTrump(it) && rateCard(it, hand) > minStrength }
            .firstOrNull { it.rank == highestRemainingNonTrump(it.suit, state) }

    private fun fishNonTrump(hand: List<Card>, state: GameState): Card? {
        val bestSuit = hand.filter { !isTrump(it) }
            .groupBy { it.suit }
            .maxByOrNull { (_, cards) -> cards.maxOfOrNull { rateCard(it, hand) } ?: 0 }
            ?.key ?: return null
        val suitCards = hand.filter { it.suit == bestSuit }.sortedByDescending { it.rank.ordinal }
        return suitCards.drop(1).firstOrNull() ?: suitCards.firstOrNull()
    }

    private fun isCutting(player: Player, suit: Suit): Boolean = when (suit) {
        Suit.SPADES   -> player.runtimeFlags.cuttingSpades
        Suit.HEARTS   -> player.runtimeFlags.cuttingHearts
        Suit.CLUBS    -> player.runtimeFlags.cuttingClubs
        Suit.DIAMONDS -> player.runtimeFlags.cuttingDiamonds
    }

    // ── Player references ──────────────────────────────────────────────────

    private fun teammate(player: Player, state: GameState): Player? {
        if (!state.gameType.useTeams) return null
        val teammateSeat = player.runtimeFlags.seatIndex xor 2 // 0↔2, 1↔3
        return state.players.find { it.runtimeFlags.seatIndex == teammateSeat }
    }

    private fun rightOpponent(player: Player, state: GameState): Player? {
        val n        = state.players.size
        val rightSeat = (player.runtimeFlags.seatIndex + 1) % n
        return state.players.find { it.runtimeFlags.seatIndex == rightSeat && it.team != player.team }
    }

    private fun leftOpponent(player: Player, state: GameState): Player? {
        val n        = state.players.size
        val leftSeat  = (player.runtimeFlags.seatIndex + n - 1) % n
        return state.players.find { it.runtimeFlags.seatIndex == leftSeat && it.team != player.team }
    }

    private fun opponents(player: Player, state: GameState): List<Player> =
        state.players.filter { it.id != player.id && it.team != player.team }
}
