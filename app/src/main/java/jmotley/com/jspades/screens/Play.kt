package jmotley.com.jspades.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel
import jmotley.com.jspades.R
import jmotley.com.jspades.data.GamePhase
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.models.GameViewModel
import jmotley.com.jspades.views.BidView
import jmotley.com.jspades.views.DiamondView
import jmotley.com.jspades.views.GameInfoView
import jmotley.com.jspades.views.HandView
import jmotley.com.jspades.views.KittyView
import jmotley.com.jspades.views.LobbyView

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
    viewModel: GameViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val resolvedGameType = GameType.fromLabel(gameType)

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Background ────────────────────────────────────────────────────────────
        Image(
            painter = painterResource(id = R.drawable.bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

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

            // DealHuman: cards dealt — reveal the human's hand
        
            GamePhase.DealHuman -> {
                HandView(
                    state = state,
                    viewModel = viewModel,
                    localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // ── Bid ───────────────────────────────────────────────────────────
            // CPU players bidding
            GamePhase.Bid -> {
                DiamondView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.Center)
                )
                GameInfoView(
                    state = state, viewModel = viewModel,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
                HandView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // Human's turn to bid
            GamePhase.BidHuman -> {
                DiamondView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.Center)
                )
                GameInfoView(
                    state = state, viewModel = viewModel,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
                BidView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.Center)
                )
                HandView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
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
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }

            GamePhase.KittyHuman -> {
                KittyView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.Center)
                )
                GameInfoView(
                    state = state, viewModel = viewModel,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
                HandView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // ── Trick / Play ──────────────────────────────────────────────────
            // CPU playing or trick resolving
            GamePhase.Trick,
            GamePhase.TrickResolve -> {
                DiamondView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.Center)
                )
                GameInfoView(
                    state = state, viewModel = viewModel,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }

            // Human's turn to play a card
            GamePhase.TrickHuman -> {
                DiamondView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.Center)
                )
                GameInfoView(
                    state = state, viewModel = viewModel,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
                HandView(
                    state = state, viewModel = viewModel, localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // ── End states ────────────────────────────────────────────────────
            GamePhase.Score,
            GamePhase.EndHand,
            GamePhase.Finished -> {
                // TODO: score / end-game overlay
            }
        }
    }
}
