package jmotley.com.jspades.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import jmotley.com.jspades.models.RenegeJokesViewModel
import jmotley.com.jspades.networking.RenegeJoke

private val RjDarkBg       = Color(0xFF1A1A2E)
private val RjCardBg       = Color(0xFF16213E)
private val RjAccentGold   = Color(0xFFFFD700)
private val RjTextPrimary  = Color.White
private val RjTextSecondary = Color(0xFFAAAAAA)

@Composable
fun RenegeJokesScreen(
    onNavigateBack: () -> Unit,
    vm: RenegeJokesViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val font = FontFamily(Font(R.font.jenna_sue))
    var draft by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RjDarkBg)
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Renege Jokes",
                    fontFamily = font,
                    fontSize = 40.sp,
                    color = RjAccentGold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
            }

            if (state.isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = RjAccentGold)
                    }
                }
            } else if (state.error != null) {
                item {
                    Text(
                        "Could not load jokes.",
                        color = RjTextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else if (state.items.isEmpty()) {
                item {
                    Text(
                        "No jokes yet — add one!",
                        color = RjTextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                items(state.items) { joke -> RenegeJokeCard(joke, onLike = { vm.like(joke) }) }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }

        // Compose bar
        Surface(color = RjCardBg, tonalElevation = 4.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Share a renege joke…", color = RjTextSecondary, fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RjAccentGold,
                        unfocusedBorderColor = RjTextSecondary,
                        focusedTextColor = RjTextPrimary,
                        unfocusedTextColor = RjTextPrimary
                    ),
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.create(draft); draft = "" },
                    colors = ButtonDefaults.buttonColors(containerColor = RjAccentGold),
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
            Text("Back", color = RjTextSecondary, fontSize = 16.sp)
        }
    }
}

@Composable
private fun RenegeJokeCard(joke: RenegeJoke, onLike: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = RjCardBg),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(joke.text, color = RjTextPrimary, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(joke.user, color = RjTextSecondary, fontSize = 11.sp)
                TextButton(onClick = onLike, contentPadding = PaddingValues(4.dp)) {
                    Text("♥ ${joke.likes}", color = RjAccentGold, fontSize = 12.sp)
                }
            }
        }
    }
}
