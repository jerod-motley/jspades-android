package jmotley.com.jspades.screens

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jmotley.com.jspades.R

private val SettDarkBg      = Color(0xFF1A1A2E)
private val SettCardBg      = Color(0xFF16213E)
private val SettAccentGold  = Color(0xFFFFD700)
private val SettTextPrimary = Color.White
private val SettTextSecond  = Color(0xFFAAAAAA)

private const val PREFS                 = "jspades_prefs"
private const val KEY_TWO_SPADES_JOKER  = "two_of_spades_joker"
private const val KEY_SPADES_MUST_BREAK = "spades_must_break"
private const val KEY_MIN_BID_FIVE      = "min_bid_five"

@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val font    = FontFamily(Font(R.font.jenna_sue))
    val context = LocalContext.current
    val prefs   = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var twoSpadesJoker  by remember { mutableStateOf(prefs.getBoolean(KEY_TWO_SPADES_JOKER, false)) }
    var spadesMustBreak by remember { mutableStateOf(prefs.getBoolean(KEY_SPADES_MUST_BREAK, false)) }
    var minBidFive      by remember { mutableStateOf(prefs.getBoolean(KEY_MIN_BID_FIVE, false)) }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
        Text(
            text = "Settings",
            fontFamily = font,
            fontSize = 40.sp,
            color = SettAccentGold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 16.dp),
            textAlign = TextAlign.Center
        )

        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("2♠ as 3rd Joker", color = SettTextPrimary, fontSize = 16.sp)
                Switch(
                    checked = twoSpadesJoker,
                    onCheckedChange = { on ->
                        twoSpadesJoker = on
                        prefs.edit().putBoolean(KEY_TWO_SPADES_JOKER, on).apply()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SettAccentGold,
                        checkedTrackColor = SettAccentGold.copy(alpha = 0.4f)
                    )
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Spades Must Break", color = SettTextPrimary, fontSize = 16.sp)
                Switch(
                    checked = spadesMustBreak,
                    onCheckedChange = { on ->
                        spadesMustBreak = on
                        prefs.edit().putBoolean(KEY_SPADES_MUST_BREAK, on).apply()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SettAccentGold,
                        checkedTrackColor = SettAccentGold.copy(alpha = 0.4f)
                    )
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Minimum Bid 5", color = SettTextPrimary, fontSize = 16.sp)
                Switch(
                    checked = minBidFive,
                    onCheckedChange = { on ->
                        minBidFive = on
                        prefs.edit().putBoolean(KEY_MIN_BID_FIVE, on).apply()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SettAccentGold,
                        checkedTrackColor = SettAccentGold.copy(alpha = 0.4f)
                    )
                )
            }
        }

        Spacer(Modifier.weight(1f))

        TextButton(
            onClick = onNavigateBack,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            Text("Back", color = SettTextSecond, fontSize = 16.sp)
        }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(SettCardBg, shape = RoundedCornerShape(8.dp))
            .padding(16.dp),
        content = content
    )
}
