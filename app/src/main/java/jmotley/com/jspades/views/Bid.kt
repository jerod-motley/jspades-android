package jmotley.com.jspades.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jmotley.com.jspades.data.GamePhase
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.models.GameViewModel

private const val MAX_BID = 13

/**
 * Bid view: modal overlay displayed when it is the local player's turn to bid.
 *
 * Behaviour differs by game type:
 *
 * **House Rules** — Human enters a team total (min 4). The CPU partner's computed
 *   individual bid is shown as a hint. When the human IS the dealer the opposing
 *   team's total bid (they went first) is also shown. Confirm calls
 *   [GameViewModel.submitHumanTeamBid].
 *
 * **Solo games** — Prior player bids (clockwise from leaderIndex) are shown above
 *   the selector so the human can see what everyone else bid before choosing.
 *
 * **All other game types** — Human enters an individual bid (min 0). Confirm calls
 *   [GameViewModel.submitHumanBid].
 */
@Composable
fun BidView(
    state: GameState,
    viewModel: GameViewModel,
    localPlayerId: String,
    modifier: Modifier = Modifier
) {
    val isHouseRules = state.gameType == GameType.HOUSE_RULES
    val isSolo       = !state.gameType.useTeams
    val minBid       = state.gameType.minimumBid
    val playerCount  = state.players.size

    // leaderIndex is the first-to-act; dealer is one seat clockwise from the leader.
    val dealerIndex   = (state.leaderIndex - 1 + playerCount) % playerCount
    val humanIsDealer = state.players.getOrNull(dealerIndex)?.id == localPlayerId

    val humanTeam = state.players.find { it.id == localPlayerId }?.team ?: 0
    val dealHand  = state.phaseHands[GamePhase.Deal]?.lastOrNull()

    // House Rules: show CPU partner's individual bid as a reference.
    val partnerBid: Int? = if (isHouseRules) {
        val partnerId = state.players
            .firstOrNull { it.team == humanTeam && it.id != localPlayerId }?.id
        dealHand?.perPlayer?.get(partnerId)?.bid
    } else null

    // House Rules + human is dealer: opponents bid first — show their team total.
    val opponentTeamBid: Int? = if (isHouseRules && humanIsDealer) {
        val opponentTeam  = 1 - humanTeam
        val opponentsDone = state.players
            .filter { it.team == opponentTeam }
            .all { it.runtimeFlags.didBid }
        if (opponentsDone) dealHand?.teamBids?.getOrNull(opponentTeam) else null
    } else null

    // Solo games: collect bids already submitted in clockwise order from leaderIndex.
    val priorBids: List<Pair<String, Int>> = if (isSolo) {
        (0 until playerCount).mapNotNull { offset ->
            val player = state.players[(state.leaderIndex + offset) % playerCount]
            if (player.runtimeFlags.didBid) {
                val bid = dealHand?.perPlayer?.get(player.id)?.bid
                if (bid != null) player.displayName to bid else null
            } else null
        }
    } else emptyList()

    var selectedBid by remember(minBid) { mutableIntStateOf(minBid) }

    Box(
        modifier = modifier.fillMaxSize().background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
    Box(
        modifier = Modifier
            .background(color = Color(0xFF16213E), shape = RoundedCornerShape(16.dp))
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {

            Text(
                text = if (isHouseRules) "Team Bid" else "Your Bid",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

            // House Rules: CPU partner's individual bid
            if (isHouseRules && partnerBid != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Partner bid $partnerBid",
                    color = Color(0xFFB0BEC5),
                    fontSize = 13.sp
                )
            }

            // House Rules + human is dealer: opponents' team total (they went first)
            if (opponentTeamBid != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Opponents bid $opponentTeamBid",
                    color = Color(0xFFFFCC80),
                    fontSize = 13.sp
                )
            }

            // Solo: prior player bids in clockwise order
            if (priorBids.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    priorBids.forEach { (name, bid) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = name, color = Color(0xFFB0BEC5), fontSize = 11.sp)
                            Text(
                                text = if (bid == 0) "Nil" else "$bid",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Bid selector: – value +
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                BidAdjustButton(label = "–") {
                    if (selectedBid > minBid) selectedBid--
                }
                Text(
                    text = if (selectedBid == 0) "Nil" else "$selectedBid",
                    color = Color.White,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(72.dp)
                )
                BidAdjustButton(label = "+") {
                    if (selectedBid < MAX_BID) selectedBid++
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Quick-pick buttons — two rows covering the valid range
            val range = (minBid..MAX_BID).toList()
            val row1  = range.take(7)
            val row2  = range.drop(7)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row1.forEach { v ->
                    QuickPick(v, label = if (v == 0) "N" else "$v", selected = selectedBid == v) {
                        selectedBid = v
                    }
                }
            }
            if (row2.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row2.forEach { v ->
                        QuickPick(v, label = "$v", selected = selectedBid == v) {
                            selectedBid = v
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Confirm button
            Box(
                modifier = Modifier
                    .size(width = 200.dp, height = 56.dp)
                    .background(color = Color(0xFF1B5E20), shape = RoundedCornerShape(8.dp))
                    .clickable {
                        if (isHouseRules) {
                            viewModel.submitHumanTeamBid(humanTeam, selectedBid, localPlayerId)
                        } else {
                            viewModel.submitHumanBid(selectedBid, localPlayerId)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Confirm Bid",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
            }
        }
    }
    }
}

@Composable
private fun BidAdjustButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(color = Color(0xFF37474F), shape = RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun QuickPick(value: Int, label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Color(0xFF1565C0) else Color(0xFF37474F)
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(color = bg, shape = RoundedCornerShape(4.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = Color.White, fontSize = 15.sp)
    }
}
