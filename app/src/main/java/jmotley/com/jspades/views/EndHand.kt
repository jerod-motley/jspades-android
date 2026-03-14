package jmotley.com.jspades.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jmotley.com.jspades.data.GamePhase
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.models.GameViewModel

private val GOLD   = Color(0xFFFFD700)
private val PANEL  = Color(0xEE0A0A14)   // near-black, slightly blue-tinted
private val HEADER = Color.White

/**
 * End-of-hand summary overlay. Shows each team's (or player's) bid, tricks taken,
 * points earned this hand, and the updated running total. Offers "Next Hand" to
 * continue or "Home" (with confirmation) to quit.
 */
@Composable
fun EndHandView(
    state: GameState,
    viewModel: GameViewModel,
    localPlayerId: String,
    onNavigateBack: () -> Unit,
    onReplayHand: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val dealHand = state.phaseHands[GamePhase.Deal]?.lastOrNull()
    var showHomeDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0x88000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(PANEL, RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Title ─────────────────────────────────────────────────────────
            Text(
                text       = "Hand Complete",
                color      = Color.White,
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(4.dp))

            // ── Stats table ───────────────────────────────────────────────────
            if (state.gameType.useTeams) {
                TeamStatsTable(state, localPlayerId, dealHand)
            } else {
                SoloStatsTable(state, localPlayerId, dealHand)
            }

            Spacer(Modifier.height(8.dp))

            // ── Buttons ───────────────────────────────────────────────────────
            Column(
                modifier            = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick  = { showHomeDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Home", color = Color.White)
                    }
                    Button(
                        onClick  = { viewModel.onNextHand() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Next Hand")
                    }
                }
                if (onReplayHand != null) {
                    OutlinedButton(
                        onClick  = { onReplayHand() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("▶ Replay Hand", color = Color.White)
                    }
                }
            }
        }
    }

    // ── Home confirmation dialog ───────────────────────────────────────────────
    if (showHomeDialog) {
        AlertDialog(
            onDismissRequest = { showHomeDialog = false },
            title = { Text("Go home?") },
            text  = { Text("This hand's progress will be lost.") },
            confirmButton = {
                TextButton(onClick = { showHomeDialog = false; onNavigateBack() }) {
                    Text("Go Home")
                }
            },
            dismissButton = {
                TextButton(onClick = { showHomeDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ── Team stats ────────────────────────────────────────────────────────────────

@Composable
private fun TeamStatsTable(
    state: GameState,
    localPlayerId: String,
    dealHand: jmotley.com.jspades.data.Hand?
) {
    val humanTeam = state.players.find { it.id == localPlayerId }?.team ?: 0
    val oppTeam   = 1 - humanTeam

    // Column headers
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(80.dp))
        Text("US",   modifier = Modifier.weight(1f), textAlign = TextAlign.Center, color = GOLD,        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
        Text("THEM", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
    }

    StatDivider()

    // Bid
    val humanBid = dealHand?.teamBids?.getOrNull(humanTeam) ?: 0
    val oppBid   = dealHand?.teamBids?.getOrNull(oppTeam)   ?: 0
    StatRow("Bid", humanBid.toString(), oppBid.toString())

    // Tricks
    val humanTricks = state.players.filter { it.team == humanTeam }.sumOf { p -> dealHand?.perPlayer?.get(p.id)?.tricksWon ?: 0 }
    val oppTricks   = state.players.filter { it.team == oppTeam   }.sumOf { p -> dealHand?.perPlayer?.get(p.id)?.tricksWon ?: 0 }
    StatRow("Won", humanTricks.toString(), oppTricks.toString())

    StatDivider()

    // This hand
    val humanPts  = state.lastHandScore.points[humanTeam.toString()] ?: 0
    val oppPts    = state.lastHandScore.points[oppTeam.toString()]   ?: 0
    val humanBags = state.lastHandScore.bags[humanTeam.toString()]   ?: 0
    val oppBags   = state.lastHandScore.bags[oppTeam.toString()]     ?: 0
    StatRow("Points", formatDelta(humanPts), formatDelta(oppPts), highlight = true)
    if (humanBags != 0 || oppBags != 0) {
        StatRow("Bags±", formatDelta(humanBags), formatDelta(oppBags))
    }

    StatDivider()

    // Running total
    val humanTotal = (state.score.points[humanTeam.toString()] ?: 0) + (state.score.bags[humanTeam.toString()] ?: 0)
    val oppTotal   = (state.score.points[oppTeam.toString()]   ?: 0) + (state.score.bags[oppTeam.toString()]   ?: 0)
    StatRow("Total", humanTotal.toString(), oppTotal.toString(), bold = true, labelColor = GOLD)
}

// ── Solo stats ────────────────────────────────────────────────────────────────

@Composable
private fun SoloStatsTable(
    state: GameState,
    localPlayerId: String,
    dealHand: jmotley.com.jspades.data.Hand?
) {
    // Human first, then others in player order
    val ordered = buildList {
        state.players.find { it.id == localPlayerId }?.let { add(it) }
        state.players.filter { it.id != localPlayerId }.forEach { add(it) }
    }
    val colLabels = ordered.map { if (it.id == localPlayerId) "You" else it.displayName }
    val colColors = ordered.map { if (it.id == localPlayerId) GOLD else Color.White }

    // Headers
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(80.dp))
        ordered.forEachIndexed { i, _ ->
            Text(
                text       = colLabels[i],
                modifier   = Modifier.weight(1f),
                textAlign  = TextAlign.Center,
                color      = colColors[i],
                fontWeight = FontWeight.Bold,
                style      = MaterialTheme.typography.bodySmall
            )
        }
    }

    StatDivider()

    // Bid
    StatRow("Bid", *ordered.map { p -> (dealHand?.perPlayer?.get(p.id)?.bid ?: 0).toString() }.toTypedArray())

    // Won
    StatRow("Won", *ordered.map { p -> (dealHand?.perPlayer?.get(p.id)?.tricksWon ?: 0).toString() }.toTypedArray())

    StatDivider()

    // Points this hand
    StatRow("Points", *ordered.map { p -> formatDelta(state.lastHandScore.points[p.id] ?: 0) }.toTypedArray(), highlight = true)

    val anyBags = ordered.any { p -> (state.lastHandScore.bags[p.id] ?: 0) != 0 }
    if (anyBags) {
        StatRow("Bags±", *ordered.map { p -> formatDelta(state.lastHandScore.bags[p.id] ?: 0) }.toTypedArray())
    }

    StatDivider()

    // Total
    StatRow(
        "Total",
        *ordered.map { p ->
            ((state.score.points[p.id] ?: 0) + (state.score.bags[p.id] ?: 0)).toString()
        }.toTypedArray(),
        bold = true,
        labelColor = GOLD
    )
}

// ── Shared row composables ────────────────────────────────────────────────────

@Composable
private fun StatRow(
    label: String,
    vararg values: String,
    highlight: Boolean = false,
    bold: Boolean = false,
    labelColor: Color = HEADER
) {
    val textColor = if (highlight) GOLD else Color.White
    val weight    = if (bold) FontWeight.Bold else FontWeight.Normal
    val fontScale = LocalConfiguration.current.fontScale
    if (fontScale >= 1.3f) {
        Column(
            modifier            = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text       = label,
                color      = labelColor,
                style      = MaterialTheme.typography.bodySmall,
                fontWeight = weight
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                values.forEach { v ->
                    Text(
                        text       = v,
                        modifier   = Modifier.weight(1f),
                        textAlign  = TextAlign.Center,
                        color      = textColor,
                        style      = MaterialTheme.typography.bodySmall,
                        fontWeight = weight
                    )
                }
            }
        }
    } else {
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text       = label,
                modifier   = Modifier.width(80.dp),
                color      = labelColor,
                style      = MaterialTheme.typography.bodySmall,
                fontWeight = weight
            )
            values.forEach { v ->
                Text(
                    text       = v,
                    modifier   = Modifier.weight(1f),
                    textAlign  = TextAlign.Center,
                    color      = textColor,
                    style      = MaterialTheme.typography.bodySmall,
                    fontWeight = weight
                )
            }
        }
    }
}

@Composable
private fun StatDivider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x33FFFFFF))
    )
}

private fun formatDelta(n: Int): String = if (n > 0) "+$n" else "$n"
