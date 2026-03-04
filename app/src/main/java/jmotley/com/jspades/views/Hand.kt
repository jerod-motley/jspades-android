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
import androidx.compose.ui.draw.alpha
import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.GamePhase
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
 * Hand view: renders the local player's cards in two rows.
 *
 * Card width is computed dynamically so the top row fills the available width.
 * Cards are sorted by suit then rank before reaching here.
 *
 * Taps are only active during [GamePhase.TrickHuman]. Invalid cards (suit-following
 * violations, Classic spades-broken gate) are dimmed and non-tappable.
 * Tapping calls [GameViewModel.submitHumanPlay].
 */
@Composable
fun HandView(
    state: GameState,
    viewModel: GameViewModel,
    localPlayerId: String,
    modifier: Modifier = Modifier
) {
    val cards        = state.localHand(localPlayerId)
    val isTrickHuman = state.phase == GamePhase.TrickHuman

    // Compute which card UIDs are valid to play right now
    val validUids: Set<String> = if (isTrickHuman) {
        validPlayableUids(cards, state, viewModel, localPlayerId)
    } else emptySet()

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
        val totalGapWidth = CARD_GAP * (topRow.size - 1)
        val cardW = (maxWidth - totalGapWidth) / topRow.size
        val cardH = cardW / CARD_ASPECT

        val animateIn = state.phase == GamePhase.DealHuman

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ROW_GAP)
        ) {
            CardRow(
                cards = topRow, cardW = cardW, cardH = cardH, startIndex = 0,
                animateIn = animateIn, isTrickHuman = isTrickHuman, validUids = validUids,
                onTap = { card -> if (isTrickHuman && card.uid in validUids) viewModel.submitHumanPlay(card, localPlayerId) }
            )
            CardRow(
                cards = bottomRow, cardW = cardW, cardH = cardH, startIndex = topRow.size,
                animateIn = animateIn, isTrickHuman = isTrickHuman, validUids = validUids,
                onTap = { card -> if (isTrickHuman && card.uid in validUids) viewModel.submitHumanPlay(card, localPlayerId) }
            )
        }
    }
}

/** Returns the UIDs of cards the human is legally allowed to play right now. */
private fun validPlayableUids(
    hand: List<Card>,
    state: GameState,
    viewModel: GameViewModel,
    localPlayerId: String
): Set<String> = hand.filter { viewModel.canPlayCard(it, localPlayerId) }.map { it.uid }.toSet()

@Composable
private fun CardRow(
    cards: List<Card>,
    cardW: androidx.compose.ui.unit.Dp,
    cardH: androidx.compose.ui.unit.Dp,
    startIndex: Int = 0,
    animateIn: Boolean = false,
    isTrickHuman: Boolean = false,
    validUids: Set<String> = emptySet(),
    onTap: (Card) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
        cards.forEachIndexed { index, card ->
            val globalIndex = startIndex + index
            val enabled = !isTrickHuman || card.uid in validUids
            CardTile(
                card = card,
                index = globalIndex,
                animateIn = animateIn,
                cardW = cardW,
                enabled = enabled,
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
    enabled: Boolean = true,
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

    val alpha = if (enabled) 1f else 0.35f
    val contentModifier = modifier.offset { IntOffset(offsetX.value.roundToInt(), 0) }.alpha(alpha)

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
