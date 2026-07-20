package jmotley.com.jspades.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import jmotley.com.jspades.BuildConfig
import jmotley.com.jspades.R
import jmotley.com.jspades.data.*
import jmotley.com.jspades.models.LobbyUiState
import jmotley.com.jspades.models.OnlineLobbyViewModel

private val AccentGold   = Color(0xFFFFD700)
private val AccentGreen  = Color(0xFF4CAF50)
private val AccentBlue   = Color(0xFF2196F3)
private val AccentRed    = Color(0xFFEF5350)
private val TextPrimary  = Color.White
private val TextSecondary = Color(0xFFB0BEC5)
private val PanelBg      = Color(0xCC000000)   // semi-transparent dark overlay on felt
private val ChipBg       = Color(0xBB0A1628)
private val DividerColor = Color(0x44FFFFFF)

@Composable
fun OnlineLobbyScreen(
    startMode: String = "host",
    initialRoomCode: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateToPlay: () -> Unit = {},
    vm: OnlineLobbyViewModel = viewModel()
) {
    val uiState by vm.uiState.collectAsState()

    LaunchedEffect(Unit) {
        vm.navigateToPlay.collect { onNavigateToPlay() }
    }

    LaunchedEffect(startMode, initialRoomCode) {
        when (startMode) {
            "host" -> vm.chooseHost()
            "join" -> if (initialRoomCode != null) vm.joinRoomCode(initialRoomCode) else vm.chooseJoin()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Green felt background — same as the game screen
        Image(
            painter = painterResource(id = R.drawable.bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        when (val state = uiState) {
            is LobbyUiState.RoleChoice -> {
                // startMode auto-triggers chooseHost/chooseJoin via LaunchedEffect.
                // Show nothing while the ViewModel transitions to Connecting.
            }
            is LobbyUiState.JoinEntry -> JoinEntryView(
                state = state,
                onCodeChange = vm::updateJoinCode,
                onSubmit = vm::submitJoin,
                onBack = { vm.disconnect(); onNavigateBack() }
            )
            is LobbyUiState.Connecting -> ConnectingView(
                onCancel = { vm.disconnect(); onNavigateBack() }
            )
            is LobbyUiState.InLobby -> if (BuildConfig.DEBUG && (state.mockRunning || state.mockLog.isNotEmpty())) {
                MockLogView(
                    state = state,
                    onSendChat = vm::sendChat,
                    onBack = { vm.disconnect(); onNavigateBack() }
                )
            } else {
                LobbyView(
                    state = state,
                    onSendChat = vm::sendChat,
                    onSwapSeats = vm::swapSeats,
                    onStartMock = vm::startMockTest,
                    onStartGame = vm::startGame,
                    onBack = { vm.disconnect(); onNavigateBack() }
                )
            }
        }
    }
}

// ── Role choice ───────────────────────────────────────────────────────────────

@Composable
private fun RoleChoiceView(
    displayName: String,
    onHost: () -> Unit,
    onJoin: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Play Online", color = AccentGold, fontSize = 28.sp, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("Playing as: $displayName", color = TextSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(48.dp))

        LobbyButton("Host Game", AccentGreen, onHost)
        Spacer(Modifier.height(16.dp))
        LobbyButton("Join Game", AccentBlue, onJoin)
        Spacer(Modifier.height(32.dp))
        TextButton(onClick = onBack) { Text("← Back", color = TextSecondary) }
    }
}

// ── Join entry ────────────────────────────────────────────────────────────────

@Composable
private fun JoinEntryView(
    state: LobbyUiState.JoinEntry,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Join a Room", color = AccentGold, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = state.codeInput,
            onValueChange = onCodeChange,
            label = { Text("Room Code", color = TextSecondary) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            textStyle = TextStyle(color = TextPrimary, fontSize = 22.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 4.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentBlue,
                unfocusedBorderColor = DividerColor,
                cursorColor = AccentBlue
            ),
            isError = state.error != null,
            supportingText = state.error?.let { { Text(it, color = AccentRed) } },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))
        LobbyButton("Join", AccentBlue, onSubmit)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("← Back", color = TextSecondary) }
    }
}

