package jmotley.com.jspades.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jmotley.com.jspades.R

/** Which sub-menu is visible, or None for the root. */
private enum class SubMenu { None, TeamPlay, Solo, Online }

@Composable
fun MainMenuScreen(
    onNavigateToPlay: (gameType: String) -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToChallenges: () -> Unit = {},
    onNavigateToMessages: () -> Unit = {},
    onNavigateToSuggestions: () -> Unit = {},
    onNavigateToRenegeJokes: () -> Unit = {},
    onNavigateToStandings: () -> Unit = {}
) {
    var currentMenu by remember { mutableStateOf(SubMenu.None) }
    val jennasue = FontFamily(Font(R.font.jenna_sue))

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val maxH = this.maxHeight

        // full-screen background
        Image(
            painter = painterResource(id = R.drawable.bg_home),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // buttons start 30% from top
        val topPad: Dp = maxH * 0.3f

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            when (currentMenu) {
                SubMenu.None -> MainButtons(jennasue) { tapped ->
                    when (tapped) {
                        "Team Play"      -> currentMenu = SubMenu.TeamPlay
                        "Solo Play"      -> currentMenu = SubMenu.Solo
                        "Challenge Mode" -> onNavigateToChallenges()
                        "Online"         -> currentMenu = SubMenu.Online
                        "Profile"        -> onNavigateToProfile()
                        "Standings"      -> onNavigateToStandings()
                        else             -> { /* no-op */ }
                    }
                }
                SubMenu.TeamPlay -> SubButtons(
                    labels = listOf("House Rules", "Kitty", "Classic", "Return"),
                    font = jennasue
                ) { tapped ->
                    if (tapped == "Return") currentMenu = SubMenu.None
                    else onNavigateToPlay(tapped)
                }
                SubMenu.Solo -> SubButtons(
                    labels = listOf("Four Man Solo", "Three Man Solo", "Two Man Solo", "Return"),
                    font = jennasue
                ) { tapped ->
                    if (tapped == "Return") currentMenu = SubMenu.None
                    else onNavigateToPlay(tapped)
                }
                SubMenu.Online -> SubButtons(
                    labels = listOf("Messages", "Suggestions", "Renege Jokes", "Return"),
                    font = jennasue
                ) { tapped ->
                    when (tapped) {
                        "Messages"     -> onNavigateToMessages()
                        "Suggestions"  -> onNavigateToSuggestions()
                        "Renege Jokes" -> onNavigateToRenegeJokes()
                        "Return"       -> currentMenu = SubMenu.None
                    }
                }
            }
        }
    }
}

@Composable
private fun MainButtons(font: FontFamily, onTap: (String) -> Unit) {
    val items = listOf(
        "Team Play",
        "Solo Play",
        "Challenge Mode",
        "Online",
        "Profile",
        "Standings",
        "Settings"
    )
    items.forEach { label ->
        MenuButton(text = label, font = font, onClick = { onTap(label) })
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun SubButtons(labels: List<String>, font: FontFamily, onTap: (String) -> Unit) {
    labels.forEach { label ->
        MenuButton(text = label, font = font, onClick = { onTap(label) })
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun MenuButton(text: String, font: FontFamily, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 260.dp, height = 56.dp)
            .clickable { onClick() }
    ) {
        Image(
            painter = painterResource(id = R.drawable.menu_item_bg),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )
        Text(
            text = text,
            fontFamily = font,
            fontSize = 36.sp,
            color = Color.White,
            modifier = Modifier.align(Alignment.Center),
            textAlign = TextAlign.Center
        )
    }
}