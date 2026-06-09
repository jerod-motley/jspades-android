package jmotley.com.jspades.models

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import jmotley.com.jspades.data.*
import jmotley.com.jspades.data.ClientDisplayState
/*import jmotley.com.jspades.data.AchievementsRepo
import jmotley.com.jspades.data.AnimationEvent
import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.GamePhase
import jmotley.com.jspades.data.GameType
import jmotley.com.jspades.data.Hand
import jmotley.com.jspades.data.Player
import jmotley.com.jspades.data.PlayerHandState
import jmotley.com.jspades.data.Play
import jmotley.com.jspades.data.Trick
import jmotley.com.jspades.data.RuntimeFlags
import jmotley.com.jspades.data.Score
import jmotley.com.jspades.data.defaultPlayers
import jmotley.com.jspades.data.defaultFourPlayers
import jmotley.com.jspades.data.localHand
import jmotley.com.jspades.data.HandReplay
import jmotley.com.jspades.data.GameLength
import jmotley.com.jspades.data.ReplayEvent
import jmotley.com.jspades.data.AppConfig*/
import jmotley.com.jspades.engine.PhaseManager
import jmotley.com.jspades.logging.PlayLogger
import jmotley.com.jspades.networking.GameObj
import jmotley.com.jspades.networking.MpHand
import jmotley.com.jspades.networking.MpHandDeal
import jmotley.com.jspades.networking.MpSeatInfo
import jmotley.com.jspades.networking.MpTrick
import android.util.Log

internal fun computeInitialLeaderIndex(playerCount: Int, localSeatIndex: Int, isMultiplayer: Boolean): Int {
	if (!isMultiplayer || localSeatIndex < 0) return 1
	return ((1 - localSeatIndex) % playerCount + playerCount) % playerCount
}

class GameViewModel(application: Application) : AndroidViewModel(application) {
	private val context: Context get() = getApplication<Application>().applicationContext

	// If an MP game config is waiting (set by OnlineLobbyViewModel before navigation),
	// mark isMultiplayer on the initial state so the very first composition is correct
	// and the rewarded-ad icon never renders even for a single frame.
	private val _state = MutableStateFlow(
		if (MPStateHolder.peek() != null) GameState(isMultiplayer = true) else GameState()
	)
	val state: StateFlow<GameState> = _state

	/**
	 * One-shot animation events emitted by [PhaseManager] after each CPU action.
	 * UI collects these, plays the animation, then calls [phaseManager].execute().
	 * Buffer of 8 ensures no events are dropped between coroutine scheduling gaps.
	 */
	private val _animationEvents = MutableSharedFlow<AnimationEvent>(extraBufferCapacity = 8)
	val animationEvents: SharedFlow<AnimationEvent> = _animationEvents

	/** Emit an animation event from the engine. Called only by [PhaseManager]. */
	suspend fun emitAnimation(event: AnimationEvent) {
		_animationEvents.emit(event)
	}

	/**
	 * One-shot challenge evaluation results emitted by [PhaseManager].
	 * PlayScreen collects these to show overlays and finalize challenge state.
	 */
	private val _challengeResults = MutableSharedFlow<jmotley.com.jspades.engine.ChallengeResult>(extraBufferCapacity = 4)
	val challengeResults: SharedFlow<jmotley.com.jspades.engine.ChallengeResult> = _challengeResults

	/** Emit a challenge result from the engine. Called only by [PhaseManager]. */
	suspend fun emitChallengeResult(result: jmotley.com.jspades.engine.ChallengeResult) {
		_challengeResults.emit(result)
	}

	// --- Video events ---
	private val _frustratedVideo = MutableSharedFlow<String>(extraBufferCapacity = 1)
	val frustratedVideo: SharedFlow<String> = _frustratedVideo

	private val _cardheadEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
	val cardheadEvent: SharedFlow<String> = _cardheadEvent

	private val _bostonVideo = MutableSharedFlow<String>(extraBufferCapacity = 1)
	val bostonVideo: SharedFlow<String> = _bostonVideo

	private val _currentVideoAsset = MutableStateFlow<String?>(null)
	val currentVideoAsset: StateFlow<String?> = _currentVideoAsset.asStateFlow()

	fun setCurrentVideoAsset(asset: String?) { _currentVideoAsset.value = asset }

	suspend fun emitFrustratedVideo(asset: String) { _frustratedVideo.emit(asset) }
	suspend fun emitCardheadEvent(asset: String) { _cardheadEvent.emit(asset) }
	suspend fun emitBostonVideo(asset: String) { _bostonVideo.emit(asset) }

	/** Guards against the same frustrated video firing more than once per hand. */
	var frustratedVideoFiredThisHand = false

	/** Single engine entry point. All phase transitions funnel through here. */
	val phaseManager = PhaseManager(this, viewModelScope, context)

	// ── Lobby ─────────────────────────────────────────────────────────────────

	/** True for WebSocket multiplayer games. Readable directly from [state].isMultiplayer. */
	val isMultiplayer: Boolean get() = _state.value.isMultiplayer

	/** Room seat index of the local player in a multiplayer game. -1 = not set. */
	var mpLocalSeatIndex: Int = -1

	/** Current hand number for MP play card messages. Set from the wire deal payload. */
	var mpHandNum: Int = 1

	// ── MP remote-human input channels (host only) ────────────────────────────

	/** Room seat indices of remote human players (populated at lobby complete, host only). */
	var remoteHumanSeats: Set<Int> = emptySet()

	/** Buffered channel receiving bids from remote human players. */
	private val remoteBidChannel = Channel<Triple<Int, Int, Boolean>>(Channel.BUFFERED)

	/** Buffered channel receiving card plays from remote human players. */
	private val remotePlayChannel = Channel<Pair<Int, String>>(Channel.BUFFERED)

	/** Returns true when [canonicalId] (e.g. "west") maps to a remote human seat. */
	fun isRemoteHumanCanonicalId(canonicalId: String): Boolean {
		val seat = listOf("south", "west", "north", "east").indexOf(canonicalId)
		return seat >= 0 && seat in remoteHumanSeats
	}

	/** Called by OnlineSession callback when a remote player submits a bid. */
	fun notifyRemoteBid(seatIndex: Int, bidAmount: Int) {
		remoteBidChannel.trySend(Triple(seatIndex, bidAmount, false))
	}

	/** Called by OnlineSession callback when a remote player plays a card. */
	fun notifyRemotePlay(seatIndex: Int, card: String) {
		remotePlayChannel.trySend(Pair(seatIndex, card))
	}

	/** Suspends until a bid for [roomSeat] arrives. Discards messages for other seats. */
	suspend fun awaitRemoteHumanBid(roomSeat: Int): Pair<Int, Boolean> {
		while (true) {
			val (seat, bid, isBlind) = remoteBidChannel.receive()
			if (seat == roomSeat) return Pair(bid, isBlind)
			Log.w("WSSMP", "awaitRemoteHumanBid: got seat=$seat, want $roomSeat — discarded")
		}
	}

