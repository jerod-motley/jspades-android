package jmotley.com.jspades.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import jmotley.com.jspades.data.effectiveMinBid
import jmotley.com.jspades.models.GameViewModel

private const val MAX_BID = 13

/**
 * Bid panel: shown during [GamePhase.Bid], [GamePhase.BidHuman], and [GamePhase.BidReview].
 *
 * Always displays a scoreboard of every player with their current bid ("-" if not yet bid).
 * Bids fill in as each player (CPU or human) submits. The human's bid selector is shown
 * only during [GamePhase.BidHuman]. After all bids are in ([GamePhase.BidReview]) an OK
 * button lets the human proceed, preventing race conditions.
 *
 * For team games the human's CPU partner's individual bid is shown as a hint.
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
    val isHumanTurn  = state.phase == GamePhase.BidHuman
    val isReview     = state.phase == GamePhase.BidReview
    val minBid       = state.effectiveMinBid
    val n            = state.players.size
    val dealHand     = state.phaseHands[GamePhase.Deal]?.lastOrNull()
    val humanTeam    = state.players.find { it.id == localPlayerId }?.team ?: 0
    val dealerIndex  = (state.leaderIndex - 1 + n) % n
    val humanIsDealer = state.players.getOrNull(dealerIndex)?.id == localPlayerId

    // Partner's individual bid as a hint (team games, human's turn)
    val partnerBid: Int? = if (isHumanTurn && !isSolo) {
        val partnerId = state.players.firstOrNull { it.team == humanTeam && it.id != localPlayerId }?.id
        dealHand?.perPlayer?.get(partnerId)?.bid
    } else null

    // House Rules + human is dealer: show opponents' team total
    val opponentTeamBid: Int? = if (isHouseRules && humanIsDealer && isHumanTurn) {
        val opponentTeam  = 1 - humanTeam
        val opponentsDone = state.players.filter { it.team == opponentTeam }.all { it.runtimeFlags.didBid }
        if (opponentsDone) dealHand?.teamBids?.getOrNull(opponentTeam) else null
    } else null

    // All players in clockwise order from leaderIndex for the scoreboard
    val bidRows = (0 until n).map { offset ->
        val player = state.players[(state.leaderIndex + offset) % n]
        val bid    = dealHand?.perPlayer?.get(player.id)?.bid
        Triple(player.displayName, bid, player.runtimeFlags.didBid)
    }

    val nilAllowed  = state.allowNilBid
    // nilSeparate: nil is a special out-of-range option (minBid > 0 but nil still allowed).
    // Applies to 2-man solo (minBid=4). In Classic (minBid=0) nil is just value 0 in range.
    val nilSeparate = nilAllowed && minBid > 0
    val floorBid    = if (nilAllowed) 0 else minBid
    var selectedBid by remember(minBid) { mutableIntStateOf(minBid) }

    Column(
        modifier = modifier
            .background(Color(0xEE0D1B2A), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // ── Scoreboard: all players with bids or "–" ──────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            bidRows.forEach { (name, bid, hasBid) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = name,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = when {
                            hasBid && bid != null -> if (bid == 0) "Nil" else "$bid"
                            else -> "–"
                        },
                        color = if (hasBid) Color(0xFFFFD700) else Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ── Human bid selector (BidHuman only) ───────────────────────────────
        if (isHumanTurn) {
            Spacer(Modifier.height(12.dp))

            if (partnerBid != null) {
                Text("Partner bid $partnerBid", color = Color.White, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
            }
            if (opponentTeamBid != null) {
                Text(
                    "Opponents bid $opponentTeamBid",
                    color = Color(0xFFFFCC80),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
            }

            Text(
                text = if (isHouseRules) "Your Team Bid" else "Your Bid",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                BidAdjustButton("–") { if (selectedBid > floorBid) selectedBid-- }
                Text(
                    text = if (selectedBid == 0) "Nil" else "$selectedBid",
                    color = Color.White,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                BidAdjustButton("+") {
                    selectedBid = if (nilSeparate && selectedBid == 0) minBid
                                  else if (selectedBid < MAX_BID) selectedBid + 1
                                  else selectedBid
                }
            }

            Spacer(Modifier.height(12.dp))

            // When nil is separate (2-man style), show a standalone Nil button first,
            // then the numeric range starting at minBid (no 1/2/3).
            if (nilSeparate) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickPick(0, label = "Nil", selected = selectedBid == 0) { selectedBid = 0 }
                }
                Spacer(Modifier.height(4.dp))
            }
            val range = if (nilSeparate) (minBid..MAX_BID).toList() else (floorBid..MAX_BID).toList()
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
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row2.forEach { v ->
                        QuickPick(v, label = "$v", selected = selectedBid == v) { selectedBid = v }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(Color(0xFF1B5E20), RoundedCornerShape(8.dp))
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
                    "Confirm Bid",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // ── OK button after all bids are in (BidReview) ───────────────────────
        if (isReview) {
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(Color(0xFF1B5E20), RoundedCornerShape(8.dp))
                    .clickable { viewModel.submitBidReview() },
                contentAlignment = Alignment.Center
            ) {
                Text("OK", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
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
        Text(text = label, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
        Text(text = label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}
