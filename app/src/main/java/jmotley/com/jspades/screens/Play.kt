package jmotley.com.jspades.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel
import jmotley.com.jspades.R
import jmotley.com.jspades.data.AnimationEvent
import jmotley.com.jspades.data.GamePhase
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.models.GameViewModel
import jmotley.com.jspades.data.DealMode
import jmotley.com.jspades.views.BidView
import jmotley.com.jspades.views.DealPickView
import jmotley.com.jspades.views.DiamondView
import jmotley.com.jspades.views.EndGameView
import jmotley.com.jspades.views.EndHandView
import jmotley.com.jspades.views.ReplayHandView
import jmotley.com.jspades.views.GameInfoView
import jmotley.com.jspades.views.HandView
import jmotley.com.jspades.views.KittyView
import jmotley.com.jspades.views.LobbyView
import kotlinx.coroutines.delay

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
    var showQuitDialog by remember { mutableStateOf(false) }
    var showReplay by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Background ────────────────────────────────────────────────────────────
        Image(
            painter = painterResource(id = R.drawable.bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

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
                        // Flash the winner's slot gold, then clear before re-entering engine
                        trickWinner = event.winnerId
                        delay(900)
                        trickWinner = null
                    }
                }
                viewModel.phaseManager.execute()
            }
        }

        // ── Top header: display the current game type label and respect status bar safe area ──
        Text(
            text = resolvedGameType.label,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(vertical = 8.dp)
        )

        // ── Close / quit button ───────────────────────────────────────────────────
        TextButton(
            onClick = {
                if (state.phase == GamePhase.Finished) onNavigateBack()
                else showQuitDialog = true
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 4.dp)
        ) {
            Text("✕", fontSize = 20.sp)
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
                    Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                        GameInfoView(
                            state = state, viewModel = viewModel,
                            localPlayerId = localPlayerId
                        )
                        HandView(
                            state = state, viewModel = viewModel,
                            localPlayerId = localPlayerId
                        )
                    }
                }
            }

            // ── Bid ───────────────────────────────────────────────────────────
            // CPU players bidding
            GamePhase.Bid -> {
                DiamondView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    trickWinner = trickWinner,
                    modifier = Modifier.align(Alignment.Center)
                )
                Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                    GameInfoView(
                        state = state, viewModel = viewModel,
                        localPlayerId = localPlayerId
                    )
                    HandView(
                        state = state, viewModel = viewModel,
                        localPlayerId = localPlayerId
                    )
                }
            }

            // Human's turn to bid
            GamePhase.BidHuman -> {
                DiamondView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    trickWinner = trickWinner,
                    modifier = Modifier.align(Alignment.Center)
                )
                BidView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.Center)
                )
                Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                    GameInfoView(
                        state = state, viewModel = viewModel,
                        localPlayerId = localPlayerId
                    )
                    HandView(
                        state = state, viewModel = viewModel,
                        localPlayerId = localPlayerId
                    )
                }
            }

            // ── Kitty ─────────────────────────────────────────────────────────
            GamePhase.KittyReveal,
            GamePhase.Kitty -> {
                KittyView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.Center)
                )
                GameInfoView(
                    state = state, viewModel = viewModel,
                    localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            GamePhase.KittyHuman -> {
                KittyView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.Center)
                )
                Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                    GameInfoView(
                        state = state, viewModel = viewModel,
                        localPlayerId = localPlayerId
                    )
                    HandView(
                        state = state, viewModel = viewModel,
                        localPlayerId = localPlayerId
                    )
                }
            }

            // ── Trick / Play ──────────────────────────────────────────────────
            // CPU playing or trick resolving — no hand visible
            GamePhase.Trick,
            GamePhase.TrickResolve -> {
                DiamondView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    trickWinner = trickWinner,
                    modifier = Modifier.align(Alignment.Center)
                )
                GameInfoView(
                    state = state, viewModel = viewModel,
                    localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // Human's turn to play a card
            GamePhase.TrickHuman -> {
                DiamondView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    trickWinner = trickWinner,
                    modifier = Modifier.align(Alignment.Center)
                )
                Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                    GameInfoView(
                        state = state, viewModel = viewModel,
                        localPlayerId = localPlayerId
                    )
                    HandView(
                        state = state, viewModel = viewModel,
                        localPlayerId = localPlayerId
                    )
                }
            }

            // ── End states ────────────────────────────────────────────────────
            GamePhase.Score -> { /* scoring runs in the engine; no UI needed */ }

            GamePhase.EndHand -> {
                EndHandView(
                    state          = state,
                    viewModel      = viewModel,
                    localPlayerId  = localPlayerId,
                    onNavigateBack = onNavigateBack,
                    onReplayHand   = if (state.lastHandReplay != null) {{ showReplay = true }} else null,
                    modifier       = Modifier.fillMaxSize()
                )
            }

            GamePhase.Finished -> {
                EndGameView(
                    state          = state,
                    viewModel      = viewModel,
                    localPlayerId  = localPlayerId,
                    onNavigateBack = onNavigateBack,
                    modifier       = Modifier.fillMaxSize()
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
    }
}