	/** Suspends until a card play for [roomSeat] arrives. Discards messages for other seats. */
	suspend fun awaitRemoteHumanPlay(roomSeat: Int): String {
		while (true) {
			val (seat, card) = remotePlayChannel.receive()
			if (seat == roomSeat) return card
			Log.w("WSSMP", "awaitRemoteHumanPlay: got seat=$seat, want $roomSeat — discarded")
		}
	}

	/** Drain stale remote inputs between hands to avoid carry-over. */
	fun clearRemoteInputChannels() {
		while (remoteBidChannel.tryReceive().isSuccess) {}
		while (remotePlayChannel.tryReceive().isSuccess) {}
	}

	// ── MP game object tracking (host only) ───────────────────────────────────

	/** Completed tricks for the current hand, in order. Cleared when a new hand seals. */
	val mpTrickHistory: MutableList<MpTrick> = mutableListOf()

	/** Completed hands sealed at score time. Append-only for the lifetime of the game. */
	val mpHandHistory: MutableList<MpHand> = mutableListOf()

	// ── MP client display state (guest only) ──────────────────────────────────

	private val _clientState = MutableStateFlow(ClientDisplayState())
	val clientState: StateFlow<ClientDisplayState> = _clientState.asStateFlow()

	/**
	 * Called by LobbyView once all seats are filled.
	 * Registers players and hands control to PhaseManager to deal and advance.
	 * [gameType] drives deck construction, player count, and team assignment.
	 * [dealtHands] — when provided (MP games), skips local shuffle and uses these cards.
	 * [mpFirstLeadIndex] — canonical trick-lead index from the deal (-1 = engine default).
	 */
	fun onLobbyComplete(ids: List<String>, names: List<String>, gameType: GameType,
	                    isMultiplayer: Boolean = false,
	                    localSeatIndex: Int = -1,
	                    handNum: Int = 1,
	                    remoteHumanSeats: Set<Int> = emptySet()) {
		require(ids.size == gameType.playerCount && names.size == gameType.playerCount)
		val players = defaultPlayers(ids, names, gameType)
		val initialLeaderIndex = computeInitialLeaderIndex(
			playerCount = gameType.playerCount,
			localSeatIndex = localSeatIndex,
			isMultiplayer = isMultiplayer
		)
		val prefs = context.getSharedPreferences("jspades_prefs", Context.MODE_PRIVATE)
		val twoOfSpadesJoker      = prefs.getBoolean("two_of_spades_joker", false)
		val twoOfDiamondsJoker    = prefs.getBoolean("two_of_diamonds_joker", false)
		val spadesMustBreak       = prefs.getBoolean("spades_must_break", false)
		val minBidFive            = prefs.getBoolean("min_bid_five", false)
		val enableSandbagPenalty  = prefs.getBoolean("count_overs", true)
		val allowNilBid = when {
			gameType == GameType.TEAM_CLASSIC                                           -> true
			gameType == GameType.HOUSE_RULES || gameType == GameType.TEAM_KITTY         -> false
			else  /* solo variants */                                                   -> prefs.getBoolean("allow_nil_bid", false)
		}
		val allowBlindExchange = when {
			gameType == GameType.TEAM_CLASSIC -> prefs.getBoolean("blind_nil_exchange", false)
			else -> false
		}
		val gameLength = if (AppConfig.TEST_MODE) GameLength.TEST
		                 else GameLength.valueOf(prefs.getString("game_length", GameLength.MEDIUM.name) ?: GameLength.MEDIUM.name)
		// In local games south is the initial dealer so west (index 1) bids first.
		// In MP guest games the canonical seats are rotated so the first bidder must rotate too.
		_state.value = _state.value.copy(
			players          = players,
			phase            = GamePhase.Deal,
			gameType         = gameType,
			leaderIndex      = initialLeaderIndex,
			handLeaderIndex  = initialLeaderIndex,
			currentTrick     = Trick(plays = List(gameType.playerCount) { null }),
			twoOfSpadesJoker     = twoOfSpadesJoker,
			twoOfDiamondsJoker   = twoOfDiamondsJoker,
			spadesMustBreak      = spadesMustBreak,
			minBidFive           = minBidFive,
			enableSandbagPenalty = enableSandbagPenalty,
			allowNilBid          = allowNilBid,
			allowBlindExchange   = allowBlindExchange,
			gameLength           = gameLength
		)
		if (isMultiplayer) {
			_state.value = _state.value.copy(isMultiplayer = true)
			if (localSeatIndex >= 0) this.mpLocalSeatIndex = localSeatIndex
			this.mpHandNum = handNum
			if (MPSession.isHost && remoteHumanSeats.isNotEmpty()) {
				this.remoteHumanSeats = remoteHumanSeats
				MPSession.session?.onRemotePlayerBid = { seat, bid -> notifyRemoteBid(seat, bid) }
				MPSession.session?.onRemotePlayCard  = { seat, card -> notifyRemotePlay(seat, card) }
			}
		}
		phaseManager.execute()
	}

	// ── MP game object building and sending (host only) ───────────────────────

	private val mpCanonicalIds = listOf("south", "west", "north", "east")

	/** Record a completed trick into mpTrickHistory. No-op for non-host or solo games. */
	fun recordMpCompletedTrick(trickNum: Int, leaderSeat: Int, plays: List<String>, winnerSeat: Int) {
		if (!isMultiplayer || !MPSession.isHost) return
		mpTrickHistory.add(MpTrick(trickNum, leaderSeat, plays, winnerSeat))
	}

	/** Seal the current hand into mpHandHistory and clear trick history. Called at score time. */
	fun sealMpHand() {
		if (!isMultiplayer || !MPSession.isHost) return
		val s = _state.value
		val currentHand = s.phaseHands[GamePhase.Deal]?.lastOrNull() ?: return
		val dealData = MpHandDeal(
			seat0 = s.originalDealHands["south"]?.map { it.toToken() } ?: emptyList(),
			seat1 = s.originalDealHands["west"]?.map { it.toToken() } ?: emptyList(),
			seat2 = s.originalDealHands["north"]?.map { it.toToken() } ?: emptyList(),
			seat3 = s.originalDealHands["east"]?.map { it.toToken() } ?: emptyList()
		)
		val seatBids = mpCanonicalIds.map { id -> currentHand.perPlayer[id]?.bid ?: 0 }
		val team0Tricks = s.players.filter { it.team == 0 }.sumOf { p -> currentHand.perPlayer[p.id]?.tricksWon ?: 0 }
		val team1Tricks = s.players.filter { it.team == 1 }.sumOf { p -> currentHand.perPlayer[p.id]?.tricksWon ?: 0 }
		mpHandHistory.add(MpHand(
			handNum      = mpHandNum - 1,
			seatBids     = seatBids,
			team0Bid     = currentHand.teamBids.getOrNull(0) ?: 0,
			team1Bid     = currentHand.teamBids.getOrNull(1) ?: 0,
			team0Tricks  = team0Tricks,
			team1Tricks  = team1Tricks,
			deal         = dealData,
			tricks       = mpTrickHistory.toList(),
			seatDidBid   = List(4) { true }  // all seats have bid by end of hand
		))
		mpTrickHistory.clear()
	}

