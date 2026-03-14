package jmotley.com.jspades.views

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.models.GameViewModel

/** Size of each kitty card displayed. */
private val KITTY_CARD_W = 68.dp
private val KITTY_CARD_H = 100.dp

/**
 * Kitty view: displayed in the Kitty phase so the winning bidder can view
 * and (in supported variants) exchange cards with their hand.
 *
 * Kitty cards are read from [GameState.kitty]. Cards are tappable for exchange.
 *
 * Behavior depends on game type / rules variant:
 *   - Classic Kitty: winner sees cards and picks up, then discards same count
 *   - Kitty Spades: winner keeps all kitty cards; standard discard rules apply
 *
 * TODO:
 *   - Implement card-exchange logic (tap kitty card ↔ tap hand card)
 *   - Add "Done" button that commits the exchange and advances phase to Bid
 *   - Enforce variant-specific constraints (no discarding spades, etc.)
 */
@Composable
fun KittyView(
    state: GameState,
    viewModel: GameViewModel,
    localPlayerId: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val kittyCards = state.kitty?.perPlayer?.values
        ?.flatMap { it.hand }
        ?: emptyList()

    Box(
        modifier = modifier
            .background(color = Color(0xCC000000), shape = RoundedCornerShape(16.dp))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {

            Text(
                text = "Kitty",
                color = Color(0xFFFFD700),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Kitty card row
            if (kittyCards.isEmpty()) {
                Text(
                    text = "No kitty cards",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    kittyCards.forEach { card ->
                        KittyCard(
                            card = card,
                            onClick = {
                                // TODO: mark card for exchange with local hand card
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Done button
            Box(
                modifier = Modifier
                    .size(width = 140.dp, height = 44.dp)
                    .background(color = Color(0xFF1565C0), shape = RoundedCornerShape(8.dp))
                    .clickable {
                        // TODO: commit exchange, advance to Bid phase
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Done",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@Composable
private fun KittyCard(card: Card, onClick: () -> Unit) {
    val context = LocalContext.current
    val assetName = card.assetFileName().removeSuffix(".png")
    val resId = context.resources.getIdentifier(assetName, "drawable", context.packageName)

    Box(
        modifier = Modifier
            .size(KITTY_CARD_W, KITTY_CARD_H)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (resId != 0) {
            Image(
                painter = androidx.compose.ui.res.painterResource(id = resId),
                contentDescription = "${card.rank.name} of ${card.suit.name}",
                modifier = Modifier.size(KITTY_CARD_W, KITTY_CARD_H),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(
                modifier = Modifier
                    .size(KITTY_CARD_W, KITTY_CARD_H)
                    .background(color = Color(0xFF37474F), shape = RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${card.rank.value}\n${card.suit.name.first()}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
