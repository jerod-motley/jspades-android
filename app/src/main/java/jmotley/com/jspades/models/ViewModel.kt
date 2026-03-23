package jmotley.com.jspades.models

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jmotley.com.jspades.data.AchievementIds
import jmotley.com.jspades.data.AchievementsRepo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import jmotley.com.jspades.data.AppConfig
import jmotley.com.jspades.engine.PhaseManager
import jmotley.com.jspades.logging.PlayLogger

class GameViewModel(application: Application) : AndroidViewModel(application) {
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
		advancePhase(GamePhase.Trick)
		phaseManager.execute()
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
				twoOfSpadesJoker = current.twoOfSpadesJoker
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
			twoOfSpadesJoker = current.twoOfSpadesJoker,
			spadesMustBreak  = current.spadesMustBreak,
			minBidFive       = current.minBidFive
		)
		frustratedVideoFiredThisHand = false
		setCurrentVideoAsset(null)
		phaseManager.execute()
	}
}