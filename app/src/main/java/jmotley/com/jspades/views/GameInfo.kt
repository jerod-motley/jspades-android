package jmotley.com.jspades.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jmotley.com.jspades.data.GamePhase
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.Hand
import jmotley.com.jspades.models.GameViewModel

private val LABEL_COLOR  = Color(0xFFFFD700)   // gold  — human team label
private val SCORE_COLOR  = Color.White
private val DETAIL_COLOR = Color(0xFFB0BEC5)    // cool grey — bid / tricks / bags
private val DIVIDER_COLOR = Color(0x44FFFFFF)

/**
 * Game info strip positioned just above the player's hand.
 *
 * **Team games** — two panels side-by-side (human's team left, opponents right):
 * ```
 *  US           |      THEM
 *  240          |      180
 *  Bid 4 · ↑3 · Bags 2 | Bid 5 · ↑2 · Bags 0
 * ```
 * **Solo games** — one panel per player in seat order, human first.
 *
 * Scores are the running total (points + bags) accumulated from prior hands.
 * Current-hand bid / tricks / bags reflect the live deal-hand state.
 * If bidding has not yet started this hand, bid shows "—".
 *
 * Not shown on Lobby, EndHand, or Finished screens.
 */
@Composable
fun GameInfoView(
    state: GameState,
    viewModel: GameViewModel,
    localPlayerId: String,
    modifier: Modifier = Modifier
) {
    val dealHand    = state.phaseHands[GamePhase.Deal]?.lastOrNull()
    val anyBidMade  = state.players.any { it.runtimeFlags.didBid }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.gameType.useTeams) {
            val humanTeam = state.players.find { it.id == localPlayerId }?.team ?: 0
            val oppTeam   = 1 - humanTeam

            TeamPanel(
                label      = "US",
                isHuman    = true,
                score      = totalScore(state, humanTeam.toString()),
                bid        = if (anyBidMade) (dealHand?.teamBids?.getOrNull(humanTeam) ?: 0) else null,
                tricksWon  = teamTricks(state, dealHand, humanTeam),
                bags       = state.score.bags[humanTeam.toString()] ?: 0,
                modifier   = Modifier.weight(1f)
            )

            Spacer(Modifier.width(1.dp).height(36.dp).background(DIVIDER_COLOR))

            TeamPanel(
                label      = "THEM",
                isHuman    = false,
                score      = totalScore(state, oppTeam.toString()),
                bid        = if (anyBidMade) (dealHand?.teamBids?.getOrNull(oppTeam) ?: 0) else null,
                tricksWon  = teamTricks(state, dealHand, oppTeam),
                bags       = state.score.bags[oppTeam.toString()] ?: 0,
                modifier   = Modifier.weight(1f)
            )
        } else {
            // Solo: one cell per player; human first, then clockwise
            val ordered = buildList {
                state.players.find { it.id == localPlayerId }?.let { add(it) }
                state.players.filter { it.id != localPlayerId }.forEach { add(it) }
            }
            ordered.forEachIndexed { idx, player ->
                val isHuman = player.id == localPlayerId
                val phs     = dealHand?.perPlayer?.get(player.id)
                SoloPanel(
                    label     = if (isHuman) "You" else player.displayName,
                    isHuman   = isHuman,
                    score     = totalScore(state, player.id),
                    bid       = if (anyBidMade) (phs?.bid ?: 0) else null,
                    tricksWon = phs?.tricksWon ?: 0,
                    bags      = state.score.bags[player.id] ?: 0,
                    modifier  = Modifier.weight(1f)
                )
                if (idx < ordered.lastIndex) {
                    Spacer(Modifier.width(1.dp).height(36.dp).background(DIVIDER_COLOR))
                }
            }
        }
    }
}

// ── Panels ────────────────────────────────────────────────────────────────────

@Composable
private fun TeamPanel(
    label: String,
    isHuman: Boolean,
    score: Int,
    bid: Int?,           // null = no bid yet this hand
    tricksWon: Int,
    bags: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Label + score on one line
        Row(
            modifier           = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment  = Alignment.CenterVertically
        ) {
            Text(
                text       = label,
                color      = if (isHuman) LABEL_COLOR else SCORE_COLOR,
                fontSize   = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text       = "$score",
                color      = SCORE_COLOR,
                fontSize   = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        // Bid · won · bags
        val bidText  = bid?.let { "Bid $it" } ?: "Bid —"
        val bagsText = if (bags > 0) "  Bags $bags" else ""
        Text(
            text     = "$bidText  ↑$tricksWon$bagsText",
            color    = DETAIL_COLOR,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun SoloPanel(
    label: String,
    isHuman: Boolean,
    score: Int,
    bid: Int?,
    tricksWon: Int,
    bags: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier              = modifier.padding(horizontal = 4.dp),
        verticalArrangement   = Arrangement.spacedBy(2.dp),
        horizontalAlignment   = Alignment.CenterHorizontally
    ) {
        Text(
            text       = label,
            color      = if (isHuman) LABEL_COLOR else SCORE_COLOR,
            fontSize   = 10.sp,
            fontWeight = if (isHuman) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            text       = "$score",
            color      = SCORE_COLOR,
            fontSize   = 12.sp,
            fontWeight = FontWeight.Bold
        )
        val bidText = bid?.let { "$it" } ?: "—"
        Text(
            text  = "B:$bidText ↑$tricksWon",
            color = DETAIL_COLOR,
            fontSize = 10.sp
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Running total displayed score = accumulated points + accumulated bags. */
private fun totalScore(state: GameState, key: String): Int =
    (state.score.points[key] ?: 0) + (state.score.bags[key] ?: 0)

/** Total tricks won by all players on [teamId] in the current deal hand. */
private fun teamTricks(state: GameState, dealHand: Hand?, teamId: Int): Int =
    state.players
        .filter { it.team == teamId }
        .sumOf { p -> dealHand?.perPlayer?.get(p.id)?.tricksWon ?: 0 }
