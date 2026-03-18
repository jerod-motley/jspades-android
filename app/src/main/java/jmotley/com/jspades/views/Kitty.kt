package jmotley.com.jspades.views

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.GamePhase
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.Rank
import jmotley.com.jspades.data.Suit
import jmotley.com.jspades.models.GameViewModel

private val GOLD    = Color(0xFFFFD700)
private val KITTY_W = 56.dp
private val KITTY_H = 80.dp
private val SEL_W   = 46.dp
private val SEL_H   = 66.dp

/**
 * KittyWinnerReveal: center overlay shown during [GamePhase.KittyReveal].
 *
 * Displays the 2♠ card and names the winner. Dismissed automatically after
 * 2 seconds when the [AnimationEvent.KittyRevealed] event completes.
 */
@Composable
fun KittyWinnerReveal(
    state: GameState,
    localPlayerId: String,
    modifier: Modifier = Modifier
) {
    val winnerId  = state.kittyWinnerId ?: return
    val winner    = state.players.find { it.id == winnerId }
    val nameLabel = if (winnerId == localPlayerId) "You win" else "${winner?.displayName} wins"

    val dealHand  = state.phaseHands[GamePhase.Deal]?.lastOrNull()
    val twoSpades = dealHand?.perPlayer?.get(winnerId)?.hand?.firstOrNull {
        it.suit == Suit.SPADES && (it.rank == Rank.TWO || it.rank == Rank.DEUCE)
    }

    Column(
        modifier = modifier
            .background(Color(0xDD0A0A14), RoundedCornerShape(16.dp))
            .padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text      = "$nameLabel the kitty!",
            color     = GOLD,
            style     = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign  = TextAlign.Center
        )
        if (twoSpades != null) {
            CardImage(card = twoSpades, width = KITTY_W, height = KITTY_H)
        }
    }
}

/**
 * KittyView: exchange UI for the human kitty winner, shown during [GamePhase.KittyHuman].
 *
 * All 18 cards (12 dealt + 6 kitty) are shown in 3 fixed rows of 6, anchored to the
 * bottom of the screen above the banner ad area. Kitty cards get a gold border + ★ badge.
 * Player taps exactly [needed] cards to discard (shrinks + dims). Confirm appears when
 * the correct count is selected.
 */
@Composable
fun KittyView(
    state: GameState,
    viewModel: GameViewModel,
    localPlayerId: String,
    modifier: Modifier = Modifier
) {
    val dealHand   = state.phaseHands[GamePhase.Deal]?.lastOrNull()
    val handCards  = dealHand?.perPlayer?.get(localPlayerId)?.hand ?: emptyList()
    val kittyCards = state.kitty?.perPlayer?.values?.flatMap { it.hand } ?: emptyList()
    val kittyUids  = kittyCards.map { it.uid }.toSet()
    // Kitty cards appended after hand cards so they occupy the third row
    val allCards   = (handCards + kittyCards).distinctBy { it.uid }
    val needed     = kittyCards.size.coerceAtLeast(1)

    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    val onToggle: (String) -> Unit = { uid ->
        val sel = selected
        selected = when {
            uid in sel          -> sel - uid
            sel.size < needed   -> sel + uid
            else                -> sel
        }
    }

    // Anchor the entire exchange UI to the bottom, matching HandView position
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 8.dp)
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ── Prompt ────────────────────────────────────────────────────────
            val selCount = selected.size
            Text(
                text  = "Select $needed to discard  ($selCount / $needed)  ★ = Kitty",
                color = if (selCount == needed) GOLD else Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall
            )

            // ── 3 rows of 6 cards ─────────────────────────────────────────────
            SelectionRow(allCards.take(6),       selected, kittyUids, onToggle)
            SelectionRow(allCards.drop(6).take(6), selected, kittyUids, onToggle)
            SelectionRow(allCards.drop(12),      selected, kittyUids, onToggle)

            // ── Confirm button ────────────────────────────────────────────────
            if (selCount == needed) {
                val discards = allCards.filter { it.uid in selected }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(Color(0xFF1B5E20), RoundedCornerShape(8.dp))
                        .clickable { viewModel.submitHumanKittyDiscard(discards) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text       = "Confirm Discard",
                        color      = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        style      = MaterialTheme.typography.titleMedium
                    )
                }
            } else {
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

// ── Private helpers ────────────────────────────────────────────────────────────

@Composable
private fun SelectionRow(
    cards: List<Card>,
    selected: Set<String>,
    kittyUids: Set<String>,
    onToggle: (String) -> Unit
) {
    if (cards.isEmpty()) return
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        cards.forEach { card ->
            val isMarked  = card.uid in selected
            val isKitty   = card.uid in kittyUids
            Box(
                modifier = Modifier
                    .size(SEL_W, SEL_H)
                    .scale(if (isMarked) 0.8f else 1f)
                    .alpha(if (isMarked) 0.8f else 1f)
                    .then(if (isKitty) Modifier.border(2.dp, GOLD, RoundedCornerShape(4.dp)) else Modifier)
                    .clickable { onToggle(card.uid) }
            ) {
                CardImage(card, SEL_W, SEL_H)
                if (isKitty) {
                    Text(
                        text     = "★",
                        color    = GOLD,
                        style    = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.TopEnd).padding(1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CardImage(card: Card, width: Dp, height: Dp) {
    val context   = LocalContext.current
    val assetName = card.assetFileName().removeSuffix(".png")
    val resId     = context.resources.getIdentifier(assetName, "drawable", context.packageName)

    Box(modifier = Modifier.size(width, height), contentAlignment = Alignment.Center) {
        if (resId != 0) {
            Image(
                painter            = painterResource(id = resId),
                contentDescription = "${card.rank.name} of ${card.suit.name}",
                modifier           = Modifier.size(width, height),
                contentScale       = ContentScale.Fit
            )
        } else {
            Box(
                modifier           = Modifier
                    .size(width, height)
                    .background(Color(0xFF37474F), RoundedCornerShape(4.dp)),
                contentAlignment   = Alignment.Center
            ) {
                Text(
                    text      = "${card.rank.value}\n${card.suit.name.first()}",
                    color     = Color.White,
                    style     = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
