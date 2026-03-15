package jmotley.com.jspades.views

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.models.GameViewModel

/** Card dimensions for the displayed pick card. */
private val PICK_CARD_W = 80.dp
private val PICK_CARD_H = 116.dp

/**
 * Deal pick view: used exclusively for [DealMode.TWO_MAN_ALTERNATE].
 *
 * Shows the top card of the remaining deck. The human chooses:
 *   - Keep → takes that card
 *   - Skip → takes the next card instead
 *
 * After each human pick the CPU auto-picks; this repeats until the
 * human's hand is full, at which point the ViewModel advances to Bid.
 */
@Composable
fun DealPickView(
    state: GameState,
    viewModel: GameViewModel,
    localPlayerId: String,
    modifier: Modifier = Modifier
) {
    val deck = state.deck
    val topCard = deck.getOrNull(0)
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Pick a card",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "If you accept a card, you discard the next card in the deck without seeing it. If you reject a card, you must take the next card in the deck without seeing it.",
            color = Color(0xFFB0BEC5),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Display the top card or a placeholder if deck is unexpectedly empty
        if (topCard != null) {
            val assetName = topCard.assetFileName().removeSuffix(".png")
            val resId = context.resources.getIdentifier(assetName, "drawable", context.packageName)
            if (resId != 0) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(id = resId),
                    contentDescription = "${topCard.rank.name} of ${topCard.suit.name}",
                    modifier = Modifier.size(PICK_CARD_W, PICK_CARD_H),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(PICK_CARD_W, PICK_CARD_H)
                        .background(Color(0x33FFFFFF), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${topCard.rank.value}\n${topCard.suit.name.first()}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .size(PICK_CARD_W, PICK_CARD_H)
                    .background(Color(0x33FFFFFF), RoundedCornerShape(8.dp))
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            PickButton(label = "Keep") {
                viewModel.applyDealPick(keep = true, localPlayerId = localPlayerId)
            }
            PickButton(label = "Skip") {
                viewModel.applyDealPick(keep = false, localPlayerId = localPlayerId)
            }
        }
    }
}

@Composable
private fun PickButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(110.dp)
            .height(48.dp)
            .background(
                color = if (label == "Keep") Color(0xFF1B5E20) else Color(0xFF37474F),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}
