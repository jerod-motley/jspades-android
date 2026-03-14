package jmotley.com.jspades.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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

private val RjDarkBg       = Color(0xFF1A1A2E)
private val RjCardBg       = Color(0xFF16213E)
private val RjAccentGold   = Color(0xFFFFD700)
private val RjTextPrimary  = Color.White
private val RjTextSecondary = Color.White

@Composable
fun RenegeJokesScreen(
    onNavigateBack: () -> Unit,
    vm: RenegeJokesViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val font = FontFamily(Font(R.font.jenna_sue))

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
                        "Renege Jokes",
                        fontFamily = font,
                        fontSize = 40.sp,
                        color = RjAccentGold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                }

                itemsIndexed(state.items) { index, joke ->
                    RenegeJokeCard(index + 1, joke)
                }

                item { Spacer(Modifier.height(8.dp)) }
            }

            TextButton(
                onClick = onNavigateBack,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Text("Back", color = RjTextSecondary, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun RenegeJokeCard(number: Int, joke: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = RjCardBg),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Text(
                "$number.",
                color = RjAccentGold,
                fontSize = 14.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(joke, color = RjTextPrimary, fontSize = 14.sp)
        }
    }
}
