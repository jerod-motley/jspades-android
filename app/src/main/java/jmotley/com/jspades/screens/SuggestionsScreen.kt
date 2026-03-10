package jmotley.com.jspades.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import jmotley.com.jspades.R
import jmotley.com.jspades.models.SuggestionsViewModel
import jmotley.com.jspades.networking.Suggestion

private val DarkBg      = Color(0xFF1A1A2E)
private val CardBg      = Color(0xFF16213E)
private val AccentGold  = Color(0xFFFFD700)
private val TextPrimary = Color.White
private val TextSecondary = Color(0xFFAAAAAA)

@Composable
fun SuggestionsScreen(
    onNavigateBack: () -> Unit,
    vm: SuggestionsViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val font = FontFamily(Font(R.font.jenna_sue))
    var draft by remember { mutableStateOf("") }

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
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Suggestions",
                    fontFamily = font,
                    fontSize = 40.sp,
                    color = AccentGold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
            }

            if (state.isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentGold)
                    }
                }
            } else if (state.error != null) {
                item {
                    Text(
                        "Could not load suggestions.",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else if (state.items.isEmpty()) {
                item {
                    Text(
                        "No suggestions yet — be the first!",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                items(state.items) { s -> SuggestionCard(s, onLike = { vm.like(s) }) }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }

        // Compose bar
        Surface(color = CardBg, tonalElevation = 4.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Your suggestion…", color = TextSecondary, fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGold,
                        unfocusedBorderColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    maxLines = 3
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.create(draft); draft = "" },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGold),
                    shape = RoundedCornerShape(6.dp),
                    enabled = draft.isNotBlank()
                ) {
                    Text("Post", color = Color.Black, fontSize = 13.sp)
                }
            }
        }

        TextButton(
            onClick = onNavigateBack,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Text("Back", color = TextSecondary, fontSize = 16.sp)
        }
    }
    }
}

@Composable
private fun SuggestionCard(suggestion: Suggestion, onLike: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(suggestion.description, color = TextPrimary, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(suggestion.user, color = TextSecondary, fontSize = 11.sp)
                TextButton(onClick = onLike, contentPadding = PaddingValues(4.dp)) {
                    Text("♥ ${suggestion.likes}", color = AccentGold, fontSize = 12.sp)
                }
            }
        }
    }
}
