package jmotley.com.jspades.engine

import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.data.Player
import jmotley.com.jspades.data.PlayerType
import jmotley.com.jspades.data.Rank
import jmotley.com.jspades.data.RuntimeFlags
import jmotley.com.jspades.data.Score
import jmotley.com.jspades.data.Suit
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the "Android player was not offered Blind 7" bug: eligibility
 * must trigger at exactly a 100-point deficit, for both team and solo games, independent
 * of player type — the same [BidEngine.isBlindEligible] formula now backs the human-seat
 * check in `PhaseManager.handleBlindBid()` and the CPU check in [BidEngine.shouldCpuBidBlind].
 */
class BidEngineTest {

    private fun player(id: String, team: Int) = Player(
        id = id, name = id, team = team, playerType = PlayerType.HUMAN,
        runtimeFlags = RuntimeFlags(seatIndex = if (team == 0) 0 else 1)
    )

    private fun teamState(myPoints: Int, oppPoints: Int): GameState = GameState(
        players = listOf(player("south", 0), player("west", 1), player("north", 0), player("east", 1)),
        gameType = GameType.HOUSE_RULES,
        score = Score(points = mapOf("0" to myPoints, "1" to oppPoints))
    )

    private fun soloState(myPoints: Int, others: List<Int>): GameState {
        val ids = listOf("south", "west", "north")
        val players = ids.mapIndexed { i, id -> player(id, team = 0).copy(playerType = PlayerType.HUMAN, runtimeFlags = RuntimeFlags(seatIndex = i)) }
        val points = mutableMapOf(ids[0] to myPoints)
        others.forEachIndexed { i, pts -> points[ids[i + 1]] = pts }
        return GameState(
            players = players,
            gameType = GameType.SOLO_THREE_MAN,
            score = Score(points = points)
        )
    }

    // ── Team eligibility boundary ──────────────────────────────────────────────

    @Test
    fun `team is not eligible at a 99-point deficit`() {
        val state = teamState(myPoints = 0, oppPoints = 99)
        val south = state.players.first { it.id == "south" }
        assertFalse(BidEngine.isBlindEligible(state, south))
    }

    @Test
    fun `team is eligible at exactly a 100-point deficit`() {
        val state = teamState(myPoints = 0, oppPoints = 100)
        val south = state.players.first { it.id == "south" }
        assertTrue(BidEngine.isBlindEligible(state, south))
    }

    @Test
    fun `team is eligible beyond a 100-point deficit`() {
        val state = teamState(myPoints = -50, oppPoints = 200)
        val south = state.players.first { it.id == "south" }
        assertTrue(BidEngine.isBlindEligible(state, south))
    }

    @Test
    fun `both teammates share eligibility`() {
        val state = teamState(myPoints = 0, oppPoints = 100)
        val north = state.players.first { it.id == "north" }
        assertTrue(BidEngine.isBlindEligible(state, north))
    }

    @Test
    fun `leading team is never eligible`() {
        val state = teamState(myPoints = 100, oppPoints = 0)
        val south = state.players.first { it.id == "south" }
        assertFalse(BidEngine.isBlindEligible(state, south))
    }

    // ── Solo eligibility boundary ──────────────────────────────────────────────

    @Test
    fun `solo player is not eligible at a 99-point deficit to the leader`() {
        val state = soloState(myPoints = 0, others = listOf(99, 40))
        val south = state.players.first { it.id == "south" }
        assertFalse(BidEngine.isBlindEligible(state, south))
    }

    @Test
    fun `solo player is eligible at exactly a 100-point deficit to the leader`() {
        val state = soloState(myPoints = 0, others = listOf(100, 40))
        val south = state.players.first { it.id == "south" }
        assertTrue(BidEngine.isBlindEligible(state, south))
    }

    // ── shouldCpuBidBlind: deterministic via an injected Random, no statistical trials ──

    private val nilHand = listOf(Card(Suit.CLUBS, Rank.TWO))

    /** A fake [Random] whose every draw deterministically lands `nextInt(3) == 0` (the "goes blind" branch). */
    private val alwaysBlindRandom = object : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextInt(until: Int): Int = 0
    }

    /** A fake [Random] whose every draw deterministically misses `nextInt(3) == 0` (the "stays put" branch). */
    private val neverBlindRandom = object : Random() {
        override fun nextBits(bitCount: Int): Int = 1
        override fun nextInt(until: Int): Int = 1
    }

    @Test
    fun `shouldCpuBidBlind is always null when ineligible, even with a Random that would otherwise go blind`() {
        val state = teamState(myPoints = 0, oppPoints = 99)
        val south = state.players.first { it.id == "south" }
        assertNull(BidEngine.shouldCpuBidBlind(nilHand, south, state, random = alwaysBlindRandom))
    }

    @Test
    fun `shouldCpuBidBlind goes blind when eligible and the 33% draw hits`() {
        val state = teamState(myPoints = 0, oppPoints = 100)
        val south = state.players.first { it.id == "south" }
        val result = BidEngine.shouldCpuBidBlind(nilHand, south, state, random = alwaysBlindRandom)
        assertNotNull(result)
        assertEquals(7, result!!.bid)
        assertTrue(result.isBlind)
    }

    @Test
    fun `shouldCpuBidBlind stays put when eligible but the 33% draw misses`() {
        val state = teamState(myPoints = 0, oppPoints = 100)
        val south = state.players.first { it.id == "south" }
        assertNull(BidEngine.shouldCpuBidBlind(nilHand, south, state, random = neverBlindRandom))
    }

    @Test
    fun `shouldCpuBidBlind uses bid 0 for TEAM_CLASSIC and SOLO_FOUR_MAN when it goes blind`() {
        val classicState = teamState(myPoints = 0, oppPoints = 100).copy(gameType = GameType.TEAM_CLASSIC)
        val south = classicState.players.first { it.id == "south" }
        val result = BidEngine.shouldCpuBidBlind(nilHand, south, classicState, random = alwaysBlindRandom)
        assertEquals(0, result!!.bid)

        val fourManState = soloState(myPoints = 0, others = listOf(100, 40)).copy(gameType = GameType.SOLO_FOUR_MAN)
        val fourManSouth = fourManState.players.first { it.id == "south" }
        val fourManResult = BidEngine.shouldCpuBidBlind(nilHand, fourManSouth, fourManState, random = alwaysBlindRandom)
        assertEquals(0, fourManResult!!.bid)
    }

    @Test
    fun `shouldCpuBidBlind uses bid 7 for other game types when it goes blind`() {
        val state = teamState(myPoints = 0, oppPoints = 100) // HOUSE_RULES
        val south = state.players.first { it.id == "south" }
        val result = BidEngine.shouldCpuBidBlind(nilHand, south, state, random = alwaysBlindRandom)
        assertEquals(7, result!!.bid)
    }
}
