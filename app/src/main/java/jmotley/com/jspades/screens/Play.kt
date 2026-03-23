package jmotley.com.jspades.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel
import jmotley.com.jspades.R
import jmotley.com.jspades.data.AnimationEvent
import jmotley.com.jspades.data.GamePhase
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jmotley.com.jspades.data.AchievementsRepo
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.engine.ChallengeResult
import jmotley.com.jspades.models.GameViewModel
import jmotley.com.jspades.data.DealMode
import jmotley.com.jspades.views.BidView
import jmotley.com.jspades.views.BlindBidView
import jmotley.com.jspades.views.BlindExchangeView
import jmotley.com.jspades.views.DealPickView
import jmotley.com.jspades.views.DiamondView
import jmotley.com.jspades.views.EndGameView
import jmotley.com.jspades.views.EndHandView
import jmotley.com.jspades.views.ReplayHandView
import jmotley.com.jspades.views.GameInfoView
import jmotley.com.jspades.views.HandView
import jmotley.com.jspades.views.KittyView
import jmotley.com.jspades.views.KittyWinnerReveal
import jmotley.com.jspades.views.LobbyView
import android.app.Activity
import android.net.Uri
import androidx.compose.ui.viewinterop.AndroidView
import jmotley.com.jspades.ads.InterstitialManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Primary game screen. Composites phase-appropriate views over the table background.
 *
 * Phase → Views:
 *   Lobby          → LobbyView (full screen)
 *   Deal           → HandView (bottom)
 *   Kitty          → KittyView (center) + GameInfoView (top) + HandView (bottom)
 *   Bid            → DiamondView (center) + GameInfoView (top) + BidView* (center) + HandView (bottom)
 *   Play           → DiamondView (center) + GameInfoView (top) + HandView (bottom)
 *   TrickResolve   → same as Play
 *   Score/Finished → TODO: score overlay
 *
 * *BidView is only shown when it is the local player's turn to bid.
 *
 * @param localPlayerId The ID of the player sitting at this device (used to select
 *                      hand data and determine active-turn UI).
 * @param gameType      The game variant label chosen from the menu (e.g. "Classic", "Kitty",
 *                      "House Rules", "Four Man Solo"). Resolved to [GameType] via
 *                      [GameType.fromLabel]; defaults to [GameType.TEAM_CLASSIC].
 * @param viewModel     Injected by default via [viewModel()]; holds the live [GameState].
 */
