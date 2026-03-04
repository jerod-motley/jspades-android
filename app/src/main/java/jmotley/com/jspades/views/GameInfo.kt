package jmotley.com.jspades.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.models.GameViewModel

/**
 * Game info bar: a compact horizontal strip anchored to the top of the play area.
 *
 * Displays:
 *   - Current phase label (left)
 *   - Score summary per team (right)
 *
 * TODO:
 *   - Add current trick count / bags count
 *   - Tap score area to open full score sheet overlay
 */
@Composable
fun GameInfoView(
    state: GameState,
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = Color(0xBB000000))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Phase
        Text(
            text = state.phase.name,
            color = Color(0xFFB0BEC5),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )

        // Team scores — two teams (0 and 1)
        val team0Points = state.score.points.filterKeys { playerId ->
            state.players.find { it.id == playerId }?.team == 0
        }.values.sum()
        val team1Points = state.score.points.filterKeys { playerId ->
            state.players.find { it.id == playerId }?.team == 1
        }.values.sum()

        Text(
            text = "$team0Points – $team1Points",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
