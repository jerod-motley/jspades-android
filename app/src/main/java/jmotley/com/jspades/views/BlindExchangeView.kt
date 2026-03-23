package jmotley.com.jspades.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.GamePhase
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.models.GameViewModel

/**
 * Shown during [GamePhase.BlindExchangeHuman] — after cards are revealed.
 * Human selects exactly 2 cards to send to their partner.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlindExchangeView(
    state: GameState,
    viewModel: GameViewModel,
    localPlayerId: String,
    modifier: Modifier = Modifier
) {
    val dealHand = state.phaseHands[GamePhase.Deal]?.lastOrNull()
    val humanPhs = dealHand?.perPlayer?.get(localPlayerId)
    val hand = humanPhs?.hand ?: emptyList()

    var selectedCards by remember { mutableStateOf<Set<String>>(emptySet()) }

    Column(
        modifier = modifier
            .background(Color(0xEE0D1B2A), RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Send 2 Cards to Partner",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Select exactly 2 cards to exchange with your partner.",
            color = Color(0xFFB0BEC5),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(4.dp))

        // Card grid
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = 7
        ) {
            hand.forEach { card ->
                val selected = card.uid in selectedCards
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 52.dp)
                        .background(
                            if (selected) Color(0xFF4A148C) else Color(0xFF1B2A3A),
                            RoundedCornerShape(4.dp)
                        )
                        .border(
                            1.dp,
                            if (selected) Color(0xFFCE93D8) else Color(0xFF37474F),
                            RoundedCornerShape(4.dp)
                        )
                        .clickable {
                            selectedCards = if (selected) {
                                selectedCards - card.uid
                            } else if (selectedCards.size < 2) {
                                selectedCards + card.uid
                            } else selectedCards
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = card.rank.value.toString(),
                        color = if (selected) Color.White else Color(0xFFB0BEC5),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Text(
            text = "${selectedCards.size}/2 selected",
            color = if (selectedCards.size == 2) Color(0xFFCE93D8) else Color(0xFF78909C),
            style = MaterialTheme.typography.labelSmall
        )

        Button(
            onClick = {
                val toSend = hand.filter { it.uid in selectedCards }
                if (toSend.size == 2) viewModel.submitHumanBlindExchange(toSend, localPlayerId)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedCards.size == 2,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20))
        ) {
            Text("Send Cards", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
