package jmotley.com.jspades.views

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
 * Diamond view: displays the active player positions with their played cards.
 *
 * Seats are looked up by canonical id ("south", "north", "east", "west") so
 * the layout adapts naturally to all player counts:
 *   - 4 players: all four slots shown
 *   - 3 players (south / west / east): north slot is absent
 *   - 2 players (south / north): east and west slots are absent
 *
 * TODO: animate cards flying from off-screen to their slot positions.
 */
@Composable
fun DiamondView(
    state: GameState,
    viewModel: GameViewModel,
    localPlayerId: String,
    modifier: Modifier = Modifier
) {
    val south = state.players.find { it.id == "south" }
    val north = state.players.find { it.id == "north" }
    val east  = state.players.find { it.id == "east"  }
    val west  = state.players.find { it.id == "west"  }

    // Map playerId → card played this trick
    val playedCards: Map<String, Card> = state.currentTrick.plays
        .filterNotNull()
        .associate { it.playerId to it.card }

    BoxWithConstraints(modifier = modifier.size(280.dp, 320.dp)) {

        // North — 4-player only
        north?.let { player ->
            PlayerSlot(
                player = player,
                card = playedCards[player.id],
                label = player.displayName,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        // South — always present (local player)
        south?.let { player ->
            PlayerSlot(
                player = player,
                card = playedCards[player.id],
                label = player.displayName,
                isLocal = player.id == localPlayerId,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // West — 3- and 4-player
        west?.let { player ->
            PlayerSlot(
                player = player,
                card = playedCards[player.id],
                label = player.displayName,
                modifier = Modifier.align(Alignment.CenterStart)
            )
        }

        // East — 3- and 4-player
        east?.let { player ->
            PlayerSlot(
                player = player,
                card = playedCards[player.id],
                label = player.displayName,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
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
