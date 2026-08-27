package jmotley.com.jspades.engine

import jmotley.com.jspades.data.GamePhase
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.data.Hand
import jmotley.com.jspades.data.MPActionType
import jmotley.com.jspades.data.MPBidFactDto
import jmotley.com.jspades.data.MPBidRecoverySnapshotPayload
import jmotley.com.jspades.data.MPNormalizedAction
import jmotley.com.jspades.data.MPNormalizedPayload
import jmotley.com.jspades.data.MPRecoveryCoordinator
import jmotley.com.jspades.data.MPRecoveryReason
import jmotley.com.jspades.data.MPRecoveryScope
import jmotley.com.jspades.data.MPRecoveryScopeKind
import jmotley.com.jspades.data.MPRetentionResult
import jmotley.com.jspades.data.MPSemanticFactStore
import jmotley.com.jspades.data.MPSemanticKey
import jmotley.com.jspades.data.Player
import jmotley.com.jspades.data.PlayerType
import jmotley.com.jspades.data.WireBidRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mp-arch.md Slice 3 conformance: order-independent, conflict-safe House Rules/Team Kitty
 * bidding. Exercises [BidReconciler] together with the real (already independently tested)
 * [MPSemanticFactStore]/[MPRecoveryCoordinator], composed exactly the way `ViewModel.kt`'s
 * `onBid`/`applyRetainedBid`/`handleBidConflict`/`onStateSnapshot` compose them — this is plain
 * JUnit, no Robolectric/GameViewModel needed, since none of this logic touches Android.
 *
 * These tests prove the reconciler layer converges correctly. They do not prove the real
 * `ViewModel`/adapter wiring calls it correctly end to end (real wire decoding, acknowledgement,
 * capability negotiation) — see MD/mp-arch.md's Slice 7 ledger for that gap.
 */
class BidReconcilerTest {

    private val canonical = listOf("south", "west", "north", "east")
    private val noCpu: (Player) -> Boolean = { false }

    /** South+North on team 0, West+East on team 1 — matches the real roster convention. */
    private fun roster() = canonical.mapIndexed { i, id ->
        Player(id = id, name = id, team = i % 2, playerType = PlayerType.MP)
    }

    private fun bid(
        seat: Int, amount: Int, role: WireBidRole, cmdId: String = "cmd-$seat-$amount-$role",
        generation: Int = 1, handNum: Int = 1, isBlind: Boolean = false
    ) = MPNormalizedAction(
        type = MPActionType.BID,
        semanticKey = MPSemanticKey(MPActionType.BID, generation, handNum, seat),
        cmdId = cmdId, senderSeat = seat, senderPlayerId = "p$seat",
        gameGeneration = generation, handNum = handNum,
        payload = MPNormalizedPayload.Bid(amount, isBlind, role)
    )

    private fun emptyHand() = Hand(teamBids = listOf(0, 0))

    // ── Test 1 ──────────────────────────────────────────────────────────────────

    @Test fun individualBidsConvergeRegardlessOfOrder() {
        val players = roster()
        val amounts = mapOf(0 to 3, 1 to 4, 2 to 2, 3 to 5)
        val orders = listOf(listOf(0, 1, 2, 3), listOf(3, 2, 1, 0), listOf(2, 0, 3, 1))

        val results = orders.map { order ->
            var hand = emptyHand()
            var ps = players
            for (seat in order) {
                val (newHand, newPlayers) = BidReconciler.applyBidFact(
                    hand, ps, canonical[seat], GameType.SOLO_FOUR_MAN,
                    bid(seat, amounts.getValue(seat), WireBidRole.INDIVIDUAL))
                hand = newHand; ps = newPlayers
            }
            hand.perPlayer.mapValues { it.value.bid } to ps.all { it.runtimeFlags.didBid }
        }

        assertEquals(1, results.distinct().size)
        assertEquals(amounts.mapKeys { canonical[it.key] }, results.first().first)
        assertTrue(results.first().second)
    }

    // ── Test 2 ──────────────────────────────────────────────────────────────────

