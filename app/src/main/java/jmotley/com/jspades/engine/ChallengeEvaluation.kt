package jmotley.com.jspades.engine

import android.content.Context
import jmotley.com.jspades.data.AchievementsRepo
import jmotley.com.jspades.data.GamePhase
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.Play
import jmotley.com.jspades.data.Rank
import jmotley.com.jspades.data.ReplayEvent
import jmotley.com.jspades.data.Suit

/** Result of evaluating a challenge constraint. */
data class ChallengeResult(val sk: String, val success: Boolean, val reason: String? = null)

/**
 * Pure challenge evaluation logic.
 *
 * [evaluatePerTrick] — called from PhaseManager.handleTrickResolve() immediately after
 * awardTrick(), before collectTrick(). Fires early-failure results as soon as a
 * per-trick constraint is violated.
 *
 * [evaluatePerHand] — called from PhaseManager.handleScore() before finalizeHandReplay().
 * Performs final success/failure determination for the completed hand.
 */
object ChallengeEvaluation {

    // ── BidRule parser ────────────────────────────────────────────────────────

    private data class BidRule(
        val allTricks: Boolean = false,
        val minBid: Int? = null,
        val isNil: Boolean = false,
        val isBlindNil: Boolean = false,
        val minBooks: Int? = null,
        val noTrump: Int? = null
    )

    private fun parseBidRule(winCriteria: String?): BidRule {
        if (winCriteria == null) return BidRule()
        return when {
            winCriteria == "AllTricks"  -> BidRule(allTricks = true)
            winCriteria == "Nil"        -> BidRule(isNil = true)
            winCriteria == "BlindNil"   -> BidRule(isBlindNil = true)
            winCriteria.startsWith("MinBid:")   -> BidRule(minBid = winCriteria.substringAfter(":").toIntOrNull())
            winCriteria.startsWith("MinBooks:") -> BidRule(minBooks = winCriteria.substringAfter(":").toIntOrNull())
            winCriteria.startsWith("NoTrump:")  -> BidRule(noTrump = winCriteria.substringAfter(":").toIntOrNull())
            else -> BidRule()
        }
    }

    // ── Per-trick evaluation ──────────────────────────────────────────────────

    /**
     * Called after awardTrick() and before collectTrick().
     * [state.currentTrick] still has all plays for this trick.
     */
    fun evaluatePerTrick(state: GameState, context: Context): List<ChallengeResult> {
        val activeSk = AchievementsRepo.getActiveChallenge(context) ?: return emptyList()
        val plays    = state.currentTrick.plays.filterNotNull()
        if (plays.isEmpty()) return emptyList()

        val winnerPlay = PlayEngine.computeTrickWinner(state.currentTrick.plays)
        val winnerId   = winnerPlay.playerId
        val winnerCard = plays.first { it.playerId == winnerId }.card

        // Human team identification
        val humanTeam   = state.players.find { it.id == "south" }?.team ?: 0
        val humanTeamIds = state.players.filter { it.team == humanTeam }.map { it.id }
        val oppTeamIds   = state.players.filter { it.team != humanTeam }.map { it.id }

        fun fail(reason: String) = listOf(ChallengeResult(activeSk, false, reason))

        return when (activeSk) {

            "walk_all_kings", "all_kings_walk" -> {
                val kingPlayed = plays.any { it.card.rank == Rank.KING }
                val kingWon    = winnerCard.rank == Rank.KING
                if (kingPlayed && !kingWon) fail("A king was played but did not win the trick") else emptyList()
            }

            "no_aces_team" -> {
                // Human holds all aces via deal bias; only fail if an *opponent's* ace walks
                val oppAceWon = winnerId in oppTeamIds && winnerCard.rank == Rank.ACE
                if (oppAceWon) fail("An opponent's ace walked") else emptyList()
            }

            "get_all_books_kitty", "run_boston", "kitty_boston" -> {
                if (winnerId in oppTeamIds) fail("Opponent won a trick") else emptyList()
            }

            "no_aces_kitty" -> {
                val opponentAceWon = winnerId in oppTeamIds && winnerCard.rank == Rank.ACE
                if (opponentAceWon) fail("An opponent's ace walked") else emptyList()
            }

            "by_yourself_kitty", "run_boston_alone", "three_man_board", "two_man_board" -> {
                if (winnerId != "south") fail("Someone other than south won a trick") else emptyList()
            }

            "get_all_two_solo" -> {
                if (winnerId != "south") fail("Someone other than south won a trick") else emptyList()
            }

            "walk_4_ladies" -> {
                val queenPlayed = plays.any { it.card.rank == Rank.QUEEN }
                val queenWon    = winnerCard.rank == Rank.QUEEN
                if (queenPlayed && !queenWon) fail("A queen was played but did not win the trick") else emptyList()
            }

            "run_boston_partner_bid" -> {
                if (winnerId != "south" && winnerId != "north") fail("Neither south nor north won the trick") else emptyList()
            }

            "boston_no_jokers" -> {
                val jokerWon = winnerCard.rank == Rank.LITTLEJOKER || winnerCard.rank == Rank.BIGJOKER
                if (winnerId in oppTeamIds) fail("Opponent won a trick") else
                if (jokerWon) fail("A joker won a trick") else emptyList()
            }

            "nil_success", "blind_nil" -> {
                if (winnerId == "south") fail("South won a trick (nil violated)") else emptyList()
            }

            "nil_partner_boston" -> {
                if (winnerId == "south") fail("South won a trick (nil violated)") else emptyList()
            }

            "all_aces_walk" -> {
                val acePlayed = plays.any { it.card.rank == Rank.ACE }
                val aceWon    = winnerCard.rank == Rank.ACE
                if (acePlayed && !aceWon) fail("An ace was played but did not win the trick") else emptyList()
            }

            "no_jokers" -> {
                val jokerWon = winnerCard.rank == Rank.LITTLEJOKER || winnerCard.rank == Rank.BIGJOKER
                if (jokerWon) fail("A joker won a trick") else emptyList()
            }

            "no_red_wins" -> {
                val redWon = winnerCard.suit == Suit.HEARTS || winnerCard.suit == Suit.DIAMONDS
                if (redWon) fail("A red card won the trick") else emptyList()
            }

            else -> emptyList() // evaluated at hand end only
        }
    }

