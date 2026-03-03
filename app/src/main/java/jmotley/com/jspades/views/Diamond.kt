package jmotley.com.jspades.views

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.Player
import jmotley.com.jspades.models.GameViewModel

/** Card slot size in the diamond. */
private val SLOT_W = 64.dp
private val SLOT_H = 96.dp

/**
 * Diamond view: displays the four player positions (North / East / South / West)
 * with each player's name and the card they have played into the current trick.
 *
 * Seat assignment relative to [localPlayerId]:
 *   - South  → local player  (index = local seat)
 *   - West   → seat + 1 (mod 4)
 *   - North  → seat + 2 (mod 4) — opponent across the table
 *   - East   → seat + 3 (mod 4)
 *
 * TODO: animate cards flying from off-screen to their slot positions as in the POC.
 */
@Composable
fun DiamondView(
    state: GameState,
    viewModel: GameViewModel,
    localPlayerId: String,
    modifier: Modifier = Modifier
) {
    val localSeat = state.players.indexOfFirst { it.id == localPlayerId }.coerceAtLeast(0)

    // Build the N/E/S/W player list rotated so local player is always South
    val rotated = List(4) { i -> state.players.getOrNull((localSeat + i) % 4) }
    val south = rotated[0]  // local
    val west  = rotated[1]
    val north = rotated[2]
    val east  = rotated[3]

    // Map playerId → card played this trick
    val playedCards: Map<String, Card> = state.currentTrick.plays
        .filterNotNull()
        .associate { it.playerId to it.card }

    BoxWithConstraints(modifier = modifier.size(280.dp, 320.dp)) {
        val cx = maxWidth  / 2
        val cy = maxHeight / 2

        // North
        PlayerSlot(
            player = north,
            card = north?.id?.let { playedCards[it] },
            label = north?.displayName ?: "—",
            modifier = Modifier.align(Alignment.TopCenter)
        )
        // South (local)
        PlayerSlot(
            player = south,
            card = south?.id?.let { playedCards[it] },
            label = south?.displayName ?: "—",
            isLocal = true,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        // West
        PlayerSlot(
            player = west,
            card = west?.id?.let { playedCards[it] },
            label = west?.displayName ?: "—",
            modifier = Modifier.align(Alignment.CenterStart)
        )
        // East
        PlayerSlot(
            player = east,
            card = east?.id?.let { playedCards[it] },
            label = east?.displayName ?: "—",
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun PlayerSlot(
    player: Player?,
    card: Card?,
    label: String,
    isLocal: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Box(
        modifier = modifier.size(SLOT_W + 16.dp, SLOT_H + 24.dp),
        contentAlignment = Alignment.Center
    ) {
        // Card image or empty slot
        if (card != null) {
            val assetName = card.assetFileName().removeSuffix(".png")
            val resId = context.resources.getIdentifier(assetName, "drawable", context.packageName)
            if (resId != 0) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(id = resId),
                    contentDescription = null,
                    modifier = Modifier.size(SLOT_W, SLOT_H),
                    contentScale = ContentScale.Fit
                )
            }
        } else {
            // Empty slot placeholder
            Box(
                modifier = Modifier
                    .size(SLOT_W, SLOT_H)
                    .background(
                        color = Color(0x33FFFFFF),
                        shape = RoundedCornerShape(8.dp)
                    )
            )
        }

        // Player name label
        Text(
            text = label,
            color = if (isLocal) Color(0xFFFFD700) else Color.White,
            fontSize = 11.sp,
            fontWeight = if (isLocal) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(top = 4.dp)
        )
    }
}
