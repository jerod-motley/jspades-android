package jmotley.com.jspades.data

import kotlinx.serialization.Serializable

/**
 * Content of [StateSnapshotMessage.logicalState] for a bid-conflict recovery snapshot.
 *
 * Deliberately scoped to bid facts only — no cards, tricks, or kitty state. A bid conflict can
 * only occur before any card has been played in that hand, so the receiver's remaining hands are
 * already correct from the original `deal` message; a general game-state snapshot (Slice 2/4) is
 * a separate, larger piece of work.
 */
@Serializable
data class MPBidFactDto(
    val seat: Int,
    val amount: Int,
    val isBlind: Boolean,
    val role: WireBidRole,
    val cmdId: String
)

@Serializable
data class MPBidRecoverySnapshotPayload(
    val gameGeneration: Int,
    val handNum: Int,
    /** Diagnostic only — the host's [jmotley.com.jspades.data.GamePhase] name at build time.
     * The receiver must never treat this as authoritative input to advance its own phase: a
     * delayed snapshot could otherwise move a client backward or skip a presentation
     * prerequisite. The receiver always derives its post-recovery phase itself from the
     * (now-authoritative) bid fact count, the same way a live bid does. */
    val logicalPhase: String,
    val bids: List<MPBidFactDto>
)
