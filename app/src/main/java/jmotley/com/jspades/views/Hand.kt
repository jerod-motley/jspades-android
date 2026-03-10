package jmotley.com.jspades.views

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import kotlinx.coroutines.Job
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
    onTapMessageChanged: ((Boolean) -> Unit)? = null,
    onRenegeAttempt: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val liveCards    = state.localHand(localPlayerId)
    val isTrickHuman = state.phase == GamePhase.TrickHuman

    // Layout template: the original dealt hand (fixed slots, never shrinks).
    // Falls back to liveCards before the first deal snapshot is available.
    val template = state.originalDealHands[localPlayerId]?.takeIf { it.isNotEmpty() } ?: liveCards

    if (template.isEmpty()) {
        // Preserve the hand area height before the deal snapshot is available.
        // Estimate card dimensions from a typical top-row of 7 cards.
        val estimatedTopCount = (state.gameType.cardsPerPlayer + 1) / 2
        BoxWithConstraints(modifier = modifier.fillMaxWidth().padding(horizontal = HAND_H_PADDING)) {
            val cardW = (maxWidth - CARD_GAP * (estimatedTopCount - 1)) / estimatedTopCount.coerceAtLeast(1)
            val cardH = cardW / CARD_ASPECT
            androidx.compose.foundation.layout.Spacer(
                modifier = Modifier.size(width = maxWidth, height = cardH * 2 + ROW_GAP)
            )
        }
        return
    }

    // UIDs still in hand — determines which slots render a card vs a placeholder
    val liveUids = liveCards.map { it.uid }.toSet()

    // Valid-to-play UIDs (only relevant during TrickHuman)
    val validUids: Set<String> = if (isTrickHuman) {
        validPlayableUids(liveCards, state, viewModel, localPlayerId)
    } else emptySet()

    // Row split is based on template size — stays constant throughout the hand
    val topCount      = (template.size + 1) / 2
    val topTemplate   = template.take(topCount)
    val bottomTemplate = template.drop(topCount)

    // ── Double-tap to play ────────────────────────────────────────────────────
    var pendingCardUid by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var pendingJob by remember { mutableStateOf<Job?>(null) }

    // Clear pending state when it's no longer the human's turn
    LaunchedEffect(isTrickHuman) {
        if (!isTrickHuman) {
            pendingJob?.cancel()
            pendingCardUid = null
            onTapMessageChanged?.invoke(false)
        }
    }

    val handleTap = { card: Card ->
        if (isTrickHuman && card.uid in validUids) {
            if (pendingCardUid == card.uid) {
                // Second tap on same card — play it
                pendingJob?.cancel()
                pendingCardUid = null
                onTapMessageChanged?.invoke(false)
                viewModel.submitHumanPlay(card, localPlayerId)
            } else {
                // First tap (or tap on a different card) — arm the pending state
                pendingJob?.cancel()
                pendingCardUid = card.uid
                onTapMessageChanged?.invoke(true)
                pendingJob = scope.launch {
                    delay(1500L)
                    pendingCardUid = null
                    onTapMessageChanged?.invoke(false)
                }
            }
        } else if (isTrickHuman && card.uid !in validUids) {
            onRenegeAttempt?.invoke()
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth().padding(horizontal = HAND_H_PADDING)) {
        val totalGapWidth = CARD_GAP * (topTemplate.size - 1)
        val cardW = (maxWidth - totalGapWidth) / topTemplate.size
        val cardH = cardW / CARD_ASPECT

        val animateIn = state.phase == GamePhase.DealHuman

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ROW_GAP)
        ) {
            CardRow(
                templateCards = topTemplate, liveUids = liveUids,
                cardW = cardW, cardH = cardH, startIndex = 0,
                animateIn = animateIn, isTrickHuman = isTrickHuman, validUids = validUids,
                pendingCardUid = pendingCardUid,
                onTap = handleTap
            )
            CardRow(
                templateCards = bottomTemplate, liveUids = liveUids,
                cardW = cardW, cardH = cardH, startIndex = topTemplate.size,
                animateIn = animateIn, isTrickHuman = isTrickHuman, validUids = validUids,
                pendingCardUid = pendingCardUid,
                onTap = handleTap
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
    templateCards: List<Card>,
    liveUids: Set<String>,
    cardW: androidx.compose.ui.unit.Dp,
    cardH: androidx.compose.ui.unit.Dp,
    startIndex: Int = 0,
    animateIn: Boolean = false,
    isTrickHuman: Boolean = false,
    validUids: Set<String> = emptySet(),
    pendingCardUid: String? = null,
    onTap: (Card) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
        templateCards.forEachIndexed { index, card ->
            val globalIndex = startIndex + index
            val slotModifier = Modifier.size(width = cardW, height = cardH)
            if (card.uid in liveUids) {
                val enabled = !isTrickHuman || card.uid in validUids
                CardTile(
                    card = card,
                    index = globalIndex,
                    animateIn = animateIn,
                    cardW = cardW,
                    enabled = enabled,
                    isPending = card.uid == pendingCardUid,
                    modifier = slotModifier.clickable { onTap(card) }
                )
            } else {
                // Card has been played — keep the slot as an invisible placeholder
                Box(modifier = slotModifier)
            }
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
    isPending: Boolean = false,
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

    val alpha = 1f  // cards are always fully visible (renege jokes later)
    val contentModifier = modifier
        .offset { IntOffset(offsetX.value.roundToInt(), 0) }
        .alpha(alpha)
        .then(
            if (isPending) Modifier.border(2.dp, Color(0xFFFFD700), RoundedCornerShape(4.dp))
            else Modifier
        )

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
