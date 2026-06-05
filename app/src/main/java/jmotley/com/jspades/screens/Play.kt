package jmotley.com.jspades.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
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
import jmotley.com.jspades.data.AchievementsRepo
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.engine.ChallengeResult
import jmotley.com.jspades.models.GameViewModel
import jmotley.com.jspades.data.DealMode
import jmotley.com.jspades.data.MPSession
import jmotley.com.jspades.data.MPStateHolder
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
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.viewinterop.AndroidView
import jmotley.com.jspades.ads.AdManager
import jmotley.com.jspades.ads.RewardedPlacement
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color as ComposeColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.util.Log

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
    val tag = "WSSMP"
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
    var animationCollectorReady by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Cheat overlay state
    var revealedCard by remember { mutableStateOf<jmotley.com.jspades.data.Card?>(null) }
    var showPeekDialog by remember { mutableStateOf(false) }
    var showRewardsDialog by remember { mutableStateOf(false) }
    val currentVideoAsset by viewModel.currentVideoAsset.collectAsState()

    // Always clear the active challenge when leaving the play screen, so a
    // challenge that was started but not completed (e.g. user backed out) can
    // never bleed into a subsequent normal game.
    DisposableEffect(Unit) {
        onDispose {
            AchievementsRepo.clearActiveChallenge(context)
            AdManager.hideBanner()
        }
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

    // ── Multiplayer bypass — skip LobbyView and start immediately ────────────
    // MPStateHolder is set by OnlineLobbyViewModel just before navigating here.
    // Delay bootstrap until the animation collector is attached so the first CPU
    // bid animation cannot be dropped before it can re-enter the engine.
    LaunchedEffect(animationCollectorReady) {
        if (!animationCollectorReady) return@LaunchedEffect
        val mpConfig = MPStateHolder.consume() ?: return@LaunchedEffect
        val mpFirstLeadIndex = (mpConfig.firstLeadRoomSeat - mpConfig.localSeatIndex + 4) % 4
        Log.i(tag, "bootstrap multiplayer localSeat=${mpConfig.localSeatIndex} firstLead=$mpFirstLeadIndex")
        viewModel.onLobbyComplete(
            ids              = mpConfig.playerIds,
            names            = mpConfig.playerNames,
            gameType         = GameType.HOUSE_RULES,
            dealtHands       = mpConfig.dealtHands,
            mpFirstLeadIndex = mpFirstLeadIndex,
            remoteHumanIds   = mpConfig.remoteHumanIds,
            remotePlayerIds  = mpConfig.remotePlayerIds,
            localSeatIndex   = mpConfig.localSeatIndex
        )
    }

    // ── Multiplayer session reference ─────────────────────────────────────────
    val mpSession = remember { MPSession.session }

    // ── Remote player bid events → unblock PhaseManager ─────────────────────
    // Forward incoming playerBid messages to the ViewModel so awaitRemoteBid() unblocks.
    // Also applies allBids (guest only) once BidHuman is reached.
    if (mpSession != null) {
        LaunchedEffect(mpSession) {
            // Collect remote individual bids (used by host's awaitRemoteBid in PhaseManager)
            launch {
                mpSession.pendingPlayerBids.collect { bids ->
                    bids.forEach { (canonicalId, amount) ->
                        viewModel.onRemoteBidReceived(canonicalId, amount)
                        mpSession.consumePlayerBid(canonicalId)
                    }
                }
            }
            // Collect remote card plays (unblocks awaitRemotePlay in PhaseManager)
            launch {
                mpSession.pendingPlayCard.collect { plays ->
                    plays.forEach { (canonicalId, card) ->
                        viewModel.onRemotePlayReceived(canonicalId, card)
                        mpSession.consumePlayCard(canonicalId)
                    }
                }
            }
            // Guest only: apply allBids from the host, but only after BidHuman is shown
            if (!MPSession.isHost) {
                mpSession.pendingAllBids.collect { allBids ->
                    allBids ?: return@collect
                    // Wait for BidHuman so the CPU animation chain completes first.
                    // Firing during Bid phase would skip the human's bid UI entirely.
                    viewModel.state.first {
                        it.phase == GamePhase.BidHuman || it.phase == GamePhase.BidReview
                    }
                    viewModel.applyRemoteAllBids(allBids.team0Bid, allBids.team1Bid)
                    mpSession.clearPendingAllBids()
                }
            }
        }
    }

    // ── Video event collector ─────────────────────────────────────────────────
    // Merges all three video flows into a single currentVideoAsset state.
    LaunchedEffect("video") {
        launch { viewModel.frustratedVideo.collect { asset -> viewModel.setCurrentVideoAsset(asset) } }
        launch { viewModel.cardheadEvent.collect  { asset -> viewModel.setCurrentVideoAsset(asset) } }
        viewModel.bostonVideo.collect { asset -> viewModel.setCurrentVideoAsset(asset) }
    }

    val isChallengeActive = AchievementsRepo.getActiveChallenge(context) != null

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Background ────────────────────────────────────────────────────────────
        Image(
            painter = painterResource(id = R.drawable.bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // ── MP: broadcast this player's individual bid when BidHuman completes ──
        // When phase transitions away from BidHuman, the local player just bid.
        // Send playerBid so remote PhaseManagers can unblock awaitRemoteBid().
        if (state.isMultiplayer && state.phase == GamePhase.Bid) {
            LaunchedEffect(GamePhase.Bid) {
                val hand = state.phaseHands[GamePhase.Deal]?.lastOrNull() ?: return@LaunchedEffect
                val southBid = hand.perPlayer["south"]?.bid ?: return@LaunchedEffect
                if (southBid > 0) MPSession.session?.sendPlayerBid(southBid)
            }
        }

        // ── MP host: broadcast allBids when bidding round is complete ────────────
        if (state.isMultiplayer && MPSession.isHost && state.phase == GamePhase.BidReview) {
            LaunchedEffect(GamePhase.BidReview) {
                val hand = state.phaseHands[GamePhase.Deal]?.lastOrNull()
                val t0 = hand?.teamBids?.getOrNull(0) ?: 0
                val t1 = hand?.teamBids?.getOrNull(1) ?: 0
                if (t0 > 0 || t1 > 0) MPSession.session?.sendAllBids(t0, t1)
            }
        }

        // ── Ad trigger (interstitial + banner) ───────────────────────────────────
        // Interstitial: every-other-hand cadence managed internally by AdManager.
        // Banner: hidden during trick phases, shown at end-hand/score.
        LaunchedEffect(state.phase) {
            val activity = context as? Activity ?: return@LaunchedEffect
            when (state.phase) {
                GamePhase.EndHand -> {
                    showEndHandOverlay = false
                    var waited = 0L
                    while (viewModel.currentVideoAsset.value != null && waited < 10_000L) {
                        delay(100); waited += 100
                    }
                    AdManager.maybeShowInterstitial(activity) {
                        showEndHandOverlay = true
                    }
                    AdManager.showBanner()
                }
                GamePhase.Finished -> {
                    showEndGameOverlay = false
                    AdManager.maybeShowInterstitial(activity) {
                        showEndGameOverlay = true
                    }
                    AdManager.showBanner()
                }
                GamePhase.Lobby,
                GamePhase.Deal,
                GamePhase.Bid,
                GamePhase.BidHuman,
                GamePhase.BidReview,
                GamePhase.Kitty,
                GamePhase.Score -> AdManager.showBanner()
                GamePhase.Trick,
                GamePhase.TrickHuman,
                GamePhase.TrickResolve -> AdManager.hideBanner()
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

        // ── Bag forgiveness — auto-decline (reward surface moved to rewards icon) ──
        LaunchedEffect(Unit) {
            viewModel.bagForgivenessOffer.collect {
                viewModel.respondToBagForgivenessOffer(false)
            }
        }

        // ── Animation event collector ─────────────────────────────────────────────
        // Collects one-shot events from PhaseManager (fire-and-forget pattern).
        // Each event drives an animation; execute() is called on completion so the
        // engine can advance to the next step.
        LaunchedEffect(Unit) {
            animationCollectorReady = true
            Log.i(tag, "animation collector ready")
            viewModel.animationEvents.collect { event ->
                Log.d(tag, "animation event=${event::class.simpleName}")
                when (event) {
                    is AnimationEvent.BidPlaced -> {
                        // Host: broadcast CPU bids so remote PhaseManagers can display them.
                        if (state.isMultiplayer && MPSession.isHost) {
                            val pId = event.playerId
                            if (pId != "south" && !state.remoteHumanIds.contains(pId)) {
                                val bid = state.phaseHands[GamePhase.Deal]?.lastOrNull()
                                    ?.perPlayer?.get(pId)?.bid ?: 0
                                if (bid > 0) viewModel.sendMpBid(pId, bid)
                            }
                        }
                        // Bid badge fades in via DiamondView state change — wait for it
                        delay(600)
                    }
                    is AnimationEvent.CardPlayed -> {
                        // Host: broadcast CPU card plays so remote awaitRemotePlay() unblocks.
                        if (state.isMultiplayer && MPSession.isHost) {
                            val pId = event.playerId
                            if (pId != "south" && !state.remoteHumanIds.contains(pId)) {
                                viewModel.sendMpPlay(pId, event.card)
                            }
                        }
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

        // ── Rewards icon — persistent entry point for all reward ads ─────────────
        // Hidden in challenge mode and multiplayer games.
        if (!isChallengeActive && !state.isMultiplayer) {
            val rewardPrefs = remember { context.getSharedPreferences("reward_prefs", android.content.Context.MODE_PRIVATE) }
            var rewardTapCount by remember { mutableStateOf(rewardPrefs.getInt("reward_tap_count", 0)) }
            RewardsIconButton(
                isActivated = showRewardsDialog,
                showHalo = rewardTapCount < 3,
                onAnimationComplete = {
                    if (rewardTapCount < 3) {
                        rewardTapCount += 1
                        rewardPrefs.edit().putInt("reward_tap_count", rewardTapCount).apply()
                    }
                    showRewardsDialog = true
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 4.dp, start = 4.dp)
            )
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
                    val humanWasSet = run {
                        val hand = state.phaseHands[GamePhase.Deal]?.lastOrNull()
                        if (hand == null) false
                        else if (state.gameType.useTeams) {
                            val humanTeam  = state.players.find { it.id == localPlayerId }?.team ?: 0
                            val teamBid    = hand.teamBids.getOrNull(humanTeam) ?: 0
                            val teamTricks = state.players.filter { it.team == humanTeam }
                                .sumOf { hand.perPlayer[it.id]?.tricksWon ?: 0 }
                            teamBid > 0 && teamTricks < teamBid
                        } else {
                            val phs = hand.perPlayer[localPlayerId]
                            phs != null && phs.bid > 0 && phs.tricksWon < phs.bid
                        }
                    }
                    EndHandView(
                        state                  = state,
                        viewModel              = viewModel,
                        localPlayerId          = localPlayerId,
                        onNavigateBack         = onNavigateBack,
                        onReplayHand           = if (state.lastHandReplay != null) {{ showReplay = true }} else null,
                        canReplayLastHand      = !viewModel.usedReplayLastHandThisGame && !isChallengeActive && humanWasSet,
                        onReplayLastHandAccepted = {
                            val activity = context as? Activity
                            if (activity != null) {
                                AdManager.showRewarded(
                                    activity  = activity,
                                    placement = RewardedPlacement.REPLAY_LAST_HAND,
                                    onReward  = { viewModel.replayLastHand() },
                                    onClosed  = { showEndHandOverlay = false }
                                )
                            }
                        },
                        modifier               = Modifier.fillMaxSize()
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

        // ── Rewards dialog — shows available reward ads for the current phase ────
        if (showRewardsDialog) {
            val activity = context as? Activity
            val phase = state.phase
            val canUndo = phase == GamePhase.TrickHuman &&
                viewModel.previousTrickStartSnapshot != null && !viewModel.usedUndoLastTrickThisGame
            val canPeek = phase == GamePhase.TrickHuman && !viewModel.usedPeekThisGame
            val canExtraBook = phase == GamePhase.TrickHuman && !viewModel.usedExtraBookThisGame
            val canBidAdjust = phase == GamePhase.TrickHuman && !viewModel.usedBidAdjustThisGame
            val hasAny = canUndo || canPeek || canExtraBook || canBidAdjust
            AlertDialog(
                onDismissRequest = { showRewardsDialog = false },
                title = { Text("Rewards") },
                text = {
                    Column {
                        if (!hasAny) {
                            Text("No rewards available right now.", color = Color(0xFFAAAAAA))
                        }
                        if (canUndo) {
                            TextButton(onClick = {
                                showRewardsDialog = false
                                if (activity != null) AdManager.showRewarded(
                                    activity = activity,
                                    placement = RewardedPlacement.UNDO_LAST_TRICK,
                                    onReward = { viewModel.rewindToPreviousTrick() }
                                )
                            }) { Text("↩ Undo Last Trick") }
                        }
                        if (canPeek) {
                            TextButton(onClick = {
                                showRewardsDialog = false
                                showPeekDialog = true
                            }) { Text("👁 Peek at a Hand") }
                        }
                        if (canExtraBook) {
                            TextButton(onClick = {
                                showRewardsDialog = false
                                if (activity != null) AdManager.showRewarded(
                                    activity = activity,
                                    placement = RewardedPlacement.EXTRA_BOOK,
                                    onReward = { viewModel.grantExtraBook(localPlayerId) }
                                )
                            }) { Text("📖 Extra Book") }
                        }
                        if (canBidAdjust) {
                            TextButton(onClick = {
                                showRewardsDialog = false
                                if (activity != null) AdManager.showRewarded(
                                    activity = activity,
                                    placement = RewardedPlacement.BID_ADJUST,
                                    onReward = { viewModel.adjustHumanBid(localPlayerId, jmotley.com.jspades.ads.BidAdjustDirection.MINUS_ONE) }
                                )
                            }) { Text("🎯 Bid −1") }
                            TextButton(onClick = {
                                showRewardsDialog = false
                                if (activity != null) AdManager.showRewarded(
                                    activity = activity,
                                    placement = RewardedPlacement.BID_ADJUST,
                                    onReward = { viewModel.adjustHumanBid(localPlayerId, jmotley.com.jspades.ads.BidAdjustDirection.PLUS_ONE) }
                                )
                            }) { Text("🎯 Bid +1") }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showRewardsDialog = false }) { Text("Close") } }
            )
        }

        // ── Peek player selection dialog ──────────────────────────────────────
        if (showPeekDialog) {
            val opponents = state.players.filter { it.id != localPlayerId }
            AlertDialog(
                onDismissRequest = { showPeekDialog = false },
                title = { Text("Peek at whose hand?") },
                text = {
                    Column {
                        opponents.forEach { player ->
                            TextButton(onClick = {
                                showPeekDialog = false
                                val activity = context as? Activity ?: return@TextButton
                                var peeked: jmotley.com.jspades.data.Card? = null
                                AdManager.showRewarded(
                                    activity = activity,
                                    placement = RewardedPlacement.PEEK_ONE_CARD,
                                    onReward = { peeked = viewModel.peekCardForPlayer(player.id) },
                                    onClosed = { revealedCard = peeked }
                                )
                            }) { Text(player.name) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showPeekDialog = false }) { Text("Cancel") }
                }
            )
        }

        // ── Revealed card overlay (peek result) ───────────────────────────────
        val peekCard = revealedCard
        if (peekCard != null) {
            LaunchedEffect(peekCard) {
                delay(4000L)
                revealedCard = null
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC1A1A3A), RoundedCornerShape(8.dp))
                    .padding(16.dp)
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Peeked: ${peekCard.rank.name} of ${peekCard.suit.name}",
                    color = Color(0xFFFFD700),
                    style = MaterialTheme.typography.titleMedium
                )
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
            AdManager.hideBanner()
            VideoPlayerOverlay(
                assetName  = videoAsset,
                modifier   = Modifier.fillMaxSize(),
                onComplete = { viewModel.setCurrentVideoAsset(null) }
            )
        }

    }
}

/**
 * Reward icon button. Shows [R.drawable.reward] with a gentle idle breathing scale.
 * On tap: pulsates 3× to 1.2f with 100 ms per phase, switches to rewardopen, then
 * calls [onAnimationComplete] so the caller can open the reward selector.
 * Reverts to idle when [isActivated] becomes false.
 */
@Composable
private fun RewardsIconButton(
    isActivated: Boolean,
    showHalo: Boolean,
    onAnimationComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showOpen by remember { mutableStateOf(false) }
    var animating by remember { mutableStateOf(false) }
    val scale = remember { Animatable(1f) }
    val haloAlpha = remember { Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(isActivated) {
        if (!isActivated) {
            showOpen = false
            animating = false
            scale.snapTo(1f)
            // Gentle idle breathing — suspends until isActivated changes
            scale.animateTo(
                targetValue = 1.05f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800),
                    repeatMode = RepeatMode.Reverse
                )
            )
        }
    }

    LaunchedEffect(showHalo) {
        if (showHalo) {
            haloAlpha.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700),
                    repeatMode = RepeatMode.Reverse
                )
            )
        } else {
            haloAlpha.snapTo(0f)
        }
    }

    Image(
        painter = painterResource(if (showOpen) R.drawable.rewardopen else R.drawable.reward),
        contentDescription = null,
        modifier = modifier
            .size(44.dp)
            .drawBehind {
                if (showHalo) {
                    drawCircle(
                        color = ComposeColor(1f, 0.9f, 0f, haloAlpha.value * 0.75f),
                        radius = size.minDimension / 2f + 6.dp.toPx(),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                    )
                }
            }
            .scale(scale.value)
            .clickable(enabled = !animating) {
                animating = true
                scope.launch {
                    // 3 pulsate cycles: grow to 1.2f then back, 100 ms each phase
                    repeat(3) {
                        scale.animateTo(1.2f, animationSpec = tween(100))
                        scale.animateTo(1f,   animationSpec = tween(100))
                    }
                    showOpen = true
                    delay(300L)
                    onAnimationComplete()
                }
            }
    )
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
