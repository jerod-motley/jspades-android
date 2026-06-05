package jmotley.com.jspades.networking

import android.util.Log
import jmotley.com.jspades.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.random.Random

private const val TAG = "WSSMP"

class OnlineSession(
    private val scope: CoroutineScope,
    private val socketUrl: String
) {
    // ── Public state ──────────────────────────────────────────────────────────

    private val _socketState = MutableStateFlow(SocketState.Disconnected)
    val socketState: StateFlow<SocketState> = _socketState

    private val _lobby = MutableStateFlow<OnlineLobbyState?>(null)
    val lobby: StateFlow<OnlineLobbyState?> = _lobby

    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat: StateFlow<List<ChatMessage>> = _chat

    private val _mockLog = MutableStateFlow<List<MockLogEntry>>(emptyList())
    val mockLog: StateFlow<List<MockLogEntry>> = _mockLog

    private val _mockRunning = MutableStateFlow(false)
    val mockRunning: StateFlow<Boolean> = _mockRunning

    /** Fires once on all devices (host + guests) when a real game is started. */
    private val _gameStartSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val gameStartSignal = _gameStartSignal

    /** Counts down 5→1→0 on all devices during the pre-game countdown. */
    private val _countdown = MutableStateFlow(0)
    val countdown: StateFlow<Int> = _countdown

    /** Final team bids broadcast by the host. Consumed once by PlayScreen on guest devices. */
    private val _pendingAllBids = MutableStateFlow<SpadesMPMessage.AllBids?>(null)
    val pendingAllBids: StateFlow<SpadesMPMessage.AllBids?> = _pendingAllBids

    /** True once the authoritative startGame payload has arrived for this hand. */
    private val _startGameReady = MutableStateFlow(false)
    val startGameReady: StateFlow<Boolean> = _startGameReady

    /** Individual player bids arriving from remote humans. Key = canonical player ID. */
    private val _pendingPlayerBids = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pendingPlayerBids: StateFlow<Map<String, Int>> = _pendingPlayerBids

    /**
     * Buffered deal from the server. Populated as soon as the deal message arrives
     * (which can be before the UI has navigated to the game screen). Consumed once
     * by the game screen; cleared on disconnect.
     */
    private val _pendingDeal = MutableStateFlow<SpadesMPMessage.Deal?>(null)
    val pendingDeal: StateFlow<SpadesMPMessage.Deal?> = _pendingDeal

    /** Card plays arriving from remote players during a live game. Key = canonical player ID. */
    private val _pendingPlayCard = MutableStateFlow<Map<String, jmotley.com.jspades.data.Card>>(emptyMap())
    val pendingPlayCard: StateFlow<Map<String, jmotley.com.jspades.data.Card>> = _pendingPlayCard

    /** True once startGame fires — routes Deal to the pending buffer instead of mock log. */
    private var realGameActive = false
    private var countdownStarted = false
    private var startGameReceived = false
    private var lastAppliedSnapshotSeq = 0

    // ── Internal ──────────────────────────────────────────────────────────────

    private lateinit var socket: GameSocketClient
    private val seenCmdIds = mutableSetOf<String>()

    // ── Connect / disconnect ──────────────────────────────────────────────────

    fun createRoom(personId: String, displayName: String) {
        val roomId = generateRoomCode()
        Log.i(TAG, "createRoom roomId=$roomId personId=${personId.takeLast(8)} name=$displayName")
        initSession(personId, displayName, roomId, isHost = true)
    }

    fun joinRoom(personId: String, displayName: String, roomCode: String) {
        Log.i(TAG, "joinRoom code=$roomCode personId=${personId.takeLast(8)} name=$displayName")
        initSession(personId, displayName, roomCode.uppercase().trim(), isHost = false)
    }

    private fun initSession(personId: String, displayName: String, roomId: String, isHost: Boolean) {
        val seatIndex = if (isHost) 0 else -1

        val initialSeats = if (isHost) {
            List(4) { i ->
                if (i == 0) OnlineSeat(0, personId, displayName, isHost = true, kind = SeatKind.Human)
                else OnlineSeat(i)
            }
        } else {
            List(4) { OnlineSeat(it) }
        }

        _lobby.value = OnlineLobbyState(
            roomId = roomId,
            inviteCode = roomId,
            hostPlayerId = if (isHost) personId else "",
            localPlayerId = personId,
            localDisplayName = displayName,
            isHost = isHost,
            seats = initialSeats
        )

        socket = GameSocketClient(
            url = socketUrl,
            scope = scope,
            onMessage = ::onRawMessage,
            onStateChange = { state ->
                _socketState.value = state
                Log.i(TAG, "socketState → $state")
                if (state == SocketState.Connected) {
                    Log.i(TAG, "sending joinRoom roomId=$roomId playerId=${personId.takeLast(8)}")
                    socket.send(buildJoinRoom(roomId, personId))
                }
            }
        )
        socket.connect()
    }

    fun disconnect() {
        Log.i(TAG, "session disconnect")
        if (::socket.isInitialized) socket.disconnect()
        _lobby.value = null
        _chat.value = emptyList()
        _mockLog.value = emptyList()
        _mockRunning.value = false
        _pendingDeal.value = null
        _pendingAllBids.value = null
        _pendingPlayerBids.value = emptyMap()
        _pendingPlayCard.value = emptyMap()
        _countdown.value = 0
        _startGameReady.value = false
        realGameActive = false
        countdownStarted = false
        startGameReceived = false
        lastAppliedSnapshotSeq = 0
        seenCmdIds.clear()
    }

    // ── Sending ───────────────────────────────────────────────────────────────

    fun sendChat(text: String) {
        val state = _lobby.value ?: return
        Log.d(TAG, "sendChat from=${state.localDisplayName} text=$text")
        socket.send(buildChat(state.localPlayerId, state.roomId, state.localDisplayName, text))
        addChat(state.localPlayerId, state.localDisplayName, text, isLocal = true)
    }

    // ── Host: send lobby snapshot ─────────────────────────────────────────────

    private fun sendLobbySnapshot() {
        val state = _lobby.value ?: return
        if (!state.isHost) return
        Log.d(TAG, "sendLobbySnapshot seats=${state.seats.map { "${it.seatIndex}:${it.kind}" }}")
        socket.send(buildLobbySnapshot(state.localPlayerId, state.roomId, state.seats))
    }

    // ── Incoming message dispatch ─────────────────────────────────────────────

    private fun onRawMessage(raw: String) {
        val msg = parseIncoming(raw)

        val cmdId = (msg as? SpadesMPMessage.PlayerBid)?.cmdId
            ?: (msg as? SpadesMPMessage.AllBids)?.cmdId
            ?: (msg as? SpadesMPMessage.StartCountdown)?.cmdId
            ?: (msg as? SpadesMPMessage.StartGame)?.cmdId
            ?: (msg as? SpadesMPMessage.Start)?.cmdId
            ?: (msg as? SpadesMPMessage.SnapShot)?.cmdId
            ?: (msg as? SpadesMPMessage.Deal)?.cmdId
            ?: (msg as? SpadesMPMessage.Bid)?.cmdId
            ?: (msg as? SpadesMPMessage.PlayCard)?.cmdId
            ?: (msg as? SpadesMPMessage.TrickResult)?.cmdId
            ?: (msg as? SpadesMPMessage.HandScore)?.cmdId
            ?: (msg as? SpadesMPMessage.GameFinished)?.cmdId
            ?: (msg as? SpadesMPMessage.Chat)?.cmdId

        if (cmdId != null) {
            if (seenCmdIds.contains(cmdId)) {
                Log.d(TAG, "DEDUP dropped cmdId=$cmdId type=${msg::class.simpleName}")
                return
            }
            seenCmdIds.add(cmdId)
        }

        Log.i(TAG, "DISPATCH ${msg::class.simpleName}")

        when (msg) {
            is SpadesMPMessage.RoomJoined   -> onRoomJoined(msg)
            is SpadesMPMessage.PlayerJoined -> onPlayerJoined(msg)
            is SpadesMPMessage.PlayerInfo   -> onPlayerInfo(msg)
            is SpadesMPMessage.SnapShot     -> onSnapShot(msg)
            is SpadesMPMessage.Chat         -> onChat(msg)
            is SpadesMPMessage.RoomFull     -> onRoomFull()
            is SpadesMPMessage.PlayerBid       -> onPlayerBid(msg)
            is SpadesMPMessage.AllBids         -> onAllBids(msg)
            is SpadesMPMessage.StartCountdown -> onStartCountdown(msg)
            is SpadesMPMessage.StartGame      -> onStartGame(msg)
            is SpadesMPMessage.Unknown        -> Log.w(TAG, "UNKNOWN message: ${msg.raw.take(200)}")

            // Game protocol messages
            is SpadesMPMessage.Deal     -> if (realGameActive || countdownStarted || startGameReceived) onDeal(msg) else logReceived(msg)
            is SpadesMPMessage.PlayCard -> if (realGameActive) onPlayCard(msg) else logReceived(msg)
            is SpadesMPMessage.Start,  // test-mode only; live path uses startGame + startCountdown
            is SpadesMPMessage.BlindOffer,
            is SpadesMPMessage.BlindResponse,
            is SpadesMPMessage.TrickResult,
            is SpadesMPMessage.HandScore,
            is SpadesMPMessage.GameFinished -> logReceived(msg)
            is SpadesMPMessage.Bid -> onLegacyBid(msg)

            else -> Unit
        }
    }

    private fun canonicalPlayerIdForSeat(roomSeatIndex: Int): String? {
        val state = _lobby.value ?: return null
        val localSeat = state.seats.find { it.playerId == state.localPlayerId }?.seatIndex ?: 0
        val offset = (roomSeatIndex - localSeat + 4) % 4
        return listOf("south", "west", "north", "east").getOrNull(offset)
    }

    private fun onRoomJoined(msg: SpadesMPMessage.RoomJoined) {
        val state = _lobby.value ?: return
        Log.i(TAG, "ROOM JOINED roomId=${msg.roomId} myPlayerId=${msg.myPlayerId.takeLast(8)}")
        if (!state.isHost) {
            Log.i(TAG, "ROOM JOINED → sending playerInfo displayName=${state.localDisplayName}")
            socket.send(buildPlayerInfo(state.localPlayerId, state.roomId, state.localDisplayName))
        }
    }

    private fun onPlayerJoined(msg: SpadesMPMessage.PlayerJoined) {
        val state = _lobby.value ?: return
        if (!state.isHost) return
        Log.i(TAG, "PLAYER JOINED playerId=${msg.playerId.takeLast(8)} seatIndex=${msg.seatIndex}")
        val updated = state.seats.map { seat ->
            if (seat.seatIndex == msg.seatIndex)
                seat.copy(playerId = msg.playerId, displayName = "Player ${msg.playerId.takeLast(4).uppercase()}", kind = SeatKind.Human)
            else seat
        }
        _lobby.value = state.copy(seats = updated)
        sendLobbySnapshot()
    }

    private fun onPlayerInfo(msg: SpadesMPMessage.PlayerInfo) {
        val state = _lobby.value ?: return
        if (!state.isHost) return
        Log.i(TAG, "PLAYER INFO personId=${msg.personId.takeLast(8)} displayName=${msg.displayName}")
        val updated = state.seats.map { seat ->
            if (seat.playerId == msg.personId) seat.copy(displayName = msg.displayName)
            else seat
        }
        if (updated == state.seats) {
            Log.w(TAG, "PLAYER INFO no seat matched personId=${msg.personId.takeLast(8)} — queued name will apply on join")
            return
        }
        _lobby.value = state.copy(seats = updated)
        sendLobbySnapshot()
    }

    private fun onSnapShot(msg: SpadesMPMessage.SnapShot) {
        val state = _lobby.value ?: return
        if (state.isHost) {
            Log.d(TAG, "SNAPSHOT received but we are host — ignored")
            return
        }
        if (msg.sequence > 0 && msg.sequence <= lastAppliedSnapshotSeq) {
            Log.d(TAG, "SNAPSHOT seq=${msg.sequence} skipped (already applied seq=$lastAppliedSnapshotSeq)")
            return
        }
        if (msg.sequence > 0) lastAppliedSnapshotSeq = msg.sequence

        val seats = (0..3).map { i ->
            val id = msg.payload["seat${i}Id"]?.takeIf { it.isNotBlank() }
            val name = msg.payload["seat${i}Name"]?.takeIf { it.isNotBlank() }
            val kindStr = msg.payload["seat${i}Kind"] ?: "open"
            val kind = when (kindStr) { "human" -> SeatKind.Human; "cpu" -> SeatKind.Cpu; else -> SeatKind.Open }
            OnlineSeat(i, id, name, isHost = i == 0, kind = kind)
        }
        // Guard: reject snapshots that would replace a known real name with a placeholder.
        // The real fix is iOS-side (I1). This is a defensive heuristic until that ships.
        for (incoming in seats) {
            val existing = state.seats.find { it.seatIndex == incoming.seatIndex }?.displayName
            val incomingName = incoming.displayName
            if (existing != null && existing != "Guest" && existing != "Open"
                && (incomingName == "Guest" || incomingName == "Open")) {
                Log.w(TAG, "SNAPSHOT seq=${msg.sequence} rejected — would downgrade seat ${incoming.seatIndex} '$existing' → '$incomingName'")
                return
            }
        }
        Log.i(TAG, "SNAPSHOT applied: ${seats.map { "${it.seatIndex}:${it.kind}:${it.displayName}" }}")

        _lobby.value = state.copy(
            seats = seats,
            hostPlayerId = seats.firstOrNull { it.isHost }?.playerId ?: state.hostPlayerId
        )
    }

    private fun onChat(msg: SpadesMPMessage.Chat) {
        val local = _lobby.value?.localPlayerId
        Log.d(TAG, "CHAT from=${msg.displayName}: ${msg.message}")
        addChat(msg.personId, msg.displayName, msg.message, isLocal = msg.personId == local)
    }

    private fun onRoomFull() {
        Log.w(TAG, "ROOM FULL")
        addChat("system", "System", "This room is full.", isLocal = false)
    }

    private fun onPlayerBid(msg: SpadesMPMessage.PlayerBid) {
        val canonicalId = canonicalPlayerIdForSeat(msg.seatIndex) ?: return
        if (_pendingPlayerBids.value.containsKey(canonicalId)) {
            Log.w(TAG, "DEDUP BID canonicalId=$canonicalId — already have bid, dropping duplicate")
            return
        }
        Log.i(TAG, "PLAYER BID: room_seat=${msg.seatIndex} → $canonicalId bid=${msg.bidAmount}")
        _pendingPlayerBids.value = _pendingPlayerBids.value + (canonicalId to msg.bidAmount)
    }

    private fun onLegacyBid(msg: SpadesMPMessage.Bid) {
        if (msg.seatIndex < 0) {
            Log.w(TAG, "LEGACY BID missing seatIndex — team=${msg.teamIndex} amount=${msg.bidAmount}")
            return
        }
        val canonicalId = canonicalPlayerIdForSeat(msg.seatIndex) ?: return
        if (_pendingPlayerBids.value.containsKey(canonicalId)) {
            Log.w(TAG, "DEDUP BID canonicalId=$canonicalId — already have bid, dropping duplicate")
            return
        }
        Log.i(TAG, "LEGACY BID: room_seat=${msg.seatIndex} → $canonicalId bid=${msg.bidAmount}")
        _pendingPlayerBids.value = _pendingPlayerBids.value + (canonicalId to msg.bidAmount)
    }

    fun consumePlayerBid(canonicalId: String) {
        _pendingPlayerBids.value = _pendingPlayerBids.value - canonicalId
    }

    /** Send the local player's individual bid (first on team) or team-total (second on team). */
    fun sendPlayerBid(bidAmount: Int) {
        val state = _lobby.value ?: return
        val localSeat = state.localSeatIndex.coerceAtLeast(0)
        Log.i(TAG, "SEND playerBid seat=$localSeat bid=$bidAmount")
        socket.send(buildPlayerBid(state.localPlayerId, state.roomId, localSeat, bidAmount))
    }

    private fun onAllBids(msg: SpadesMPMessage.AllBids) {
        Log.i(TAG, "ALL BIDS from=${msg.personId.takeLast(8)} t0=${msg.team0Bid} t1=${msg.team1Bid}")
        _pendingAllBids.value = msg
    }

    fun clearPendingAllBids() { _pendingAllBids.value = null }

    fun consumePendingDeal() { _pendingDeal.value = null }

    /** Host broadcasts final team bids to all clients. */
    fun sendAllBids(team0Bid: Int, team1Bid: Int) {
        val state = _lobby.value ?: return
        if (!state.isHost) return
        Log.i(TAG, "SEND allBids t0=$team0Bid t1=$team1Bid")
        socket.send(buildAllBids(state.localPlayerId, state.roomId, team0Bid, team1Bid))
    }

    private fun onStartCountdown(msg: SpadesMPMessage.StartCountdown) {
        if (_lobby.value?.isHost == true) return   // host drives its own countdown
        Log.i(TAG, "COUNTDOWN ${msg.seconds}s — guest starting")
        countdownStarted = true
        realGameActive = true
        Log.d(TAG, "COUNTDOWN coroutine launch seconds=${msg.seconds}")
        scope.launch { runCountdownThenNavigate(msg.seconds) }
    }

    private fun onStartGame(msg: SpadesMPMessage.StartGame) {
        Log.i(TAG, "START GAME from=${msg.personId.takeLast(8)}")
        startGameReceived = true
        _startGameReady.value = true
        realGameActive = true
        // Apply the authoritative final seat map from the payload.
        val state = _lobby.value
        if (state != null && msg.payload.isNotEmpty()) {
            val seats = (0..3).map { i ->
                val id      = msg.payload["seat${i}Id"]?.takeIf { it.isNotBlank() }
                val name    = msg.payload["seat${i}Name"]?.takeIf { it.isNotBlank() }
                val kindStr = msg.payload["seat${i}Kind"] ?: "open"
                val kind    = when (kindStr) { "human" -> SeatKind.Human; "cpu" -> SeatKind.Cpu; else -> SeatKind.Open }
                OnlineSeat(i, id, name, isHost = i == 0, kind = kind)
            }
            Log.i(TAG, "START GAME seats: ${seats.map { "${it.seatIndex}:${it.kind}:${it.displayName}" }}")
            _lobby.value = state.copy(seats = seats, status = LobbyStatus.Starting)
        }
        // If startCountdown hasn't arrived yet but the payload carries countdownSeconds, start now.
        // This makes Android robust to startGame arriving before startCountdown (Issue 2).
        if (!countdownStarted) {
            val seconds = msg.payload["countdownSeconds"]?.toIntOrNull()
            if (seconds != null) {
                Log.i(TAG, "START GAME — starting countdown from payload seconds=$seconds")
                countdownStarted = true
                scope.launch { runCountdownThenNavigate(seconds) }
            }
        }
        // Navigation is triggered by the countdown completing, not by startGame directly.
    }

    private fun onDeal(msg: SpadesMPMessage.Deal) {
        Log.i(TAG, "REAL GAME DEAL handNum=${msg.handNum} dealer=${msg.dealerSeat} — buffering")
        _pendingDeal.value = msg
        Log.d(TAG, "REAL GAME DEAL buffered seats=${msg.hands.mapValues { it.value.size }}")
    }

    private fun onPlayCard(msg: SpadesMPMessage.PlayCard) {
        val lobby = _lobby.value ?: return
        // Skip echoes of our own plays
        if (msg.personId == lobby.localPlayerId) return
        val card = jmotley.com.jspades.data.cardFromToken(msg.card) ?: run {
            Log.w(TAG, "onPlayCard: unrecognised card token '${msg.card}'")
            return
        }
        // Convert room seat index to local canonical player ID
        val localSeat = lobby.localSeatIndex.coerceAtLeast(0)
        val senderSeat = lobby.seats.find { it.playerId == msg.personId }?.seatIndex
            ?: msg.seatIndex   // fall back to payload seatIndex if personId not found
        val offset = (senderSeat - localSeat + 4) % 4
        val canonicalId = listOf("south", "west", "north", "east")[offset]
        Log.i(TAG, "PLAY CARD seat=$senderSeat → $canonicalId card=${msg.card}")
        _pendingPlayCard.value = _pendingPlayCard.value + (canonicalId to card)
    }

    fun consumePlayCard(canonicalId: String) {
        _pendingPlayCard.value = _pendingPlayCard.value - canonicalId
    }

    fun sendPlayCard(token: String, roomSeatIndex: Int, trickNum: Int, leadSeat: Int) {
        val lobby = _lobby.value ?: return
        val msg = buildPlayCard(
            personId   = lobby.localPlayerId,
            roomId     = lobby.roomId,
            handNum    = 1,   // placeholder; both devices advance together
            trickNum   = trickNum,
            leadSeat   = leadSeat,
            seatIndex  = roomSeatIndex,
            card       = token
        )
        Log.d(TAG, "SEND playCard seat=$roomSeatIndex card=$token trickNum=$trickNum leadSeat=$leadSeat")
        socket.send(msg)
    }

    /** Ticks the shared countdown display and fires gameStartSignal when it reaches 0. */
    private suspend fun runCountdownThenNavigate(seconds: Int) {
        for (i in seconds downTo 1) {
            Log.d(TAG, "COUNTDOWN tick=$i")
            _countdown.value = i
            delay(1000L)
        }
        _countdown.value = 0
        Log.i(TAG, "COUNTDOWN complete — emitting gameStartSignal")
        _gameStartSignal.emit(Unit)
    }

    /** Host swaps the players assigned to two room seat indices, then broadcasts the new snapshot. */
    fun swapSeats(seatA: Int, seatB: Int) {
        val state = _lobby.value ?: return
        if (!state.isHost || seatA == seatB) return
        val a = state.seats.find { it.seatIndex == seatA } ?: return
        val b = state.seats.find { it.seatIndex == seatB } ?: return
        val newSeats = state.seats.map { s ->
            when (s.seatIndex) {
                seatA -> s.copy(playerId = b.playerId, displayName = b.displayName, kind = b.kind)
                seatB -> s.copy(playerId = a.playerId, displayName = a.displayName, kind = a.kind)
                else  -> s
            }
        }
        Log.i(TAG, "SWAP seats $seatA ↔ $seatB")
        _lobby.value = state.copy(seats = newSeats)
        sendLobbySnapshot()
    }

    /**
     * Host: broadcasts startCountdown, then immediately sends startGame + deal so every client
     * buffers the deal while the countdown runs. When the timer expires, all devices navigate
     * together with the deal already waiting in their buffer — no race condition.
     */
    fun startRealGame() {
        val state = _lobby.value ?: return
        if (!state.isHost) {
            Log.w(TAG, "startRealGame called on non-host — ignored")
            return
        }
        Log.i(TAG, "========== START REAL GAME ==========")
        realGameActive = true
        scope.launch {
            val pid  = state.localPlayerId
            val room = state.roomId
            val finalSeats = state.seats.map { seat ->
                if (seat.kind == SeatKind.Open) {
                    seat.copy(
                        playerId = "cpu-${seat.seatIndex}",
                        displayName = "CPU",
                        kind = SeatKind.Cpu
                    )
                } else {
                    seat
                }
            }

            _lobby.value = state.copy(seats = finalSeats, status = LobbyStatus.Starting)
            countdownStarted = true
            startGameReceived = true
            _startGameReady.value = true

            // 1. Broadcast countdown — all clients start their timers simultaneously.
            socket.send(buildStartCountdown(pid, room, 5))

            // 2. Send startGame, then wait 100 ms before deal.
            //    AWS API Gateway does not guarantee ordering of frames sent near-simultaneously
            //    from different write threads, so deal can arrive before startGame if sent back-
            //    to-back. The gap ensures startGame is fully relayed before deal is queued.
            socket.send(buildStartGame(pid, room, finalSeats))
            delay(100L)

            val deck  = buildHouseRulesDeck()
            val hands = (0..3).associateWith { seat -> deck.subList(seat * 13, seat * 13 + 13) }

            // Compute the first lead: first non-open seat clockwise from the dealer (seat 0).
            // Hardcoding 1 is wrong when seat 1 is empty — it tells remote clients to wait
            // for a player who doesn't exist.
            val dealerSeat = 0
            val firstLead  = (1..3).map { (dealerSeat + it) % 4 }
                .firstOrNull { idx -> finalSeats.find { s -> s.seatIndex == idx }?.kind != SeatKind.Open }
                ?: (dealerSeat + 1) % 4
            Log.i(TAG, "DEALING hands=${hands.map { (s, h) -> "seat$s:${h.size}cards" }} firstLead=$firstLead")
            val wireHands = hands.mapValues { (_, tokens) -> tokens.map { androidToWireCard(it) } }
            socket.send(buildDeal(pid, room, 1, dealerSeat, firstLead, wireHands))

            // Buffer locally in Android format — host's PlayScreen reads via cardFromToken().
            _pendingDeal.value = SpadesMPMessage.Deal(
                personId = pid, roomId = room, cmdId = "",
                handNum = 1, dealerSeat = dealerSeat, firstLead = firstLead, hands = hands
            )

            // 3. Run the same 5-second countdown the clients are showing, then navigate.
            runCountdownThenNavigate(5)
        }
    }

    private fun addChat(senderId: String, name: String, text: String, isLocal: Boolean) {
        _chat.value = _chat.value + ChatMessage(senderId, name, text, isLocal)
    }

    // ── Mock protocol ─────────────────────────────────────────────────────────

    fun startMockProtocol() {
        val state = _lobby.value ?: return
        if (!state.isHost) {
            Log.w(TAG, "startMockProtocol called on non-host — ignored")
            return
        }
        if (_mockRunning.value) return
        Log.i(TAG, "========== MOCK PROTOCOL START ==========")
        _mockRunning.value = true
        _mockLog.value = emptyList()
        scope.launch { runMockSequence(state) }
    }

    private suspend fun runMockSequence(state: OnlineLobbyState) {
        val pid = state.localPlayerId
        val room = state.roomId
        val step = 600L

        sendAndLog("start", "countdown=3") { buildStart(pid, room) }
        delay(step)

        val deck = buildHouseRulesDeck().shuffled()
        val hands = (0..3).associate { seat -> seat to deck.subList(seat * 13, seat * 13 + 13) }
        sendAndLog("deal", "52 cards, dealer=0 firstLead=1") { buildDeal(pid, room, 1, 0, 1, hands) }
        delay(step)

        val bid0 = Random.nextInt(4, 10)
        val bid1 = Random.nextInt(4, 10)
        sendAndLog("bid", "team=0 amount=$bid0") { buildBid(pid, room, 1, 0, bid0) }
        delay(step)
        sendAndLog("bid", "team=1 amount=$bid1") { buildBid(pid, room, 1, 1, bid1) }
        delay(step)

        var lead = 1
        val trickCards = Array(4) { ArrayDeque(hands[it]!!) }
        val trickTally = intArrayOf(0, 0)

        repeat(3) { trickIdx ->
            val trickNum = trickIdx + 1
            Log.i(TAG, "--- trick $trickNum lead=seat$lead ---")
            val played = mutableMapOf<Int, String>()
            for (offset in 0..3) {
                val seat = (lead + offset) % 4
                val card = trickCards[seat].removeFirst()
                played[seat] = card
                sendAndLog("playCard", "seat=$seat card=$card trick=$trickNum") {
                    buildPlayCard(pid, room, 1, trickNum, lead, seat, card)
                }
                delay(step)
            }
            val winner = determineWinner(played, lead)
            val winTeam = winner % 2
            trickTally[winTeam]++
            Log.i(TAG, "trick $trickNum winner=seat$winner team$winTeam tally=${trickTally.toList()}")
            sendAndLog("trickResult", "trick=$trickNum winner=seat$winner") {
                buildTrickResult(pid, room, 1, trickNum, lead, winner, played)
            }
            lead = winner
            delay(step)
        }

        val total0 = if (trickTally[0] >= bid0) bid0 * 10 + (trickTally[0] - bid0) else -(bid0 * 10)
        val total1 = if (trickTally[1] >= bid1) bid1 * 10 + (trickTally[1] - bid1) else -(bid1 * 10)
        sendAndLog("handScore", "t0=$total0 t1=$total1 bags0=${(trickTally[0] - bid0).coerceAtLeast(0)} bags1=${(trickTally[1] - bid1).coerceAtLeast(0)}") {
            buildHandScore(pid, room, 1,
                bid0, trickTally[0], total0, (trickTally[0] - bid0).coerceAtLeast(0),
                bid1, trickTally[1], total1, (trickTally[1] - bid1).coerceAtLeast(0),
                false)
        }
        delay(step)

        val winner = if (total0 >= total1) 0 else 1
        sendAndLog("gameFinished", "winner=team$winner score0=$total0 score1=$total1") {
            buildGameFinished(pid, room, 1, winner, total0, total1)
        }

        Log.i(TAG, "========== MOCK PROTOCOL COMPLETE ==========")
        _mockRunning.value = false
    }

    private fun sendAndLog(type: String, summary: String, builder: () -> String) {
        val json = builder()
        Log.i(TAG, "MOCK SEND → $type | $summary")
        socket.send(json)
        addLog(LogDirection.Sent, type, summary)
    }

    private fun logReceived(msg: SpadesMPMessage) {
        val (type, summary) = when (msg) {
            is SpadesMPMessage.Start        -> "start"       to "cmdId=${msg.cmdId.take(8)}"
            is SpadesMPMessage.Deal         -> "deal"        to "handNum=${msg.handNum} dealer=${msg.dealerSeat}"
            is SpadesMPMessage.Bid          -> "bid"         to "team=${msg.teamIndex} amount=${msg.bidAmount}"
            is SpadesMPMessage.BlindOffer   -> "blindOffer"  to "handNum=${msg.handNum} eligible=${msg.eligibleSeats}"
            is SpadesMPMessage.BlindResponse-> "blindResponse" to "seat=${msg.seatIndex} accepted=${msg.accepted}"
            is SpadesMPMessage.PlayCard     -> "playCard"    to "seat=${msg.seatIndex} card=${msg.card} trick=${msg.trickNum}"
            is SpadesMPMessage.TrickResult  -> "trickResult" to "trick=${msg.trickNum} winner=${msg.winnerSeat}"
            is SpadesMPMessage.HandScore    -> "handScore"   to "t0=${msg.team0Score} t1=${msg.team1Score}"
            is SpadesMPMessage.GameFinished -> "gameFinished" to "winner=${msg.winnerTeam} hand=${msg.handNum}"
            else -> "?" to ""
        }
        Log.i(TAG, "MOCK RECV ← $type | $summary")
        addLog(LogDirection.Received, type, summary)
    }

    private fun addLog(direction: LogDirection, type: String, summary: String) {
        _mockLog.value = _mockLog.value + MockLogEntry(direction, type, summary)
    }

    // ── Trick winner (simplified) ─────────────────────────────────────────────

    private fun determineWinner(played: Map<Int, String>, leadSeat: Int): Int {
        val leadCard = played[leadSeat] ?: return leadSeat
        val leadSuitStr = leadCard.substringAfter("_")

        var bestSeat = leadSeat
        var bestRank = -1
        var bestIsTrump = false

        played.forEach { (seat, card) ->
            val parts = card.split("_")
            val rank = parts[0].toIntOrNull() ?: 0
            val suit = parts[1]
            val isTrump = suit == "3" || rank >= 14
            val isLedSuit = suit == leadSuitStr

            val beats = when {
                isTrump && bestIsTrump  -> rank > bestRank
                isTrump && !bestIsTrump -> true
                !isTrump && bestIsTrump -> false
                isLedSuit               -> rank > bestRank
                else                    -> false
            }

            if (beats) {
                bestSeat = seat
                bestRank = rank
                bestIsTrump = isTrump
            }
        }
        return bestSeat
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }
}
