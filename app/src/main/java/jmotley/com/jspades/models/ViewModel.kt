package jmotley.com.jspades.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
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
import jmotley.com.jspades.engine.PhaseManager

class GameViewModel : ViewModel() {
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

	/** Single engine entry point. All phase transitions funnel through here. */
	val phaseManager = PhaseManager(this, viewModelScope)

	// ── Lobby ─────────────────────────────────────────────────────────────────

	/**
	 * Called by LobbyView once all seats are filled.
	 * Registers players and hands control to PhaseManager to deal and advance.
	 * [gameType] drives deck construction, player count, and team assignment.
	 */
	fun onLobbyComplete(ids: List<String>, names: List<String>, gameType: GameType) {
		require(ids.size == gameType.playerCount && names.size == gameType.playerCount)
		val players = defaultPlayers(ids, names, gameType)
		// leaderIndex = 1: west (left of south) bids/leads first; south is initial dealer.
		_state.value = _state.value.copy(players = players, phase = GamePhase.Deal, gameType = gameType, leaderIndex = 1)
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
		_state.value = _state.value.copy(phaseHands = phaseHands)
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

	/** Play a card into the current trick slot. */
	fun playCard(playerId: String, card: Card) {
		val current = _state.value
		val plays   = current.currentTrick.plays.toMutableList()
		val idx     = plays.indexOfFirst { it == null }
		if (idx >= 0) {
			plays[idx] = Play(playerId = playerId, card = card)
			_state.value = current.copy(currentTrick = Trick(plays = plays))
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
		_state.value = current.copy(
			players       = players,
			leaderIndex   = (current.leaderIndex + 1) % n,
			currentTrick  = Trick(plays = List(n) { null }),
			discard       = emptyList(),
			spadesBroken  = false,
			kittyWinnerId = null,
			phaseHands    = emptyMap()
		)
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

		val phaseHands = current.phaseHands.toMutableMap()
		val dealHands  = phaseHands[GamePhase.Deal]?.toMutableList() ?: return
		val hand       = dealHands.lastOrNull() ?: return
		val perPlayer  = hand.perPlayer.toMutableMap()

		val humanState   = perPlayer[localPlayerId] ?: PlayerHandState()
		val newHumanHand = (humanState.hand + humanCard)
			.sortedWith(compareBy({ it.suit.ordinal }, { it.rank.ordinal }))
		perPlayer[localPlayerId] = humanState.copy(hand = newHumanHand)

		val humanDone = newHumanHand.size >= current.gameType.cardsPerPlayer

		// If more rounds remain, CPU auto-picks for the next round
		if (!humanDone && deck.size >= 2) {
			val cpuId   = current.players.first { it.id != localPlayerId }.id
			val cpuTop  = deck.removeAt(0)
			val cpuNext = deck.removeAt(0)
			val cpuCard = if (kotlin.random.Random.nextBoolean()) cpuTop else cpuNext
			val cpuState = perPlayer[cpuId] ?: PlayerHandState()
			perPlayer[cpuId] = cpuState.copy(
				hand = (cpuState.hand + cpuCard)
					.sortedWith(compareBy({ it.suit.ordinal }, { it.rank.ordinal }))
			)
		}

		dealHands[dealHands.lastIndex] = hand.copy(perPlayer = perPlayer)
		phaseHands[GamePhase.Deal] = dealHands
		_state.value = current.copy(deck = deck, phaseHands = phaseHands)

		if (humanDone) {
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
	fun submitHumanBid(bid: Int, localPlayerId: String) {
		submitBid(playerId = localPlayerId, bid = bid)
		advancePhase(GamePhase.Bid)
		phaseManager.execute()
	}

	// ── Human trick play ──────────────────────────────────────────────────────

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

		// Leading: Classic gate — can't lead trump unless that's all you have
		if (leadPlay == null) {
			if (s.gameType == GameType.TEAM_CLASSIC && !s.spadesBroken && isTrump(card)) {
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

	// ── End-of-hand / end-of-game actions ─────────────────────────────────────

	/** Called by the "Next Hand" button on EndHandView. Resets per-hand state and deals. */
	fun onNextHand() {
		resetForNextHand()
		advancePhase(GamePhase.Deal)
		phaseManager.execute()
	}

	/**
	 * Called by the "Play Again" button on EndGameView.
	 * Resets the full game (scores included) and starts a new deal with the same
	 * game type and players.
	 */
	fun playAgain() {
		val current = _state.value
		_state.value = GameState(
			players     = current.players.map { p ->
				p.copy(runtimeFlags = RuntimeFlags(seatIndex = p.runtimeFlags.seatIndex))
			},
			gameType    = current.gameType,
			phase       = GamePhase.Deal,
			leaderIndex = 1
		)
		phaseManager.execute()
	}
}