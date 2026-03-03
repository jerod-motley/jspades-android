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
 * @param gameType      The game variant chosen from the menu (e.g. "Classic", "Kitty",
 *                      "House Rules", "Four Man Solo"). Drives rule differences and
 *                      which phases are active. TODO: map to a sealed variant class.
 * @param viewModel     Injected by default via [viewModel()]; holds the live [GameState].
 */
@Composable
fun PlayScreen(
    localPlayerId: String,
    gameType: String = "Classic",
    viewModel: GameViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Background ────────────────────────────────────────────────────────────
        Image(
            painter = painterResource(id = R.drawable.bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // ── Phase-based view composition ──────────────────────────────────────────
        when (state.phase) {

            GamePhase.Lobby -> {
                LobbyView(
                    state = state,
                    viewModel = viewModel,
                    localPlayerId = localPlayerId,
                    modifier = Modifier.fillMaxSize()
                )
            }

            GamePhase.Deal -> {
                HandView(
                    state = state,
                    viewModel = viewModel,
                    localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            GamePhase.Kitty -> {
                KittyView(
                    state = state,
                    viewModel = viewModel,
                    localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.Center)
                )
                GameInfoView(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
                HandView(
                    state = state,
                    viewModel = viewModel,
                    localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            GamePhase.Bid -> {
                DiamondView(
                    state = state,
                    viewModel = viewModel,
                    localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.Center)
                )
                GameInfoView(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
                // BidView is modal — only shown on this player's bid turn
                if (viewModel.isMyBidTurn(localPlayerId)) {
                    BidView(
                        state = state,
                        viewModel = viewModel,
                        localPlayerId = localPlayerId,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                HandView(
                    state = state,
                    viewModel = viewModel,
                    localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            GamePhase.Play,
            GamePhase.TrickResolve -> {
                DiamondView(
                    state = state,
                    viewModel = viewModel,
                    localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.Center)
                )
                GameInfoView(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
                HandView(
                    state = state,
                    viewModel = viewModel,
                    localPlayerId = localPlayerId,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            GamePhase.Score,
            GamePhase.Finished -> {
                // TODO: score / end-game overlay
            }
        }
    }
}
