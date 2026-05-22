package jmotley.com.jspades.screens

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import jmotley.com.jspades.R
import jmotley.com.jspades.data.AppConfig

private val StdDarkBg       = Color(0xFF1A1A2E)
private val StdAccentGold   = Color(0xFFFFD700)
private val StdTextSecondary = Color.White

@Composable
fun StandingsScreen(onNavigateBack: () -> Unit) {
    val font = FontFamily(Font(R.font.jenna_sue))
    val url  = AppConfig.LEADERBOARD_URL

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
        Text(
            "Standings",
            fontFamily = font,
            style = MaterialTheme.typography.displaySmall,
            color = StdAccentGold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
            textAlign = TextAlign.Center
        )

        if (url.isBlank()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Leaderboard coming soon!",
                    color = StdTextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewClient = WebViewClient()
                        settings.javaScriptEnabled = true
                        loadUrl(url)
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }

    }
        TextButton(
            onClick = onNavigateBack,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)
        ) {
            Text("Back", color = StdTextSecondary, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
