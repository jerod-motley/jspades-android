package jmotley.com.jspades.data

enum class MPActionType { BLIND_OFFER, BLIND_RESPONSE, BLIND_PHASE_COMPLETE, BID, CARD_PLAY, READY_NEXT_HAND, PLAY_AGAIN_REQUEST }

data class MPSemanticKey(
    val actionType: MPActionType,
    val gameGeneration: Int,
    val handNum: Int,
    val seat: Int? = null,
    val trickNum: Int? = null,
    val trickPlayNum: Int? = null
)

sealed interface MPNormalizedPayload {
	data class BlindOffer(val teamSeats: List<Int>, val decidingSeats: List<Int>) : MPNormalizedPayload
	data class BlindResponse(val accepted: Boolean) : MPNormalizedPayload
	data object BlindPhaseComplete : MPNormalizedPayload
	data class Bid(val amount: Int, val isBlind: Boolean, val role: WireBidRole) : MPNormalizedPayload
	data class CardPlay(val cardId: String) : MPNormalizedPayload
	data object ReadyNextHand : MPNormalizedPayload
	data object PlayAgainRequest : MPNormalizedPayload
}

data class MPNormalizedAction(
    val type: MPActionType,
    val semanticKey: MPSemanticKey,
    val cmdId: String,
    val senderSeat: Int,
    val senderPlayerId: String,
    val gameGeneration: Int,
    val handNum: Int,
    val trickNum: Int? = null,
    val trickPlayNum: Int? = null,
    val payload: MPNormalizedPayload
)

enum class MPRetentionResult { RETAINED, DUPLICATE, STALE, CONFLICT, REJECTED }

internal fun isMPSemanticallyStale(
    actionGeneration: Int,
    actionHand: Int,
    currentGeneration: Int,
    currentHand: Int
): Boolean = actionGeneration < currentGeneration ||
    (actionGeneration == currentGeneration && actionHand < currentHand)

/**
 * Slice-1 storage. Strict semantic conflicts intentionally remain disabled until the
 * recovery gate exists; in that mode a changed same-key fact is retained as legacy traffic.
 */
class MPSemanticFactStore {
    private val factsByKey = linkedMapOf<MPSemanticKey, MPNormalizedAction>()
    private val resultsByCommand = mutableMapOf<String, MPRetentionResult>()
    private val pendingByKey = linkedMapOf<MPSemanticKey, MPNormalizedAction>()

    fun retain(action: MPNormalizedAction, strictConflicts: Boolean = false): MPRetentionResult {
        resultsByCommand[action.cmdId]?.let {
            return if (it == MPRetentionResult.RETAINED || it == MPRetentionResult.DUPLICATE) {
                MPRetentionResult.DUPLICATE
            } else it
        }
        val existing = factsByKey[action.semanticKey]
        val result = when {
            existing == null -> MPRetentionResult.RETAINED
            existing.payload == action.payload -> MPRetentionResult.DUPLICATE
            strictConflicts -> MPRetentionResult.CONFLICT
            else -> MPRetentionResult.RETAINED
        }
        resultsByCommand[action.cmdId] = result
        if (result == MPRetentionResult.RETAINED) factsByKey[action.semanticKey] = action
        return result
    }

    fun stage(action: MPNormalizedAction) {
        pendingByKey[action.semanticKey] = action
    }

    /**
     * Correct the store after [retain] speculatively marked [action] RETAINED (and cached its
     * fact/cmdId) but downstream validation — seat, ownership, or legality — determined it must
     * not be applied. Without this, a retry reusing the same cmdId would hit the cached RETAINED
     * result and return DUPLICATE, silently acknowledging a play that was never actually applied.
     * Overwrites the cached command result with the true terminal outcome and frees the semantic
     * key so a legitimate corrected play can still land in that slot.
     */
    fun invalidate(action: MPNormalizedAction, result: MPRetentionResult) {
        resultsByCommand[action.cmdId] = result
        if (factsByKey[action.semanticKey]?.cmdId == action.cmdId) factsByKey.remove(action.semanticKey)
    }

    fun fact(key: MPSemanticKey): MPNormalizedAction? = factsByKey[key]
    fun facts(predicate: (MPNormalizedAction) -> Boolean): List<MPNormalizedAction> = factsByKey.values.filter(predicate)
    fun pending(key: MPSemanticKey): MPNormalizedAction? = pendingByKey[key]
    fun takePending(predicate: (MPNormalizedAction) -> Boolean): List<MPNormalizedAction> {
        val ready = pendingByKey.values.filter(predicate).sortedWith(
            compareBy<MPNormalizedAction> { it.handNum }
                .thenBy { it.trickNum ?: 0 }
                .thenBy { it.trickPlayNum ?: 0 }
        )
        ready.forEach { pendingByKey.remove(it.semanticKey) }
        return ready
    }
    fun clear() {
        factsByKey.clear()
        resultsByCommand.clear()
        pendingByKey.clear()
    }
}
