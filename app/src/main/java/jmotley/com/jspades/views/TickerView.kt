package jmotley.com.jspades.views

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset

@Composable
fun TickerView(text: String) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val textStyle = MaterialTheme.typography.headlineMedium

    val textMeasurer = rememberTextMeasurer()
    val textWidthPx = remember(text, textStyle) {
        textMeasurer.measure(text = text, style = textStyle, softWrap = false).size.width.toFloat()
    }

    // maintain the same speed as the base spec: 3x screen width in 10s
    val speedPxPerMs = 3f * screenWidthPx / 10_000f
    val durationMs = ((screenWidthPx + textWidthPx) / speedPxPerMs).toInt()

    val offsetX = remember(text) { Animatable(screenWidthPx) }

    LaunchedEffect(text) {
        offsetX.snapTo(screenWidthPx)
        while (true) {
            offsetX.animateTo(
                targetValue = -textWidthPx,
                animationSpec = tween(durationMillis = durationMs, easing = LinearEasing)
            )
            offsetX.snapTo(screenWidthPx)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(vertical = 6.dp)
            .clipToBounds()
    ) {
        Text(
            text = text,
            color = Color(0xFFFFD700),
            style = textStyle,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .wrapContentWidth(unbounded = true)
                .offset { IntOffset(offsetX.value.toInt(), 0) }
        )
    }
}
