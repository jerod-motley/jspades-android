package jmotley.com.jspades.screens

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jmotley.com.jspades.R
import jmotley.com.jspades.data.ChatMessage
import jmotley.com.jspades.data.ClientDisplayState
import jmotley.com.jspades.data.Rank
import jmotley.com.jspades.data.Suit
import jmotley.com.jspades.data.cardFromToken

/**
 * Full-screen composable for MP guest clients.
 * Renders the latest GameObj received from the host — no engine runs here.
 *
 * Visual layout (relative to myRoomSeat):
 *   Visual 0 = south  (this client)
 *   Visual 1 = west
 *   Visual 2 = north  (top)
 *   Visual 3 = east
 */
@Composable
fun ClientPlayScreen(
    clientState: ClientDisplayState,
    myRoomSeat: Int,
    chatMessages: List<ChatMessage> = emptyList(),
    onSendBid: (Int) -> Unit,
    onSendPlay: (token: String, trickNum: Int, leaderSeat: Int) -> Unit,
    onSendChat: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val obj = clientState.gameObj

    // Hoisted above return@Box so Compose slot table stays stable regardless of obj nullability.
    // Keys reset the flag automatically when a new hand/trick arrives from the host.
    var bidSubmitted by remember(obj?.currentHand?.handNum) { mutableStateOf(false) }
    var cardPlayed   by remember(obj?.currentTrick?.trickNum) { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        if (obj == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Waiting for game to start…",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            return@Box
        }

        val currentHand  = obj.currentHand
        val currentTrick = obj.currentTrick

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            ScoreBar(obj.team0Score, obj.team1Score, obj.team0Bags, obj.team1Bags)

            Spacer(Modifier.height(8.dp))

            OpponentNames(clientState, obj)

            Spacer(Modifier.weight(1f))

            if (currentTrick != null) {
                TrickArea(clientState, currentTrick, context)
            }

            Spacer(Modifier.weight(1f))

            when {
                !clientState.isMyTurn && obj.phase == "bid" && obj.waitingSeat < 0 ->
                    WaitingIndicator("All bids submitted…")
                !clientState.isMyTurn -> WaitingIndicator(clientState.waitingSeatName)

                obj.phase == "bid" -> if (bidSubmitted) {
                    WaitingIndicator("Bid submitted…")
                } else {
                    BidPickerClient(
                        handNum = currentHand?.handNum ?: 0,
                        onBid   = { bid ->
                            bidSubmitted = true
                            onSendBid(bid)
                        }
                    )
                }

                obj.phase == "trick" -> {
                    val trickNum   = currentTrick?.trickNum ?: 0
                    val leaderSeat = currentTrick?.leader ?: obj.waitingSeat
                    MyHandArea(
                        myHand       = clientState.myHand,
                        currentTrick = currentTrick,
                        context      = context,
                        onPlayCard   = if (cardPlayed) null else { token ->
                            cardPlayed = true
                            onSendPlay(token, trickNum, leaderSeat)
                        }
                    )
                }

                obj.phase == "score" || obj.phase == "endHand" -> HandScoreSummary(obj)

                obj.phase == "finished" -> GameOverOverlay(obj)

                else -> {}
            }

            if (!clientState.isMyTurn && obj.phase == "trick") {
                MyHandArea(
                    myHand       = clientState.myHand,
                    currentTrick = currentTrick,
                    context      = context,
                    onPlayCard   = null
                )
            }
        }

        // ── In-game chat overlay ──────────────────────────────────────────────
        var chatOpen by remember { mutableStateOf(false) }
        InGameChatOverlay(
            messages = chatMessages,
            open     = chatOpen,
            onToggle = { chatOpen = !chatOpen },
            onSend   = onSendChat,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp, end = 8.dp)
        )
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun ScoreBar(t0Score: Int, t1Score: Int, t0Bags: Int, t1Bags: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xAA000000))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Us: $t0Score  (bags: $t0Bags)", color = Color.White, fontSize = 14.sp)
        Text("Them: $t1Score  (bags: $t1Bags)", color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun OpponentNames(clientState: ClientDisplayState, obj: jmotley.com.jspades.networking.GameObj) {
    // Show names at north (visual pos 2), west (visual pos 1), east (visual pos 3)
    val hand = obj.currentHand
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // West
        val westSeat = clientState.roomSeatForVisual(1)
        val westInfo = obj.seats.find { it.seatIndex == westSeat }
        PlayerChip(westInfo?.playerName ?: "West", hand?.seatBids?.getOrNull(westSeat) ?: 0, obj.waitingSeat == westSeat)

        // North
        val northSeat = clientState.roomSeatForVisual(2)
        val northInfo = obj.seats.find { it.seatIndex == northSeat }
        PlayerChip(northInfo?.playerName ?: "North", hand?.seatBids?.getOrNull(northSeat) ?: 0, obj.waitingSeat == northSeat)

        // East
        val eastSeat = clientState.roomSeatForVisual(3)
        val eastInfo = obj.seats.find { it.seatIndex == eastSeat }
        PlayerChip(eastInfo?.playerName ?: "East", hand?.seatBids?.getOrNull(eastSeat) ?: 0, obj.waitingSeat == eastSeat)
    }
}

@Composable
private fun PlayerChip(name: String, bid: Int, isWaiting: Boolean) {
    val bg = if (isWaiting) Color(0xFFFFD700) else Color(0xAA000000)
    val fg = if (isWaiting) Color.Black else Color.White
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text("$name  bid:$bid", color = fg, fontSize = 12.sp)
    }
}

