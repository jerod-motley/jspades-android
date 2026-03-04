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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.models.GameViewModel

private val EG_GOLD   = Color(0xFFFFD700)
private val EG_PANEL  = Color(0xEE0A0A14)
private val EG_HEADER = Color(0xFFB0BEC5)

/**
 * Game-over overlay. Shows the winner, final scores for all teams/players,
 * and offers "Play Again" (no confirm) or "Home" (no confirm).
 */
@Composable
fun EndGameView(
    state: GameState,
    viewModel: GameViewModel,
    localPlayerId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val winnerLabel = resolveWinnerLabel(state, localPlayerId)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xBB000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .background(EG_PANEL, RoundedCornerShape(16.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Game over banner ──────────────────────────────────────────────
            Text(
                text       = "Game Over",
                color      = Color.White,
                fontSize   = 22.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text       = winnerLabel,
                color      = EG_GOLD,
                fontSize   = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center
            )

            Spacer(Modifier.height(4.dp))

            // ── Final scores table ────────────────────────────────────────────
            if (state.gameType.useTeams) {
                TeamFinalScores(state, localPlayerId)
            } else {
                SoloFinalScores(state, localPlayerId)
            }

            Spacer(Modifier.height(8.dp))

            // ── Buttons ───────────────────────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick  = { onNavigateBack() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Home", color = Color.White)
                }
                Button(
                    onClick  = { viewModel.playAgain() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Play Again")
                }
            }
        }
    }
}

// ── Winner resolution ─────────────────────────────────────────────────────────

private fun resolveWinnerLabel(state: GameState, localPlayerId: String): String {
    val totals: Map<String, Int> = if (state.gameType.useTeams) {
        mapOf(
            "0" to ((state.score.points["0"] ?: 0) + (state.score.bags["0"] ?: 0)),
            "1" to ((state.score.points["1"] ?: 0) + (state.score.bags["1"] ?: 0))
        )
    } else {
        state.players.associate { p ->
            p.id to ((state.score.points[p.id] ?: 0) + (state.score.bags[p.id] ?: 0))
        }
    }

    val winnerKey = totals.maxByOrNull { it.value }?.key ?: return "Game Over"

    return if (state.gameType.useTeams) {
        val humanTeam = state.players.find { it.id == localPlayerId }?.team ?: 0
        if (winnerKey == humanTeam.toString()) "You Win! 🏆" else "You Lose"
    } else {
        val winner = state.players.find { it.id == winnerKey }
        if (winnerKey == localPlayerId) "You Win! 🏆"
        else "${winner?.displayName ?: "Opponent"} Wins"
    }
}

// ── Team final scores ─────────────────────────────────────────────────────────

@Composable
private fun TeamFinalScores(state: GameState, localPlayerId: String) {
    val humanTeam = state.players.find { it.id == localPlayerId }?.team ?: 0
    val oppTeam   = 1 - humanTeam

    // Header row
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(80.dp))
        Text("US",   modifier = Modifier.weight(1f), textAlign = TextAlign.Center, color = EG_GOLD,       fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("THEM", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, color = Color.White,   fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }

    EGDivider()

    // Points
    val humanPts = state.score.points[humanTeam.toString()] ?: 0
    val oppPts   = state.score.points[oppTeam.toString()]   ?: 0
    EGStatRow("Points", humanPts.toString(), oppPts.toString())

    // Bags
    val humanBags = state.score.bags[humanTeam.toString()] ?: 0
    val oppBags   = state.score.bags[oppTeam.toString()]   ?: 0
    EGStatRow("Bags", humanBags.toString(), oppBags.toString())

    EGDivider()

    // Total
    val humanTotal = humanPts + humanBags
    val oppTotal   = oppPts + oppBags
    EGStatRow("Final", humanTotal.toString(), oppTotal.toString(), bold = true, labelColor = EG_GOLD)
}

// ── Solo final scores ─────────────────────────────────────────────────────────

@Composable
private fun SoloFinalScores(state: GameState, localPlayerId: String) {
    val ordered = buildList {
        state.players.find { it.id == localPlayerId }?.let { add(it) }
        state.players.filter { it.id != localPlayerId }.forEach { add(it) }
    }

    // Header row
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(80.dp))
        ordered.forEach { p ->
            Text(
                text       = if (p.id == localPlayerId) "You" else p.displayName,
                modifier   = Modifier.weight(1f),
                textAlign  = TextAlign.Center,
                color      = if (p.id == localPlayerId) EG_GOLD else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize   = 12.sp
            )
        }
    }

    EGDivider()

    EGStatRow("Points", *ordered.map { p -> (state.score.points[p.id] ?: 0).toString() }.toTypedArray())
    EGStatRow("Bags",   *ordered.map { p -> (state.score.bags[p.id]   ?: 0).toString() }.toTypedArray())

    EGDivider()

    EGStatRow(
        "Final",
        *ordered.map { p ->
            ((state.score.points[p.id] ?: 0) + (state.score.bags[p.id] ?: 0)).toString()
        }.toTypedArray(),
        bold       = true,
        labelColor = EG_GOLD
    )
}

// ── Shared helpers ────────────────────────────────────────────────────────────

@Composable
private fun EGStatRow(
    label: String,
    vararg values: String,
    bold: Boolean = false,
    labelColor: Color = EG_HEADER
) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text       = label,
            modifier   = Modifier.width(80.dp),
            color      = labelColor,
            fontSize   = 12.sp,
            fontWeight = weight
        )
        values.forEach { v ->
            Text(
                text       = v,
                modifier   = Modifier.weight(1f),
                textAlign  = TextAlign.Center,
                color      = Color.White,
                fontSize   = 13.sp,
                fontWeight = weight
            )
        }
    }
}

@Composable
private fun EGDivider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x33FFFFFF))
    )
}
