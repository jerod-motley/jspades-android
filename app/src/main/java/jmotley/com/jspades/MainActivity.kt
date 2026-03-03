package jmotley.com.jspades

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.Rank
import jmotley.com.jspades.data.Suit
import jmotley.com.jspades.ui.theme.JSpadesTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JSpadesTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    GameScreen()
                }
            }
        }
    }
}

@Composable
fun GameScreen() {
    // card visual size
    val cardWidthDp: Dp = 80.dp
    val cardHeightDp: Dp = 120.dp

    // create four random cards (north, east, south, west)
    val cards = remember {
        listOf(
            Card(Suit.SPADES, Rank.TWO),
            Card(Suit.HEARTS, Rank.THREE),
            Card(Suit.CLUBS, Rank.FOUR),
            Card(Suit.DIAMONDS, Rank.FIVE)
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }

        // compute target positions for the player diamond
        val cardW = with(density) { cardWidthDp.toPx() }
        val cardH = with(density) { cardHeightDp.toPx() }
        val safeTop = 24.dp
        val safeTopPx = with(density) { safeTop.toPx() }

        // north: centered horizontally, top respecting safe area
        val northX = (containerWidthPx - cardW) / 2f
        val northY = safeTopPx

        // south: two card heights below north
        val southX = northX
        val southY = northY + cardH * 2f

        // east/west vertically between north and south
        val middleY = (northY + southY) / 2f
        // east is one card width left of north center point
        val eastX = northX - cardW
        val westX = northX + cardW

        // starting positions (off-screen)
        val offTopY = -cardH - 100f
        val offBottomY = containerHeightPx + 100f
        val offLeftX = -cardW - 100f
        val offRightX = containerWidthPx + 100f

        // create animatables for each card (separate X/Y floats to avoid Offset converter issues)
        val animNX = remember { Animatable(northX) }
        val animNY = remember { Animatable(offTopY) }

        val animEX = remember { Animatable(offLeftX) }
        val animEY = remember { Animatable(middleY) }

        val animSX = remember { Animatable(southX) }
        val animSY = remember { Animatable(offBottomY) }

        val animWX = remember { Animatable(offRightX) }
        val animWY = remember { Animatable(middleY) }

        // launch animations sequentially: north, east, south, west one at a time
        LaunchedEffect(Unit) {
            val duration = 600
            // north slides in from the top
            launch { animNX.animateTo(northX, animationSpec = tween(durationMillis = duration)) }
            animNY.animateTo(northY, animationSpec = tween(durationMillis = duration))
            // east slides in from the left
            launch { animEY.animateTo(middleY, animationSpec = tween(durationMillis = duration)) }
            animEX.animateTo(eastX, animationSpec = tween(durationMillis = duration))
            // south slides in from the bottom
            launch { animSX.animateTo(southX, animationSpec = tween(durationMillis = duration)) }
            animSY.animateTo(southY, animationSpec = tween(durationMillis = duration))
            // west slides in from the right
            launch { animWY.animateTo(middleY, animationSpec = tween(durationMillis = duration)) }
            animWX.animateTo(westX, animationSpec = tween(durationMillis = duration))
        }

        // render cards at animated positions (compose Offset from X/Y animatables)
        CardView(cards[0], cardWidthDp, cardHeightDp, Offset(animNX.value, animNY.value))
        CardView(cards[1], cardWidthDp, cardHeightDp, Offset(animEX.value, animEY.value))
        CardView(cards[2], cardWidthDp, cardHeightDp, Offset(animSX.value, animSY.value))
        CardView(cards[3], cardWidthDp, cardHeightDp, Offset(animWX.value, animWY.value))
    }
}

@Composable
fun CardView(card: Card, w: Dp, h: Dp, pos: Offset) {
    val density = LocalDensity.current
    val xDp = with(density) { pos.x.toDp() }
    val yDp = with(density) { pos.y.toDp() }
    val context = LocalContext.current

    // resolve drawable resource by filename (without extension)
    val resName = card.assetFileName().substringBeforeLast('.')
    val resId = remember(resName) { context.resources.getIdentifier(resName, "drawable", context.packageName) }

    Box(modifier = Modifier
        .zIndex(1f)
        .offset(x = xDp, y = yDp)
    ) {
        if (resId != 0) {
            Image(
                painter = painterResource(id = resId),
                contentDescription = null,
                modifier = Modifier.size(w, h)
            )
        } else {
            // fallback: white box with filename if resource not found
            Box(modifier = Modifier.size(w, h).background(Color.White)) {
                Text(
                    text = card.assetFileName(),
                    modifier = Modifier.align(Alignment.Center),
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}