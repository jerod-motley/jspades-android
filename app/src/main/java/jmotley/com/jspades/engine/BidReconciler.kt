package jmotley.com.jspades.engine

import jmotley.com.jspades.data.GamePhase
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.data.Hand
import jmotley.com.jspades.data.MPActionType
import jmotley.com.jspades.data.MPBidFactDto
import jmotley.com.jspades.data.MPBidRecoverySnapshotPayload
import jmotley.com.jspades.data.MPNormalizedAction
import jmotley.com.jspades.data.MPNormalizedPayload
import jmotley.com.jspades.data.MPSemanticFactStore
import jmotley.com.jspades.data.Player
import jmotley.com.jspades.data.PlayerHandState
import jmotley.com.jspades.data.WireBidRole

/**
 * Pure, Android-free House Rules / Team Kitty bidding-convergence rules (mp-arch.md Slice 3).
 *
 * Everything here takes explicit state as parameters and returns new copies — no `GameViewModel`,
 * no `PhaseManager`, no adapter, no animation emission. That is what makes it testable with plain
 * JUnit instead of requiring Robolectric to construct an `AndroidViewModel`. Side effects (state
 * flow updates, phase advancement, engine dispatch, network sends) stay in `ViewModel.kt`, which
 * calls into this object and then performs those effects based on the returned result.
 */
object BidReconciler {

    /**
     * A team's fixed bid order for this hand: relative to `leaderIndex` (the hand's dealer, which
     * rotates every hand), with the existing "lone CPU bids first" tiebreak layered on top.
     *
     * This is the single source of truth for team bid order. The sender (choosing whose turn it
     * is) and the receiver (validating a claimed bid role) must agree on this ordering, or
     * legitimate bids get spuriously rejected on roughly half of all hands as the dealer rotates.
     */
    fun fixedTeamBidOrder(
        players: List<Player>, leaderIndex: Int, teamId: Int, isCpu: (Player) -> Boolean
    ): List<Player>? {
        val n = players.size
        if (n == 0) return null
        val ordered = (0 until n)
            .map { offset -> players[(leaderIndex + offset) % n] }
            .filter { it.team == teamId }
        if (ordered.size != 2) return null
        return if (ordered.count(isCpu) == 1) ordered.sortedByDescending(isCpu) else ordered
    }

    /**
     * Next bidder for a team-total contract, honoring live `didBid` state. Local scheduling only:
     * decides whom *this device* should prompt or generate a CPU bid for next. Must never be used
     * to gate whether a received bid fact may be retained — a remote bid is valid regardless of
     * arrival order (mp-arch.md invariant 1).
     */
    fun currentTeamBidTurn(
        players: List<Player>, leaderIndex: Int, teamId: Int, isCpu: (Player) -> Boolean
    ): Pair<Player, Boolean>? {
        val order = fixedTeamBidOrder(players, leaderIndex, teamId, isCpu) ?: return null
        val (first, second) = order
        if (!first.runtimeFlags.didBid) return first to false
        return if (!second.runtimeFlags.didBid) second to true else null
    }

    /**
     * Fixed bidding-plan role for [canonicalId] — depends only on hand-start turn order, never on
     * live bidding progress, so it is safe to validate an incoming bid's claimed role against it
     * regardless of arrival order.
     */
    fun fixedBidRoleForSeat(
        players: List<Player>, leaderIndex: Int, gameType: GameType, canonicalId: String,
        isCpu: (Player) -> Boolean
    ): WireBidRole? {
        val player = players.find { it.id == canonicalId } ?: return null
        if (gameType !in setOf(GameType.HOUSE_RULES, GameType.TEAM_KITTY)) return WireBidRole.INDIVIDUAL
        val order = fixedTeamBidOrder(players, leaderIndex, player.team, isCpu) ?: return null
        return if (order.first().id == player.id) WireBidRole.INDIVIDUAL else WireBidRole.TEAM_TOTAL
    }

    /**
     * Pure mutation: store [action]'s bid fact into [hand]/[players] — sets `didBid` and, when the
     * role is `TEAM_TOTAL`, replaces (never sums) that team's contract — regardless of arrival
     * order. Returns new copies; never mutates its arguments.
     */
    fun applyBidFact(
        hand: Hand, players: List<Player>, canonicalId: String, gameType: GameType,
        action: MPNormalizedAction
    ): Pair<Hand, List<Player>> {
        val payload = action.payload as MPNormalizedPayload.Bid
        val perPlayer = hand.perPlayer.toMutableMap()
        perPlayer[canonicalId] = (perPlayer[canonicalId] ?: PlayerHandState())
            .copy(bid = payload.amount, bidPlaced = true, isBlind = payload.isBlind)
        var newHand = hand.copy(perPlayer = perPlayer)
        if (payload.role == WireBidRole.TEAM_TOTAL && gameType in setOf(GameType.HOUSE_RULES, GameType.TEAM_KITTY)) {
            val teamId = players.find { it.id == canonicalId }?.team
            if (teamId != null && teamId in newHand.teamBids.indices) {
                val teamBids = newHand.teamBids.toMutableList()
                teamBids[teamId] = payload.amount
                newHand = newHand.copy(teamBids = teamBids)
            }
        }
        val newPlayers = players.map { p ->
            if (p.id == canonicalId) p.copy(runtimeFlags = p.runtimeFlags.copy(didBid = true)) else p
        }
        return newHand to newPlayers
    }

    /**
     * The only authoritative source for the post-bid logical phase: never accept a claimed phase
     * (e.g. from a recovery snapshot) as input. `strict` gates the stricter "all seats have bid"
     * completion rule the same way live bid application already does.
     */
    fun derivedBidPhase(strict: Boolean, bidCount: Int, playerCount: Int): GamePhase =
        if (strict && bidCount >= playerCount) GamePhase.BidReview else GamePhase.Bid

