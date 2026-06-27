package jmotley.com.jspades.models

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import jmotley.com.jspades.data.*
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
import jmotley.com.jspades.networking.MPAdapter
import jmotley.com.jspades.networking.MPAdapterDelegate
import jmotley.com.jspades.networking.gameTypeToWireString
import jmotley.com.jspades.networking.wireStringToGameType
import android.util.Log

private const val MP_TAG = "WSSMP"

class GameViewModel(application: Application) : AndroidViewModel(application), MPAdapterDelegate {
	private val context: Context get() = getApplication<Application>().applicationContext

	private val _state = MutableStateFlow(GameState())
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

	/**
	 * Called by LobbyView once all seats are filled.
	 * Registers players and hands control to PhaseManager to deal and advance.
	 * [gameType] drives deck construction, player count, and team assignment.
	 */
	fun onLobbyComplete(ids: List<String>, names: List<String>, gameType: GameType) {
		require(ids.size == gameType.playerCount && names.size == gameType.playerCount)
		val players = defaultPlayers(ids, names, gameType)
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
		// leaderIndex = 1: west (left of south) bids/leads first; south is initial dealer.
		_state.value = _state.value.copy(
			players          = players,
			phase            = GamePhase.Deal,
			gameType         = gameType,
			leaderIndex      = 1,
			handLeaderIndex  = 1,
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
		phaseManager.execute()
	}

	// ── State mutations used by PhaseManager ──────────────────────────────────

	/** Advance to a new phase. */
	fun advancePhase(phase: GamePhase) {
		_state.value = _state.value.copy(phase = phase)
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
		setTeamBid(teamId, bid)
		val players = _state.value.players.map { p ->
			if (p.id == localPlayerId) p.copy(runtimeFlags = p.runtimeFlags.copy(didBid = true)) else p
		}
		_state.value = _state.value.copy(players = players)
		// Send the human's individual contribution, not the team total.
		// Remote clients sum all individual bids in handleBidHouseRules (!humanOnTeam path).
		mpAdapter?.let { adapter ->
			val s2 = _state.value
			val partnerBidSum = s2.players
				.filter { it.team == teamId && it.id != localPlayerId }
				.sumOf { p -> s2.phaseHands[GamePhase.Deal]?.lastOrNull()?.perPlayer?.get(p.id)?.bid ?: 0 }
			val individualContribution = (bid - partnerBidSum).coerceAtLeast(0)
			Log.d(MP_TAG, "submitHumanTeamBid teamId=$teamId teamBid=$bid partnerBidSum=$partnerBidSum sending=$individualContribution hand=$mpCurrentHandNum")
			adapter.sendBid(localMPSeat, localWirePlayerId, individualContribution, false, mpCurrentHandNum)
		}
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
		// Every MP client sends its own human action (not host-only); CPU actions are host-only.
		if (mpAdapter != null && mpCurrentHandNum >= 0) {
			Log.d(MP_TAG, "submitHumanBid bid=$bid blind=$isBlind seat=$localMPSeat player=${localWirePlayerId.take(8)} hand=$mpCurrentHandNum")
			mpAdapter!!.sendBid(localMPSeat, localWirePlayerId, bid, isBlind, mpCurrentHandNum)
		}
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
		var resultingBid: Int? = null
		if (goBlind) {
			val s = _state.value
			val blindBid = when (s.gameType) {
				GameType.TEAM_CLASSIC, GameType.SOLO_FOUR_MAN -> 0
				else -> 7
			}
			resultingBid = blindBid
			submitBid(localPlayerId, blindBid, isBlind = true)
		}
		markBlindDecision(localPlayerId)
		// Every MP client sends its own human action (not host-only); CPU actions are host-only.
		if (mpAdapter != null && mpCurrentHandNum >= 0) {
			Log.d(MP_TAG, "submitHumanBlindBid goBlind=$goBlind resultingBid=$resultingBid hand=$mpCurrentHandNum")
			mpAdapter!!.sendBlindResponse(goBlind, mpCurrentHandNum)
		}
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
		// Capture trick position before playCard mutates currentTrick.
		val s            = _state.value
		val n            = s.players.size.coerceAtLeast(1)
		val trickNum     = s.discard.size / n + 1
		val trickPlayNum = s.currentTrick.plays.count { it != null } + 1
		Log.d(MP_TAG, "submitHumanPlay card=${card.uid} hand=$mpCurrentHandNum trick=$trickNum play=$trickPlayNum")
		playCard(localPlayerId, card)
		removeCardFromHand(localPlayerId, card)
		// Every MP client sends its own human action (not host-only); CPU actions are host-only.
		if (mpAdapter != null && mpCurrentHandNum >= 0) {
			mpAdapter!!.sendPlayCard(localMPSeat, localWirePlayerId, card, mpCurrentHandNum, trickNum, trickPlayNum)
		}
		advancePhase(GamePhase.Trick)
		// Fire-and-forget: emit CardPlayed animation; Play.kt's 550ms callback drives execute().
		// Do NOT call execute() here — holding busy=true during a delay drops incoming MP plays.
		viewModelScope.launch { emitAnimation(AnimationEvent.CardPlayed(localPlayerId, card)) }
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

	// ── Multiplayer ───────────────────────────────────────────────────────────────

	/** Non-null when this session is an online multiplayer game. */
	var mpAdapter: MPAdapter? = null

	/** Room seat index of the local player (0–3). Set by [attachMPAdapter]. */
	private var localMPSeat: Int = 0

	/** Maps host-assigned playerId → canonical id ("south"/"west"/"north"/"east"). Built in [onGameConfig]. */
	private var mpPlayerIdToCanonical: Map<String, String> = emptyMap()

	/** Host-assigned hand number of the current deal; −1 before the first deal arrives. */
	private var mpCurrentHandNum: Int = -1

	/** True if this device is the game host (deals, proxies CPUs, broadcasts all host actions). */
	var isMPHost: Boolean = false

	/** Room-seat-indexed player identity map; populated by the host in [onMPHostLobbyComplete]. */
	private var mpRoomSeatPlayers: Map<String, WireSeatPlayer> = emptyMap()

	/** The local player's host-assigned wire playerId; set in [onGameConfig] and [onMPHostLobbyComplete]. */
	private var localWirePlayerId: String = ""

	private fun roomSeatToCanonicalId(roomSeat: Int): String {
		val n = _state.value.players.size.coerceAtLeast(4)
		return listOf("south", "west", "north", "east")
			.getOrElse((roomSeat - localMPSeat + n) % n) { "seat$roomSeat" }
	}

	/** Attach an [MPAdapter], activating the MP receive path. Call once from the online lobby. */
	fun attachMPAdapter(adapter: MPAdapter, localSeat: Int) {
		mpAdapter = adapter
		localMPSeat = localSeat
		Log.d(MP_TAG, "attachMPAdapter localSeat=$localSeat")
	}

	/**
	 * Host entry point — called once all seats are confirmed and [MPAdapter] is attached.
	 * Sends [gameConfig] to remote clients, then sets up local state and starts dealing.
	 *
	 * @param remoteHumanSeats Room seat indices (not canonical) that belong to remote humans.
	 *   These seats get [PlayerType.MP]; the host's own seat gets [PlayerType.HUMAN];
	 *   all others get [PlayerType.CPU].
	 */
	fun onMPHostLobbyComplete(
		seatPlayers: Map<String, WireSeatPlayer>,
		config: WireGameConfig,
		ids: List<String>,
		names: List<String>,
		gameType: GameType,
		remoteHumanSeats: Set<Int>
	) {
		isMPHost = true
		mpRoomSeatPlayers = seatPlayers
		localWirePlayerId = seatPlayers[localMPSeat.toString()]?.playerId ?: ""
		mpCurrentHandNum = 0  // broadcastDeal() increments to 1 before first sendDeal

		// Seed the canonical map so the host can look up canonical IDs from wire playerIds.
		val n2 = gameType.playerCount
		val canonicalIds2 = listOf("south", "west", "north", "east")
		mpPlayerIdToCanonical = buildMap {
			seatPlayers.forEach { (seatKey, wirePlayer) ->
				val roomSeat = seatKey.toIntOrNull() ?: return@forEach
				val canonicalIdx = (roomSeat - localMPSeat + n2) % n2
				put(wirePlayer.playerId, canonicalIds2.getOrElse(canonicalIdx) { "seat$roomSeat" })
			}
		}

		Log.d(MP_TAG, "onMPHostLobbyComplete seatPlayers=${seatPlayers.map { (k, v) -> "$k→${v.playerId.take(8)}" }} remoteHumanSeats=$remoteHumanSeats gameType=${gameType.name}")
		mpAdapter?.sendGameConfig(config, seatPlayers)

		// Build players with correct types: host seat = HUMAN, remote human seats = MP, rest = CPU.
		val n = gameType.playerCount
		val players = (0 until n).map { canonicalIdx ->
			val roomSeat = (canonicalIdx + localMPSeat) % n
			val wirePlayer = seatPlayers[roomSeat.toString()]
			val playerType = when {
				roomSeat == localMPSeat -> PlayerType.HUMAN
				roomSeat in remoteHumanSeats -> PlayerType.MP
				else -> PlayerType.CPU
			}
			Player(
				id          = ids.getOrElse(canonicalIdx) { "player$canonicalIdx" },
				name        = names.getOrElse(canonicalIdx) { wirePlayer?.displayName ?: "Player $canonicalIdx" },
				team        = if (gameType.useTeams && canonicalIdx % 2 == 1) 1 else 0,
				playerType  = playerType,
				runtimeFlags = RuntimeFlags(seatIndex = canonicalIdx)
			)
		}

		// Apply config from wire (same values remote clients will receive) rather than local prefs.
		_state.value = _state.value.copy(
			players          = players,
			phase            = GamePhase.Deal,
			gameType         = gameType,
			leaderIndex      = 1,
			handLeaderIndex  = 1,
			currentTrick     = Trick(plays = List(n) { null }),
			twoOfSpadesJoker     = config.twoOfSpadesJoker,
			twoOfDiamondsJoker   = config.twoOfDiamondsJoker,
			enableDoubleBidBonus = config.enableDoubleBidBonus,
			spadesMustBreak      = config.spadesMustBreak,
			minBidFive           = config.minBidFive,
			enableSandbagPenalty = config.enableSandbagPenalty,
			allowNilBid          = config.allowNilBid,
			allowBlindExchange   = config.allowBlindExchange,
			gameLength           = runCatching { GameLength.valueOf(config.gameLength) }.getOrElse { GameLength.MEDIUM }
		)
		phaseManager.execute()
	}

	// ── MP session bootstrap ──────────────────────────────────────────────────────

	/**
	 * Called from PlayScreen once on entry. If an MP game is pending in [MPStateHolder],
	 * creates an [MPAdapter] backed by the live [OnlineSession] socket, registers it as the
	 * raw-message hook, and (for the host) kicks off the game via [onMPHostLobbyComplete].
	 * Non-host clients wait for the [WireMessage] gameConfig to arrive via the hook.
	 */
	fun startMPSessionIfPending(gameType: GameType) {
		val mpConfig = MPStateHolder.consume()
		if (mpConfig == null) {
			Log.d(MP_TAG, "startMPSessionIfPending: no pending config")
			return
		}
		val session  = MPSession.session ?: return
		val socket   = session.getGameSocketClient() ?: return
		val lobby    = session.lobby.value ?: return

		val localSeat      = mpConfig.localSeatIndex
		val localWireId    = lobby.localPlayerId

		Log.d(MP_TAG, "startMPSessionIfPending isHost=${MPSession.isHost} localSeat=$localSeat localWireId=${localWireId.take(8)}")

		val adapter = MPAdapter(
			socket        = socket,
			delegate      = this,
			localSeat     = localSeat,
			localPlayerId = localWireId,
			roomId        = lobby.roomId,
			scope         = viewModelScope
		)
		session.rawMessageHook = adapter::receive
		attachMPAdapter(adapter, localSeat)

		if (MPSession.isHost) {
			val seatPlayers = buildMap<String, WireSeatPlayer> {
				(0 until 4).forEach { i ->
					val seat = lobby.seats.find { it.seatIndex == i }
					put("$i", WireSeatPlayer(
						playerId    = seat?.playerId ?: "cpu-$i",
						displayName = seat?.displayName ?: "CPU $i"
					))
				}
			}
			val prefs = context.getSharedPreferences("jspades_prefs", Context.MODE_PRIVATE)
			val wireConfig = WireGameConfig(
				gameType             = gameTypeToWireString(gameType),
				spadesMustBreak      = prefs.getBoolean("spades_must_break", false),
				minBidFive           = prefs.getBoolean("min_bid_five", false),
				enableSandbagPenalty = prefs.getBoolean("count_overs", true),
				allowNilBid          = gameType == GameType.TEAM_CLASSIC,
				allowBlindExchange   = gameType == GameType.TEAM_CLASSIC && prefs.getBoolean("blind_nil_exchange", false),
				gameLength           = if (AppConfig.TEST_MODE) GameLength.TEST.name
				                       else prefs.getString("game_length", GameLength.MEDIUM.name) ?: GameLength.MEDIUM.name
			)
			onMPHostLobbyComplete(
				seatPlayers      = seatPlayers,
				config           = wireConfig,
				ids              = mpConfig.playerIds,
				names            = mpConfig.playerNames,
				gameType         = gameType,
				remoteHumanSeats = mpConfig.remoteHumanSeats
			)
		}
		// Non-host: adapter is live and will receive the host's gameConfig message via the hook.
	}

	// ── Host-to-wire conversion helpers ──────────────────────────────────────────

	/** Canonical player list index (0=south…) → room seat number. */
	internal fun canonicalIdxToRoomSeat(canonicalIdx: Int): Int {
		val n = _state.value.players.size.coerceAtLeast(4)
		return (canonicalIdx + localMPSeat) % n
	}

	/** Wire playerId for a given room seat (host-only; reads [mpRoomSeatPlayers]). Null when the seat is not mapped. */
	private fun roomSeatToWirePlayerId(roomSeat: Int): String? =
		mpRoomSeatPlayers[roomSeat.toString()]?.playerId

	// ── Host broadcast methods (all no-ops when [isMPHost] is false) ─────────────

	/**
	 * Broadcast the current hand's deal to all remote clients.
	 * Called by PhaseManager immediately after the deal algorithms populate phaseHands.
	 * No-op when not the host or when mpAdapter is null.
	 */
	internal fun broadcastDeal() {
		val adapter = mpAdapter ?: return
		if (!isMPHost) return
		val s = _state.value
		val n = s.players.size
		val hand = s.phaseHands[GamePhase.Deal]?.lastOrNull() ?: return

		mpCurrentHandNum++

		val dealerCanonicalIdx = (s.handLeaderIndex - 1 + n) % n
		val dealerRoomSeat     = canonicalIdxToRoomSeat(dealerCanonicalIdx)
		val seatOrder = (0 until n).mapNotNull { roomSeat ->
			roomSeatToWirePlayerId(roomSeat) ?: run {
				Log.w(MP_TAG, "broadcastDeal: missing playerId for roomSeat=$roomSeat — aborting")
				return
			}
		}

		val handsBySeat = buildMap<String, List<Card>> {
			s.players.forEachIndexed { canonicalIdx, player ->
				put(canonicalIdxToRoomSeat(canonicalIdx).toString(), hand.perPlayer[player.id]?.hand ?: emptyList())
			}
		}

		val kittyCards = s.kitty?.perPlayer?.get("kitty")?.hand
		val kittyWinnerId = s.kittyWinnerId?.let { cId ->
			val idx = listOf("south", "west", "north", "east").indexOf(cId)
			if (idx >= 0) roomSeatToWirePlayerId(canonicalIdxToRoomSeat(idx)) else null
		}

		Log.d(MP_TAG, "broadcastDeal handNum=$mpCurrentHandNum dealer=$dealerRoomSeat seatOrder=$seatOrder handSizes=${handsBySeat.mapValues { it.value.size }} kittyCount=${kittyCards?.size ?: 0}")
		adapter.sendDeal(mpCurrentHandNum, dealerRoomSeat, seatOrder, handsBySeat, kittyCards, kittyWinnerId)
	}

	/** Broadcast a CPU player's computed bid. Host only. */
	internal fun broadcastCPUBid(canonicalId: String, amount: Int, isBlind: Boolean) {
		val adapter = mpAdapter ?: return
		if (!isMPHost) return
		val idx = listOf("south", "west", "north", "east").indexOf(canonicalId)
		if (idx < 0) return
		val roomSeat     = canonicalIdxToRoomSeat(idx)
		val wirePlayerId = roomSeatToWirePlayerId(roomSeat) ?: run {
			Log.w(MP_TAG, "broadcastCPUBid: missing playerId for seat $roomSeat — skipping")
			return
		}
		Log.d(MP_TAG, "broadcastCPUBid canonicalId=$canonicalId → roomSeat=$roomSeat amount=$amount blind=$isBlind hand=$mpCurrentHandNum")
		adapter.sendBid(roomSeat, wirePlayerId, amount, isBlind, mpCurrentHandNum)
	}

	/**
	 * Broadcast a CPU player's selected card play.
	 * MUST be called BEFORE [playCard] so trickPlayNum reflects the pre-play slot count.
	 * Host only.
	 */
	internal fun broadcastCPUPlay(canonicalId: String, card: Card) {
		val adapter = mpAdapter ?: return
		if (!isMPHost) return
		val s = _state.value
		val n = s.players.size.coerceAtLeast(1)
		val idx = listOf("south", "west", "north", "east").indexOf(canonicalId)
		if (idx < 0) return
		val roomSeat     = canonicalIdxToRoomSeat(idx)
		val wirePlayerId = roomSeatToWirePlayerId(roomSeat) ?: run {
			Log.w(MP_TAG, "broadcastCPUPlay: missing playerId for seat $roomSeat — skipping")
			return
		}
		val trickNum     = s.discard.size / n + 1
		val trickPlayNum = s.currentTrick.plays.count { it != null } + 1
		Log.d(MP_TAG, "broadcastCPUPlay canonicalId=$canonicalId → roomSeat=$roomSeat card=${card.uid} hand=$mpCurrentHandNum trick=$trickNum play=$trickPlayNum")
		adapter.sendPlayCard(roomSeat, wirePlayerId, card, mpCurrentHandNum, trickNum, trickPlayNum)
	}

	/** Broadcast a blind offer to all clients. Host only. */
	internal fun broadcastBlindOffer(teamSeats: List<Int>, decidingSeats: List<Int>) {
		val adapter = mpAdapter ?: return
		if (!isMPHost) return
		Log.d(MP_TAG, "broadcastBlindOffer teamSeats=$teamSeats decidingSeats=$decidingSeats hand=$mpCurrentHandNum")
		adapter.sendBlindOffer(mpCurrentHandNum, teamSeats, decidingSeats)
	}

	/** Broadcast a CPU player's blind-bid decision. Host only. */
	internal fun broadcastCPUBlindResponse(canonicalId: String, accepted: Boolean) {
		val adapter = mpAdapter ?: return
		if (!isMPHost) return
		val idx = listOf("south", "west", "north", "east").indexOf(canonicalId)
		if (idx < 0) return
		val roomSeat     = canonicalIdxToRoomSeat(idx)
		val wirePlayerId = roomSeatToWirePlayerId(roomSeat) ?: run {
			Log.w(MP_TAG, "broadcastCPUBlindResponse: missing playerId for seat $roomSeat — skipping")
			return
		}
		Log.d(MP_TAG, "broadcastCPUBlindResponse canonicalId=$canonicalId → roomSeat=$roomSeat accepted=$accepted hand=$mpCurrentHandNum")
		adapter.sendBlindResponse(roomSeat, wirePlayerId, accepted, mpCurrentHandNum)
	}

	// ── MPAdapterDelegate ─────────────────────────────────────────────────────────

	override fun onGameConfig(config: WireGameConfig, seatPlayers: Map<String, WireSeatPlayer>) {
		val gameType = wireStringToGameType(config.gameType) ?: GameType.HOUSE_RULES
		val n = gameType.playerCount
		val canonicalIds = listOf("south", "west", "north", "east")

		// Iterate by canonical index so players[] is canonical-ordered (south=0…east=3).
		// PhaseManager uses players[leaderIndex] and players[leaderIndex-1] as canonical
		// positions; room-seat ordering here misaligns those lookups for the guest.
		val players = (0 until n).map { canonicalIdx ->
			val roomSeat    = (canonicalIdx + localMPSeat) % n
			val canonicalId = canonicalIds[canonicalIdx]
			val wirePlayer  = seatPlayers[roomSeat.toString()]
			Player(
				id           = canonicalId,
				name         = wirePlayer?.displayName ?: canonicalId,
				team         = if (gameType.useTeams && canonicalIdx % 2 == 1) 1 else 0,
				playerType   = if (roomSeat == localMPSeat) PlayerType.HUMAN else PlayerType.MP,
				runtimeFlags = RuntimeFlags(seatIndex = canonicalIdx)
			)
		}

		mpPlayerIdToCanonical = buildMap {
			seatPlayers.forEach { (seatKey, wirePlayer) ->
				val roomSeat     = seatKey.toIntOrNull() ?: return@forEach
				val canonicalIdx = (roomSeat - localMPSeat + n) % n
				put(wirePlayer.playerId, canonicalIds.getOrElse(canonicalIdx) { "seat$roomSeat" })
			}
		}

		localWirePlayerId = seatPlayers[localMPSeat.toString()]?.playerId ?: ""

		Log.d(MP_TAG, "onGameConfig gameType=${gameType.name} players=${players.map { "${it.id}(${it.playerType})" }}")
		_state.value = _state.value.copy(
			players              = players,
			gameType             = gameType,
			leaderIndex          = 1,
			handLeaderIndex      = 1,
			currentTrick         = Trick(plays = List(n) { null }),
			twoOfSpadesJoker     = config.twoOfSpadesJoker,
			twoOfDiamondsJoker   = config.twoOfDiamondsJoker,
			enableDoubleBidBonus = config.enableDoubleBidBonus,
			spadesMustBreak      = config.spadesMustBreak,
			minBidFive           = config.minBidFive,
			enableSandbagPenalty = config.enableSandbagPenalty,
			allowNilBid          = config.allowNilBid,
			allowBlindExchange   = config.allowBlindExchange,
			gameLength           = runCatching { GameLength.valueOf(config.gameLength) }.getOrElse { GameLength.MEDIUM }
		)
		// Phase stays at Lobby — wait for the host's `deal` message to start the hand.
	}

	override fun onDeal(
		handNum: Int,
		dealerSeat: Int,
		seatOrder: List<String>,
		handsBySeat: Map<String, List<Card>>,
		kitty: List<Card>?,
		kittyWinnerId: String?
	) {
		if (mpCurrentHandNum != -1 && handNum <= mpCurrentHandNum) {
			Log.w(MP_TAG, "onDeal DROPPED stale handNum=$handNum current=$mpCurrentHandNum")
			return
		}
		val dealPhaseBefore = _state.value.phase
		mpCurrentHandNum = handNum

		val n = _state.value.players.size
		val canonicalIds = listOf("south", "west", "north", "east")

		if (_state.value.phase != GamePhase.Lobby) resetForNextHand()

		val perPlayer = buildMap<String, PlayerHandState> {
			for ((seatKey, cards) in handsBySeat) {
				val roomSeat     = seatKey.toIntOrNull() ?: continue
				val canonicalIdx = (roomSeat - localMPSeat + n) % n
				val canonicalId  = canonicalIds.getOrElse(canonicalIdx) { "seat$roomSeat" }
				val sorted = cards.sortedWith(compareBy({ it.suit.ordinal }, { it.rank.ordinal }))
				put(canonicalId, PlayerHandState(hand = sorted))
			}
		}

		applyDeal(Hand(perPlayer = perPlayer))

		if (kitty != null) {
			applyKitty(Hand(perPlayer = mapOf("kitty" to PlayerHandState(hand = kitty))))
			val winnerId = kittyWinnerId?.let { mpPlayerIdToCanonical[it] }
			if (winnerId != null) setKittyWinner(winnerId)
		}

		// Player after the dealer bids and leads first.
		val firstBidderCanonicalIdx = ((dealerSeat + 1) - localMPSeat + n) % n
		_state.value = _state.value.copy(
			leaderIndex     = firstBidderCanonicalIdx,
			handLeaderIndex = firstBidderCanonicalIdx
		)

		Log.d(MP_TAG, "onDeal ACCEPTED handNum=$handNum phaseBefore=$dealPhaseBefore phaseAfter=Bid")
		advancePhase(GamePhase.Bid)
		phaseManager.execute()
	}

	override fun onBlindOffer(handNum: Int, teamSeats: List<Int>, decidingSeats: List<Int>) {
		val deciding = localMPSeat in decidingSeats
		Log.d(MP_TAG, "onBlindOffer handNum=$handNum teamSeats=$teamSeats decidingSeats=$decidingSeats localSeat=$localMPSeat deciding=$deciding")
		// Only advance if the local player is one of the designated deciding seats.
		// If not, wait: onBlindResponse callbacks will drive the phase forward.
		if (!deciding) return
		advancePhase(GamePhase.BlindBid)
		phaseManager.execute()
	}

	override fun onBlindResponse(seat: Int, accepted: Boolean, handNum: Int) {
		val canonicalId = roomSeatToCanonicalId(seat)
		val blindPhaseBefore = _state.value.phase
		Log.d(MP_TAG, "onBlindResponse seat=$seat canonicalId=$canonicalId accepted=$accepted handNum=$handNum phaseBefore=$blindPhaseBefore")
		markBlindDecision(canonicalId)
		if (accepted) {
			val blindBid = when (_state.value.gameType) {
				GameType.TEAM_CLASSIC, GameType.SOLO_FOUR_MAN -> 0
				else -> 7
			}
			submitBid(canonicalId, blindBid, isBlind = true)
		}
		advancePhase(GamePhase.BlindBid)
		phaseManager.execute()
	}

	override fun onBid(seat: Int, amount: Int, isBlind: Boolean, handNum: Int) {
		if (handNum != mpCurrentHandNum) {
			Log.w(MP_TAG, "onBid DROPPED stale handNum=$handNum current=$mpCurrentHandNum seat=$seat")
			return
		}
		val canonicalId = roomSeatToCanonicalId(seat)
		val didBid = _state.value.players.find { it.id == canonicalId }?.runtimeFlags?.didBid
		Log.d(MP_TAG, "onBid ACCEPTED seat=$seat canonicalId=$canonicalId amount=$amount blind=$isBlind didBid=$didBid")
		submitBid(canonicalId, amount, isBlind)
		advancePhase(GamePhase.Bid)
		phaseManager.execute()
	}

	override fun onPlayCard(seat: Int, cardUid: String, handNum: Int, trickNum: Int, trickPlayNum: Int) {
		if (handNum != mpCurrentHandNum) {
			Log.w(MP_TAG, "onPlayCard DROPPED stale handNum=$handNum current=$mpCurrentHandNum")
			return
		}
		val n = _state.value.players.size
		val playedCount = _state.value.currentTrick.plays.count { it != null }
		if (playedCount >= n) {
			Log.w(MP_TAG, "onPlayCard DROPPED trick already complete trickPlayNum=$trickPlayNum playedCount=$playedCount")
			return
		}
		if (trickPlayNum - 1 != playedCount) {
			Log.w(MP_TAG, "onPlayCard DROPPED out-of-order trickPlayNum=$trickPlayNum playedCount=$playedCount")
			return
		}
		val canonicalId = roomSeatToCanonicalId(seat)
		val playPhaseBefore = _state.value.phase
		// phaseHands[Deal] is the LIVE hand (mutated by removeCardFromHand after each play),
		// not the original deal snapshot — so a duplicate play for an already-played card
		// yields null here and is safely dropped by the guard below.
		val card = _state.value.phaseHands[GamePhase.Deal]?.lastOrNull()
			?.perPlayer?.get(canonicalId)?.hand?.firstOrNull { it.uid == cardUid }
		if (card == null) {
			Log.w(MP_TAG, "onPlayCard DROPPED card=$cardUid not in live hand of $canonicalId")
			return
		}
		Log.d(MP_TAG, "onPlayCard ACCEPTED seat=$seat canonicalId=$canonicalId card=$cardUid hand=$handNum trick=$trickNum play=$trickPlayNum phaseBefore=$playPhaseBefore")
		playCard(canonicalId, card)
		removeCardFromHand(canonicalId, card)
		advancePhase(GamePhase.Trick)
		// Fire-and-forget: let the card animate before the engine advances, same as submitHumanPlay.
		// Calling execute() directly would race ahead of the animation pipeline and skip the card
		// appearing on screen — especially visible on the final trick where Score/EndHand follows.
		viewModelScope.launch { emitAnimation(AnimationEvent.CardPlayed(canonicalId, card)) }
	}

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
		phaseManager.execute()
	}
}