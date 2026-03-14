package jmotley.com.jspades.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import jmotley.com.jspades.R
import jmotley.com.jspades.data.AchievementIds
import jmotley.com.jspades.data.AchievementItem
import jmotley.com.jspades.data.Stats
import jmotley.com.jspades.models.ProfilePhase
import jmotley.com.jspades.models.ProfileViewModel

private val DarkBg = Color(0xFF1A1A2E)
private val CardBg = Color(0xFF16213E)
private val AccentGreen = Color(0xFF4CAF50)
private val AccentGold = Color(0xFFFFD700)
private val TextPrimary = Color.White
private val TextSecondary = Color.White

@Composable
fun ProfileScreen(
    onNavigateBack: () -> Unit,
    viewModel: ProfileViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val font = FontFamily(Font(R.font.jenna_sue))

    LaunchedEffect(Unit) {
        viewModel.onEnter()
    }

    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onNavigateBack() }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Image(
            painter = painterResource(id = R.drawable.bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        when (state.phase) {
            ProfilePhase.FORM -> RegistrationForm(
                email = state.email,
                username = state.username,
                errorMessage = state.errorMessage,
                font = font,
                onEmailChange = viewModel::updateEmail,
                onUsernameChange = viewModel::updateUsername,
                onSubmit = viewModel::submit,
                onBack = onNavigateBack
            )
            ProfilePhase.CHECKING -> LoadingView(font)
            ProfilePhase.SENT -> SentView(font)
            ProfilePhase.NOT_VERIFIED -> NotVerifiedView(
                errorMessage = state.errorMessage,
                font = font,
                onRecheck = viewModel::checkStatus,
                onBack = onNavigateBack
            )
            ProfilePhase.REGISTERED -> RegisteredView(
                username = state.username,
                stats = state.stats,
                achievements = state.achievements,
                font = font,
                onBack = onNavigateBack
            )
        }
    }
}

@Composable
private fun RegistrationForm(
    email: String,
    username: String,
    errorMessage: String?,
    font: FontFamily,
    onEmailChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Profile", fontFamily = font, style = MaterialTheme.typography.displaySmall, color = AccentGold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Create a free account to sync your stats and achievements.",
            color = TextSecondary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("Username", color = TextSecondary) },
            singleLine = true,
            colors = outlinedTextFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text("Email", color = TextSecondary) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            colors = outlinedTextFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )

        if (errorMessage != null) {
            Spacer(Modifier.height(12.dp))
            Text(errorMessage, color = Color.Red, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentGold),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Register", color = Color.Black, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack) {
            Text("Back", color = TextSecondary)
        }
    }
}

@Composable
private fun LoadingView(font: FontFamily) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = AccentGold)
        Spacer(Modifier.height(16.dp))
        Text("Checking...", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SentView(font: FontFamily) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Check Your Email", fontFamily = font, style = MaterialTheme.typography.headlineMedium, color = AccentGold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            "A verification link has been sent. Click the link in your email, then return to the app.",
            color = TextSecondary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun NotVerifiedView(
    errorMessage: String?,
    font: FontFamily,
    onRecheck: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Not Verified Yet", fontFamily = font, style = MaterialTheme.typography.headlineMedium, color = AccentGold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            "Your email hasn't been verified. Check your inbox and click the verification link.",
            color = TextSecondary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )
        if (errorMessage != null) {
            Spacer(Modifier.height(12.dp))
            Text(errorMessage, color = Color.Red, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onRecheck,
            colors = ButtonDefaults.buttonColors(containerColor = AccentGold),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Recheck", color = Color.Black, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack) {
            Text("Back", color = TextSecondary)
        }
    }
}

@Composable
private fun RegisteredView(
    username: String,
    stats: Stats?,
    achievements: List<AchievementItem>,
    font: FontFamily,
    onBack: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onBack) {
                Text("Back", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
            }
            Text(username.ifBlank { "Player" }, fontFamily = font, style = MaterialTheme.typography.displaySmall, color = AccentGold,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text("Verified Player", color = AccentGreen, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
        }

        if (stats == null || stats.gamesPlayed == 0) {
            item {
                StatCard("Stats") {
                    Text(
                        "No local stats yet. Play a game to start tracking!",
                        color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        } else {
            val winRate = if (stats.gamesPlayed > 0) stats.wins * 100 / stats.gamesPlayed else 0
            val bookDiff = stats.booksWon - stats.booksAgainst
            val totalBids = stats.bidsMade + stats.bidsSet
            val bidSuccessRate = if (totalBids > 0) stats.bidsMade * 100 / totalBids else 0
            val avgBid = if (stats.bidsMade > 0) stats.totalBidLevel.toFloat() / stats.bidsMade else 0f
            val totalDeclarer = stats.handsAsDeclarerWon + stats.handsAsDeclarerLost
            val declarerRate = if (totalDeclarer > 0) stats.handsAsDeclarerWon * 100 / totalDeclarer else 0

            item {
                StatCard("Match Record") {
                    StatRow("Games Played", "${stats.gamesPlayed}")
                    StatRow("Wins", "${stats.wins}")
                    StatRow("Losses", "${stats.losses}")
                    StatRow("Win Rate", "$winRate%")
                    StatRow("Bostons", "${stats.bostons}")
                    StatRow("Win Streak", "${stats.consecutiveWins}")
                }
            }
            item {
                StatCard("Books Performance") {
                    StatRow("Books Won", "${stats.booksWon}")
                    StatRow("Books Against", "${stats.booksAgainst}")
                    StatRow("Book Differential", if (bookDiff >= 0) "+$bookDiff" else "$bookDiff")
                }
            }
            item {
                StatCard("Bidding Performance") {
                    StatRow("Bids Made", "${stats.bidsMade}")
                    StatRow("Bids Set", "${stats.bidsSet}")
                    StatRow("Bid Success Rate", "$bidSuccessRate%")
                    StatRow("Avg Bid Level", String.format("%.1f", avgBid))
                }
            }
            item {
                StatCard("As Bid Winner") {
                    StatRow("Hands Won", "${stats.handsAsDeclarerWon}")
                    StatRow("Hands Lost", "${stats.handsAsDeclarerLost}")
                    StatRow("Success Rate", "$declarerRate%")
                }
            }
        }

        item {
            Text("Achievements", fontFamily = font, style = MaterialTheme.typography.headlineMedium, color = AccentGold,
                modifier = Modifier.padding(top = 8.dp))
        }

        items(AchievementIds.ALL) { meta ->
            val earned = achievements.find { it.name == meta.id && it.achieved }
            AchievementRow(
                title = meta.title,
                description = meta.description,
                earnedDate = earned?.date,
                achieved = earned != null
            )
        }

        item {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = AccentGold, style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val fontScale = LocalConfiguration.current.fontScale
    if (fontScale >= 1.3f) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            Text(value, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            Text(value, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AchievementRow(title: String, description: String, earnedDate: String?, achieved: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (achieved) "✓" else "🔒",
                style = MaterialTheme.typography.titleLarge,
                color = if (achieved) AccentGreen else TextSecondary,
                modifier = Modifier.width(36.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = if (achieved) TextPrimary else TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Text(description, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                if (earnedDate != null) {
                    val displayDate = earnedDate.take(10) // ISO-8601 date portion
                    Text(displayDate, color = AccentGreen, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun outlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = AccentGold,
    unfocusedBorderColor = TextSecondary,
    cursorColor = AccentGold
)
