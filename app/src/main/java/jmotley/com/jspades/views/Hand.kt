package jmotley.com.jspades.views

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.localHand
import jmotley.com.jspades.models.GameViewModel

/** Horizontal padding on each side of the hand grid. */
private val HAND_H_PADDING = 8.dp

/** Vertical gap between the two card rows. */
private val ROW_GAP = 6.dp

/** Horizontal gap between cards in a row. */
private val CARD_GAP = 4.dp

/** Card aspect ratio (standard playing card ~0.69). */
private const val CARD_ASPECT = 0.69f

/**
 * Hand view: renders the local player's 13 cards in two rows — 7 on top, 6 on bottom.
 *
 * Card width is computed dynamically so 7 cards fill the available width exactly.
 * Cards are sorted by the ViewModel before reaching here (low suit → high suit,
 * low rank → high rank).
 *
 * Cards are tappable; tapping fires [viewModel.playCard].
 * TODO: gate on play-turn ownership.
 */
@Composable
fun HandView(
    state: GameState,
    viewModel: GameViewModel,
    localPlayerId: String,
    modifier: Modifier = Modifier
) {
    val cards = state.localHand(localPlayerId)

    if (cards.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No cards", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        }
        return
    }

    // Split into two rows: first 7, then remaining 6
    val topRow    = cards.take(7)
    val bottomRow = cards.drop(7)

    BoxWithConstraints(modifier = modifier.fillMaxWidth().padding(horizontal = HAND_H_PADDING)) {
        // Card width: fit 7 cards + 6 gaps across the available width
        val totalGapWidth = CARD_GAP * 6
        val cardW = (maxWidth - totalGapWidth) / 7
        val cardH = cardW / CARD_ASPECT

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ROW_GAP)
        ) {
            CardRow(cards = topRow, cardW = cardW, cardH = cardH,
                    onTap = { viewModel.playCard(localPlayerId, it) })
            CardRow(cards = bottomRow, cardW = cardW, cardH = cardH,
                    onTap = { viewModel.playCard(localPlayerId, it) })
        }
    }
}

@Composable
private fun CardRow(
    cards: List<Card>,
    cardW: androidx.compose.ui.unit.Dp,
    cardH: androidx.compose.ui.unit.Dp,
    onTap: (Card) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
        cards.forEach { card ->
            CardTile(
                card = card,
                modifier = Modifier
                    .size(width = cardW, height = cardH)
                    .clickable { onTap(card) }
            )
        }
    }
}

@Composable
private fun CardTile(card: Card, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val assetName = card.assetFileName().removeSuffix(".png")
    val resId = context.resources.getIdentifier(assetName, "drawable", context.packageName)

    if (resId != 0) {
        Image(
            painter = androidx.compose.ui.res.painterResource(id = resId),
            contentDescription = "${card.rank.name} of ${card.suit.name}",
            modifier = modifier,
            contentScale = ContentScale.FillBounds
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "${card.rank.value}\n${card.suit.name.first()}",
                fontSize = 10.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }
    }
}
