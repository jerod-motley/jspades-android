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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.offset
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

    // Split into two rows: ceil(n/2) on top, floor(n/2) on bottom
    val topCount  = (cards.size + 1) / 2
    val topRow    = cards.take(topCount)
    val bottomRow = cards.drop(topCount)

    BoxWithConstraints(modifier = modifier.fillMaxWidth().padding(horizontal = HAND_H_PADDING)) {
        // Card width: fit topRow cards + gaps across the available width
        val totalGapWidth = CARD_GAP * (topRow.size - 1)
        val cardW = (maxWidth - totalGapWidth) / topRow.size
        val cardH = cardW / CARD_ASPECT

        val animateIn = state.phase == jmotley.com.jspades.data.GamePhase.DealHuman

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ROW_GAP)
        ) {
            CardRow(cards = topRow, cardW = cardW, cardH = cardH, startIndex = 0, animateIn = animateIn,
                onTap = { viewModel.playCard(localPlayerId, it) })
            CardRow(cards = bottomRow, cardW = cardW, cardH = cardH, startIndex = topRow.size, animateIn = animateIn,
                onTap = { viewModel.playCard(localPlayerId, it) })
        }
    }
}

@Composable
private fun CardRow(
    cards: List<Card>,
    cardW: androidx.compose.ui.unit.Dp,
    cardH: androidx.compose.ui.unit.Dp,
    startIndex: Int = 0,
    animateIn: Boolean = false,
    onTap: (Card) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
        cards.forEachIndexed { index, card ->
            val globalIndex = startIndex + index
            CardTile(
                card = card,
                index = globalIndex,
                animateIn = animateIn,
                cardW = cardW,
                modifier = Modifier
                    .size(width = cardW, height = cardH)
                    .clickable { onTap(card) }
            )
        }
    }
}

@Composable
private fun CardTile(
    card: Card,
    index: Int = 0,
    animateIn: Boolean = false,
    cardW: androidx.compose.ui.unit.Dp = 60.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val assetName = card.assetFileName().removeSuffix(".png")
    val resId = context.resources.getIdentifier(assetName, "drawable", context.packageName)

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val offsetX = remember { Animatable(-screenWidthPx) }

    LaunchedEffect(key1 = animateIn) {
        if (animateIn) {
            delay((index * 50).toLong())
            offsetX.animateTo(0f, animationSpec = tween(durationMillis = 320, easing = LinearEasing))
        } else {
            offsetX.snapTo(0f)
        }
    }

    val contentModifier = modifier.offset { IntOffset(offsetX.value.roundToInt(), 0) }

    if (resId != 0) {
        Image(
            painter = androidx.compose.ui.res.painterResource(id = resId),
            contentDescription = "${card.rank.name} of ${card.suit.name}",
            modifier = contentModifier,
            contentScale = ContentScale.FillBounds
        )
    } else {
        Box(modifier = contentModifier, contentAlignment = Alignment.Center) {
            Text(
                text = "${card.rank.value}\n${card.suit.name.first()}",
                fontSize = 10.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }
    }
}