    @Test fun houseRulesPreliminaryAndTotalConvergeInBothOrders() {
        val players = roster()
        // Team 1 = west(1)/east(3); west preliminary, east final, per fixedTeamBidOrder(leaderIndex=0).
        val preliminary = bid(1, 3, WireBidRole.INDIVIDUAL)
        val total = bid(3, 6, WireBidRole.TEAM_TOTAL)

        fun applyBoth(first: MPNormalizedAction, second: MPNormalizedAction): List<Int?> {
            var hand = emptyHand()
            var ps = players
            for (a in listOf(first, second)) {
                val (h, p) = BidReconciler.applyBidFact(hand, ps, canonical[a.senderSeat], GameType.HOUSE_RULES, a)
                hand = h; ps = p
            }
            return hand.teamBids
        }

        assertEquals(applyBoth(preliminary, total), applyBoth(total, preliminary))
        assertEquals(6, applyBoth(preliminary, total)[1])
    }

    // ── Test 3 — the August 26 regression ──────────────────────────────────────

    @Test fun august26Regression_conflictingTurnOrderStillRetainsBoth() {
        val facts = MPSemanticFactStore()
        // East (team 1, individual/preliminary) arrives before South's team-0 total — the exact
        // ordering that caused the original hang by being rejected as "not the expected bidder".
        val eastBid = bid(3, 3, WireBidRole.INDIVIDUAL)
        val southTotal = bid(0, 6, WireBidRole.TEAM_TOTAL)

        assertEquals(MPRetentionResult.RETAINED, facts.retain(eastBid, strictConflicts = true))
        assertEquals(MPRetentionResult.RETAINED, facts.retain(southTotal, strictConflicts = true))

        // Both retained as facts regardless of arrival order — neither was rejected.
        assertNotNull(facts.fact(eastBid.semanticKey))
        assertNotNull(facts.fact(southTotal.semanticKey))
        // West (team 1's committing bidder) has not yet bid — the hand is not complete.
        assertNull(facts.fact(MPSemanticKey(MPActionType.BID, 1, 1, 1)))
    }

    // ── Test 4 ──────────────────────────────────────────────────────────────────

    @Test fun teamTotalBeforePreliminaryKeepsExplicitTotal() {
        val players = roster()
        var hand = emptyHand()
        var ps = players
        // Team-total (west/east's east=TEAM_TOTAL) arrives BEFORE its preliminary teammate.
        val (h1, p1) = BidReconciler.applyBidFact(hand, ps, "east", GameType.HOUSE_RULES, bid(3, 6, WireBidRole.TEAM_TOTAL))
        hand = h1; ps = p1
        val (h2, p2) = BidReconciler.applyBidFact(hand, ps, "west", GameType.HOUSE_RULES, bid(1, 3, WireBidRole.INDIVIDUAL))
        hand = h2; ps = p2

        assertEquals(6, hand.teamBids[1]) // explicit total, never summed with the preliminary 3
    }

    // ── Test 5 ──────────────────────────────────────────────────────────────────

    @Test fun equivalentSemanticFactDeduplicatesAcrossCommandIds() {
        val facts = MPSemanticFactStore()
        val first = bid(1, 4, WireBidRole.INDIVIDUAL, cmdId = "cmd-a")
        val retry = first.copy(cmdId = "cmd-b") // same semantic key/payload, different cmdId

        assertEquals(MPRetentionResult.RETAINED, facts.retain(first, strictConflicts = true))
        assertEquals(MPRetentionResult.DUPLICATE, facts.retain(retry, strictConflicts = true))
        // A DUPLICATE result is the caller's signal never to invoke applyBidFact a second time —
        // exactly what ViewModel.onBid does (`if (retention != RETAINED) return retention`).
    }

    // ── Test 6 ──────────────────────────────────────────────────────────────────

    @Test fun conflictingBidsFreezeAndTriggerExactlyOneRecovery() {
        val facts = MPSemanticFactStore()
        val recovery = MPRecoveryCoordinator()
        val first = bid(1, 4, WireBidRole.INDIVIDUAL, cmdId = "cmd-a")
        val conflicting = bid(1, 7, WireBidRole.INDIVIDUAL, cmdId = "cmd-b")
        val scope = MPRecoveryScope(1, MPRecoveryScopeKind.HAND, 1)

        assertEquals(MPRetentionResult.RETAINED, facts.retain(first, strictConflicts = true))
        assertEquals(MPRetentionResult.CONFLICT, facts.retain(conflicting, strictConflicts = true))
        assertEquals(first, facts.fact(first.semanticKey)) // first fact unchanged

        val gate = recovery.freeze(scope, MPRecoveryReason.CONFLICTING_FACT)
        // Repeated delivery of the identical conflicting cmdId must not install a second gate/request.
        assertEquals(MPRetentionResult.CONFLICT, facts.retain(conflicting, strictConflicts = true))
        val gateAgain = recovery.freeze(scope, MPRecoveryReason.CONFLICTING_FACT)
        assertEquals(gate, gateAgain)
        assertTrue(recovery.isFrozen(1, 1))
    }