// ── Connecting ────────────────────────────────────────────────────────────────

@Composable
private fun ConnectingView(onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = AccentBlue)
        Spacer(Modifier.height(16.dp))
        Text("Connecting…", color = TextSecondary, fontSize = 16.sp)
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onCancel) { Text("Cancel", color = TextSecondary) }
    }
}

// ── Main lobby ────────────────────────────────────────────────────────────────

@Composable
private fun LobbyView(
    state: LobbyUiState.InLobby,
    onSendChat: (String) -> Unit,
    onSwapSeats: (Int, Int) -> Unit,
    onStartMock: () -> Unit,
    onStartGame: () -> Unit,
    onBack: () -> Unit
) {
    val lobby = state.lobby
    val countdown = state.countdownSeconds
    val counting = countdown > 0
    val context = LocalContext.current

    // Seat swap selection — locked during countdown
    var selectedSeat by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(counting) { if (counting) selectedSeat = null }
    val localSeat = lobby.localSeatIndex.coerceAtLeast(0)

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Header ────────────────────────────────────────────────────────────
        LobbyHeader(lobby, onBack)

        // ── Diamond ───────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LobbyDiamond(
                lobby = lobby,
                selectedSeatIndex = if (counting) null else selectedSeat,
                onTapSeat = { tapped ->
                    if (counting || !lobby.isHost || tapped == localSeat) return@LobbyDiamond
                    val current = selectedSeat
                    when {
                        current == null   -> selectedSeat = tapped
                        current == tapped -> selectedSeat = null
                        else -> { onSwapSeats(current, tapped); selectedSeat = null }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Countdown overlay — sits on top of the diamond
            if (counting) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "$countdown",
                        color = AccentGold,
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Game starting…", color = TextSecondary, fontSize = 16.sp)
                }
            }
        }

        // ── Chat + controls ───────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(PanelBg)
        ) {
            Column {
                HorizontalDivider(color = DividerColor)

                if (lobby.isHost) {
                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Button(
                            onClick = {
                                val inviteUrl = "${AppConfig.WEB_URL}invite/jspades?room=${lobby.inviteCode}"
                                val text = "${lobby.localDisplayName} wants to play jSpades with you.\n$inviteUrl"
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(Intent.createChooser(intent, "Invite via"))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                        ) {
                            Text("Invite Players", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                    }
                    HorizontalDivider(color = DividerColor)
                }

                ChatSection(
                    messages = state.chat,
                    modifier = Modifier.heightIn(min = 100.dp, max = 200.dp),
                    onSend = onSendChat
                )

                HorizontalDivider(color = DividerColor)
                when {
                    counting -> {
                        // Countdown bar — shown for all players
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Starting in $countdown…",
                                color = AccentGold,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    lobby.isHost -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onStartGame,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                            ) {
                                Text("Start Game", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                            if (BuildConfig.DEBUG) {
                                Button(
                                    onClick = onStartMock,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF546E7A))
                                ) {
                                    Text("Protocol Test", fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Lobby diamond ─────────────────────────────────────────────────────────────

@Composable
private fun LobbyDiamond(
    lobby: OnlineLobbyState,
    selectedSeatIndex: Int?,
    onTapSeat: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val localSeat = lobby.localSeatIndex.coerceAtLeast(0)

    // Map visual positions to room seat indices using local-player rotation
    val southIdx = localSeat
    val westIdx  = (localSeat + 1) % 4
    val northIdx = (localSeat + 2) % 4
    val eastIdx  = (localSeat + 3) % 4

    fun seatAt(idx: Int) = lobby.seats.find { it.seatIndex == idx }

    BoxWithConstraints(modifier = modifier) {
        val armV: Dp = maxHeight * 0.28f
        val armH: Dp = maxWidth  * 0.36f

        // Connector lines between the four positions
        Canvas(
            modifier = Modifier
                .size(armH * 2, armV * 2)
                .align(Alignment.Center)
        ) {
            val cx = size.width / 2; val cy = size.height / 2
            val lc = Color(0x55FFFFFF); val sw = 1.5f
            drawLine(lc, Offset(cx, cy), Offset(cx, 0f),           sw)
            drawLine(lc, Offset(cx, cy), Offset(cx, size.height),  sw)
            drawLine(lc, Offset(cx, cy), Offset(0f, cy),           sw)
            drawLine(lc, Offset(cx, cy), Offset(size.width, cy),   sw)
        }

        // South — always local player, locked
        Box(modifier = Modifier.align(Alignment.Center).offset(y = armV)) {
            SeatChip(
                seat        = seatAt(southIdx),
                label       = "South",
                isLocal     = true,
                isSelected  = false,
                canTap      = false,
                onClick     = {}
            )
        }

        // North
        Box(modifier = Modifier.align(Alignment.Center).offset(y = -armV)) {
            SeatChip(
                seat        = seatAt(northIdx),
                label       = "North",
                isLocal     = false,
                isSelected  = selectedSeatIndex == northIdx,
                canTap      = lobby.isHost,
                onClick     = { onTapSeat(northIdx) }
            )
        }

        // West
        Box(modifier = Modifier.align(Alignment.Center).offset(x = -armH)) {
            SeatChip(
                seat        = seatAt(westIdx),
                label       = "West",
                isLocal     = false,
                isSelected  = selectedSeatIndex == westIdx,
                canTap      = lobby.isHost,
                onClick     = { onTapSeat(westIdx) }
            )
        }

        // East
        Box(modifier = Modifier.align(Alignment.Center).offset(x = armH)) {
            SeatChip(
                seat        = seatAt(eastIdx),
                label       = "East",
                isLocal     = false,
                isSelected  = selectedSeatIndex == eastIdx,
                canTap      = lobby.isHost,
                onClick     = { onTapSeat(eastIdx) }
            )
        }

        // Hint for host when a seat is selected
        if (selectedSeatIndex != null && lobby.isHost) {
            Text(
                text = "Tap another seat to swap",
                color = AccentGold,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun SeatChip(
    seat: OnlineSeat?,
    label: String,
    isLocal: Boolean,
    isSelected: Boolean,
    canTap: Boolean,
    onClick: () -> Unit
) {
    val isEmpty = seat == null || seat.kind == SeatKind.Open

    val bg = when {
        isLocal   -> Color(0xBB1B5E20)
        isSelected -> Color(0xBB1A237E)
        isEmpty   -> Color(0x661A1A1A)
        else      -> ChipBg
    }

    val borderColor = when {
        isLocal    -> AccentGold
        isSelected -> AccentGold
        isEmpty    -> Color(0x44FFFFFF)
        else       -> Color(0x66FFFFFF)
    }

    val borderWidth = if (isLocal || isSelected) 2.dp else 1.dp

    Box(
        modifier = Modifier
            .width(110.dp)
            .wrapContentHeight()
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .background(bg, RoundedCornerShape(12.dp))
            .then(if (canTap && !isLocal) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Position label
            Text(
                text = label,
                color = if (isLocal) AccentGold else TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(4.dp))
            // Player name / status
            when {
                isEmpty -> Text(
                    "— open —",
                    color = Color(0x88FFFFFF),
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                    textAlign = TextAlign.Center
                )
                else -> if (seat != null) {
                    val name = seat.displayName ?: seat.playerId?.takeLast(8) ?: "?"
                    Text(
                        text = name,
                        color = if (isLocal) AccentGold else TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = if (isLocal) FontWeight.Bold else FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Badges row
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isLocal) {
                            Spacer(Modifier.height(2.dp))
                            Text("YOU", color = AccentGold, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                        if (seat.isHost) {
                            if (isLocal) Spacer(Modifier.width(4.dp))
                            Text("HOST", color = AccentBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                    }
                }
            }
            // Swap hint arrow when selected
            if (isSelected) {
                Spacer(Modifier.height(4.dp))
                Text("↕", color = AccentGold, fontSize = 14.sp)
            }
        }
    }
}

// ── Lobby header ──────────────────────────────────────────────────────────────

@Composable
private fun LobbyHeader(lobby: OnlineLobbyState, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context   = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) { Text("← Back", color = TextSecondary) }
        Spacer(Modifier.weight(1f))

        if (lobby.isHost) {
            Column(horizontalAlignment = Alignment.End) {
                Text("ROOM CODE", color = TextSecondary, fontSize = 9.sp, letterSpacing = 1.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = lobby.inviteCode,
                        color = AccentGold,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(lobby.inviteCode))
                            Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(4.dp)
                    ) { Text("Copy", color = AccentBlue, fontSize = 11.sp) }
                    TextButton(
                        onClick = {
                            val inviteUrl = "${AppConfig.WEB_URL}invite/jspades?room=${lobby.inviteCode}"
                            val text = "Join my jSpades game: $inviteUrl\nRoom code: ${lobby.inviteCode}"
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share via"))
                        },
                        contentPadding = PaddingValues(4.dp)
                    ) { Text("Share", color = AccentGreen, fontSize = 11.sp) }
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.End) {
                Text("ROOM", color = TextSecondary, fontSize = 9.sp, letterSpacing = 1.sp)
                Text(lobby.inviteCode, color = AccentGold, fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 3.sp)
            }
        }
    }
}

// ── Chat ──────────────────────────────────────────────────────────────────────

@Composable
private fun ChatSection(messages: List<ChatMessage>, modifier: Modifier, onSend: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            items(messages) { msg ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    if (msg.isLocal) Spacer(Modifier.weight(1f))
                    Column(
                        modifier = Modifier
                            .background(
                                if (msg.isLocal) Color(0xBB1A3A5C) else Color(0xBB152236),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .widthIn(max = 260.dp)
                    ) {
                        if (!msg.isLocal) {
                            Text(msg.displayName, color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(msg.text, color = TextPrimary, fontSize = 14.sp)
                    }
                    if (!msg.isLocal) Spacer(Modifier.weight(1f))
                }
            }
        }

        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Message…", color = TextSecondary, fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend(input); input = "" }),
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = DividerColor,
                    cursorColor = AccentBlue
                )
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onSend(input); input = "" },
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            ) { Text("Send", fontSize = 13.sp) }
        }
    }
}

// ── Mock test log ─────────────────────────────────────────────────────────────

@Composable
private fun MockLogView(
    state: LobbyUiState.InLobby,
    onSendChat: (String) -> Unit,
    onBack: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.mockLog.size) {
        if (state.mockLog.isNotEmpty()) listState.animateScrollToItem(state.mockLog.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xDD0A1628))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Back", color = TextSecondary) }
            Spacer(Modifier.weight(1f))
            Text("Protocol Test", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (state.mockRunning) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentGreen, strokeWidth = 2.dp)
            } else {
                Text("Done", color = AccentGreen, fontSize = 14.sp)
            }
        }
        HorizontalDivider(color = DividerColor)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(state.mockLog) { entry -> MockLogRow(entry) }
        }

        HorizontalDivider(color = DividerColor)
        ChatSection(
            messages = state.chat,
            modifier = Modifier.heightIn(min = 120.dp, max = 220.dp),
            onSend = onSendChat
        )
    }
}

@Composable
private fun MockLogRow(entry: MockLogEntry) {
    val isSent     = entry.direction == LogDirection.Sent
    val dirColor   = if (isSent) AccentBlue else AccentGreen
    val dirSymbol  = if (isSent) "→" else "←"
    val statusText = if (entry.status == LogStatus.Ok) "✓" else "✗"
    val statusColor = if (entry.status == LogStatus.Ok) AccentGreen else AccentRed

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xBB152236), RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(dirSymbol, color = dirColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(entry.type, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.width(96.dp))
        Spacer(Modifier.width(6.dp))
        Text(entry.summary, color = TextSecondary, fontSize = 11.sp,
            modifier = Modifier.weight(1f), maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text(statusText, color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

// ── Shared button ─────────────────────────────────────────────────────────────

@Composable
private fun LobbyButton(text: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
