package jmotley.com.jspades

import jmotley.com.jspades.models.computeInitialLeaderIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class MultiplayerSeatRotationTest {
	@Test
	fun guestInSeatOne_bidsFirstLocally() {
		assertEquals(0, computeInitialLeaderIndex(playerCount = 4, localSeatIndex = 1, isMultiplayer = true))
	}

	@Test
	fun guestInSeatZero_keepsWestAsFirstBidder() {
		assertEquals(1, computeInitialLeaderIndex(playerCount = 4, localSeatIndex = 0, isMultiplayer = true))
	}

	@Test
	fun localGames_keepWestAsFirstBidder() {
		assertEquals(1, computeInitialLeaderIndex(playerCount = 4, localSeatIndex = -1, isMultiplayer = false))
	}
}