	/** Build a full GameObj snapshot from current engine state. Host only. */
	fun buildMpGameObj(phase: String, waitingSeat: Int): GameObj {
		val s = _state.value
		val mpSeats = s.players.mapIndexed { idx, player ->
			MpSeatInfo(idx, player.id, player.displayName, player.team,
				isHuman = idx == mpLocalSeatIndex || idx in remoteHumanSeats)
		}
		val currentHand = s.phaseHands[GamePhase.Deal]?.lastOrNull()
		val dealData = MpHandDeal(
			seat0 = s.originalDealHands["south"]?.map { it.toToken() } ?: emptyList(),
			seat1 = s.originalDealHands["west"]?.map { it.toToken() } ?: emptyList(),
			seat2 = s.originalDealHands["north"]?.map { it.toToken() } ?: emptyList(),
			seat3 = s.originalDealHands["east"]?.map { it.toToken() } ?: emptyList()
		)
		val seatBids    = mpCanonicalIds.map { id -> currentHand?.perPlayer?.get(id)?.bid ?: 0 }
		val seatDidBid  = mpCanonicalIds.map { id -> s.players.find { it.id == id }?.runtimeFlags?.didBid ?: false }
		val team0Tricks = s.players.filter { it.team == 0 }.sumOf { p -> currentHand?.perPlayer?.get(p.id)?.tricksWon ?: 0 }
		val team1Tricks = s.players.filter { it.team == 1 }.sumOf { p -> currentHand?.perPlayer?.get(p.id)?.tricksWon ?: 0 }

		// Build in-progress trick if any plays have been made this trick
		val inProgressTrick = s.currentTrick.plays.let { trickPlays ->
			if (trickPlays.any { it != null }) {
				val byRoomSeat = MutableList(4) { "" }
				trickPlays.forEach { play ->
					if (play != null) {
						val seat = mpCanonicalIds.indexOf(play.playerId)
						if (seat >= 0) byRoomSeat[seat] = play.card.toToken()
					}
				}
				MpTrick(mpTrickHistory.size, s.leaderIndex, byRoomSeat, -1)
			} else null
		}

		val allTricks = mpTrickHistory + listOfNotNull(inProgressTrick)
		val currentMpHand = MpHand(
			handNum     = mpHandNum - 1,
			seatBids    = seatBids,
			team0Bid    = currentHand?.teamBids?.getOrNull(0) ?: 0,
			team1Bid    = currentHand?.teamBids?.getOrNull(1) ?: 0,
			team0Tricks = team0Tricks,
			team1Tricks = team1Tricks,
			deal        = dealData,
			tricks      = allTricks,
			seatDidBid  = seatDidBid
		)

		return GameObj(
			phase       = phase,
			waitingSeat = waitingSeat,
			gameOver    = s.phase == GamePhase.Finished,
			team0Score  = s.score.points["0"] ?: 0,
			team1Score  = s.score.points["1"] ?: 0,
			team0Bags   = s.score.bags["0"] ?: 0,
			team1Bags   = s.score.bags["1"] ?: 0,
			seats       = mpSeats,
			hands       = mpHandHistory + listOf(currentMpHand)
		)
	}

	/** Serialize and broadcast the current game object to all clients. Host only, no-op otherwise. */
	fun sendMpGameState(phase: String, waitingSeat: Int) {
		if (!isMultiplayer || !MPSession.isHost) return
		val session = MPSession.session ?: return
		val obj = buildMpGameObj(phase, waitingSeat)
		session.sendGameState(obj)
	}

	// ── MP client state (guest only) ──────────────────────────────────────────

	/** Apply an incoming GameObj from the host and update clientState. Guest only. */
	fun applyGameState(obj: GameObj, myRoomSeat: Int) {
		// Sanitize: void any play slot whose token doesn't parse to a valid card.
		val sanitized = run {
			val trick = obj.currentTrick ?: return@run obj
			val cleanPlays = trick.plays.map { token ->
				if (token.isBlank() || cardFromToken(token) != null) token
				else {
					Log.w("WSSMP", "applyGameState: invalid play token='$token' — voided")
					""
				}
			}
			if (cleanPlays == trick.plays) return@run obj
			val hand = obj.currentHand ?: return@run obj
			val updatedTricks = hand.tricks.dropLast(1) + trick.copy(plays = cleanPlays)
			val updatedHands  = obj.hands.dropLast(1) + hand.copy(tricks = updatedTricks)
			obj.copy(hands = updatedHands)
		}
		val myHand = sanitized.currentHand?.deal?.forSeat(myRoomSeat) ?: emptyList()
		val waitingName = sanitized.seats.find { it.seatIndex == sanitized.waitingSeat }?.playerName ?: ""
		_clientState.value = ClientDisplayState(
			gameObj         = sanitized,
			myRoomSeat      = myRoomSeat,
			isMyTurn        = sanitized.waitingSeat == myRoomSeat,
			myHand          = myHand,
			waitingSeatName = waitingName
		)
	}

	// ── State mutations used by PhaseManager ──────────────────────────────────

	/** Advance to a new phase. */
	fun advancePhase(phase: GamePhase) {
		_state.value = _state.value.copy(phase = phase)
	}

	/** Override the current leader index (used by MP to apply firstLead from the deal). */
	fun setLeaderIndex(index: Int) {
		_state.value = _state.value.copy(leaderIndex = index, handLeaderIndex = index)
	}

	/**
	 * Store dealt cards into phaseHands[Deal].
	 * Called by PhaseManager.handleDeal() after shuffling.
	 */
	fun applyDeal(hand: Hand) {
		val phaseHands = _state.value.phaseHands.toMutableMap()
		phaseHands[GamePhase.Deal] = listOf(hand)
		val originalDealHands = hand.perPlayer.mapValues { (_, phs) -> phs.hand }
		_state.value = _state.value.copy(phaseHands = phaseHands, originalDealHands = originalDealHands)
		recordReplayEvent(ReplayEvent.Deal(
			hand.perPlayer.mapValues { (_, phs) -> phs.hand.map { c -> c.uid } }
		))
	}

	/** Store the kitty hand. Called by PhaseManager for [GameType.TEAM_KITTY]. */
	fun applyKitty(kitty: Hand) {
		_state.value = _state.value.copy(kitty = kitty)
	}

