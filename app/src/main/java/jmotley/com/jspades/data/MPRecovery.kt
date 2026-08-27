package jmotley.com.jspades.data

import java.util.UUID

enum class MPRecoveryScopeKind { GENERATION, HAND }
enum class MPRecoveryReason { CONFLICTING_FACT, IMPOSSIBLE_PROGRESS, DELIVERY_TIMEOUT, RECONNECT_DIVERGENCE }

data class MPRecoveryScope(val gameGeneration: Int, val kind: MPRecoveryScopeKind, val handNum: Int? = null) {
    init { require((kind == MPRecoveryScopeKind.HAND) == (handNum != null)) }
}

data class MPRecoveryGate(
    val scope: MPRecoveryScope,
    val reason: MPRecoveryReason,
    val requestId: String? = null,
    val snapshotId: String? = null,
    val frozenAtMillis: Long = System.currentTimeMillis(),
    val recoveryRound: Int = 1
)

/** Serialized recovery state. Old attempts can never clear a newer gate. */
class MPRecoveryCoordinator(private val maxRounds: Int = 3) {
    private val gates = linkedMapOf<MPRecoveryScope, MPRecoveryGate>()
    private val appliedSnapshots = mutableSetOf<Triple<Int, Long, Int>>()
    private val latestSnapshotVersion = mutableMapOf<MPRecoveryScope, Long>()

    fun currentGate(): MPRecoveryGate? = gates.values.lastOrNull()
    fun gate(scope: MPRecoveryScope): MPRecoveryGate? = gates[scope]
    fun isFrozen(generation: Int, handNum: Int?): Boolean = gates.keys.any {
        it.gameGeneration == generation && (it.kind == MPRecoveryScopeKind.GENERATION || it.handNum == handNum)
    }

    fun freeze(scope: MPRecoveryScope, reason: MPRecoveryReason, requestId: String? = UUID.randomUUID().toString()): MPRecoveryGate {
        gates[scope]?.let { return it }
        return MPRecoveryGate(scope, reason, requestId = requestId).also { gates[scope] = it }
    }

    fun nextRound(scope: MPRecoveryScope, expectedRequestId: String?): MPRecoveryGate? {
        val current = gates[scope] ?: return null
        if (current.requestId != expectedRequestId || current.recoveryRound >= maxRounds) return null
        return current.copy(recoveryRound = current.recoveryRound + 1, frozenAtMillis = System.currentTimeMillis()).also { gates[scope] = it }
    }

    fun classifySnapshot(scope: MPRecoveryScope, snapshotVersion: Long, targetSeat: Int): MPRetentionResult {
        val identity = Triple(scope.gameGeneration, snapshotVersion, targetSeat)
        if (identity in appliedSnapshots || snapshotVersion <= (latestSnapshotVersion[scope] ?: -1)) return MPRetentionResult.STALE
        return MPRetentionResult.RETAINED
    }

    fun complete(scope: MPRecoveryScope, requestId: String?, snapshotId: String, snapshotVersion: Long, targetSeat: Int): Boolean {
        val current = gates[scope] ?: return false
        if (current.requestId != requestId) return false
        recordAppliedSnapshot(scope, snapshotVersion, targetSeat)
        gates.remove(scope)
        return true
    }

    /** Record [snapshotVersion] as applied for [scope]/[targetSeat] without requiring (or
     * clearing) an existing gate. Used for a host-initiated correction the target never
     * requested — there is no local gate to correlate or clear, but the version/idempotency
     * bookkeeping must still be recorded so a retried delivery of the same snapshot is
     * classified `STALE` instead of being reapplied on every retry. */
    fun recordAppliedSnapshot(scope: MPRecoveryScope, snapshotVersion: Long, targetSeat: Int) {
        appliedSnapshots += Triple(scope.gameGeneration, snapshotVersion, targetSeat)
        latestSnapshotVersion[scope] = snapshotVersion
    }
}