    // ── Test 21 ─────────────────────────────────────────────────────────────────

    @Test fun frozenScopePerformsNoGameplayWork() {
        val facts = MPSemanticFactStore()
        val recovery = MPRecoveryCoordinator()
        val scope = MPRecoveryScope(1, MPRecoveryScopeKind.HAND, 1)
        recovery.freeze(scope, MPRecoveryReason.CONFLICTING_FACT)

        val laterBid = bid(2, 2, WireBidRole.INDIVIDUAL) // a different, otherwise-valid seat
        assertEquals(MPRetentionResult.RETAINED, facts.retain(laterBid, strictConflicts = true))
        // Frozen: the caller stages rather than applies (mirrors ViewModel.onBid's frozen branch).
        assertTrue(recovery.isFrozen(laterBid.gameGeneration, laterBid.handNum))
        facts.stage(laterBid)
        assertEquals(laterBid, facts.pending(laterBid.semanticKey))
        assertTrue(facts.facts { it.senderSeat == 2 }.isNotEmpty()) // retained as a fact...
        assertEquals(0, facts.takePending { it.gameGeneration == 999 }.size) // ...but nothing drains under the wrong predicate
    }

    // ── Test 22 ─────────────────────────────────────────────────────────────────

    @Test fun snapshotApplyUnfreezesAndReconciles() {
        val facts = MPSemanticFactStore()
        val recovery = MPRecoveryCoordinator()
        val scope = MPRecoveryScope(1, MPRecoveryScopeKind.HAND, 1)
        val gate = recovery.freeze(scope, MPRecoveryReason.CONFLICTING_FACT)

        // A legitimate bid for another seat arrives while frozen: staged, not applied.
        val staged = bid(2, 2, WireBidRole.INDIVIDUAL)
        facts.retain(staged, strictConflicts = true)
        facts.stage(staged)

        // Host resolves the conflict and sends a snapshot for seat 1's true bid.
        val payload = BidReconciler.buildRecoverySnapshotPayload(
            facts.also { it.installAuthoritative(bid(1, 4, WireBidRole.INDIVIDUAL, cmdId = "authoritative")) },
            gameGeneration = 1, handNum = 1, logicalPhase = "Bid")
        assertTrue(payload.bids.any { it.seat == 1 && it.amount == 4 })

        val actions = BidReconciler.installRecoverySnapshot(facts, payload) { dto ->
            bid(dto.seat, dto.amount, dto.role, cmdId = dto.cmdId)
        }
        val completed = recovery.complete(scope, gate.requestId, "snap-1", 1L, targetSeat = 1)
        assertTrue(completed)
        assertFalse(recovery.isFrozen(1, 1))

        // Replay the authoritative facts, then drain what was staged during the freeze.
        var hand = emptyHand()
        var ps = roster()
        for (a in actions) {
            val (h, p) = BidReconciler.applyBidFact(hand, ps, canonical[a.senderSeat], GameType.HOUSE_RULES, a)
            hand = h; ps = p
        }
        assertEquals(4, hand.perPlayer[canonical[1]]?.bid)

        val drained = facts.takePending { it.gameGeneration == 1 && it.handNum == 1 }
        assertEquals(listOf(staged), drained)
    }

    // ── Test 23 ─────────────────────────────────────────────────────────────────

    @Test fun staleRecoveryArtifactCannotClearNewerGate() {
        val recovery = MPRecoveryCoordinator()
        val scope = MPRecoveryScope(1, MPRecoveryScopeKind.HAND, 1)
        recovery.freeze(scope, MPRecoveryReason.CONFLICTING_FACT, requestId = "r1")

        assertFalse(recovery.complete(scope, "stale-request", "snap-x", 1L, targetSeat = 1))
        assertTrue(recovery.isFrozen(1, 1))
        assertEquals("r1", recovery.gate(scope)?.requestId)
    }

    // ── Test 23 (correlation) — found on review: responseToRequestId, not a coordinator round ──