	/**
	 * Record a bid for [playerId] and mark their didBid flag.
	 * Called by PhaseManager during Bid phase (CPU) or by BidView (human).
	 * [isBlind] is true only for a blind-nil (Four Man Classic CPU logic).
	 */
	fun submitBid(playerId: String, bid: Int, isBlind: Boolean = false) {
		val current = _state.value
		val phaseHands = current.phaseHands.toMutableMap()
		val dealHands  = phaseHands[GamePhase.Deal]?.toMutableList() ?: return
		val hand       = dealHands.lastOrNull() ?: return
		val perPlayer  = hand.perPlayer.toMutableMap()
		perPlayer[playerId] = (perPlayer[playerId] ?: PlayerHandState()).copy(bid = bid, isBlind = isBlind)
		dealHands[dealHands.lastIndex] = hand.copy(perPlayer = perPlayer)
		phaseHands[GamePhase.Deal] = dealHands

		// Mark didBid on the Player
		val players = current.players.map { p ->
			if (p.id == playerId) p.copy(runtimeFlags = p.runtimeFlags.copy(didBid = true)) else p
		}
		_state.value = current.copy(players = players, phaseHands = phaseHands)
		recordReplayEvent(ReplayEvent.Bid(playerId, bid, isBlind))

		// Log the bid for debugging
		PlayLogger.logBid(playerId, bid, isBlind)
	}

	/**
	 * Store a team-level bid in [Hand.teamBids].
	 * Called by PhaseManager for CPU-only teams and by [submitHumanTeamBid] for House Rules.
	 */
	fun setTeamBid(teamId: Int, bid: Int) {
		val current = _state.value
		val phaseHands = current.phaseHands.toMutableMap()
		val dealHands  = phaseHands[GamePhase.Deal]?.toMutableList() ?: return
		val hand       = dealHands.lastOrNull() ?: return
		val teamBids   = hand.teamBids.toMutableList()
		if (teamId in teamBids.indices) teamBids[teamId] = bid
		dealHands[dealHands.lastIndex] = hand.copy(teamBids = teamBids)
		phaseHands[GamePhase.Deal] = dealHands
		_state.value = current.copy(phaseHands = phaseHands)
	}

	/** Store the blind flag for a team in [Hand.teamBlind]. */
	fun setTeamBlind(teamId: Int, isBlind: Boolean) {
		val current = _state.value
		val phaseHands = current.phaseHands.toMutableMap()
		val dealHands  = phaseHands[GamePhase.Deal]?.toMutableList() ?: return
		val hand       = dealHands.lastOrNull() ?: return
		val teamBlind  = hand.teamBlind.toMutableList()
		if (teamId in teamBlind.indices) teamBlind[teamId] = isBlind
		dealHands[dealHands.lastIndex] = hand.copy(teamBlind = teamBlind)
		phaseHands[GamePhase.Deal] = dealHands
		_state.value = current.copy(phaseHands = phaseHands)
	}

	/**
	 * House Rules human bid: the human enters the team total after seeing their
	 * CPU partner's individual bid. Stores the team bid and hands control to the engine.
	 */
	fun submitHumanTeamBid(teamId: Int, bid: Int, localPlayerId: String) {
		submitBid(localPlayerId, bid, false)
		if (_state.value.isMultiplayer && !jmotley.com.jspades.data.MPSession.isHost)
			jmotley.com.jspades.data.MPSession.session?.sendPlayerBid(bid)
		setTeamBid(teamId, bid)
		advancePhase(GamePhase.Bid)
		phaseManager.execute()
	}

	/** Record the player who holds the 2♠ after a Kitty deal. */
	fun setKittyWinner(playerId: String) {
		_state.value = _state.value.copy(kittyWinnerId = playerId)
	}

	/**
	 * CPU kitty exchange: merge the kitty cards into [winnerId]'s hand, remove [discards],
	 * sort the resulting hand, and clear the kitty from state.
	 */
	fun applyKittyExchange(winnerId: String, discards: List<Card>) {
		val current    = _state.value
		val kittyCards = current.kitty?.perPlayer?.values?.flatMap { it.hand } ?: emptyList()
		val phaseHands = current.phaseHands.toMutableMap()
		val dealHands  = phaseHands[GamePhase.Deal]?.toMutableList() ?: return
		val hand       = dealHands.lastOrNull() ?: return
		val perPlayer  = hand.perPlayer.toMutableMap()
		val phs        = perPlayer[winnerId] ?: return
		val discardUids = discards.map { it.uid }.toSet()
		val newHand = (phs.hand + kittyCards)
			.filter { it.uid !in discardUids }
			.sortedWith(compareBy({ it.suit.ordinal }, { it.rank.ordinal }))
		perPlayer[winnerId] = phs.copy(hand = newHand)
		dealHands[dealHands.lastIndex] = hand.copy(perPlayer = perPlayer)
		phaseHands[GamePhase.Deal] = dealHands
		// Update originalDealHands so HandView's slot template reflects the post-exchange hand
		val updatedOriginalHands = current.originalDealHands + (winnerId to newHand)
		_state.value = current.copy(phaseHands = phaseHands, kitty = null, originalDealHands = updatedOriginalHands)
	}

	/** Play a card into the current trick slot. */
	fun playCard(playerId: String, card: Card) {
		val current = _state.value
		val plays   = current.currentTrick.plays.toMutableList()
		val idx     = plays.indexOfFirst { it == null }
		if (idx >= 0) {
			plays[idx] = Play(playerId = playerId, card = card)
			_state.value = current.copy(currentTrick = Trick(plays = plays))
			recordReplayEvent(ReplayEvent.CardPlay(playerId, card.uid))
			// Log the play (also logged as trick when trick resolves)
			PlayLogger.logPlay(Play(playerId = playerId, card = card))
		}
	}

	/** Remove a card from a player's live hand after it has been played. */
	fun removeCardFromHand(playerId: String, card: Card) {
		val current    = _state.value
		val phaseHands = current.phaseHands.toMutableMap()
		val dealHands  = phaseHands[GamePhase.Deal]?.toMutableList() ?: return
		val hand       = dealHands.lastOrNull() ?: return
		val perPlayer  = hand.perPlayer.toMutableMap()
		val phs        = perPlayer[playerId] ?: return
		perPlayer[playerId] = phs.copy(hand = phs.hand.filter { it.uid != card.uid })
		dealHands[dealHands.lastIndex] = hand.copy(perPlayer = perPlayer)
		phaseHands[GamePhase.Deal] = dealHands
		_state.value = current.copy(phaseHands = phaseHands)
	}