@Composable
private fun TrickArea(
    clientState: ClientDisplayState,
    trick: jmotley.com.jspades.networking.MpTrick,
    context: Context
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center
    ) {
        // Show each played card at its visual position
        val positions = listOf(
            Alignment.BottomCenter,  // south (visual 0 = me)
            Alignment.CenterStart,   // west  (visual 1)
            Alignment.TopCenter,     // north (visual 2)
            Alignment.CenterEnd      // east  (visual 3)
        )
        positions.forEachIndexed { visualPos, alignment ->
            val roomSeat = clientState.roomSeatForVisual(visualPos)
            val token = trick.plays.getOrNull(roomSeat) ?: ""
            if (token.isNotBlank()) {
                Box(modifier = Modifier.align(alignment).padding(16.dp)) {
                    CardImage(token, context, size = 60.dp)
                }
            }
        }
    }
}

@Composable
private fun MyHandArea(
    myHand: List<String>,
    currentTrick: jmotley.com.jspades.networking.MpTrick?,
    context: Context,
    onPlayCard: ((String) -> Unit)?
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy((-24).dp)
    ) {
        items(myHand) { token ->
            val alreadyPlayed = currentTrick?.plays?.contains(token) == true
            val legal = currentTrick == null || isLegalClientPlay(token, myHand, currentTrick)
            val tappable = onPlayCard != null && !alreadyPlayed && legal
            CardImage(
                token   = token,
                context = context,
                size    = 90.dp,
                alpha   = if (tappable) 1f else 0.5f,
                modifier = if (tappable) Modifier.clickable { onPlayCard?.invoke(token) } else Modifier
            )
        }
    }
}

/**
 * Returns true if [token] is a legal play given the current trick and the client's full hand.
 * Only checks suit-following; spades-broken gate is omitted (client lacks that state).
 */
private fun isLegalClientPlay(
    token: String,
    myHand: List<String>,
    trick: jmotley.com.jspades.networking.MpTrick
): Boolean {
    val card = cardFromToken(token) ?: return false
    val isTrump = { t: String ->
        val c = cardFromToken(t); c != null &&
            (c.suit == Suit.SPADES || c.rank == Rank.LITTLEJOKER || c.rank == Rank.BIGJOKER)
    }
    val leadToken = trick.plays.firstOrNull { it.isNotBlank() } ?: return true  // leading — allow any
    val leadCard  = cardFromToken(leadToken) ?: return true
    val isLedTrump = leadCard.suit == Suit.SPADES ||
        leadCard.rank == Rank.LITTLEJOKER || leadCard.rank == Rank.BIGJOKER
    val myCards = myHand.mapNotNull { cardFromToken(it) }
    val hasLedSuit = if (isLedTrump) {
        myCards.any { c -> c.suit == Suit.SPADES || c.rank == Rank.LITTLEJOKER || c.rank == Rank.BIGJOKER }
    } else {
        myCards.any { c -> c.suit == leadCard.suit && c.suit != Suit.SPADES &&
            c.rank != Rank.LITTLEJOKER && c.rank != Rank.BIGJOKER }
    }
    if (!hasLedSuit) return true  // void in led suit — anything goes
    return if (isLedTrump) isTrump(token)
    else card.suit == leadCard.suit && card.suit != Suit.SPADES &&
        card.rank != Rank.LITTLEJOKER && card.rank != Rank.BIGJOKER
}

@Composable
private fun BidPickerClient(handNum: Int, onBid: (Int) -> Unit) {
    var bid by remember(handNum) { mutableIntStateOf(4) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Your bid: $bid", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Slider(
            value = bid.toFloat(),
            onValueChange = { bid = it.toInt() },
            valueRange = 0f..13f,
            steps = 12,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onBid(bid) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
        ) {
            Text("Submit Bid $bid", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WaitingIndicator(waitingName: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (waitingName.isNotBlank()) "Waiting for $waitingName…" else "Waiting…",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xAA000000))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun HandScoreSummary(obj: jmotley.com.jspades.networking.GameObj) {
    val hand = obj.currentHand ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Hand Complete", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Team 0 — bid ${hand.team0Bid}, won ${hand.team0Tricks}", color = Color.White)
        Text("Team 1 — bid ${hand.team1Bid}, won ${hand.team1Tricks}", color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text("Score: ${obj.team0Score} vs ${obj.team1Score}", color = Color(0xFFFFD700), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GameOverOverlay(obj: jmotley.com.jspades.networking.GameObj) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Game Over", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text("Final Score", color = Color(0xFFFFD700), fontSize = 18.sp)
            Text("Team 0: ${obj.team0Score}", color = Color.White, fontSize = 16.sp)
            Text("Team 1: ${obj.team1Score}", color = Color.White, fontSize = 16.sp)
        }
    }
}

@Composable
private fun CardImage(
    token: String,
    context: Context,
    size: androidx.compose.ui.unit.Dp,
    alpha: Float = 1f,
    modifier: Modifier = Modifier
) {
    val card = remember(token) { cardFromToken(token) }
    val resId = remember(card) {
        if (card != null) {
            val name = card.assetFileName().dropLast(4)  // strip ".png"
            context.resources.getIdentifier(name, "drawable", context.packageName)
        } else 0
    }

    if (resId != 0) {
        Image(
            painter = painterResource(id = resId),
            contentDescription = token,
            modifier = modifier
                .size(width = size * 0.7f, height = size)
                .clip(RoundedCornerShape(4.dp)),
            alpha = alpha,
            contentScale = ContentScale.Fit
        )
    } else {
        // Fallback: card back or blank
        Box(
            modifier = modifier
                .size(width = size * 0.7f, height = size)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF1A3A2A))
                .border(1.dp, Color.White, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(token, color = Color.White, fontSize = 8.sp, textAlign = TextAlign.Center)
        }
    }
}