    @Test fun mismatchedResponseToRequestIdIsStaleAndDoesNotMutate() {
        // A response claiming to answer a request must match the CURRENT gate's requestId.
        assertFalse(BidReconciler.shouldApplySnapshot("some-other-request", "current-gate-request"))
        assertTrue(BidReconciler.shouldApplySnapshot("current-gate-request", "current-gate-request"))
        // No local gate at all: any correction — solicited or not — is accepted, since nothing
        // local could be wrongly superseded by it.
        assertTrue(BidReconciler.shouldApplySnapshot("some-request", null))
        assertTrue(BidReconciler.shouldApplySnapshot(null, null))
        // Found on review: an unsolicited (nil responseToRequestId) snapshot must NOT be allowed
        // to clear an active, unrelated local gate — only when no gate exists is nil accepted.
        assertFalse(BidReconciler.shouldApplySnapshot(null, "unrelated-local-gate"))
    }

    @Test fun recordAppliedSnapshotAllowsIdempotencyWithoutAnExistingGate() {
        val recovery = MPRecoveryCoordinator()
        val scope = MPRecoveryScope(1, MPRecoveryScopeKind.HAND, 1)
        // No freeze() call — this is the unsolicited host-push path with nothing local to clear.
        assertFalse(recovery.isFrozen(1, 1))
        recovery.recordAppliedSnapshot(scope, snapshotVersion = 5L, targetSeat = 1)
        // The version is now recorded: a retried delivery of the same/older version is stale.
        assertEquals(MPRetentionResult.STALE, recovery.classifySnapshot(scope, 5L, targetSeat = 1))
        assertEquals(MPRetentionResult.STALE, recovery.classifySnapshot(scope, 4L, targetSeat = 1))
        assertEquals(MPRetentionResult.RETAINED, recovery.classifySnapshot(scope, 6L, targetSeat = 1))
        assertFalse(recovery.isFrozen(1, 1)) // recording never installs a gate
    }

    // ── Bug 1 regression: sender/receiver role order must agree across dealer rotation ──

    @Test fun roleOrderMatchesSenderAcrossDealerRotation() {
        val players = roster()
        for (leaderIndex in listOf(0, 2)) { // south-leads vs north-leads: flips team 0's pick order
            val order = BidReconciler.fixedTeamBidOrder(players, leaderIndex, teamId = 0, isCpu = noCpu)!!
            val (expectedPreliminary, expectedFinal) = order
            assertEquals(WireBidRole.INDIVIDUAL,
                BidReconciler.fixedBidRoleForSeat(players, leaderIndex, GameType.HOUSE_RULES, expectedPreliminary.id, noCpu))
            assertEquals(WireBidRole.TEAM_TOTAL,
                BidReconciler.fixedBidRoleForSeat(players, leaderIndex, GameType.HOUSE_RULES, expectedFinal.id, noCpu))
        }
        // The two leaderIndex values must actually disagree about who's preliminary — otherwise
        // this test would pass trivially without exercising the rotation.
        val southFirstAtZero = BidReconciler.fixedTeamBidOrder(players, 0, 0, noCpu)!!.first().id
        val southFirstAtTwo = BidReconciler.fixedTeamBidOrder(players, 2, 0, noCpu)!!.first().id
        assertTrue(southFirstAtZero != southFirstAtTwo)
    }

    // ── Bug 2 regression: host's recovery snapshot reflects authoritative (first-wins) truth ──

    @Test fun hostSelfHealsOnDetectedConflict() {
        val facts = MPSemanticFactStore()
        val first = bid(1, 4, WireBidRole.INDIVIDUAL, cmdId = "cmd-first")
        val conflicting = bid(1, 9, WireBidRole.INDIVIDUAL, cmdId = "cmd-second")
        assertEquals(MPRetentionResult.RETAINED, facts.retain(first, strictConflicts = true))
        assertEquals(MPRetentionResult.CONFLICT, facts.retain(conflicting, strictConflicts = true))

        // The snapshot the host would send back to the conflicting sender must carry the
        // authoritative (first-retained) amount, never the rejected second payload.
        val payload = BidReconciler.buildRecoverySnapshotPayload(facts, gameGeneration = 1, handNum = 1, logicalPhase = "Bid")
        val seat1 = payload.bids.single { it.seat == 1 }
        assertEquals(4, seat1.amount)
        assertEquals("cmd-first", seat1.cmdId)
    }

    // ── New: snapshot content validation (test 20) ─────────────────────────────

