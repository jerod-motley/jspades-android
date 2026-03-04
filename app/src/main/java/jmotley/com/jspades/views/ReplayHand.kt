package jmotley.com.jspades.views

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.HandReplay
import jmotley.com.jspades.data.Player
import jmotley.com.jspades.data.ReplayEvent
import jmotley.com.jspades.engine.buildCardLookup
import kotlinx.coroutines.delay

private val RP_PANEL  = Color(0xEE0A0A14)
private val RP_GOLD   = Color(0xFFFFD700)
private val RP_BORDER = Color(0x44FFFFFF)

// ── Public entry point ────────────────────────────────────────────────────────

/**
 * Full-screen replay overlay for a completed hand.
 * Shows a 4-slot trick diamond and step-through controls.
 * Driven by a pure state machine that recomputes from the event list.
 */
@Composable
fun ReplayHandView(
    replay: HandReplay,
    onDismiss: () -> Unit
) {
    val lookup   = remember(replay.gameType) { buildCardLookup(replay.gameType) }
    val total    = replay.events.size
    var stepIndex by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var speedMs   by remember { mutableIntStateOf(900) }   // ms per step

    val rs = remember(stepIndex) { computeStateAt(stepIndex, replay.events, lookup) }

    // Auto-play loop
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (stepIndex < total) {
                delay(speedMs.toLong())
                stepIndex++
                if (stepIndex >= total) { isPlaying = false; break }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .background(RP_PANEL, RoundedCornerShape(16.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Title row ─────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text("Hand Replay", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick  = { isPlaying = false; onDismiss() },
                    modifier = Modifier.size(36.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    border   = null
                ) {
                    Text("✕", color = Color.White, fontSize = 16.sp)
                }
            }

            // ── Trick diamond ─────────────────────────────────────────────────
            TrickDiamond(
                players    = replay.players,
                trickPlays = rs.currentTrickPlays,
                bids       = rs.bids
            )

            // ── Step info ─────────────────────────────────────────────────────
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text      = if (rs.trickCount > 0) "Trick ${rs.trickCount}" else "Bidding",
                    color     = RP_GOLD,
                    fontSize  = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text      = "Step $stepIndex / $total",
                    color     = Color(0xFFB0BEC5),
                    fontSize  = 12.sp
                )
                if (rs.description.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text      = rs.description,
                        color     = Color.White,
                        fontSize  = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // ── Hand counts ───────────────────────────────────────────────────
            HandCountsRow(players = replay.players, hands = rs.hands)

            // ── Speed selector ────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1400 to "1×", 900 to "2×", 450 to "3×").forEach { (ms, label) ->
                    val selected = speedMs == ms
                    OutlinedButton(
                        onClick  = { speedMs = ms },
                        modifier = Modifier.height(32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors   = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) RP_GOLD.copy(alpha = 0.15f) else Color.Transparent
                        )
                    ) {
                        Text(label, color = if (selected) RP_GOLD else Color.White, fontSize = 12.sp)
                    }
                }
            }

            // ── Controls ──────────────────────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick  = { isPlaying = false; if (stepIndex > 0) stepIndex-- },
                    modifier = Modifier.weight(1f),
                    enabled  = stepIndex > 0
                ) { Text("◀ Prev", color = Color.White, fontSize = 13.sp) }

                Button(
                    onClick  = { isPlaying = !isPlaying },
                    modifier = Modifier.weight(1f),
                    enabled  = stepIndex < total || isPlaying
                ) {
                    Text(if (isPlaying) "⏸ Pause" else "▶ Play", fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick  = { if (stepIndex < total) stepIndex++ },
                    modifier = Modifier.weight(1f),
                    enabled  = stepIndex < total
                ) { Text("Next ▶", color = Color.White, fontSize = 13.sp) }
            }
        }
    }
}

// ── Trick diamond ─────────────────────────────────────────────────────────────

@Composable
private fun TrickDiamond(
    players: List<Player>,
    trickPlays: List<Pair<String, Card>>,
    bids: Map<String, Int>
) {
    val playMap = trickPlays.toMap()

    // Resolve seats by id
    fun seat(id: String) = players.find { it.id == id }
    val north = seat("north")
    val south = seat("south")
    val east  = seat("east")
    val west  = seat("west")

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // North
        TrickSlot(player = north, card = north?.let { playMap[it.id] }, bid = bids[north?.id])

        Spacer(Modifier.height(4.dp))

        // West / East row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TrickSlot(player = west, card = west?.let { playMap[it.id] }, bid = bids[west?.id])
            Spacer(Modifier.width(8.dp))
            TrickSlot(player = east, card = east?.let { playMap[it.id] }, bid = bids[east?.id])
        }

        Spacer(Modifier.height(4.dp))

        // South
        TrickSlot(player = south, card = south?.let { playMap[it.id] }, bid = bids[south?.id])
    }
}

