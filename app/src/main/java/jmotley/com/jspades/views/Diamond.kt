package jmotley.com.jspades.views

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.Player
import jmotley.com.jspades.models.GameViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.ui.draw.scale

/** Card slot size in the diamond. */
private val SLOT_W = 64.dp
private val SLOT_H = 96.dp

/**
 * Diamond view: displays the active player positions with their played cards,
 * bid badges, and winner flash.
 *
 * Seats are looked up by canonical id ("south", "north", "east", "west").
 *
 * [trickWinner] — when non-null, the matching player's slot flashes gold to
 * indicate they took the trick. Set by PlayScreen from AnimationEvent.TrickWon.
 */
@Composable
fun DiamondView(
    state: GameState,
    viewModel: GameViewModel,
    localPlayerId: String,
    trickWinner: String? = null,
    frozenPlays: List<jmotley.com.jspades.data.Play> = emptyList(),
    modifier: Modifier = Modifier
) {
    val south = state.players.find { it.id == "south" }
    val north = state.players.find { it.id == "north" }
    val east  = state.players.find { it.id == "east"  }
    val west  = state.players.find { it.id == "west"  }

    // Map playerId → card played this trick.
    // During TrickResolve animation, currentTrick is already cleared so we
    // fall back to frozenPlays (the snapshot captured before collectTrick ran).
    val playedCards: Map<String, Card> = if (frozenPlays.isNotEmpty()) {
        frozenPlays.associate { it.playerId to it.card }
    } else {
        state.currentTrick.plays
            .filterNotNull()
            .associate { it.playerId to it.card }
    }

    // Map playerId → bid (0 = not yet bid)
    val density = LocalDensity.current
    val slotW   = with(density) { (SLOT_W + 16.dp).toPx() }
    val slotH   = with(density) { (SLOT_H + 24.dp).toPx() }

    BoxWithConstraints(modifier = modifier.size(280.dp, 320.dp)) {

        val hasTrickWinner = trickWinner != null

        // North — 4-player only
        north?.let { player ->
            PlayerSlot(
                player             = player,
                card               = playedCards[player.id],
                label              = player.displayName,
                isWinner           = trickWinner == player.id,
                hasTrickWinner     = hasTrickWinner,
                initialCardOffset  = Offset(0f, -slotH * 1.5f),  // slides in from above
                modifier           = Modifier.align(Alignment.TopCenter)
            )
        }

        // South — always present (local player)
        south?.let { player ->
            PlayerSlot(
                player             = player,
                card               = playedCards[player.id],
                label              = player.displayName,
                isWinner           = trickWinner == player.id,
                isLocal            = player.id == localPlayerId,
                hasTrickWinner     = hasTrickWinner,
                initialCardOffset  = Offset(0f, slotH * 1.5f),   // slides in from below
                modifier           = Modifier.align(Alignment.BottomCenter)
            )
        }

        // West — 3- and 4-player
        west?.let { player ->
            PlayerSlot(
                player             = player,
                card               = playedCards[player.id],
                label              = player.displayName,
                isWinner           = trickWinner == player.id,
                hasTrickWinner     = hasTrickWinner,
                initialCardOffset  = Offset(-slotW * 1.5f, 0f),  // slides in from left
                modifier           = Modifier.align(Alignment.CenterStart)
            )
        }

        // East — 3- and 4-player
        east?.let { player ->
            PlayerSlot(
                player             = player,
                card               = playedCards[player.id],
                label              = player.displayName,
                isWinner           = trickWinner == player.id,
                hasTrickWinner     = hasTrickWinner,
                initialCardOffset  = Offset(slotW * 1.5f, 0f),   // slides in from right
                modifier           = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
private fun PlayerSlot(
    player: Player?,
    card: Card?,
    label: String,
    isWinner: Boolean = false,
    isLocal: Boolean = false,
    hasTrickWinner: Boolean = false,
    /**
     * Pixel offset the card starts from before animating into position.
     * (0,0) = no animation; otherwise the card slides from this offset to rest.
     */
    initialCardOffset: Offset = Offset.Zero,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // ── Card slide-in animation ──────────────────────────────────────────────
    val cardOffX = remember { Animatable(initialCardOffset.x) }
    val cardOffY = remember { Animatable(initialCardOffset.y) }

    LaunchedEffect(card?.uid) {
        if (card != null) {
            // Snap to initial position then slide to rest (0, 0)
            cardOffX.snapTo(initialCardOffset.x)
            cardOffY.snapTo(initialCardOffset.y)
            launch { cardOffX.animateTo(0f, tween(350)) }
            cardOffY.animateTo(0f, tween(350))
        } else {
            cardOffX.snapTo(0f)
            cardOffY.snapTo(0f)
        }
    }

    // ── Winner border (shows whenever alpha > 0 during transition) ───────────
    val winnerBorderAlpha by animateFloatAsState(
        targetValue   = if (isWinner) 1f else 0f,
        animationSpec = tween(180),
        label         = "winner-border"
    )

    // ── Trick-resolve: winner scale-up, loser dim + timed disappear ──────────
    val cardScale = remember { Animatable(1f) }
    val cardAlpha = remember { Animatable(1f) }

    // Winner card grows 20% while it's the winning slot
    LaunchedEffect(isWinner) {
        cardScale.animateTo(if (isWinner) 1.2f else 1.0f, tween(200))
    }

    // Losers dim to 75% opacity then fade out after 1000ms; winner stays full then fades after 1500ms
    LaunchedEffect(hasTrickWinner) {
        if (hasTrickWinner) {
            if (!isWinner && card != null) {
                // Losers: dim immediately, disappear after 1000ms
                cardAlpha.animateTo(0.75f, tween(150))
                delay(850L)
                cardAlpha.animateTo(0f, tween(200))
            } else if (isWinner) {
                // Winner: stay full, disappear after 1500ms
                delay(1300L)
                cardAlpha.animateTo(0f, tween(200))
            }
        } else {
            cardAlpha.snapTo(1f)
        }
    }

    Box(
        modifier = modifier
            .size(SLOT_W + 16.dp, SLOT_H + 24.dp)
            .then(
                if (winnerBorderAlpha > 0f)
                    Modifier.border(
                        width = 2.dp,
                        color = Color(0xFFFFD700).copy(alpha = winnerBorderAlpha),
                        shape = RoundedCornerShape(10.dp)
                    )
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        // ── Card image or empty slot ─────────────────────────────────────────
        val cardModifier = Modifier
            .size(SLOT_W, SLOT_H)
            .offset { IntOffset(cardOffX.value.roundToInt(), cardOffY.value.roundToInt()) }
            .scale(cardScale.value)
            .alpha(cardAlpha.value)

        val displayCard = card
        if (displayCard != null) {
            val assetName = displayCard.assetFileName().removeSuffix(".png")
            val resId     = context.resources.getIdentifier(assetName, "drawable", context.packageName)
            if (resId != 0) {
                Image(
                    painter            = androidx.compose.ui.res.painterResource(id = resId),
                    contentDescription = null,
                    modifier           = cardModifier,
                    contentScale       = ContentScale.Fit
                )
            }
        } else {
            Box(
                modifier = cardModifier.background(
                    color  = Color(0x33FFFFFF),
                    shape  = RoundedCornerShape(8.dp)
                )
            )
        }

        // ── Player name label ────────────────────────────────────────────────
        Text(
            text       = label,
            color      = if (isLocal) Color(0xFFFFD700) else Color.White,
            fontSize   = 22.sp,
            fontWeight = if (isLocal) FontWeight.Bold else FontWeight.Normal,
            textAlign  = TextAlign.Center,
            softWrap   = false,
            overflow   = TextOverflow.Visible,
            modifier   = Modifier
                .align(Alignment.BottomCenter)
                .padding(top = 4.dp)
        )
    }
}
