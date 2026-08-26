package jmotley.com.jspades.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MPSemanticFactStoreTest {
	@Test fun strictPlayConflictRetainsFirstCardInSlot() {
		val store = MPSemanticFactStore()
		val key = MPSemanticKey(MPActionType.CARD_PLAY, 2, 4, trickNum = 3, trickPlayNum = 2)
		val first = MPNormalizedAction(MPActionType.CARD_PLAY, key, "first", 1, "p1", 2, 4,
			trickNum = 3, trickPlayNum = 2, payload = MPNormalizedPayload.CardPlay("10_2"))
		val changed = first.copy(cmdId = "changed", payload = MPNormalizedPayload.CardPlay("11_2"))
		assertEquals(MPRetentionResult.RETAINED, store.retain(first, strictConflicts = true))
		assertEquals(MPRetentionResult.CONFLICT, store.retain(changed, strictConflicts = true))
		assertEquals(first, store.fact(key))
	}

	@Test fun strictBidConflictRetainsFirstFact() {
		val store = MPSemanticFactStore()
		val key = MPSemanticKey(MPActionType.BID, 2, 4, 1)
		val first = MPNormalizedAction(MPActionType.BID, key, "first", 1, "p1", 2, 4,
			payload = MPNormalizedPayload.Bid(3, false, WireBidRole.INDIVIDUAL))
		val changed = first.copy(cmdId = "changed",
			payload = MPNormalizedPayload.Bid(5, false, WireBidRole.INDIVIDUAL))
		assertEquals(MPRetentionResult.RETAINED, store.retain(first, strictConflicts = true))
		assertEquals(MPRetentionResult.CONFLICT, store.retain(changed, strictConflicts = true))
		assertEquals(first, store.fact(key))
	}
    private fun bid(cmdId: String, amount: Int = 4) = MPNormalizedAction(
        type = MPActionType.BID,
        semanticKey = MPSemanticKey(MPActionType.BID, 2, 3, 1),
        cmdId = cmdId,
        senderSeat = 1,
        senderPlayerId = "p1",
        gameGeneration = 2,
        handNum = 3,
        payload = MPNormalizedPayload.Bid(amount, false, WireBidRole.INDIVIDUAL)
    )

    @Test
    fun identicalLocalAndRemoteFactsDeduplicateBySemanticKey() {
        val store = MPSemanticFactStore()
        val local = bid("local")
        val remoteRetry = bid("remote")

        assertEquals(MPRetentionResult.RETAINED, store.retain(local))
        assertEquals(MPRetentionResult.DUPLICATE, store.retain(remoteRetry))
        assertEquals(local, store.fact(local.semanticKey))
    }

    @Test
    fun strictConflictsRemainDisabledForSliceOne() {
        val store = MPSemanticFactStore()
        val first = bid("first", 3)
        val changed = bid("changed", 7)

        assertEquals(MPRetentionResult.RETAINED, store.retain(first))
        assertEquals(MPRetentionResult.RETAINED, store.retain(changed))
        assertEquals(changed, store.fact(first.semanticKey))
    }

    @Test
    fun exactCommandRetryNeverMutatesTheFactTwice() {
        val store = MPSemanticFactStore()
        val action = bid("same")

        assertEquals(MPRetentionResult.RETAINED, store.retain(action))
        assertEquals(MPRetentionResult.DUPLICATE, store.retain(action))
        assertEquals(action, store.fact(action.semanticKey))
    }

    @Test
    fun firstHandOfFutureGenerationIsNotComparedToOldGenerationHandCounter() {
        assertEquals(false, isMPSemanticallyStale(2, 1, 1, 8))
        assertEquals(true, isMPSemanticallyStale(1, 7, 1, 8))
        assertEquals(true, isMPSemanticallyStale(0, 99, 1, 8))
    }

    @Test
    fun stagedFactsCanBeDrainedAtTheirBarrier() {
        val store = MPSemanticFactStore()
        val action = bid("future")
        store.retain(action)
        store.stage(action)

        assertEquals(listOf(action), store.takePending { it.gameGeneration == 2 && it.handNum == 3 })
        assertEquals(null, store.pending(action.semanticKey))
    }
}
