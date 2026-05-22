package jmotley.com.jspades.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jmotley.com.jspades.R
import jmotley.com.jspades.data.AchievementItem
import jmotley.com.jspades.data.AchievementsRepo
import jmotley.com.jspades.data.Challenge
import jmotley.com.jspades.data.ChallengesRepo

private val DarkBg     = Color(0xFF1A1A2E)
private val CardBg     = Color(0xFF16213E)
private val AccentGold = Color(0xFFFFD700)
private val AccentGreen= Color(0xFF4CAF50)
private val TextPrimary   = Color.White
private val TextSecondary = Color.White

/**
 * Screen that lists all challenges for a given game type.
 *
 * @param gameTypeLabel  The game type label (e.g. "House Rules", "Classic").
 * @param onStartChallenge  Called with the challenge SK when the player taps "Start".
 *                          Caller should navigate to the play screen after this.
 * @param onNavigateBack    Called when the player taps Back.
 */
@Composable
fun ChallengesScreen(
    gameTypeLabel: String,
    onStartChallenge: (sk: String, gameType: String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context  = LocalContext.current
    val font     = FontFamily(Font(R.font.jenna_sue))

    // Seed and load challenges on first open
    LaunchedEffect(Unit) {
        ChallengesRepo.init(context)
    }

    val completedList   by AchievementsRepo.completedChallengesFlow.collectAsState()
    val activeChallenge by AchievementsRepo.activeChallengeFlow.collectAsState()

    val allChallenges by ChallengesRepo.challengesFlow.collectAsState()
    val challenges = remember(allChallenges, gameTypeLabel) {
        ChallengesRepo.forGameType(gameTypeLabel)
    }
    val completedSks = remember(completedList, gameTypeLabel) {
        completedList
            .filter { it.achieved && (it.gameType.isBlank() || it.gameType == gameTypeLabel) }
            .map { it.challengeSk() }
            .toSet()
    }

    // State for the MinBooks:X picker dialog
    var pendingChallenge by remember { mutableStateOf<Challenge?>(null) }
    var pickedX by remember { mutableStateOf(7) }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Image(
            painter = painterResource(id = R.drawable.bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Challenges",
                    fontFamily = font,
                    fontSize = 40.sp,
                    color = AccentGold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
            }

            if (challenges.isEmpty()) {
                item {
                    Text(
                        "No challenges available for this game type.",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                items(challenges) { ch ->
                    val isCompleted = ch.sk in completedSks
                    val isActive    = ch.sk == activeChallenge
                    ChallengeCard(
                        challenge   = ch,
                        isCompleted = isCompleted,
                        isActive    = isActive,
                        onStart = {
                            if (ch.winCriteria == "MinBooks:X") {
                                pendingChallenge = ch
                                pickedX = 7
                            } else {
                                AchievementsRepo.setActiveChallenge(context, ch.sk, ch.winCriteria, ch.allowBid, ch.shortText)
                                AchievementsRepo.markChallengePending(context, ch.sk)
                                onStartChallenge(ch.sk, gameTypeLabel)
                            }
                        }
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
        TextButton(
            onClick = onNavigateBack,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)
        ) {
            Text("Back", color = TextSecondary, fontSize = 16.sp)
        }
    }

    // MinBooks picker dialog
    val ch = pendingChallenge
    if (ch != null) {
        MinBooksDialog(
            challengeTitle = ch.shortText,
            currentX = pickedX,
            onXChange = { pickedX = it },
            onConfirm = {
                val bidrule = "MinBooks:$pickedX"
                AchievementsRepo.setActiveChallenge(context, ch.sk, bidrule, ch.allowBid, ch.shortText)
                AchievementsRepo.markChallengePending(context, ch.sk)
                pendingChallenge = null
                onStartChallenge(ch.sk, gameTypeLabel)
            },
            onDismiss = { pendingChallenge = null }
        )
    }
}

@Composable
private fun ChallengeCard(
    challenge: Challenge,
    isCompleted: Boolean,
    isActive: Boolean,
    onStart: () -> Unit
) {
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
                text = when {
                    isCompleted -> "✓"
                    isActive    -> "▶"
                    else        -> "○"
                },
                fontSize = 20.sp,
                color = when {
                    isCompleted -> AccentGreen
                    isActive    -> AccentGold
                    else        -> TextSecondary
                },
                modifier = Modifier.width(32.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    challenge.shortText,
                    color = if (isCompleted) AccentGreen else TextPrimary,
                    fontSize = 15.sp
                )
                Text(
                    challenge.longDesc,
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                if (isActive) {
                    Text("Active", color = AccentGold, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = AccentGold),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Start", color = Color.Black, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun MinBooksDialog(
    challengeTitle: String,
    currentX: Int,
    onXChange: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(challengeTitle, color = AccentGold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("How many books?", color = TextSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = { if (currentX > 1) onXChange(currentX - 1) }) {
                        Text("−", fontSize = 22.sp, color = AccentGold)
                    }
                    Text(
                        "$currentX",
                        fontSize = 28.sp,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    TextButton(onClick = { if (currentX < 13) onXChange(currentX + 1) }) {
                        Text("+", fontSize = 22.sp, color = AccentGold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Start", color = AccentGold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
        containerColor = CardBg
    )
}
