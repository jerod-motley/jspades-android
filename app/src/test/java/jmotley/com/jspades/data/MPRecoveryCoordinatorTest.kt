package jmotley.com.jspades.data

import org.junit.Assert.*
import org.junit.Test

class MPRecoveryCoordinatorTest {
    private val scope = MPRecoveryScope(4, MPRecoveryScopeKind.HAND, 2)

    @Test fun triggersForOneScopeAreCoalesced() {
        val coordinator = MPRecoveryCoordinator()
        val first = coordinator.freeze(scope, MPRecoveryReason.CONFLICTING_FACT, "r1")
        val second = coordinator.freeze(scope, MPRecoveryReason.DELIVERY_TIMEOUT, "r2")
        assertEquals(first, second)
        assertTrue(coordinator.isFrozen(4, 2))
    }

    @Test fun staleAttemptCannotClearNewGate() {
        val coordinator = MPRecoveryCoordinator()
        coordinator.freeze(scope, MPRecoveryReason.CONFLICTING_FACT, "new")
        assertFalse(coordinator.complete(scope, "old", "s1", 1, 1))
        assertTrue(coordinator.isFrozen(4, 2))
    }

    @Test fun snapshotsAreMonotonicAndIdempotent() {
        val coordinator = MPRecoveryCoordinator()
        coordinator.freeze(scope, MPRecoveryReason.CONFLICTING_FACT, "r1")
        assertEquals(MPRetentionResult.RETAINED, coordinator.classifySnapshot(scope, 7, 1))
        assertTrue(coordinator.complete(scope, "r1", "s7", 7, 1))
        assertEquals(MPRetentionResult.STALE, coordinator.classifySnapshot(scope, 7, 1))
        assertEquals(MPRetentionResult.STALE, coordinator.classifySnapshot(scope, 6, 1))
        assertEquals(MPRetentionResult.RETAINED, coordinator.classifySnapshot(scope, 8, 1))
    }

    @Test fun recoveryRoundsAreBounded() {
        val coordinator = MPRecoveryCoordinator(maxRounds = 2)
        coordinator.freeze(scope, MPRecoveryReason.DELIVERY_TIMEOUT, "r1")
        assertNotNull(coordinator.nextRound(scope, "r1"))
        assertNull(coordinator.nextRound(scope, "r1"))
    }

    @Test fun unrelatedScopesRemainFrozenIndependently() {
        val coordinator = MPRecoveryCoordinator()
        val next = MPRecoveryScope(4, MPRecoveryScopeKind.HAND, 3)
        coordinator.freeze(scope, MPRecoveryReason.CONFLICTING_FACT, "r1")
        coordinator.freeze(next, MPRecoveryReason.DELIVERY_TIMEOUT, "r2")
        assertTrue(coordinator.isFrozen(4, 2))
        assertTrue(coordinator.isFrozen(4, 3))
        assertTrue(coordinator.complete(next, "r2", "s2", 2, 1))
        assertTrue(coordinator.isFrozen(4, 2))
    }

    /** Found on review: snapshot versions are monotonic PER SCOPE, so hand 2 legitimately
     * restarts at version 1 — the applied-snapshot identity must include handNum, or hand 2's
     * version-1 snapshot collides with hand 1's already-recorded version-1 entry for the same
     * generation/targetSeat and gets wrongly classified STALE, leaving that hand unrecoverable. */
    @Test fun appliedSnapshotIdentityDoesNotCollideAcrossHandsAtTheSameVersion() {
        val coordinator = MPRecoveryCoordinator()
        val hand1 = MPRecoveryScope(4, MPRecoveryScopeKind.HAND, 1)
        val hand2 = MPRecoveryScope(4, MPRecoveryScopeKind.HAND, 2)

        coordinator.freeze(hand1, MPRecoveryReason.CONFLICTING_FACT, "r-hand1")
        assertTrue(coordinator.complete(hand1, "r-hand1", "s-hand1", 1, targetSeat = 1))

        // Hand 2's very first recovery snapshot for the same target seat also starts at
        // version 1 — this must NOT be classified stale because of hand 1's entry.
        assertEquals(MPRetentionResult.RETAINED, coordinator.classifySnapshot(hand2, 1, targetSeat = 1))
        coordinator.freeze(hand2, MPRecoveryReason.CONFLICTING_FACT, "r-hand2")
        assertTrue(coordinator.complete(hand2, "r-hand2", "s-hand2", 1, targetSeat = 1))
        assertEquals(MPRetentionResult.STALE, coordinator.classifySnapshot(hand2, 1, targetSeat = 1))
    }
}