	/** Mark [playerId] as void in [suit] (they failed to follow that suit). */
	fun markCutting(playerId: String, suit: jmotley.com.jspades.data.Suit) {
		val current = _state.value
		val players = current.players.map { p ->
			if (p.id != playerId) p else {
				val f = p.runtimeFlags
				p.copy(runtimeFlags = when (suit) {
					jmotley.com.jspades.data.Suit.SPADES   -> f.copy(cuttingSpades   = true)
					jmotley.com.jspades.data.Suit.HEARTS   -> f.copy(cuttingHearts   = true)
					jmotley.com.jspades.data.Suit.CLUBS    -> f.copy(cuttingClubs    = true)
					jmotley.com.jspades.data.Suit.DIAMONDS -> f.copy(cuttingDiamonds = true)
				})
			}
		}
		_state.value = current.copy(players = players)
	}

	/** Record the first non-lead, non-trump discard suit for [playerId] (partner signal). */
	fun markSuitFirstThrowOff(playerId: String, suit: jmotley.com.jspades.data.Suit) {
		val current = _state.value
		val players = current.players.map { p ->
			if (p.id != playerId || p.runtimeFlags.suitFirstThrowOff != null) p
			else p.copy(runtimeFlags = p.runtimeFlags.copy(suitFirstThrowOff = suit))
		}
		_state.value = current.copy(players = players)
	}

	/** Spades are now broken — allow leading trump in Classic. */
	fun breakSpades() {
		_state.value = _state.value.copy(spadesBroken = true)
	}

	/** Increment tricksWon for [winnerId] in the current deal hand. */
	fun awardTrick(winnerId: String) {
		val current    = _state.value
		val phaseHands = current.phaseHands.toMutableMap()
		val dealHands  = phaseHands[GamePhase.Deal]?.toMutableList() ?: return
		val hand       = dealHands.lastOrNull() ?: return
		val perPlayer  = hand.perPlayer.toMutableMap()
		val phs        = perPlayer[winnerId] ?: PlayerHandState()
		perPlayer[winnerId] = phs.copy(tricksWon = phs.tricksWon + 1)
		dealHands[dealHands.lastIndex] = hand.copy(perPlayer = perPlayer)
		phaseHands[GamePhase.Deal] = dealHands
		_state.value = current.copy(phaseHands = phaseHands)
		recordReplayEvent(ReplayEvent.TrickWon(winnerId))
	}

	/**
	 * Clear the current trick, move played cards to the discard pile,
	 * and set [winnerIndex] as the next leader.
	 */
	fun collectTrick(winnerIndex: Int) {
		val current     = _state.value
		val playedCards = current.currentTrick.plays.filterNotNull().map { it.card }
		_state.value   = current.copy(
			currentTrick = Trick(plays = List(current.players.size) { null }),
			discard      = current.discard + playedCards,
			leaderIndex  = winnerIndex
		)
	}

	// ── Scoring ───────────────────────────────────────────────────────────────

	/**
	 * Add a scored hand's point and bag deltas to the running totals.
	 * Keys match how [PhaseManager.scoreHand] produces them:
	 * team games → "0" / "1"; solo games → player id.
	 */
	fun applyScore(delta: Score) {
		val current   = _state.value
		val newPoints = current.score.points.toMutableMap()
		val newBags   = current.score.bags.toMutableMap()
		delta.points.forEach { (k, v) -> newPoints[k] = (newPoints[k] ?: 0) + v }
		delta.bags.forEach   { (k, v) -> newBags[k]   = (newBags[k]   ?: 0) + v }
		_state.value = current.copy(
			score         = Score(points = newPoints, bags = newBags),
			lastHandScore = delta
		)
	}

	/**
	 * Reset all per-hand transient state and rotate the dealer for the next hand.
	 *
	 * - RuntimeFlags are wiped (cutting, throwoff, didBid) but seatIndex is preserved.
	 * - `leaderIndex` advances by one seat clockwise.
	 * - `spadesBroken`, `discard`, `kittyWinnerId`, and `currentTrick` are cleared.
	 * - `phaseHands` is cleared so the next deal starts fresh.
	 */
	fun resetForNextHand() {
		val current = _state.value
		val n = current.players.size
		val players = current.players.map { p ->
			p.copy(runtimeFlags = RuntimeFlags(seatIndex = p.runtimeFlags.seatIndex))
		}
		val nextLeader = (current.handLeaderIndex + 1) % n
		_state.value = current.copy(
			players           = players,
			leaderIndex       = nextLeader,
			handLeaderIndex   = nextLeader,
			currentTrick      = Trick(plays = List(n) { null }),
			discard           = emptyList(),
			spadesBroken      = false,
			kittyWinnerId     = null,
			phaseHands        = emptyMap(),
			replayEvents      = emptyList(),
			originalDealHands = emptyMap()
		)
		frustratedVideoFiredThisHand = false
		// Trick history is sealed into mpHandHistory by sealMpHand() before this call;
		// clear any residual in case sealMpHand wasn't called (e.g. play-again path).
		mpTrickHistory.clear()
		clearRemoteInputChannels()
	}

	// ── Two Man Solo deal ─────────────────────────────────────────────────────

	/** Store the remaining deal deck for [DealMode.TWO_MAN_ALTERNATE] pick UI. */
	fun storeDeck(deck: List<Card>) {
		_state.value = _state.value.copy(deck = deck)
	}

	/**
	 * Apply one human keep/skip pick for Two Man Solo.
	 * [keep] = true → take top card; false → take second card (skip top).
	 * After the human picks, auto-picks for the CPU for the next round.
	 * When the human's hand is full, advances to [GamePhase.Bid].
	 */
	fun applyDealPick(keep: Boolean, localPlayerId: String) {
		val current = _state.value
		val deck    = current.deck.toMutableList()
		if (deck.size < 2) return

		// Human picks from top two cards
		val top      = deck.removeAt(0)
		val next     = deck.removeAt(0)
		val humanCard = if (keep) top else next

		// Log two-man pick (accepted / discarded)
		PlayLogger.logTwoManPick(localPlayerId, humanCard, if (keep) next else top)

		val phaseHands = current.phaseHands.toMutableMap()
		val dealHands  = phaseHands[GamePhase.Deal]?.toMutableList() ?: return
		val hand       = dealHands.lastOrNull() ?: return
		val perPlayer  = hand.perPlayer.toMutableMap()

		val humanState   = perPlayer[localPlayerId] ?: PlayerHandState()
		val newHumanHand = (humanState.hand + humanCard)
			.sortedWith(compareBy({ it.suit.ordinal }, { it.rank.ordinal }))
		perPlayer[localPlayerId] = humanState.copy(hand = newHumanHand)

		val cpp       = current.gameType.cardsPerPlayer
		val humanDone = newHumanHand.size >= cpp

		// CPU picks after the human whenever it hasn't yet reached its hand limit.
		// Handles both orderings: CPU-first (pre-picked one card in dealTwoManAlternate)
		// and human-first (human is the non-dealer; CPU starts with 0 cards).
		val cpuId    = current.players.first { it.id != localPlayerId }.id
		val cpuState = perPlayer[cpuId] ?: PlayerHandState()
		if (cpuState.hand.size < cpp && deck.size >= 2) {
			val cpuTop  = deck.removeAt(0)
			val cpuNext = deck.removeAt(0)
			val cpuCard = if (kotlin.random.Random.nextBoolean()) cpuTop else cpuNext
			perPlayer[cpuId] = cpuState.copy(
				hand = (cpuState.hand + cpuCard)
					.sortedWith(compareBy({ it.suit.ordinal }, { it.rank.ordinal }))
			)
		}
		val bothDone = humanDone && (perPlayer[cpuId]?.hand?.size ?: 0) >= cpp

		dealHands[dealHands.lastIndex] = hand.copy(perPlayer = perPlayer)
		phaseHands[GamePhase.Deal] = dealHands

		// When the human's hand is complete, stamp originalDealHands so HandView
		// has a stable slot template throughout trick play.
		val updatedOriginalHands = if (humanDone) {
			current.originalDealHands + (localPlayerId to newHumanHand)
		} else {
			current.originalDealHands
		}
		_state.value = current.copy(deck = deck, phaseHands = phaseHands, originalDealHands = updatedOriginalHands)

		if (bothDone) {
			advancePhase(GamePhase.Bid)
			phaseManager.execute()
		}
	}

