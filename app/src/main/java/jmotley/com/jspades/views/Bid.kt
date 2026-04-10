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

private data class BidRow(val name: String, val bid: Int?, val hasBid: Boolean, val isBlind: Boolean)

/** "B7", "Nil", "B0" (blind nil), or the plain number. */
private fun formatBid(bid: Int, isBlind: Boolean): String = when {
    bid == 0 && isBlind -> "B Nil"
    bid == 0            -> "Nil"
    isBlind             -> "B$bid"
    else                -> "$bid"
}

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
    val isHouseRules = state.gameType == GameType.HOUSE_RULES || state.gameType == GameType.TEAM_KITTY
    val isSolo       = !state.gameType.useTeams
    val isHumanTurn  = state.phase == GamePhase.BidHuman
    val isReview     = state.phase == GamePhase.BidReview
    val minBid       = state.effectiveMinBid
    val n            = state.players.size
    val dealHand     = state.phaseHands[GamePhase.Deal]?.lastOrNull()
    val humanTeam    = state.players.find { it.id == localPlayerId }?.team ?: 0

    // All players in clockwise order from leaderIndex for the scoreboard (solo games)
    val bidRows = (0 until n).map { offset ->
        val player  = state.players[(state.leaderIndex + offset) % n]
        val phs     = dealHand?.perPlayer?.get(player.id)
        BidRow(player.displayName, phs?.bid, player.runtimeFlags.didBid, phs?.isBlind ?: false)
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

        // ── Scoreboard ────────────────────────────────────────────────────────
        // BidReview in a team game: collapse to US / THEM totals.
        // Active bidding in a team game: vertical layout grouped by team.
        // Solo games: horizontal row of all players.
        if (isReview && !isSolo && state.gameType != GameType.TEAM_CLASSIC) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(0, 1).forEach { teamId ->
                    val label = if (teamId == humanTeam) "US" else "THEM"
                    val total = dealHand?.teamBids?.getOrNull(teamId)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = label, color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = if (total != null) "$total" else "–",
                            color = Color(0xFFFFD700),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else if (!isSolo && state.gameType != GameType.TEAM_CLASSIC) {
            // House Rules / Kitty: show opponents section, then teammate
            val opponentTeamId  = 1 - humanTeam
            val opponentPlayers = state.players.filter { it.team == opponentTeamId }
            val partnerPlayer   = state.players.firstOrNull { it.team == humanTeam && it.id != localPlayerId }

            val allOppBid   = opponentPlayers.all { it.runtimeFlags.didBid }
            val oppTotal    = if (allOppBid) dealHand?.teamBids?.getOrNull(opponentTeamId) else null

            val partnerHasBid  = partnerPlayer?.runtimeFlags?.didBid ?: false
            val partnerPhs     = if (partnerHasBid) dealHand?.perPlayer?.get(partnerPlayer?.id) else null
            val partnerBidText = when {
                partnerHasBid && partnerPhs?.bid != null -> formatBid(partnerPhs.bid, partnerPhs.isBlind)
                else -> "–"
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                // Opponents header
                Text(
                    text = "Opponents Bid: ${oppTotal?.toString() ?: "–"}",
                    color = Color(0xFFFFCC80),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                // Individual opponent rows (all non-TEAM_CLASSIC team games show per-player bids)
                opponentPlayers.forEach { p ->
                    val phs    = dealHand?.perPlayer?.get(p.id)
                    val hasBid = p.runtimeFlags.didBid
                    val bid    = if (hasBid) phs?.bid else null
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(p.displayName, color = Color.White, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = if (hasBid && bid != null) formatBid(bid, phs?.isBlind ?: false) else "–",
                            color = if (hasBid) Color(0xFFFFD700) else Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Teammate
                Text(
                    text = "Teammate Bid: $partnerBidText",
                    color = if (partnerHasBid) Color(0xFF90CAF9) else Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            // Solo: horizontal row.
            // During BidHuman, only show players who bid before the local player so
            // the human cannot see bids from players who haven't gone yet.
            val localOffset = (0 until n).firstOrNull { offset ->
                state.players[(state.leaderIndex + offset) % n].id == localPlayerId
            } ?: n
            val visibleRows = if (isHumanTurn) bidRows.take(localOffset) else bidRows
            if (visibleRows.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    visibleRows.forEach { row ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = row.name, color = Color.White, style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = when {
                                    row.hasBid && row.bid != null -> formatBid(row.bid, row.isBlind)
                                    else -> "–"
                                },
                                color = if (row.hasBid) Color(0xFFFFD700) else Color.White,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // ── Human bid selector (BidHuman only) ───────────────────────────────
        if (isHumanTurn) {
            Spacer(Modifier.height(12.dp))

            Text(
                text = when {
                    state.gameType == GameType.TEAM_CLASSIC -> "Your Bid"
                    !isSolo -> "Your Team Bid"
                    else -> "Your Bid"
                },
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                BidAdjustButton("–") {
                    when {
                        nilSeparate && selectedBid == minBid -> selectedBid = 0  // jump to nil, skip invalid range
                        selectedBid > floorBid -> selectedBid--
                    }
                }
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

