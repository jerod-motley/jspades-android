package jmotley.com.jspades.screens

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.FontWeight
import jmotley.com.jspades.R
import jmotley.com.jspades.data.GameLength

private val SettDarkBg      = Color(0xFF1A1A2E)
private val SettCardBg      = Color(0xFF16213E)
private val SettAccentGold  = Color(0xFFFFD700)
private val SettTextPrimary = Color.White
private val SettTextSecond  = Color.White

private const val PREFS                 = "jspades_prefs"
private const val KEY_TWO_SPADES_JOKER  = "two_of_spades_joker"
private const val KEY_SPADES_MUST_BREAK = "spades_must_break"
private const val KEY_MIN_BID_FIVE      = "min_bid_five"
private const val KEY_ALLOW_NIL_BID     = "allow_nil_bid"
private const val KEY_COUNT_OVERS       = "count_overs"
private const val KEY_GAME_LENGTH       = "game_length"
private const val KEY_BLIND_EXCHANGE    = "blind_nil_exchange"

@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val font    = FontFamily(Font(R.font.jenna_sue))
    val context = LocalContext.current
    val prefs   = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var twoSpadesJoker  by remember { mutableStateOf(prefs.getBoolean(KEY_TWO_SPADES_JOKER, false)) }
    var spadesMustBreak by remember { mutableStateOf(prefs.getBoolean(KEY_SPADES_MUST_BREAK, false)) }
    var minBidFive      by remember { mutableStateOf(prefs.getBoolean(KEY_MIN_BID_FIVE, false)) }
    var allowNilBid     by remember { mutableStateOf(prefs.getBoolean(KEY_ALLOW_NIL_BID, false)) }
    var countOvers      by remember { mutableStateOf(prefs.getBoolean(KEY_COUNT_OVERS, true)) }
    var blindExchange   by remember { mutableStateOf(prefs.getBoolean(KEY_BLIND_EXCHANGE, false)) }
    var gameLength      by remember { mutableStateOf(GameLength.valueOf(prefs.getString(KEY_GAME_LENGTH, GameLength.MEDIUM.name) ?: GameLength.MEDIUM.name)) }

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
            style = MaterialTheme.typography.displaySmall,
            color = SettAccentGold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 16.dp),
            textAlign = TextAlign.Center
        )

        SettingsCard {
            val fontScale = LocalConfiguration.current.fontScale
            if (fontScale >= 1.3f) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("2♠ as 3rd Joker", color = SettTextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = twoSpadesJoker,
                        onCheckedChange = { on: Boolean ->
                            twoSpadesJoker = on
                            prefs.edit().putBoolean(KEY_TWO_SPADES_JOKER, on).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SettAccentGold,
                            checkedTrackColor = SettAccentGold.copy(alpha = 0.4f)
                        )
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("2♠ as 3rd Joker", color = SettTextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = twoSpadesJoker,
                        onCheckedChange = { on: Boolean ->
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
        }

        Spacer(Modifier.height(8.dp))

        SettingsCard {
            val fontScale = LocalConfiguration.current.fontScale
            if (fontScale >= 1.3f) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Spades Must Break", color = SettTextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = spadesMustBreak,
                        onCheckedChange = { on: Boolean ->
                            spadesMustBreak = on
                            prefs.edit().putBoolean(KEY_SPADES_MUST_BREAK, on).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SettAccentGold,
                            checkedTrackColor = SettAccentGold.copy(alpha = 0.4f)
                        )
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Spades Must Break", color = SettTextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = spadesMustBreak,
                        onCheckedChange = { on: Boolean ->
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
        }

        Spacer(Modifier.height(8.dp))

        SettingsCard {
            val fontScale = LocalConfiguration.current.fontScale
            if (fontScale >= 1.3f) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Allow Nil Bid (Solo)", color = SettTextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = allowNilBid,
                        onCheckedChange = { on: Boolean ->
                            allowNilBid = on
                            prefs.edit().putBoolean(KEY_ALLOW_NIL_BID, on).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SettAccentGold,
                            checkedTrackColor = SettAccentGold.copy(alpha = 0.4f)
                        )
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Allow Nil Bid (Solo)", color = SettTextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = allowNilBid,
                        onCheckedChange = { on: Boolean ->
                            allowNilBid = on
                            prefs.edit().putBoolean(KEY_ALLOW_NIL_BID, on).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SettAccentGold,
                            checkedTrackColor = SettAccentGold.copy(alpha = 0.4f)
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        SettingsCard {
            val fontScale = LocalConfiguration.current.fontScale
            if (fontScale >= 1.3f) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Count Overs", color = SettTextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = countOvers,
                        onCheckedChange = { on: Boolean ->
                            countOvers = on
                            prefs.edit().putBoolean(KEY_COUNT_OVERS, on).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SettAccentGold,
                            checkedTrackColor = SettAccentGold.copy(alpha = 0.4f)
                        )
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Count Overs", color = SettTextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = countOvers,
                        onCheckedChange = { on: Boolean ->
                            countOvers = on
                            prefs.edit().putBoolean(KEY_COUNT_OVERS, on).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SettAccentGold,
                            checkedTrackColor = SettAccentGold.copy(alpha = 0.4f)
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        SettingsCard {
            val fontScale = LocalConfiguration.current.fontScale
            if (fontScale >= 1.3f) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Blind Nil Exchange", color = SettTextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = blindExchange,
                        onCheckedChange = { on: Boolean ->
                            blindExchange = on
                            prefs.edit().putBoolean(KEY_BLIND_EXCHANGE, on).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SettAccentGold,
                            checkedTrackColor = SettAccentGold.copy(alpha = 0.4f)
                        )
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Blind Nil Exchange", color = SettTextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = blindExchange,
                        onCheckedChange = { on: Boolean ->
                            blindExchange = on
                            prefs.edit().putBoolean(KEY_BLIND_EXCHANGE, on).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SettAccentGold,
                            checkedTrackColor = SettAccentGold.copy(alpha = 0.4f)
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        SettingsCard {
            val fontScale = LocalConfiguration.current.fontScale
            if (fontScale >= 1.3f) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Minimum Bid 5", color = SettTextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = minBidFive,
                        onCheckedChange = { on: Boolean ->
                            minBidFive = on
                            prefs.edit().putBoolean(KEY_MIN_BID_FIVE, on).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SettAccentGold,
                            checkedTrackColor = SettAccentGold.copy(alpha = 0.4f)
                        )
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Minimum Bid 5", color = SettTextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = minBidFive,
                        onCheckedChange = { on: Boolean ->
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
        }

        Spacer(Modifier.height(8.dp))

        SettingsCard {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Game Length", color = SettTextPrimary, style = MaterialTheme.typography.bodyLarge)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GameLength.values().filter { it != GameLength.TEST }.forEach { option: GameLength ->
                        val selected = gameLength == option
                        val label = option.name.lowercase().replaceFirstChar { c -> c.uppercase() }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, if (selected) SettAccentGold else Color.White.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .background(if (selected) SettAccentGold.copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable {
                                    gameLength = option
                                    prefs.edit().putString(KEY_GAME_LENGTH, option.name).apply()
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (selected) SettAccentGold else Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
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
            Text("Back", color = SettTextSecond, style = MaterialTheme.typography.bodyLarge)
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