	// ── Human bid ─────────────────────────────────────────────────────────────

	/**
	 * Commit the human player's bid and return control to the engine.
	 * Advances to [GamePhase.Bid] so [PhaseManager] can process any remaining
	 * CPU bidders or move directly to the next phase.
	 */
	fun submitHumanBid(bid: Int, localPlayerId: String, isBlind: Boolean = false) {
		submitBid(playerId = localPlayerId, bid = bid, isBlind = isBlind)
		if (_state.value.isMultiplayer && !jmotley.com.jspades.data.MPSession.isHost)
			jmotley.com.jspades.data.MPSession.session?.sendPlayerBid(bid)
		advancePhase(GamePhase.Bid)
		phaseManager.execute()
	}

	/** Human confirmed the bid review summary — advance to the post-bid phase. */
	fun submitBidReview() {
		phaseManager.executeBidConfirmed()
	}

	/** Mark [playerId] as having made their blind bid decision (regardless of outcome). */
	fun markBlindDecision(playerId: String) {
		val players = _state.value.players.map { p ->
			if (p.id == playerId) p.copy(runtimeFlags = p.runtimeFlags.copy(didBlindDecide = true)) else p
		}
		_state.value = _state.value.copy(players = players)
	}

	/** Mark [playerId] as having completed their blind exchange. */
	fun markBlindExchange(playerId: String) {
		val players = _state.value.players.map { p ->
			if (p.id == playerId) p.copy(runtimeFlags = p.runtimeFlags.copy(didBlindExchange = true)) else p
		}
		_state.value = _state.value.copy(players = players)
	}

	/**
	 * Human blind bid decision. [goBlind]=true submits a blind bid; false declines.
	 * Returns control to BlindBid phase for engine to continue.
	 */
	fun submitHumanBlindBid(goBlind: Boolean, localPlayerId: String) {
		if (goBlind) {
			val s = _state.value
			val blindBid = when (s.gameType) {
				GameType.TEAM_CLASSIC, GameType.SOLO_FOUR_MAN -> 0
				else -> 7
			}
			submitBid(localPlayerId, blindBid, isBlind = true)
		}
		markBlindDecision(localPlayerId)
		advancePhase(GamePhase.BlindBid)
		phaseManager.execute()
	}

	/**
	 * Apply a blind nil exchange between [fromId] and [toId].
	 * [fromCards] are removed from [fromId]'s hand and added to [toId]'s hand, and vice versa.
	 */
	fun applyBlindExchange(fromId: String, toId: String, fromCards: List<Card>, toCards: List<Card>) {
		val current = _state.value
		val phaseHands = current.phaseHands.toMutableMap()
		val dealHands = phaseHands[GamePhase.Deal]?.toMutableList() ?: return
		val hand = dealHands.lastOrNull() ?: return
		val perPlayer = hand.perPlayer.toMutableMap()

		val fromPhs = perPlayer[fromId] ?: return
		val toPhs = perPlayer[toId] ?: return

		val fromCardUids = fromCards.map { it.uid }.toSet()
		val toCardUids = toCards.map { it.uid }.toSet()

		val newFromHand = (fromPhs.hand.filter { it.uid !in fromCardUids } + toCards)
			.sortedWith(compareBy({ it.suit.ordinal }, { it.rank.ordinal }))
		val newToHand = (toPhs.hand.filter { it.uid !in toCardUids } + fromCards)
			.sortedWith(compareBy({ it.suit.ordinal }, { it.rank.ordinal }))

		perPlayer[fromId] = fromPhs.copy(hand = newFromHand)
		perPlayer[toId] = toPhs.copy(hand = newToHand)
		dealHands[dealHands.lastIndex] = hand.copy(perPlayer = perPlayer)
		phaseHands[GamePhase.Deal] = dealHands

		// Update originalDealHands for HandView slot template
		val updatedOriginal = current.originalDealHands + (fromId to newFromHand) + (toId to newToHand)
		_state.value = current.copy(phaseHands = phaseHands, originalDealHands = updatedOriginal)
	}

	/**
	 * Human submits their blind exchange cards (2 cards to send to partner).
	 * If partner is CPU, computes partner's response automatically.
	 */
	fun submitHumanBlindExchange(selectedCards: List<Card>, localPlayerId: String) {
		val s = _state.value
		val humanPlayer = s.players.find { it.id == localPlayerId } ?: return
		val partnerPlayer = s.players.firstOrNull { it.team == humanPlayer.team && it.id != localPlayerId } ?: return
		val dealHand = s.phaseHands[GamePhase.Deal]?.lastOrNull() ?: return

		val humanPhs = dealHand.perPlayer[localPlayerId] ?: return
		val partnerPhs = dealHand.perPlayer[partnerPlayer.id] ?: return

		val humanIsBlind = humanPhs.isBlind
		val partnerIsBlind = partnerPhs.isBlind

		val partnerHand = partnerPhs.hand
		val partnerCards = jmotley.com.jspades.engine.PlayEngine.selectBlindExchangeCards(
			partnerHand,
			senderIsBlind = partnerIsBlind,
			receiverIsBlind = humanIsBlind
		)

		applyBlindExchange(localPlayerId, partnerPlayer.id, selectedCards, partnerCards)
		markBlindExchange(localPlayerId)
		markBlindExchange(partnerPlayer.id)
		advancePhase(GamePhase.BlindExchange)
		phaseManager.execute()
	}

	// ── Human trick play ──────────────────────────────────────────────────────

