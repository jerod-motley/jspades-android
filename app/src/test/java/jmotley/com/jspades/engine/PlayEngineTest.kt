package jmotley.com.jspades.engine

import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.GamePhase
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.data.Hand
import jmotley.com.jspades.data.Play
import jmotley.com.jspades.data.Player
import jmotley.com.jspades.data.PlayerHandState
import jmotley.com.jspades.data.PlayerType
import jmotley.com.jspades.data.Rank
import jmotley.com.jspades.data.RuntimeFlags
import jmotley.com.jspades.data.Suit
import jmotley.com.jspades.data.Trick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression coverage for the room V5ZWV4 hand 4 bug: a CPU teammate overtrumping
 * its own partner's already-winning trump card in a House Rules (team-bid) game,
 * because an unset individual bid was misread as an explicit nil to rescue.
 *
 * Every state below gives north (the acting CPU) and its own team a real, unmet
 * team bid, and gives the opposing team a bid they have NOT yet met either — this
 * keeps [PlayEngine] in winner mode (the branch the reported bug lives in) rather
 * than falling into loser/loser-high mode, which would exercise unrelated code.
 */
class PlayEngineTest {

    private fun player(id: String, team: Int, seatIndex: Int) = Player(
        id = id,
        name = id,
        team = team,
        playerType = PlayerType.CPU,
        runtimeFlags = RuntimeFlags(seatIndex = seatIndex)
    )

    // Canonical south/west/north/east seats: south+north are team 0, west+east are team 1.
    private val south = player("south", team = 0, seatIndex = 0)
    private val west  = player("west",  team = 1, seatIndex = 1)
    private val north = player("north", team = 0, seatIndex = 2)
    private val east  = player("east",  team = 1, seatIndex = 3)

    private fun trick6Plays() = listOf(
        Play("east", Card(Suit.CLUBS, Rank.EIGHT)),
        Play("south", Card(Suit.SPADES, Rank.SEVEN)),
        Play("west", Card(Suit.CLUBS, Rank.TEN)),
        null
    )

    private fun state(
        gameType: GameType,
        southBid: Int,
        southBidPlaced: Boolean,
        southTricksWon: Int,
        northHand: List<Card>,
        plays: List<Play?>
    ): GameState {
        val perPlayer = mapOf(
            "south" to PlayerHandState(bid = southBid, bidPlaced = southBidPlaced, tricksWon = southTricksWon),
            "west"  to PlayerHandState(bid = 3, bidPlaced = true, tricksWon = 0),
            "north" to PlayerHandState(bid = 3, bidPlaced = true, tricksWon = 0, hand = northHand),
            "east"  to PlayerHandState(bid = 3, bidPlaced = true, tricksWon = 0)
        )
        return GameState(
            players = listOf(south, west, north, east),
            gameType = gameType,
            currentTrick = Trick(plays = plays),
            // Both teams still owe tricks toward their bid, and the opposing team hasn't
            // met theirs either — this is what keeps PlayEngine in winner mode.
            phaseHands = mapOf(
                GamePhase.Deal to listOf(Hand(perPlayer = perPlayer, teamBids = listOf(6, 6)))
            )
        )
    }

    /** Trick 6: east leads 8♣, south cuts with 7♠ and is winning, west follows with 10♣. */
    @Test
    fun `House Rules CPU throws off instead of overtrumping a trump-winning teammate — trick 6`() {
        val northHand = listOf(
            Card(Suit.SPADES, Rank.NINE), // the card that was wrongly played (9♠ overtrump)
            Card(Suit.HEARTS, Rank.QUEEN) // the legal non-trump throw-off (Q♥, led on trick 7)
        )
        val s = state(
            gameType = GameType.HOUSE_RULES,
            southBid = 0, southBidPlaced = false, southTricksWon = 0, // unset individual bid
            northHand = northHand,
            plays = trick6Plays()
        )

        val played = PlayEngine.selectCard("north", s)

        assertEquals(Card(Suit.HEARTS, Rank.QUEEN), played)
        assertNotEquals(Card(Suit.SPADES, Rank.NINE), played)
    }

    /** Trick 8: same shape, south winning with J♠ instead of 7♠. */
    @Test
    fun `House Rules CPU throws off instead of overtrumping a trump-winning teammate — trick 8`() {
        val northHand = listOf(
            Card(Suit.SPADES, Rank.QUEEN), // the card that was wrongly played (Q♠ overtrump)
            Card(Suit.DIAMONDS, Rank.NINE) // a legal non-trump throw-off
        )
        val plays = listOf(
            Play("east", Card(Suit.CLUBS, Rank.FOUR)),
            Play("south", Card(Suit.SPADES, Rank.JACK)),
            Play("west", Card(Suit.CLUBS, Rank.KING)),
            null
        )
        val s = state(
            gameType = GameType.HOUSE_RULES,
            southBid = 0, southBidPlaced = false, southTricksWon = 0,
            northHand = northHand,
            plays = plays
        )

        val played = PlayEngine.selectCard("north", s)

        assertNotEquals(Card(Suit.SPADES, Rank.QUEEN), played)
        assertEquals(false, PlayEngine.isTrump(played))
    }

    /**
     * Even if a House Rules teammate's individual bid happens to have been explicitly
     * recorded as 0 (e.g. auto-filled after the team went blind), it still isn't a real
     * nil declaration in this game type — the throw-off behavior must not depend on
     * [PlayerHandState.bidPlaced] here, only on game type.
     */
    @Test
    fun `House Rules ignores teammate bid of 0 even when explicitly placed`() {
        val northHand = listOf(Card(Suit.SPADES, Rank.NINE), Card(Suit.HEARTS, Rank.QUEEN))
        val s = state(
            gameType = GameType.HOUSE_RULES,
            southBid = 0, southBidPlaced = true, southTricksWon = 0, // explicitly placed, still not a real nil
            northHand = northHand,
            plays = trick6Plays()
        )

        val played = PlayEngine.selectCard("north", s)

        assertEquals(Card(Suit.HEARTS, Rank.QUEEN), played)
    }

    /** Classic mode: an intact, explicitly-placed nil bid is still correctly rescued. */
    @Test
    fun `Classic mode still rescues an intact nil-bidding teammate`() {
        val northHand = listOf(Card(Suit.SPADES, Rank.NINE), Card(Suit.HEARTS, Rank.QUEEN))
        val s = state(
            gameType = GameType.TEAM_CLASSIC,
            southBid = 0, southBidPlaced = true, southTricksWon = 0,
            northHand = northHand,
            plays = trick6Plays()
        )

        val played = PlayEngine.selectCard("north", s)

        // South's nil is intact and winning — north must cut to protect it, not throw off.
        assertEquals(Card(Suit.SPADES, Rank.NINE), played)
    }

    /** Classic mode: once the nil bidder has already taken a trick, the nil is broken — no rescue. */
    @Test
    fun `Classic mode does not rescue an already-broken nil`() {
        val northHand = listOf(Card(Suit.SPADES, Rank.NINE), Card(Suit.HEARTS, Rank.QUEEN))
        val s = state(
            gameType = GameType.TEAM_CLASSIC,
            southBid = 0, southBidPlaced = true, southTricksWon = 1, // nil already broken
            northHand = northHand,
            plays = trick6Plays()
        )

        val played = PlayEngine.selectCard("north", s)

        // South's nil is already broken — south winning with trump should be thrown off, not cut higher.
        assertEquals(Card(Suit.HEARTS, Rank.QUEEN), played)
    }
}
