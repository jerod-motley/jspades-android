package jmotley.com.jspades.models

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.GamePhase
import jmotley.com.jspades.data.Hand
import jmotley.com.jspades.data.Player
import jmotley.com.jspades.data.PlayerHandState
import jmotley.com.jspades.data.Play
import jmotley.com.jspades.data.Trick
import jmotley.com.jspades.data.defaultFourPlayers

class GameViewModel : ViewModel() {
	private val _state = MutableStateFlow(GameState())
	val state: StateFlow<GameState> = _state

	fun setPlayers(ids: List<String>, names: List<String>) {
		val players: List<Player> = defaultFourPlayers(ids, names)
		_state.value = _state.value.copy(players = players)
	}

	/** Deal cards to players: `dealt` is playerId -> list of cards. This creates a new Hand
	 * and appends it to the `Deal` phase hands.
	 */
	fun dealToPlayers(dealt: Map<String, List<Card>>) {
		val perPlayer: Map<String, PlayerHandState> = dealt.mapValues { (_, cards) ->
			PlayerHandState(hand = cards)
		}
		val playerOrder = _state.value.players.map { it.id }
		val hand = Hand(trickPlays = emptyList(), playerOrder = playerOrder, perPlayer = perPlayer)

		val phaseHands = _state.value.phaseHands.toMutableMap()
		val list = (phaseHands[GamePhase.Deal] ?: emptyList()).toMutableList()
		list.add(hand)
		phaseHands[GamePhase.Deal] = list
		_state.value = _state.value.copy(phaseHands = phaseHands)
	}

	/** Play a card for the current trick. */
	fun playCard(playerId: String, card: Card) {
		val current = _state.value
		val plays = current.currentTrick.plays.toMutableList()
		val idx = plays.indexOfFirst { it == null }
		if (idx >= 0) {
			plays[idx] = Play(playerId = playerId, card = card)
			val newTrick = Trick(plays = plays)
			_state.value = current.copy(currentTrick = newTrick)
		}
	}

	/** Reset the current trick (after collection) and append played cards to discard. */
	fun collectTrick(winnerId: String) {
		val current = _state.value
		val playedCards = current.currentTrick.plays.filterNotNull().map { it.card }
		val newDiscard = current.discard + playedCards
		val newTrick = Trick() // empty trick
		_state.value = current.copy(currentTrick = newTrick, discard = newDiscard)
	}
}