	/**
	 * Human kitty discard: apply the exchange and return control to the engine.
	 * Advances to [GamePhase.Bid] so bidding follows kitty exchange.
	 */
	fun submitHumanKittyDiscard(discards: List<Card>) {
		applyKittyExchange("south", discards)
		// Kitty winner is awarded one free trick before play begins
		val winnerId = _state.value.kittyWinnerId
		if (winnerId != null) awardTrick(winnerId)
		advancePhase(GamePhase.Bid)
		phaseManager.execute()
	}

	/**
	 * Commit the human player's card play and return control to the engine.
	 * Removes the card from hand, advances to [GamePhase.Trick].
	 */
	fun submitHumanPlay(card: Card, localPlayerId: String) {
		playCard(localPlayerId, card)
		removeCardFromHand(localPlayerId, card)
		// TODO: client sends playCard to host (new game object architecture)
		advancePhase(GamePhase.Trick)
		phaseManager.execute()
	}

	/**
	 * Called by PhaseManager for every CPU card play. Applies the play locally and, if this
	 * device is the MP host, relays it over WSS so guests can unblock awaitRemotePlay.
	 */
	fun cpuPlay(playerId: String, card: Card) {
		playCard(playerId, card)
		removeCardFromHand(playerId, card)
		// TODO: host sends gameState after CPU play (new game object architecture)
	}

	/**
	 * Returns true if [card] is a legal play for [localPlayerId] right now.
	 * Enforces suit-following and the Classic spades-broken gate on leading.
	 */
	fun canPlayCard(card: Card, localPlayerId: String): Boolean {
		val s = _state.value
		val hand = s.localHand(localPlayerId)
		val isTrump = { c: Card -> c.suit == jmotley.com.jspades.data.Suit.SPADES
				|| c.rank == jmotley.com.jspades.data.Rank.LITTLEJOKER
				|| c.rank == jmotley.com.jspades.data.Rank.BIGJOKER }
		val leadPlay = s.currentTrick.plays.firstOrNull { it != null }

		// Leading: spades-must-break gate — can't lead trump unless spades broken or hand is all trump
		if (leadPlay == null) {
			val mustBreakGate = s.gameType == GameType.TEAM_CLASSIC || s.spadesMustBreak
			if (mustBreakGate && !s.spadesBroken && isTrump(card)) {
				return hand.all { isTrump(it) }
			}
			return true
		}

		val leadCard = leadPlay.card
		val hasLeadSuit = if (isTrump(leadCard)) {
			hand.any { isTrump(it) }
		} else {
			hand.any { it.suit == leadCard.suit && !isTrump(it) }
		}
		if (!hasLeadSuit) return true  // void — anything goes

		return if (isTrump(leadCard)) isTrump(card)
		else card.suit == leadCard.suit && !isTrump(card)
	}

	// ── UI helpers ────────────────────────────────────────────────────────────

	/**
	 * Returns true when it is [localPlayerId]'s turn to bid.
	 * Walks players clockwise from leaderIndex; first un-bid player is the active bidder.
	 */
	fun isMyBidTurn(localPlayerId: String): Boolean {
		val s = _state.value
		if (s.phase != GamePhase.BidHuman) return false
		val n = s.players.size
		for (offset in 0 until n) {
			val player = s.players[(s.leaderIndex + offset) % n]
			if (!player.runtimeFlags.didBid) return player.id == localPlayerId
		}
		return false
	}

	// ── Replay recording ──────────────────────────────────────────────────────

	/** Append one event to the in-progress replay log. */
	fun recordReplayEvent(event: ReplayEvent) {
		_state.value = _state.value.copy(replayEvents = _state.value.replayEvents + event)
	}

	/**
	 * Snapshot the current [replayEvents] into [lastHandReplay] and clear the log.
	 * Called by [PhaseManager] at the start of [GamePhase.Score], before scoring.
	 */
	fun finalizeHandReplay() {
		val current = _state.value
		_state.value = current.copy(
			lastHandReplay = HandReplay(
				players          = current.players,
				gameType         = current.gameType,
				events           = current.replayEvents,
				twoOfSpadesJoker    = current.twoOfSpadesJoker,
				twoOfDiamondsJoker  = current.twoOfDiamondsJoker
			),
			replayEvents = emptyList()
		)
	}

	// ── End-of-hand / end-of-game actions ─────────────────────────────────────

	/** Called by the "Next Hand" button on EndHandView. Resets per-hand state and deals. */
	fun onNextHand() {
		if (isMultiplayer) mpHandNum++
		resetForNextHand()
		advancePhase(GamePhase.Deal)
		phaseManager.execute()
	}

	/** Award the Quits achievement if the player leaves mid-game. */
	override fun onCleared() {
		super.onCleared()
		val phase = _state.value.phase
		if (phase != GamePhase.Lobby && phase != GamePhase.Finished) {
			AchievementsRepo.mark(context, AchievementIds.QUITS)
		}
	}

	// ── Ad checkpoints and rewarded cheat helpers ─────────────────────────────

	// Set by PhaseManager at deal time and at each trick start.
	var handStartSnapshot: GameSnapshot? = null
	var currentTrickStartSnapshot: GameSnapshot? = null
	var previousTrickStartSnapshot: GameSnapshot? = null

	// Once-per-game flags — never reset mid-game (survive replay-last-hand).
	var usedUndoLastTrickThisGame: Boolean = false
	var usedPeekThisGame: Boolean = false
	var usedReplayLastHandThisGame: Boolean = false
	var usedBagForgivenessThisGame: Boolean = false
	var usedBidAdjustThisGame: Boolean = false
	var usedExtraBookThisGame: Boolean = false

	/** Rewind to the start of the previous trick. Once per game. */
	fun rewindToPreviousTrick() {
		val snap = previousTrickStartSnapshot ?: return
		if (usedUndoLastTrickThisGame) return
		Log.i("REWARDDEBUG", "rewindToPreviousTrick invoked — applying previousTrickStartSnapshot")
		_state.value = _state.value.applySnapshot(snap)   // restores phase = Trick
		usedUndoLastTrickThisGame = true
		currentTrickStartSnapshot = null
		previousTrickStartSnapshot = null
		phaseManager.execute()
	}

	/** Replay the hand that just finished using the exact same deal. Once per game. */
	fun replayLastHand() {
		val snap = handStartSnapshot ?: return
		if (usedReplayLastHandThisGame) return
		Log.i("REWARDDEBUG", "replayLastHand invoked — restoring handStartSnapshot handIndex=snap.handIndex//remove due to chatgpt bug")
		_state.value = _state.value.applySnapshot(snap)
		usedReplayLastHandThisGame = true
		// Do NOT reset any other usage flags — cheats spent in the original play stay spent.
		currentTrickStartSnapshot = null
		previousTrickStartSnapshot = null
		advancePhase(GamePhase.Bid)   // intentional override of restored phase
		phaseManager.execute()
	}