    @Test fun malformedOrInconsistentSnapshotIsRejected() {
        val players = roster()
        fun payloadOf(vararg dtos: MPBidFactDto) = MPBidRecoverySnapshotPayload(1, 1, "Bid", dtos.toList())
        fun seatFor(seat: Int) = canonical[seat]
        fun valid(payload: MPBidRecoverySnapshotPayload, retainedCmdIds: List<String> = emptyList()) =
            BidReconciler.isValidRecoverySnapshot(payload, envelopeGeneration = 1, envelopeHandNum = 1,
                retainedCmdIds = retainedCmdIds, players = players, leaderIndex = 0, gameType = GameType.HOUSE_RULES,
                canonicalIdForSeat = ::seatFor, isCpu = noCpu)

        val goodWest = MPBidFactDto(1, 3, false, WireBidRole.INDIVIDUAL, "cmd-w") // west is HOUSE_RULES preliminary at leaderIndex=0
        assertTrue("well-formed payload should validate", valid(payloadOf(goodWest)))

        assertFalse("generation mismatch", BidReconciler.isValidRecoverySnapshot(
            MPBidRecoverySnapshotPayload(2, 1, "Bid", listOf(goodWest)), 1, 1, emptyList(),
            players, 0, GameType.HOUSE_RULES, ::seatFor, noCpu))
        assertFalse("hand mismatch", BidReconciler.isValidRecoverySnapshot(
            MPBidRecoverySnapshotPayload(1, 2, "Bid", listOf(goodWest)), 1, 1, emptyList(),
            players, 0, GameType.HOUSE_RULES, ::seatFor, noCpu))
        assertFalse("duplicate seat", valid(payloadOf(goodWest, goodWest.copy(cmdId = "cmd-w2"))))
        assertFalse("blank cmdId", valid(payloadOf(goodWest.copy(cmdId = ""))))
        assertFalse("duplicate cmdId across different seats", valid(payloadOf(
            goodWest, MPBidFactDto(3, 6, false, WireBidRole.TEAM_TOTAL, "cmd-w"))))
        assertFalse("role does not match fixed plan", valid(payloadOf(goodWest.copy(role = WireBidRole.TEAM_TOTAL))))
        assertFalse("bid count exceeds roster", valid(payloadOf(
            MPBidFactDto(0, 1, false, WireBidRole.INDIVIDUAL, "c0"),
            MPBidFactDto(1, 1, false, WireBidRole.INDIVIDUAL, "c1"),
            MPBidFactDto(2, 1, false, WireBidRole.TEAM_TOTAL, "c2"),
            MPBidFactDto(3, 1, false, WireBidRole.TEAM_TOTAL, "c3"),
            MPBidFactDto(0, 1, false, WireBidRole.INDIVIDUAL, "c4"))))
        assertFalse("retainedCmdIds disagrees with decoded facts",
            valid(payloadOf(goodWest), retainedCmdIds = listOf("some-other-cmd")))
        assertTrue("retainedCmdIds agreeing with decoded facts is fine",
            valid(payloadOf(goodWest), retainedCmdIds = listOf("cmd-w")))
    }

    // ── New: a bid-only snapshot must not silently "resolve" a non-bid recovery (finding 1) ──

    @Test fun onlyABidScopedSemanticKeyAuthorizesABidRecoverySnapshot() {
        assertTrue(BidReconciler.isBidScopedRecoveryKey(BidReconciler.BID_CONFLICT_SEMANTIC_KEY_TAG))
        assertFalse("a card-play conflict must not be treated as bid-resolvable",
            BidReconciler.isBidScopedRecoveryKey(MPSemanticKey(MPActionType.CARD_PLAY, 1, 1, trickNum = 1, trickPlayNum = 2).toString()))
        // onTerminalDeliveryFailure never sets semanticKey at all — the common real-world case
        // (any message type's retry exhaustion, not just bids) must also stay unresolved.
        assertFalse("absent semanticKey (e.g. a delivery-timeout request) must not be treated as a bid conflict",
            BidReconciler.isBidScopedRecoveryKey(null))
    }

    /** Cross-platform regression: the tag must be a short, wire-stable literal — not a
     * platform-specific default struct dump — or Android and iOS silently fail to recognize
     * each other's bid-conflict resync requests in either direction. */
    @Test fun bidConflictSemanticKeyTagIsTheCrossPlatformStableLiteral() {
        assertEquals("bid", BidReconciler.BID_CONFLICT_SEMANTIC_KEY_TAG)
    }

    // ── New: clearing a superseded fact must also free its own cmdId for re-evaluation (finding 4) ──

