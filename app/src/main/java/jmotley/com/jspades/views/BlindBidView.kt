package jmotley.com.jspades.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.models.GameViewModel

/**
 * Shown during [GamePhase.BlindBidHuman] — before cards are revealed.
 * Human decides whether to bid blind (nil or 7 depending on game type).
 */
@Composable
fun BlindBidView(
    state: GameState,
    viewModel: GameViewModel,
    localPlayerId: String,
    modifier: Modifier = Modifier
) {
    val isNilBlind = state.gameType == GameType.TEAM_CLASSIC || state.gameType == GameType.SOLO_FOUR_MAN
    val blindLabel = if (isNilBlind) "Bid Blind Nil" else "Bid Blind 7"
    val scoreLabel = if (isNilBlind) "+200 pts if success -200 if fail" else "±140 pts"

    Column(
        modifier = modifier
            .background(Color(0xEE0D1B2A), RoundedCornerShape(16.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Blind Bid?",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Commit your bid before seeing your cards.",
            color = Color(0xFFB0BEC5),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = { viewModel.submitHumanBlindBid(true, localPlayerId) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A148C))
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(blindLabel, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                Text(scoreLabel, style = MaterialTheme.typography.labelSmall, color = Color(0xFFCE93D8))
            }
        }

        OutlinedButton(
            onClick = { viewModel.submitHumanBlindBid(false, localPlayerId) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("No Thanks", color = Color.White, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