	/**
	 * Reveal the strongest trump (or highest card) from [playerId]'s current hand.
	 * Returns null if already used or the player has no cards.
	 * Once per game.
	 */
	fun peekCardForPlayer(playerId: String): Card? {
		if (usedPeekThisGame) {
			Log.w("REWARDDEBUG", "peekCardForPlayer denied — already used this game player=$playerId")
			return null
		}
		val hand = _state.value.phaseHands[GamePhase.Deal]
			?.lastOrNull()
			?.perPlayer
			?.get(playerId)
			?.hand
			?: return null
		val card = hand.filter { it.suit == Suit.SPADES || it.rank == Rank.LITTLEJOKER || it.rank == Rank.BIGJOKER }
			.maxByOrNull { it.rank.value }
			?: hand.maxByOrNull { it.rank.value }
			?: return null
		usedPeekThisGame = true
		Log.i("REWARDDEBUG", "peekCardForPlayer granted player=$playerId card=${card.uid}")
		return card
	}

	/**
	 * Grant the human player an extra trick (book) in the current hand.
	 * Increments tricksWon by 1 in the current PlayerHandState. Once per game.
	 */
	fun grantExtraBook(localPlayerId: String) {
		if (usedExtraBookThisGame) {
			Log.w("REWARDDEBUG", "grantExtraBook denied — already used this game player=$localPlayerId")
			return
		}
		val current = _state.value
		val phaseHands = current.phaseHands.toMutableMap()
		val dealHands = phaseHands[GamePhase.Deal]?.toMutableList() ?: return
		val hand = dealHands.lastOrNull() ?: return
		val perPlayer = hand.perPlayer.toMutableMap()
		val phs = perPlayer[localPlayerId] ?: return
		perPlayer[localPlayerId] = phs.copy(tricksWon = phs.tricksWon + 1)
		dealHands[dealHands.lastIndex] = hand.copy(perPlayer = perPlayer)
		phaseHands[GamePhase.Deal] = dealHands
		_state.value = current.copy(phaseHands = phaseHands)
		usedExtraBookThisGame = true
		Log.i("REWARDDEBUG", "grantExtraBook applied player=$localPlayerId newTricksWon=${phs.tricksWon + 1}")
	}

	/**
	 * Remove 3 bags from the human team's running total.
	 * Only eligible when human team bags >= 8 and not yet used this game.
	 */
	fun applyBagForgiveness(localPlayerId: String) {
		if (usedBagForgivenessThisGame) {
			Log.w("REWARDDEBUG", "applyBagForgiveness denied — already used this game player=$localPlayerId")
			return
		}
		val humanTeam = _state.value.players.firstOrNull { it.id == localPlayerId }?.team ?: return
		val teamKey = humanTeam.toString()
		val currentBags = _state.value.score.bags[teamKey] ?: 0
		if (currentBags < 8) return
		val newBags = (currentBags - 3).coerceAtLeast(0)
		_state.value = _state.value.copy(
			score = _state.value.score.copy(
				bags = _state.value.score.bags.toMutableMap().also { it[teamKey] = newBags }
			)
		)
		usedBagForgivenessThisGame = true
		Log.i("REWARDDEBUG", "applyBagForgiveness applied player=$localPlayerId team=$teamKey before=$currentBags after=$newBags")
	}

	/**
	 * Adjust the human player's individual bid by +1 or -1.
	 * Clamps to legal bid range [0..cardsPerPlayer].
	 * Only available in the post-bid, pre-trick window. Once per game.
	 */
	fun adjustHumanBid(localPlayerId: String, direction: jmotley.com.jspades.ads.BidAdjustDirection) {
		if (usedBidAdjustThisGame) {
			Log.w("REWARDDEBUG", "adjustHumanBid denied — already used this game player=$localPlayerId")
			return
		}
		val current = _state.value
		val phaseHands = current.phaseHands.toMutableMap()
		val dealHands = phaseHands[GamePhase.Deal]?.toMutableList() ?: return
		val hand = dealHands.lastOrNull() ?: return
		val perPlayer = hand.perPlayer.toMutableMap()
		val phs = perPlayer[localPlayerId] ?: return
		val delta = if (direction == jmotley.com.jspades.ads.BidAdjustDirection.PLUS_ONE) 1 else -1
		val max = current.gameType.cardsPerPlayer
		val newBid = (phs.bid + delta).coerceIn(current.effectiveMinBid, max)
		perPlayer[localPlayerId] = phs.copy(bid = newBid)
		dealHands[dealHands.lastIndex] = hand.copy(perPlayer = perPlayer)
		phaseHands[GamePhase.Deal] = dealHands
		_state.value = current.copy(phaseHands = phaseHands)
		usedBidAdjustThisGame = true
		Log.i("REWARDDEBUG", "adjustHumanBid applied player=$localPlayerId direction=$direction newBid=$newBid")
	}

	// ── Bag forgiveness offer signal ───────────────────────────────────────────

	/**
	 * Emitted by PhaseManager during handleScore() when the human team reaches >= 8 bags
	 * and the forgiveness offer has not yet been used.
	 * UI collects this and shows the offer; response is sent back via [respondToBagForgivenessOffer].
	 */
	private val _bagForgivenessOffer = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
	val bagForgivenessOffer: kotlinx.coroutines.flow.SharedFlow<Unit> = _bagForgivenessOffer

	private val _bagForgivenessResponse = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(extraBufferCapacity = 1)

	suspend fun emitBagForgivenessOffer() = _bagForgivenessOffer.emit(Unit)

	/** Called by UI after the player accepts or declines the forgiveness offer. */
	fun respondToBagForgivenessOffer(accepted: Boolean) {
		_bagForgivenessResponse.tryEmit(accepted)
	}

	/** Called by PhaseManager to suspend until UI responds to the bag forgiveness offer. */
	suspend fun awaitBagForgivenessResponse(): Boolean =
		_bagForgivenessResponse.first()

	/**
	 * Called by the "Play Again" button on EndGameView.
	 * Resets the full game (scores included) and starts a new deal with the same
	 * game type and players.
	 */
	fun playAgain() {
		val current = _state.value
		_state.value = GameState(
			players          = current.players.map { p ->
				p.copy(runtimeFlags = RuntimeFlags(seatIndex = p.runtimeFlags.seatIndex))
			},
			gameType         = current.gameType,
			phase            = GamePhase.Deal,
			leaderIndex      = 1,
			handLeaderIndex  = 1,
			twoOfSpadesJoker   = current.twoOfSpadesJoker,
			twoOfDiamondsJoker = current.twoOfDiamondsJoker,
			spadesMustBreak    = current.spadesMustBreak,
			minBidFive         = current.minBidFive
		)
		frustratedVideoFiredThisHand = false
		setCurrentVideoAsset(null)
		mpTrickHistory.clear()
		mpHandHistory.clear()
		clearRemoteInputChannels()
		phaseManager.execute()
	}
}