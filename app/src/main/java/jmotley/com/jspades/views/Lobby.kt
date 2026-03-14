package jmotley.com.jspades.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jmotley.com.jspades.data.Constants
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.models.GameViewModel
import kotlin.random.Random
import kotlinx.coroutines.delay

/** Delay between each CPU seat being revealed (ms). */
private const val REVEAL_DELAY_MS = 350L

/** Duration of the fade + slide entrance animation (ms). */
private const val ANIM_DURATION_MS = 450

/**
 * Lobby screen: four player names in a diamond (N / E / S / W).
 *
 * - South ("You") is visible immediately.
 * - West → North → East are revealed one at a time, each [REVEAL_DELAY_MS] apart.
 * - "Start Game" button fades in after all names appear.
 *
 * CPU names draw from [Constants.OFFLINE_PLAYER_NAMES] and [Constants.NAME_PAIRS]:
 *   - West may be paired with East; if so East's name is the pair value.
 *   - North always draws from OFFLINE_PLAYER_NAMES.
 *   - No name is reused across seats.
 */
@Composable
fun LobbyView(
    state: GameState,
    viewModel: GameViewModel,
    localPlayerId: String,
    gameType: GameType = GameType.TEAM_CLASSIC,
    modifier: Modifier = Modifier
) {
    // Generate CPU seat names once for this composition.
    data class SeatNames(val south: String, val west: String, val north: String, val east: String)
    val names: SeatNames = remember {
        val offline  = Constants.OFFLINE_PLAYER_NAMES
        val pairKeys = Constants.NAME_PAIRS.keys.toList()
        val combined = offline.size + pairKeys.size
        val used     = mutableSetOf<String>()
        val rng      = Random.Default

        // 1. WEST — pick from combined pool; determines whether EAST is forced
        val wi = rng.nextInt(combined)
        val westName: String
        val forcedEast: String?          // non-null only when WEST is a pair key
        if (wi < offline.size) {
            westName  = offline[wi]
            forcedEast = null
        } else {
            westName  = pairKeys[wi - offline.size]
            forcedEast = Constants.NAME_PAIRS[westName]!!
        }
        used += westName
        forcedEast?.let { used += it }   // reserve pair value so NORTH can't take it

        // 2. NORTH — always from offline, never a pair name, never a repeat
        val northName = offline.filter { it !in used }.random(rng)
        used += northName

        // 3. EAST — forced by pair, or free pick from remaining offline names
        val eastName = forcedEast ?: offline.filter { it !in used }.random(rng)

        SeatNames(south = "You", west = westName, north = northName, east = eastName)
    }

    var showWest  by remember { mutableStateOf(false) }
    var showNorth by remember { mutableStateOf(false) }
    var showEast  by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Reveal CPU seats according to player count, then kick off the deal.
        when (gameType.playerCount) {
            4 -> {
                delay(REVEAL_DELAY_MS); showWest  = true
                delay(REVEAL_DELAY_MS); showNorth = true
                delay(REVEAL_DELAY_MS); showEast  = true
            }
            3 -> {
                delay(REVEAL_DELAY_MS); showWest = true
                delay(REVEAL_DELAY_MS); showEast = true
            }
            else -> {
                // 2-player: only North seat fills
                delay(REVEAL_DELAY_MS); showNorth = true
            }
        }
        delay(REVEAL_DELAY_MS)

        // Build ID / name lists for the seats that are actually in use.
        val (seatIds, seatNames) = when (gameType.playerCount) {
            4    -> listOf("south", "west",  "north",       "east") to
                    listOf(names.south, names.west, names.north, names.east)
            3    -> listOf("south", "west",  "east") to
                    listOf(names.south, names.west,  names.east)
            else -> listOf("south", "north") to
                    listOf(names.south, names.north)
        }
        viewModel.onLobbyComplete(ids = seatIds, names = seatNames, gameType = gameType)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalW = maxWidth
        val totalH = maxHeight

        // Diamond: arm lengths from screen center
        val armV: Dp = totalH * 0.22f
        val armH: Dp = totalW * 0.36f

        // Connector lines drawn behind the plates
        Canvas(modifier = Modifier
            .size(armH * 2, armV * 2)
            .align(Alignment.Center)
        ) {
            val cx = size.width / 2
            val cy = size.height / 2
            val lineColor = Color(0x55FFFFFF)
            val sw = 1.5f
            drawLine(lineColor, Offset(cx, cy), Offset(cx,          0f),          sw)
            drawLine(lineColor, Offset(cx, cy), Offset(cx,          size.height), sw)
            drawLine(lineColor, Offset(cx, cy), Offset(0f,          cy),          sw)
            drawLine(lineColor, Offset(cx, cy), Offset(size.width,  cy),          sw)
        }

        // ── SOUTH — always visible ────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = armV)
        ) {
            NamePlate(name = names.south, isLocal = true)
        }

        // ── WEST ──────────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = -armH)
        ) {
            AnimatedVisibility(
                visible = showWest,
                enter = fadeIn(tween(ANIM_DURATION_MS)) +
                        slideInHorizontally(tween(ANIM_DURATION_MS)) { -48 }
            ) {
                NamePlate(name = names.west, isLocal = false)
            }
        }

        // ── NORTH ─────────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = -armV)
        ) {
            AnimatedVisibility(
                visible = showNorth,
                enter = fadeIn(tween(ANIM_DURATION_MS)) +
                        slideInVertically(tween(ANIM_DURATION_MS)) { -48 }
            ) {
                NamePlate(name = names.north, isLocal = false)
            }
        }

        // ── EAST ──────────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = armH)
        ) {
            AnimatedVisibility(
                visible = showEast,
                enter = fadeIn(tween(ANIM_DURATION_MS)) +
                        slideInHorizontally(tween(ANIM_DURATION_MS)) { 48 }
            ) {
                NamePlate(name = names.east, isLocal = false)
            }
        }

    }
}

@Composable
private fun NamePlate(name: String, isLocal: Boolean) {
    val bg     = if (isLocal) Color(0xCC1B5E20) else Color(0xCC000000)
    val fg     = if (isLocal) Color(0xFFFFD700) else Color.White
    val weight = if (isLocal) FontWeight.ExtraBold else FontWeight.SemiBold

    Box(
        modifier = Modifier
            .wrapContentSize()
            .background(color = bg, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name,
            color = fg,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = weight,
            textAlign = TextAlign.Center
        )
    }
}