    // ── Per-hand evaluation ───────────────────────────────────────────────────

    /**
     * Called from handleScore() before finalizeHandReplay(), so [state.replayEvents] is intact.
     * Returns an empty list if no active challenge or the challenge fired a per-trick failure
     * already (per-trick failures are emitted immediately; per-hand only fires on success here).
     */
    fun evaluatePerHand(state: GameState, context: Context): List<ChallengeResult> {
        val activeSk  = AchievementsRepo.getActiveChallenge(context) ?: return emptyList()
        val bidRule   = parseBidRule(AchievementsRepo.getActiveChallengeBidRule(context))
        val hand      = state.phaseHands[GamePhase.Deal]?.lastOrNull() ?: return emptyList()
        val southPhs  = hand.perPlayer["south"] ?: return emptyList()

        val humanTeam    = state.players.find { it.id == "south" }?.team ?: 0
        val humanTeamIds = state.players.filter { it.team == humanTeam }.map { it.id }
        val oppTeamIds   = state.players.filter { it.team != humanTeam }.map { it.id }

        val humanTeamTricks = humanTeamIds.sumOf { hand.perPlayer[it]?.tricksWon ?: 0 }
        val totalTricks     = state.gameType.cardsPerPlayer

        fun success()              = listOf(ChallengeResult(activeSk, true))
        fun fail(reason: String)   = listOf(ChallengeResult(activeSk, false, reason))

        // Reconstruct trick-by-trick history from replayEvents for NoTrump counting
        val trickHistory = reconstructTrickHistory(state)

        return when (activeSk) {

            // ── House Rules ───────────────────────────────────────────────────

            "walk_all_kings" -> {
                // Per-trick handles early failures; if we reach here, all kings walked
                success()
            }

            "get_x_books" -> {
                val x = bidRule.minBooks ?: return emptyList()
                if (humanTeamTricks >= x) success() else fail("Team won $humanTeamTricks books, needed $x")
            }

            "no_aces_team" -> {
                // Per-trick fires immediately if ace walked; reaching here means success
                // Also require team made their bid
                val teamBid = hand.teamBids.getOrNull(humanTeam) ?: 0
                if (humanTeamTricks >= teamBid) success() else fail("Team did not make their bid")
            }

            // ── Kitty ─────────────────────────────────────────────────────────

            "get_all_books_kitty" -> {
                if (humanTeamTricks == totalTricks) success() else fail("Team won $humanTeamTricks/$totalTricks tricks")
            }

            "no_aces_kitty" -> {
                // Per-trick fires if opponent ace walked; reaching here means success
                success()
            }

            "by_yourself_kitty" -> {
                if (southPhs.tricksWon == totalTricks) success()
                else fail("South won ${southPhs.tricksWon}/$totalTricks tricks")
            }

            // ── Classic ───────────────────────────────────────────────────────

            "get_4_no_trump_classic" -> {
                val noTrumpWins = countNoTrumpWins("south", trickHistory)
                val target = bidRule.noTrump ?: 4
                if (noTrumpWins >= target) success() else fail("South won $noTrumpWins no-trump tricks, needed $target")
            }

            "get_x_books_classic" -> {
                val x = bidRule.minBooks ?: return emptyList()
                if (humanTeamTricks >= x) success() else fail("Team won $humanTeamTricks books, needed $x")
            }

            "double_bid_classic" -> {
                val oppBid = hand.teamBids.getOrNull(1 - humanTeam) ?: 0
                val oppTricks = oppTeamIds.sumOf { hand.perPlayer[it]?.tricksWon ?: 0 }
                if (oppTricks >= oppBid * 2) success()
                else fail("Opponents won $oppTricks tricks, needed ${oppBid * 2} (double their bid of $oppBid)")
            }

            // ── Four Man Solo ─────────────────────────────────────────────────

            "get_x_books_four_solo" -> {
                val x = bidRule.minBooks ?: return emptyList()
                if (southPhs.tricksWon >= x) success() else fail("South won ${southPhs.tricksWon} books, needed $x")
            }

            "set_two_four_solo" -> {
                val setCount = state.players.filter { it.id != "south" }.count { p ->
                    val phs = hand.perPlayer[p.id] ?: return@count false
                    phs.bid > 0 && phs.tricksWon < phs.bid
                }
                if (setCount >= 2) success() else fail("Only $setCount opponents were set (needed 2)")
            }

            "get_4_no_trump_four_solo" -> {
                val noTrumpWins = countNoTrumpWins("south", trickHistory)
                val target = bidRule.noTrump ?: 4
                if (noTrumpWins >= target) success() else fail("South won $noTrumpWins no-trump tricks, needed $target")
            }

            // ── Three Man Solo ────────────────────────────────────────────────

            "get_5_no_trump_three_solo" -> {
                val noTrumpWins = countNoTrumpWins("south", trickHistory)
                val target = bidRule.noTrump ?: 5
                if (noTrumpWins >= target) success() else fail("South won $noTrumpWins no-trump tricks, needed $target")
            }

            "get_x_books_three_solo" -> {
                val x = bidRule.minBooks ?: return emptyList()
                if (southPhs.tricksWon >= x) success() else fail("South won ${southPhs.tricksWon} books, needed $x")
            }

            "set_two_three_solo" -> {
                val setCount = state.players.filter { it.id != "south" }.count { p ->
                    val phs = hand.perPlayer[p.id] ?: return@count false
                    phs.bid > 0 && phs.tricksWon < phs.bid
                }
                if (setCount >= 2) success() else fail("Only $setCount opponents were set (needed 2)")
            }

            // ── Two Man Solo ──────────────────────────────────────────────────

            "get_all_two_solo" -> {
                if (southPhs.tricksWon == totalTricks) success()
                else fail("South won ${southPhs.tricksWon}/$totalTricks tricks")
            }

            "walk_4_ladies" -> {
                // Per-trick fires if a queen didn't walk; reaching here means success
                success()
            }

            "get_5_no_trump_two_solo" -> {
                val noTrumpWins = countNoTrumpWins("south", trickHistory)
                val target = bidRule.noTrump ?: 5
                if (noTrumpWins >= target) success() else fail("South won $noTrumpWins no-trump tricks, needed $target")
            }

            // ── Boston / AllTricks ────────────────────────────────────────────

            "run_boston" -> {
                if (humanTeamTricks == totalTricks) success()
                else fail("Team won $humanTeamTricks/$totalTricks tricks")
            }

            "run_boston_alone" -> {
                if (southPhs.tricksWon == totalTricks) success()
                else fail("South won ${southPhs.tricksWon}/$totalTricks tricks")
            }

            "run_boston_partner_bid" -> {
                val northBid = hand.perPlayer["north"]?.bid ?: 0
                if (humanTeamTricks == totalTricks && northBid > 0) success()
                else if (northBid == 0) fail("North did not hold the bid")
                else fail("Team won $humanTeamTricks/$totalTricks tricks")
            }

            "boston_no_jokers" -> {
                // Per-trick fires if joker won or opponent won; reaching here means success
                if (humanTeamTricks == totalTricks) success()
                else fail("Team won $humanTeamTricks/$totalTricks tricks")
            }

            "kitty_boston" -> {
                if (humanTeamTricks == totalTricks) success()
                else fail("Team won $humanTeamTricks/$totalTricks tricks")
            }

            "three_man_board" -> {
                if (southPhs.tricksWon == totalTricks) success()
                else fail("South won ${southPhs.tricksWon}/$totalTricks tricks")
            }

            "two_man_board" -> {
                if (southPhs.tricksWon == totalTricks) success()
                else fail("South won ${southPhs.tricksWon}/$totalTricks tricks")
            }

            // ── Nil challenges ────────────────────────────────────────────────

            "nil_success" -> {
                if (southPhs.tricksWon == 0) success() else fail("South took ${southPhs.tricksWon} tricks")
            }

            "blind_nil" -> {
                if (southPhs.tricksWon == 0 && southPhs.isBlind) success()
                else if (!southPhs.isBlind) fail("Bid was not blind")
                else fail("South took ${southPhs.tricksWon} tricks")
            }

            "nil_partner_boston" -> {
                val northTricks = hand.perPlayer["north"]?.tricksWon ?: 0
                val remaining   = totalTricks - 1  // south takes 0, north should take the rest (for 2-player partnership)
                if (southPhs.tricksWon == 0 && northTricks == remaining) success()
                else if (southPhs.tricksWon > 0) fail("South took ${southPhs.tricksWon} tricks")
                else fail("North won $northTricks tricks (needed $remaining)")
            }

            // ── Bid / count challenges ────────────────────────────────────────

            "big_bid_10" -> {
                val minBid = bidRule.minBid ?: 10
                if (southPhs.bid >= minBid && southPhs.tricksWon >= southPhs.bid) success()
                else if (southPhs.bid < minBid) fail("South bid ${southPhs.bid} (needed $minBid+)")
                else fail("South was set (bid ${southPhs.bid}, won ${southPhs.tricksWon})")
            }

            "set_opponents" -> {
                val oppBid    = hand.teamBids.getOrNull(1 - humanTeam) ?: 0
                val oppTricks = oppTeamIds.sumOf { hand.perPlayer[it]?.tricksWon ?: 0 }
                if (oppBid > 0 && oppTricks < oppBid) success()
                else fail("Opponents made their bid ($oppTricks/$oppBid)")
            }

            "no_bags" -> {
                if (southPhs.tricksWon == southPhs.bid) success()
                else if (southPhs.tricksWon < southPhs.bid) fail("South was set")
                else fail("South overbid by ${southPhs.tricksWon - southPhs.bid} bags")
            }

            // ── Per-rank / per-suit (hand-end confirmation) ───────────────────

            "all_aces_walk" -> {
                // Per-trick fires if any ace didn't walk; reaching here means all aces walked
                success()
            }

            "all_kings_walk" -> {
                success()
            }

            "no_jokers" -> {
                // Per-trick fires if joker won; reaching here means no joker won
                val teamBid = hand.teamBids.getOrNull(humanTeam) ?: 0
                if (humanTeamTricks >= teamBid) success() else fail("Team did not make their bid")
            }

            "no_red_wins" -> {
                // Per-trick fires if red card won; reaching here means success
                success()
            }

            "big_joker_leads" -> {
                val originalHand = state.originalDealHands["south"] ?: emptyList()
                val hasBigJoker  = originalHand.any { it.rank == Rank.BIGJOKER }
                if (!hasBigJoker) return fail("South did not have the Big Joker")
                // Verify south's very first card play was the Big Joker
                val firstSouthPlay = state.replayEvents.filterIsInstance<ReplayEvent.CardPlay>()
                    .firstOrNull { it.playerId == "south" }
                val playedBigJoker = firstSouthPlay?.let { play ->
                    originalHand.any { it.uid == play.cardUid && it.rank == Rank.BIGJOKER }
                } == true
                if (playedBigJoker) success() else fail("South did not lead the Big Joker on their first play")
            }

            else -> emptyList()
        }
    }

    // ── Trick history reconstruction ──────────────────────────────────────────

    private data class TrickRecord(val plays: List<Play>, val winnerId: String)

    private fun reconstructTrickHistory(state: GameState): List<TrickRecord> {
        val n        = state.players.size
        val allCards = state.originalDealHands.values.flatten()
        val plays    = mutableListOf<Play>()
        val winners  = mutableListOf<String>()

        for (event in state.replayEvents) {
            when (event) {
                is ReplayEvent.CardPlay -> {
                    val card = allCards.firstOrNull { it.uid == event.cardUid } ?: continue
                    plays.add(Play(event.playerId, card))
                }
                is ReplayEvent.TrickWon -> winners.add(event.winnerId)
                else -> {}
            }
        }

        return plays.chunked(n).mapIndexed { i, chunk ->
            TrickRecord(chunk, winners.getOrElse(i) { "" })
        }.filter { it.winnerId.isNotEmpty() }
    }

    /** Count tricks won by [playerId] where no spade was played by any player. */
    private fun countNoTrumpWins(playerId: String, trickHistory: List<TrickRecord>): Int =
        trickHistory.count { trick ->
            trick.winnerId == playerId &&
            trick.plays.none { it.card.suit == Suit.SPADES }
        }
}
