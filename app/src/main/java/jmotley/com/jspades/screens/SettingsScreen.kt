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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

private const val PREFS            = "jspades_prefs"
private const val KEY_SOUND        = "sound_enabled"
private const val KEY_HOUSE_MIN_BID = "house_rules_min_bid"

@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val font    = FontFamily(Font(R.font.jenna_sue))
    val context = LocalContext.current
    val prefs   = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var soundEnabled by remember { mutableStateOf(prefs.getBoolean(KEY_SOUND, true)) }
    var houseMinBid  by remember { mutableIntStateOf(prefs.getInt(KEY_HOUSE_MIN_BID, 4)) }

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
                Text("Sound Effects", color = SettTextPrimary, fontSize = 16.sp)
                Switch(
                    checked = soundEnabled,
                    onCheckedChange = { on ->
                        soundEnabled = on
                        prefs.edit().putBoolean(KEY_SOUND, on).apply()
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
            Text(
                text = "House Rules Minimum Bid",
                color = SettTextPrimary,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf(4, 5).forEach { value: Int ->
                    RadioButton(
                        selected = houseMinBid == value,
                        onClick = {
                            houseMinBid = value
                            prefs.edit().putInt(KEY_HOUSE_MIN_BID, value).apply()
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = SettAccentGold)
                    )
                    Text("$value", color = SettTextPrimary, fontSize = 14.sp)
                    Spacer(Modifier.width(24.dp))
                }
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
