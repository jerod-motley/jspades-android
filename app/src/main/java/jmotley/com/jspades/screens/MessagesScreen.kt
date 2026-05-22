package jmotley.com.jspades.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import jmotley.com.jspades.models.MessagesViewModel
import jmotley.com.jspades.networking.Message

private val MsgDarkBg       = Color(0xFF1A1A2E)
private val MsgCardBg       = Color(0xFF16213E)
private val MsgAccentGold   = Color(0xFFFFD700)
private val MsgTextPrimary  = Color.White
private val MsgTextSecondary = Color.White

@Composable
fun MessagesScreen(
    onNavigateBack: () -> Unit,
    vm: MessagesViewModel = viewModel()
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
            reverseLayout = true
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            if (state.isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MsgAccentGold)
                    }
                }
            } else if (state.error != null) {
                item {
                    Text(
                        "Could not load messages.",
                        color = MsgTextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else if (state.items.isEmpty()) {
                item {
                    Text(
                        "No messages yet — start the conversation!",
                        color = MsgTextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                items(state.items) { msg -> MessageBubble(msg) }
            }

            item {
                Text(
                    "Community Messages",
                    fontFamily = font,
                    style = MaterialTheme.typography.displaySmall,
                    color = MsgAccentGold,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    textAlign = TextAlign.Center
                )
            }
        }

        // Compose bar
        Surface(color = MsgCardBg, tonalElevation = 4.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Say something…", color = MsgTextSecondary, style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MsgAccentGold,
                        unfocusedBorderColor = MsgTextSecondary,
                        focusedTextColor = MsgTextPrimary,
                        unfocusedTextColor = MsgTextPrimary
                    ),
                    maxLines = 3
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.create(draft); draft = "" },
                    colors = ButtonDefaults.buttonColors(containerColor = MsgAccentGold),
                    shape = RoundedCornerShape(6.dp),
                    enabled = draft.isNotBlank()
                ) {
                    Text("Send", color = Color.Black, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

    }
        TextButton(
            onClick = onNavigateBack,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)
        ) {
            Text("Back", color = MsgTextSecondary, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MsgCardBg),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(message.author, color = MsgAccentGold, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(2.dp))
            Text(message.text, color = MsgTextPrimary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
