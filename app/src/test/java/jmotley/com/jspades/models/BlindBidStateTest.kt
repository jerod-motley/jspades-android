package jmotley.com.jspades.models

import jmotley.com.jspades.data.Hand
import jmotley.com.jspades.data.PlayerHandState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlindBidStateTest {
    @Test fun `team blind seven updates player and team contract together`() {
        val hand = Hand(perPlayer = mapOf("south" to PlayerHandState()))
        val updated = hand.withAcceptedBlindBid("south", 0, 7, usesTeamContract = true)

        assertEquals(7, updated.perPlayer.getValue("south").bid)
        assertTrue(updated.perPlayer.getValue("south").isBlind)
        assertEquals(7, updated.teamBids[0])
        assertTrue(updated.teamBlind[0])
    }

    @Test fun `classic blind nil stays a per-player contract`() {
        val hand = Hand(perPlayer = mapOf("south" to PlayerHandState()))
        val updated = hand.withAcceptedBlindBid("south", 0, 0, usesTeamContract = false)

        assertEquals(0, updated.perPlayer.getValue("south").bid)
        assertTrue(updated.perPlayer.getValue("south").isBlind)
        assertEquals(0, updated.teamBids[0])
        assertFalse(updated.teamBlind[0])
    }
}