    @Test fun clearFactsAlsoFreesTheRemovedFactsOwnCommandResultButKeepsOtherTerminalResults() {
        val facts = MPSemanticFactStore()
        val superseded = bid(1, 4, WireBidRole.INDIVIDUAL, cmdId = "superseded-cmd")
        val conflicting = bid(1, 9, WireBidRole.INDIVIDUAL, cmdId = "conflicting-cmd")
        assertEquals(MPRetentionResult.RETAINED, facts.retain(superseded, strictConflicts = true))
        assertEquals(MPRetentionResult.CONFLICT, facts.retain(conflicting, strictConflicts = true))

        facts.clearFacts { it.type == MPActionType.BID && it.handNum == 1 }
        facts.installAuthoritative(bid(1, 4, WireBidRole.INDIVIDUAL, cmdId = "authoritative-cmd"))

        // A retry of the SUPERSEDED fact's own cmdId must be re-evaluated fresh (here: it matches
        // the new authoritative fact's payload, so it comes back DUPLICATE against the new fact —
        // never the stale cached RETAINED verdict for a fact that no longer exists).
        assertEquals(MPRetentionResult.DUPLICATE, facts.retain(superseded.copy(cmdId = "superseded-cmd"), strictConflicts = true))
        // The CONFLICT verdict for the cmdId that was correctly rejected remains a stable,
        // terminal record — clearing the winning fact must not un-reject it.
        assertEquals(MPRetentionResult.CONFLICT, facts.retain(conflicting, strictConflicts = true))
    }

    // ── New: post-recovery phase is always derived from facts, never accepted from the payload (finding 5) ──

    @Test fun derivedBidPhaseIgnoresClaimedPhaseAndComesOnlyFromFactCompleteness() {
        assertEquals(GamePhase.Bid, BidReconciler.derivedBidPhase(strict = true, bidCount = 3, playerCount = 4))
        assertEquals(GamePhase.BidReview, BidReconciler.derivedBidPhase(strict = true, bidCount = 4, playerCount = 4))
        // Non-strict (legacy) rooms never auto-advance to BidReview from fact completeness alone.
        assertEquals(GamePhase.Bid, BidReconciler.derivedBidPhase(strict = false, bidCount = 4, playerCount = 4))
    }

    // ── New: replaying a snapshot must not leave stale bid state for seats it omits (found on review) ──

    @Test fun resetBidBaselineClearsEverySeatBeforeReplayingAPartialSnapshot() {
        val players = roster()
        // All four seats have already bid locally...
        var hand = emptyHand()
        var ps = players
        for (seat in 0..3) {
            // One TEAM_TOTAL bidder per team (seats 0 and 1: south=team0, west=team1) so both
            // teamBids entries actually get set, matching a real completed hand.
            val role = if (seat == 0 || seat == 1) WireBidRole.TEAM_TOTAL else WireBidRole.INDIVIDUAL
            val (h, p) = BidReconciler.applyBidFact(hand, ps, canonical[seat], GameType.HOUSE_RULES, bid(seat, 9, role))
            hand = h; ps = p
        }
        assertTrue(ps.all { it.runtimeFlags.didBid })
        assertTrue(hand.teamBids.all { it == 9 })

        // ...but the authoritative recovery snapshot only knows about seat 1 (e.g. the host
        // hadn't retained the other seats' bids yet when it built the snapshot). Naively
        // replaying just that one fact onto the existing hand would leave seats 0/2/3's stale
        // amount=9 sitting in perPlayer/teamBids with no backing fact anymore.
        val (resetHand, resetPlayers) = BidReconciler.resetBidBaseline(hand, ps)
        assertTrue("reset must clear didBid for every seat, not just the ones in the new payload",
            resetPlayers.none { it.runtimeFlags.didBid })
        assertTrue(resetHand.teamBids.all { it == 0 })
        assertTrue(resetHand.perPlayer.values.all { it.bid == 0 && !it.bidPlaced && !it.isBlind })

        val (finalHand, finalPlayers) = BidReconciler.applyBidFact(resetHand, resetPlayers, "west", GameType.HOUSE_RULES,
            bid(1, 3, WireBidRole.TEAM_TOTAL))
        assertEquals(3, finalHand.teamBids[1])
        assertEquals("team 0's stale total must be gone, not merged with the old value", 0, finalHand.teamBids[0])
        assertEquals("only west's didBid should be set post-reset", listOf("west"), finalPlayers.filter { it.runtimeFlags.didBid }.map { it.id })
    }
}
