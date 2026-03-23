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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jmotley.com.jspades.data.GamePhase
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.data.Hand
import jmotley.com.jspades.models.GameViewModel

private val LABEL_COLOR   = Color(0xFFFFD700)   // gold  — human team / local player
private val SCORE_COLOR   = Color.White
private val DETAIL_COLOR  = Color.White
private val DIVIDER_COLOR = Color(0x44FFFFFF)

/**
 * Game info strip positioned just above the player's hand.
 *
 * Each panel stacks vertically: label → Bid N → Books N → Score N.
 *
 * **Team games** — two panels side-by-side (US left, THEM right).
 * **Solo games** — one panel per player in seat order, human first.
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
    val dealHand   = state.phaseHands[GamePhase.Deal]?.lastOrNull()
    val anyBidMade = state.players.any { it.runtimeFlags.didBid }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.gameType.useTeams && state.gameType != GameType.TEAM_CLASSIC) {
            val humanTeam = state.players.find { it.id == localPlayerId }?.team ?: 0
            val oppTeam   = 1 - humanTeam

            InfoPanel(
                nameLabel = "US",
                isHuman   = true,
                bid       = if (anyBidMade) dealHand?.teamBids?.getOrNull(humanTeam) else null,
                tricksWon = teamTricks(state, dealHand, humanTeam),
                score     = totalScore(state, humanTeam.toString()),
                modifier  = Modifier.weight(1f)
            )

            Spacer(Modifier.width(1.dp).height(56.dp).background(DIVIDER_COLOR))

            InfoPanel(
                nameLabel = "THEM",
                isHuman   = false,
                bid       = if (anyBidMade) dealHand?.teamBids?.getOrNull(oppTeam) else null,
                tricksWon = teamTricks(state, dealHand, oppTeam),
                score     = totalScore(state, oppTeam.toString()),
                modifier  = Modifier.weight(1f)
            )
        } else {
            // Solo games + TEAM_CLASSIC: one panel per player.
            // TEAM_CLASSIC shows individual bids/tricks but team score.
            val ordered = buildList {
                state.players.find { it.id == localPlayerId }?.let { add(it) }
                state.players.filter { it.id != localPlayerId }.forEach { add(it) }
            }
            ordered.forEachIndexed { idx, player ->
                val isHuman  = player.id == localPlayerId
                val phs      = dealHand?.perPlayer?.get(player.id)
                val scoreKey = if (state.gameType.useTeams) player.team.toString() else player.id
                InfoPanel(
                    nameLabel = if (isHuman) "You" else player.displayName,
                    isHuman   = isHuman,
                    bid       = if (anyBidMade) phs?.bid else null,
                    tricksWon = phs?.tricksWon ?: 0,
                    score     = totalScore(state, scoreKey),
                    modifier  = Modifier.weight(1f)
                )
                if (idx < ordered.lastIndex) {
                    Spacer(Modifier.width(1.dp).height(56.dp).background(DIVIDER_COLOR))
                }
            }
        }
    }
}

// ── Panel ──────────────────────────────────────────────────────────────────────

@Composable
private fun InfoPanel(
    nameLabel: String,
    isHuman: Boolean,
    bid: Int?,
    tricksWon: Int,
    score: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier            = modifier.padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text       = nameLabel,
            color      = if (isHuman) LABEL_COLOR else SCORE_COLOR,
            style      = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            textAlign  = TextAlign.Center
        )
        Text(
            text      = "Bid ${bid ?: "—"}",
            color     = DETAIL_COLOR,
            style     = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
        Text(
            text      = "Books $tricksWon",
            color     = DETAIL_COLOR,
            style     = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
        Text(
            text       = "Score $score",
            color      = SCORE_COLOR,
            style      = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            textAlign  = TextAlign.Center
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun totalScore(state: GameState, key: String): Int =
    (state.score.points[key] ?: 0) + (state.score.bags[key] ?: 0)

private fun teamTricks(state: GameState, dealHand: Hand?, teamId: Int): Int =
    state.players
        .filter { it.team == teamId }
        .sumOf { p -> dealHand?.perPlayer?.get(p.id)?.tricksWon ?: 0 }