@Composable
fun PlayScreen(
    localPlayerId: String,
    gameType: String = "Classic",
    onNavigateBack: () -> Unit = {},
    viewModel: GameViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val resolvedGameType = GameType.fromLabel(gameType)

    // Drives the gold winner flash in DiamondView — set from TrickWon events below.
    var trickWinner by remember { mutableStateOf<String?>(null) }
    // Snapshot of trick plays frozen for the resolve animation (cards stay visible after collectTrick clears currentTrick)
    var frozenPlays by remember { mutableStateOf<List<jmotley.com.jspades.data.Play>>(emptyList()) }
    var showQuitDialog by remember { mutableStateOf(false) }
    var showReplay by remember { mutableStateOf(false) }
    var showTapMessage by remember { mutableStateOf(false) }
    var showChallengeResult by remember { mutableStateOf<ChallengeResult?>(null) }
    var showEndHandOverlay by remember { mutableStateOf(false) }
    var showEndGameOverlay by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val currentVideoAsset by viewModel.currentVideoAsset.collectAsState()

    // Always clear the active challenge when leaving the play screen, so a
    // challenge that was started but not completed (e.g. user backed out) can
    // never bleed into a subsequent normal game.
    DisposableEffect(Unit) {
        onDispose { AchievementsRepo.clearActiveChallenge(context) }
    }

    // ── Renege joke state (persisted across sessions) ─────────────────────────
    var renegeJokeText by remember { mutableStateOf<String?>(null) }
    val renegeScope = androidx.compose.runtime.rememberCoroutineScope()
    var renegeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val renegeJokes = jmotley.com.jspades.data.Constants.RENEGE_JOKES
    val renegePrefs = remember { context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE) }
    val defaultRenegeMessage = "You must follow suit!"

    val onSpadesNotBroken: () -> Unit = {
        renegeJob?.cancel()
        renegeJokeText = "Spades have not broken yet"
        renegeJob = renegeScope.launch {
            delay(2500L)
            renegeJokeText = null
        }
    }

    val onRenegeAttempt: () -> Unit = {
        renegeJob?.cancel()
        val count = renegePrefs.getInt("renege_count", 0)
        val lastIndex = renegePrefs.getInt("last_joke_index", -1)

        if (count < 3) {
            // First 3 attempts: show default message
            renegeJokeText = defaultRenegeMessage
            renegePrefs.edit().putInt("renege_count", count + 1).apply()
        } else {
            // After 3 defaults: cycle through jokes sequentially
            val nextIndex = (lastIndex + 1) % renegeJokes.size
            renegeJokeText = renegeJokes[nextIndex]
            val editor = renegePrefs.edit().putInt("last_joke_index", nextIndex)
            // Reset only after every joke has been shown
            if (nextIndex == renegeJokes.size - 1) {
                editor.putInt("renege_count", 0).putInt("last_joke_index", -1)
            }
            editor.apply()
        }

        renegeJob = renegeScope.launch {
            delay(3000L)
            renegeJokeText = null
        }
    }

    // ── Video event collector ─────────────────────────────────────────────────
    // Merges all three video flows into a single currentVideoAsset state.
    LaunchedEffect("video") {
        launch { viewModel.frustratedVideo.collect { asset -> viewModel.setCurrentVideoAsset(asset) } }
        launch { viewModel.cardheadEvent.collect  { asset -> viewModel.setCurrentVideoAsset(asset) } }
        viewModel.bostonVideo.collect { asset -> viewModel.setCurrentVideoAsset(asset) }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Background ────────────────────────────────────────────────────────────
        Image(
            painter = painterResource(id = R.drawable.bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // ── Interstitial trigger ──────────────────────────────────────────────────
        // Shows an interstitial on EndHand (after any in-flight video finishes) and
        // on Finished. The end-phase overlay is gated so it only renders after the
        // ad closes (or is skipped immediately when no ad is cached).
        LaunchedEffect(state.phase) {
            val activity = context as? Activity ?: return@LaunchedEffect
            when (state.phase) {
                GamePhase.EndHand -> {
                    showEndHandOverlay = false
                    var waited = 0L
                    while (viewModel.currentVideoAsset.value != null && waited < 10_000L) {
                        delay(100); waited += 100
                    }
                    InterstitialManager.showIfReady(activity) {
                        InterstitialManager.preload(activity)
                        showEndHandOverlay = true
                    }
                }
                GamePhase.Finished -> {
                    showEndGameOverlay = false
                    InterstitialManager.showIfReady(activity) {
                        InterstitialManager.preload(activity)
                        showEndGameOverlay = true
                    }
                }
                else -> {
                    // Leaving EndHand/Finished — clear overlays so they don't flash on re-entry
                    showEndHandOverlay  = false
                    showEndGameOverlay  = false
                }
            }
        }

        // ── Challenge result collector ────────────────────────────────────────────
        // Collects ChallengeResult events emitted by PhaseManager, finalizes in storage,
        // and shows a pass/fail banner for 1.5 s. No interstitial is shown here —
        // the EndHand and Finished LaunchedEffects own the ad at hand/game end.
        LaunchedEffect(Unit) {
            viewModel.challengeResults.collect { cr ->
                AchievementsRepo.finalizeChallenge(context, cr.sk, resolvedGameType.label, cr.success)
                showChallengeResult = cr
                delay(1500)
                showChallengeResult = null
            }
        }

        // ── Animation event collector ─────────────────────────────────────────────
        // Collects one-shot events from PhaseManager (fire-and-forget pattern).
        // Each event drives an animation; execute() is called on completion so the
        // engine can advance to the next step.
        LaunchedEffect(Unit) {
            viewModel.animationEvents.collect { event ->
                when (event) {
                    is AnimationEvent.BidPlaced -> {
                        // Bid badge fades in via DiamondView state change — wait for it
                        delay(600)
                    }
                    is AnimationEvent.CardPlayed -> {
                        // Card slides in from player's seat via DiamondView state change
                        delay(550)
                    }
                    is AnimationEvent.TrickWon -> {
                        // Freeze played cards so DiamondView can show them during animation
                        // (collectTrick already cleared currentTrick before this event)
                        frozenPlays = event.plays
                        trickWinner = event.winnerId
                        delay(1750)
                        trickWinner = null
                        frozenPlays = emptyList()
                    }
                    is AnimationEvent.DealComplete -> {
                        // Wait for the last card's slide-in: (cardCount-1)×50ms stagger + 320ms.
                        // Uses the same constants as CardTile so we track the real animation end.
                        delay(((event.cardCount - 1) * 50 + 320).toLong())
                        val anyBlindBid = state.phaseHands[GamePhase.Deal]?.lastOrNull()
                            ?.perPlayer?.values?.any { it.isBlind } ?: false
                        when {
                            state.gameType == GameType.TEAM_KITTY ->
                                viewModel.advancePhase(GamePhase.DeuceReveal)
                            state.allowBlindExchange && state.gameType == GameType.TEAM_CLASSIC && anyBlindBid ->
                                viewModel.advancePhase(GamePhase.BlindExchange)
                            else ->
                                viewModel.advancePhase(GamePhase.Bid)
                        }
                    }
                    is AnimationEvent.DeuceRevealed -> {
                        // Show 2♠ holder for 2 seconds before kitty exchange begins
                        delay(2000)
                        viewModel.advancePhase(GamePhase.KittyReveal)
                    }
                    is AnimationEvent.KittyRevealed -> {
                        // Show winner reveal for 2 seconds, then route to the correct phase
                        delay(2000)
                        if (event.winnerId == localPlayerId) {
                            viewModel.advancePhase(GamePhase.KittyHuman)
                        } else {
                            viewModel.advancePhase(GamePhase.Kitty)
                        }
                    }
                }
                viewModel.phaseManager.execute()
            }
        }

        // ── Top header: show challenge short name when active, otherwise game type label ──
        val headerTitle = AchievementsRepo.getActiveChallengeShortText(context) ?: resolvedGameType.label
        Text(
            text = headerTitle,
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xFFFFD700),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(vertical = 4.dp)
        )

        // ── Close / quit button ───────────────────────────────────────────────────
        TextButton(
            onClick = {
                if (state.phase == GamePhase.Finished) onNavigateBack()
                else showQuitDialog = true
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 4.dp, end = 4.dp)
        ) {
            Text("✕", style = MaterialTheme.typography.titleSmall)
        }

        // ── Quit confirmation dialog ──────────────────────────────────────────────
        if (showQuitDialog) {
            AlertDialog(
                onDismissRequest = { showQuitDialog = false },
                title = { Text("Quit game?") },
                text = { Text("Your progress will be lost.") },
                confirmButton = {
                    TextButton(onClick = { showQuitDialog = false; onNavigateBack() }) {
                        Text("Quit")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showQuitDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // ── Phase-based view composition ──────────────────────────────────────────
        when (state.phase) {

            // ── Lobby ─────────────────────────────────────────────────────────
            GamePhase.Lobby -> {
                LobbyView(
                    state = state,
                    viewModel = viewModel,
                    localPlayerId = localPlayerId,
                    gameType = resolvedGameType,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── Deal ──────────────────────────────────────────────────────────
            // Deal: background work in progress — show a neutral table
            GamePhase.Deal -> { /* placeholder — cards dealing behind the scenes */ }

            // DealHuman: cards dealt — pick UI for Two Man Solo, hand view for all others
            GamePhase.DealHuman -> {
                if (state.gameType.dealMode == DealMode.TWO_MAN_ALTERNATE) {
                    DealPickView(
                        state = state,
                        viewModel = viewModel,
                        localPlayerId = localPlayerId,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
                        Spacer(Modifier.height(10.dp))
                        GameInfoView(
                            state = state, viewModel = viewModel,
                            localPlayerId = localPlayerId
                        )
                        HandView(
                            state = state, viewModel = viewModel,
                            localPlayerId = localPlayerId
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            // ── Blind bid (pre-deal decision) ─────────────────────────────────
            // BlindBid: engine-driven, show hand (cards face-down still dealt — show hand frame)
            GamePhase.BlindBid -> { /* engine processing — no special UI */ }

            // BlindBidHuman: show the decision panel over the table
            GamePhase.BlindBidHuman -> {
                BlindBidView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp)
                )
            }

            // BlindExchange: engine-driven CPU exchange
            GamePhase.BlindExchange -> { /* engine processing */ }

            // BlindExchangeHuman: show card selection UI over hand
            GamePhase.BlindExchangeHuman -> {
                Column(modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
                    Spacer(Modifier.height(10.dp))
                    GameInfoView(
                        state = state, viewModel = viewModel,
                        localPlayerId = localPlayerId
                    )
                    HandView(
                        state = state, viewModel = viewModel,
                        localPlayerId = localPlayerId
                    )
                    Spacer(Modifier.height(10.dp))
                }
                BlindExchangeView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 16.dp)
                )
            }

            // ── Bid ───────────────────────────────────────────────────────────
            // All bid phases: scoreboard panel at top, hand visible at bottom.
            // BidHuman adds the selector; BidReview adds the OK button.
            GamePhase.Bid,
            GamePhase.BidHuman,
            GamePhase.BidReview -> {
                Column(modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
                    Spacer(Modifier.height(10.dp))
                    GameInfoView(
                        state = state, viewModel = viewModel,
                        localPlayerId = localPlayerId
                    )
                    HandView(
                        state = state, viewModel = viewModel,
                        localPlayerId = localPlayerId
                    )
                    Spacer(Modifier.height(10.dp))
                }
                BidView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 28.dp, start = 16.dp, end = 16.dp)
                )
            }

            // ── Deuce reveal (kitty mode, pre-bid) ───────────────────────────
            // Show which player holds the 2♠ for 2 seconds, then proceed to bidding
            GamePhase.DeuceReveal -> {
                DiamondView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    trickWinner = null, frozenPlays = emptyList(), showKittyCard = true,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)
                )
                KittyWinnerReveal(
                    state = state, localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // ── Kitty ─────────────────────────────────────────────────────────
            // KittyReveal: diamond only (2♠ in winner's slot); no overlay — already shown at DeuceReveal
            GamePhase.KittyReveal -> {
                DiamondView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    trickWinner = null, frozenPlays = emptyList(), showKittyCard = true,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)
                )
            }

            // Kitty (CPU exchange): diamond visible; CPU resolves instantly in engine
            GamePhase.Kitty -> {
                DiamondView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    trickWinner = null, frozenPlays = emptyList(),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)
                )
            }

            // KittyHuman: full-screen card exchange UI — no diamond, no bid view
            GamePhase.KittyHuman -> {
                KittyView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── Trick / Play ──────────────────────────────────────────────────
            // All trick phases use the same layout so GameInfoView and HandView
            // never move. Cards are non-interactive during Trick and TrickResolve.
            GamePhase.Trick,
            GamePhase.TrickResolve,
            GamePhase.TrickHuman -> {
                DiamondView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    trickWinner = trickWinner,
                    frozenPlays = frozenPlays,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)
                )
                Column(modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
                    Spacer(Modifier.height(10.dp))
                    if (showTapMessage) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xCC1A3A5C), RoundedCornerShape(0.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Tap again to play", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    renegeJokeText?.let { joke ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xCC5C1A1A), RoundedCornerShape(0.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(joke, color = Color(0xFFFFD700), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    GameInfoView(
                        state = state, viewModel = viewModel,
                        localPlayerId = localPlayerId
                    )
                    HandView(
                        state = state, viewModel = viewModel,
                        localPlayerId = localPlayerId,
                        onTapMessageChanged = { showTapMessage = it },
                        onRenegeAttempt = onRenegeAttempt,
                        onSpadesNotBroken = onSpadesNotBroken
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            // ── End states ────────────────────────────────────────────────────
            GamePhase.Score -> { /* scoring runs in the engine; no UI needed */ }

            GamePhase.EndHand -> {
                if (showEndHandOverlay) {
                    EndHandView(
                        state          = state,
                        viewModel      = viewModel,
                        localPlayerId  = localPlayerId,
                        onNavigateBack = onNavigateBack,
                        onReplayHand   = if (state.lastHandReplay != null) {{ showReplay = true }} else null,
                        modifier       = Modifier.fillMaxSize()
                    )
                }
            }

            GamePhase.Finished -> {
                if (showEndGameOverlay) {
                    EndGameView(
                        state          = state,
                        viewModel      = viewModel,
                        localPlayerId  = localPlayerId,
                        onNavigateBack = onNavigateBack,
                        modifier       = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // ── Challenge result overlay ──────────────────────────────────────────
        val cr = showChallengeResult
        if (cr != null) {
            val bannerColor = if (cr.success) Color(0xCC1B5E20) else Color(0xCCB71C1C)
            val bannerText  = if (cr.success) "Challenge Complete!" else "Challenge Failed"
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bannerColor)
                    .padding(horizontal = 16.dp, vertical = 20.dp)
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(bannerText, color = Color.White, style = MaterialTheme.typography.titleLarge)
                    if (cr.reason != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(cr.reason, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // ── Replay overlay (shown over EndHand) ───────────────────────────────
        val replay = state.lastHandReplay
        if (showReplay && replay != null) {
            ReplayHandView(
                replay    = replay,
                onDismiss = { showReplay = false }
            )
        }

        // ── Video overlay ─────────────────────────────────────────────────────
        val videoAsset = currentVideoAsset
        if (videoAsset != null) {
            VideoPlayerOverlay(
                assetName  = videoAsset,
                modifier   = Modifier.fillMaxSize(),
                onComplete = { viewModel.setCurrentVideoAsset(null) }
            )
        }
    }
}

@Composable
private fun VideoPlayerOverlay(
    assetName: String,
    modifier: Modifier = Modifier,
    onComplete: () -> Unit
) {
    Box(
        modifier = modifier
            .background(Color.Black)
            .clickable { onComplete() }
    ) {
        AndroidView(
            factory = { ctx ->
                android.widget.VideoView(ctx).apply {
                    val resId = ctx.resources.getIdentifier(assetName, "raw", ctx.packageName)
                    if (resId != 0) {
                        setVideoURI(Uri.parse("android.resource://${ctx.packageName}/$resId"))
                    }
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener { onComplete() }
                    setOnErrorListener { _, _, _ -> onComplete(); true }
                }
            },
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
        )
    }
}