    // ── Bid-recovery snapshot (Slice 3 minimal scope: bid facts only) ─────────────

    /** Host-side: gather every retained bid fact for (generation, hand) into the wire payload. */
    fun buildRecoverySnapshotPayload(
        facts: MPSemanticFactStore, gameGeneration: Int, handNum: Int, logicalPhase: String
    ): MPBidRecoverySnapshotPayload {
        val bidFacts = facts.facts {
            it.type == MPActionType.BID && it.gameGeneration == gameGeneration && it.handNum == handNum
        }
        return MPBidRecoverySnapshotPayload(gameGeneration, handNum, logicalPhase, bidFacts.map {
            val p = it.payload as MPNormalizedPayload.Bid
            MPBidFactDto(it.senderSeat, p.amount, p.isBlind, p.role, it.cmdId)
        })
    }

    /**
     * Validates an incoming bid-recovery snapshot payload before it is allowed to replace cached
     * facts. Decoding JSON successfully is necessary but not sufficient — a well-formed but
     * internally contradictory or mismatched payload must still be rejected:
     *
     * - the payload's generation/hand match the enclosing envelope's;
     * - every fact's seat is unique within the payload and resolves to an actual rostered player;
     * - every fact's role matches that seat's *fixed* bidding-plan role;
     * - every fact's cmdId is non-blank, and cmdIds are unique within the payload;
     * - the bid count does not exceed the roster size;
     * - if [retainedCmdIds] is non-empty, it agrees with the set of cmdIds actually present.
     */
    fun isValidRecoverySnapshot(
        payload: MPBidRecoverySnapshotPayload, envelopeGeneration: Int, envelopeHandNum: Int,
        retainedCmdIds: List<String>, players: List<Player>, leaderIndex: Int, gameType: GameType,
        canonicalIdForSeat: (Int) -> String, isCpu: (Player) -> Boolean
    ): Boolean {
        if (payload.gameGeneration != envelopeGeneration || payload.handNum != envelopeHandNum) return false
        if (payload.bids.size > players.size) return false
        if (retainedCmdIds.isNotEmpty() && retainedCmdIds.toSet() != payload.bids.map { it.cmdId }.toSet()) return false
        val seatsSeen = mutableSetOf<Int>()
        val cmdIdsSeen = mutableSetOf<String>()
        for (dto in payload.bids) {
            if (!seatsSeen.add(dto.seat)) return false
            if (dto.cmdId.isBlank() || !cmdIdsSeen.add(dto.cmdId)) return false
            val canonicalId = canonicalIdForSeat(dto.seat)
            val player = players.find { it.id == canonicalId } ?: return false
            val expectedRole = fixedBidRoleForSeat(players, leaderIndex, gameType, player.id, isCpu) ?: return false
            if (dto.role != expectedRole) return false
        }
        return true
    }

    /**
     * Receiver/self-heal side: install [payload]'s bid facts as authoritative — replacing whatever
     * this store already holds for that (generation, hand) — and return them as normalized actions
     * ready to replay through [applyBidFact]. Callers must validate with [isValidRecoverySnapshot]
     * first; this function performs no validation of its own. [toAction] converts a wire DTO back
     * into the caller's normalized-action shape (kept as a callback so this object never needs the
     * caller's cmdId/seat/generation plumbing).
     */
    fun installRecoverySnapshot(
        facts: MPSemanticFactStore, payload: MPBidRecoverySnapshotPayload,
        toAction: (MPBidFactDto) -> MPNormalizedAction
    ): List<MPNormalizedAction> {
        facts.clearFacts {
            it.type == MPActionType.BID && it.gameGeneration == payload.gameGeneration &&
                it.handNum == payload.handNum
        }
        val actions = payload.bids.map(toAction)
        actions.forEach { facts.installAuthoritative(it) }
        return actions
    }

    /**
     * Pure correlation check for `stateSnapshot.responseToRequestId` against the receiver's
     * current recovery gate for that scope.
     *
     * If a local gate already exists ([currentGateRequestId] non-nil), the snapshot must carry
     * and match that exact request id — an unsolicited (`nil`) response, or a response to some
     * other id, means this snapshot answers a different, no-longer-current recovery attempt and
     * must not be allowed to clear an active gate it was never asked to resolve. Only when no
     * local gate exists at all is a `nil` (host-initiated, unsolicited) or any other correction
     * accepted — there is nothing local it could wrongly supersede.
     */
    fun shouldApplySnapshot(responseToRequestId: String?, currentGateRequestId: String?): Boolean =
        currentGateRequestId == null || responseToRequestId == currentGateRequestId

    /**
     * Best-effort discriminator for whether a `resyncRequest`'s `semanticKey` identifies a BID
     * conflict specifically, as opposed to a play conflict, delivery timeout, impossible
     * progress, or reconnect divergence — none of which a bid-only recovery snapshot can
     * resolve. `semanticKey` is currently carried as a free-form diagnostic string
     * (`MPSemanticKey.toString()`), not a validated typed field; until the wire protocol adds an
     * explicit, validated snapshot-kind discriminator, an unrecognized or absent key must be
     * treated as "not a bid conflict" — a host that cannot positively identify the request as
     * bid-scoped must leave the scope frozen rather than falsely "resolve" it with bid facts.
     */
    fun isBidScopedRecoveryKey(semanticKey: String?): Boolean =
        semanticKey != null && semanticKey.contains("actionType=${MPActionType.BID.name}")
}
