package jmotley.com.jspades.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import jmotley.com.jspades.data.defaultPlayers
import jmotley.com.jspades.data.defaultFourPlayers
import jmotley.com.jspades.engine.PhaseManager

class GameViewModel : ViewModel() {
	private val _state = MutableStateFlow(GameState())
	val state: StateFlow<GameState> = _state

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
		_state.value = _state.value.copy(players = players, phase = GamePhase.Deal, gameType = gameType)
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
	 */
	fun submitBid(playerId: String, bid: Int) {
		val current = _state.value
		val phaseHands = current.phaseHands.toMutableMap()
		val dealHands  = phaseHands[GamePhase.Deal]?.toMutableList() ?: return
		val hand       = dealHands.lastOrNull() ?: return
		val perPlayer  = hand.perPlayer.toMutableMap()
		perPlayer[playerId] = (perPlayer[playerId] ?: PlayerHandState()).copy(bid = bid)
		dealHands[dealHands.lastIndex] = hand.copy(perPlayer = perPlayer)
		phaseHands[GamePhase.Deal] = dealHands

		// Mark didBid on the Player
		val players = current.players.map { p ->
			if (p.id == playerId) p.copy(runtimeFlags = p.runtimeFlags.copy(didBid = true)) else p
		}
		_state.value = current.copy(players = players, phaseHands = phaseHands)
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

	/** Clear the current trick and move played cards to the discard pile. */
	fun collectTrick(winnerId: String) {
		val current     = _state.value
		val playedCards = current.currentTrick.plays.filterNotNull().map { it.card }
		_state.value   = current.copy(
			currentTrick = Trick(),
			discard      = current.discard + playedCards
		)
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
}