@Composable
private fun TrickSlot(
    player: Player?,
    card: Card?,
    bid: Int?
) {
    val context = LocalContext.current
    Column(
        modifier              = Modifier.width(72.dp),
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.spacedBy(2.dp)
    ) {
        // Player label + bid
        Text(
            text      = if (player != null) "${player.displayName}${if (bid != null && bid > 0) " ($bid)" else ""}" else "",
            color     = Color(0xFFB0BEC5),
            fontSize  = 10.sp,
            textAlign = TextAlign.Center,
            maxLines  = 1
        )

        // Card slot
        Box(
            modifier          = Modifier
                .size(60.dp, 88.dp)
                .border(1.dp, RP_BORDER, RoundedCornerShape(6.dp))
                .background(Color(0x22FFFFFF), RoundedCornerShape(6.dp)),
            contentAlignment  = Alignment.Center
        ) {
            if (card != null) {
                val assetName = card.assetFileName().removeSuffix(".png")
                val resId = context.resources.getIdentifier(assetName, "drawable", context.packageName)
                if (resId != 0) {
                    Image(
                        painter            = painterResource(resId),
                        contentDescription = "${card.rank.name} of ${card.suit.name}",
                        modifier           = Modifier.fillMaxSize().padding(2.dp),
                        contentScale       = ContentScale.Fit
                    )
                } else {
                    Text(card.uid, color = Color.White, fontSize = 9.sp, textAlign = TextAlign.Center)
                }
            } else if (player == null) {
                // empty slot — no player in this seat
            } else {
                Text("–", color = Color(0x55FFFFFF), fontSize = 20.sp)
            }
        }
    }
}

// ── Hand size row ─────────────────────────────────────────────────────────────

@Composable
private fun HandCountsRow(
    players: List<Player>,
    hands: Map<String, List<Card>>
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        players.forEach { p ->
            val count = hands[p.id]?.size ?: 0
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(p.displayName, color = Color(0xFF90A4AE), fontSize = 10.sp)
                Text("$count", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Pure replay state machine ──────────────────────────────────────────────────

private data class ReplayState(
    val stepIndex: Int = 0,
    val hands: Map<String, List<Card>> = emptyMap(),
    val bids: Map<String, Int> = emptyMap(),
    val currentTrickPlays: List<Pair<String, Card>> = emptyList(),
    val trickCount: Int = 0,
    val description: String = ""
)

/**
 * Recompute replay state by applying events 0..<[targetIndex] in order.
 * Pure function — no side effects. O(n) per call but n ≤ ~100.
 */
private fun computeStateAt(
    targetIndex: Int,
    events: List<ReplayEvent>,
    lookup: Map<String, Card>
): ReplayState {
    var hands: Map<String, MutableList<Card>> = emptyMap()
    var bids: MutableMap<String, Int>         = mutableMapOf()
    var trickPlays: MutableList<Pair<String, Card>> = mutableListOf()
    var trickCount  = 0
    var description = ""

    val limit = targetIndex.coerceIn(0, events.size)
    for (i in 0 until limit) {
        when (val event = events[i]) {
            is ReplayEvent.Deal -> {
                hands = event.playerHands.mapValues { (_, uids) ->
                    uids.mapNotNull { lookup[it] }.toMutableList()
                }.toMutableMap()
                description = "Cards dealt"
            }
            is ReplayEvent.Bid -> {
                bids[event.playerId] = event.bid
                val label = if (event.bid == 0) "nil${if (event.isBlind) " (blind)" else ""}" else event.bid.toString()
                description = "${event.playerId} bid $label"
            }
            is ReplayEvent.CardPlay -> {
                val card = lookup[event.cardUid] ?: continue
                hands[event.playerId]?.removeIf { it.uid == event.cardUid }
                trickPlays.add(event.playerId to card)
                description = "${event.playerId} played ${card.rank.name.lowercase().replaceFirstChar { it.uppercase() }} of ${card.suit.name.lowercase().replaceFirstChar { it.uppercase() }}"
            }
            is ReplayEvent.TrickWon -> {
                trickCount++
                trickPlays = mutableListOf()
                description = "${event.winnerId} won trick $trickCount"
            }
        }
    }

    return ReplayState(
        stepIndex        = targetIndex,
        hands            = hands.mapValues { (_, v) -> v.toList() },
        bids             = bids.toMap(),
        currentTrickPlays = trickPlays.toList(),
        trickCount       = trickCount,
        description      = description
    )
}
