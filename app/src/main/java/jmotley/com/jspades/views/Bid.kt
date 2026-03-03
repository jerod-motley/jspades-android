package jmotley.com.jspades.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.models.GameViewModel

/** Bid range for a standard 13-trick hand; 0 = Nil, 14+ if board variants allow. */
private const val MIN_BID = 0
private const val MAX_BID = 13

/**
 * Bid view: modal overlay displayed when it is the local player's turn to bid.
 *
 * Shows a number selector (0–13) and a Confirm button. The selected value is
 * committed via [viewModel] once confirmed.
 *
 * TODO:
 *  - Add Blind Nil button guarded by game-type rules
 *  - Disable bid values below the minimum allowed by the current variant
 *  - Commit bid through a dedicated ViewModel method (e.g., `submitBid`)
 */
@Composable
fun BidView(
    state: GameState,
    viewModel: GameViewModel,
    localPlayerId: String,
    modifier: Modifier = Modifier
) {
    var selectedBid by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .background(color = Color(0xCC000000), shape = RoundedCornerShape(16.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {

            Text(
                text = "Your Bid",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Bid selector: – value +
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                BidAdjustButton(label = "–") {
                    if (selectedBid > MIN_BID) selectedBid--
                }
                Text(
                    text = if (selectedBid == 0) "Nil" else "$selectedBid",
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(60.dp)
                )
                BidAdjustButton(label = "+") {
                    if (selectedBid < MAX_BID) selectedBid++
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Quick-pick row: tap any number 0–13 directly
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..7).forEach { n ->
                    QuickPick(n, selected = selectedBid == n) { selectedBid = n }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (8..MAX_BID).forEach { n ->
                    QuickPick(n, selected = selectedBid == n) { selectedBid = n }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Confirm button
            Box(
                modifier = Modifier
                    .size(width = 160.dp, height = 48.dp)
                    .background(color = Color(0xFF1B5E20), shape = RoundedCornerShape(8.dp))
                    .clickable {
                        // TODO: call viewModel.submitBid(localPlayerId, selectedBid)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Confirm Bid",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
private fun BidAdjustButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(color = Color(0xFF37474F), shape = RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun QuickPick(value: Int, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Color(0xFF1565C0) else Color(0xFF37474F)
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(color = bg, shape = RoundedCornerShape(4.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (value == 0) "N" else "$value",
            color = Color.White,
            fontSize = 11.sp
        )
    }
}
