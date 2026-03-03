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
import jmotley.com.jspades.data.Rank
import jmotley.com.jspades.data.RuntimeFlags
import jmotley.com.jspades.data.Suit
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

	/**
	 * Returns true when it is [localPlayerId]'s turn to bid.
	 * Bid order starts at [GameState.leaderIndex] and proceeds clockwise (increasing index,
	 * wrapping). The first player in that order who has not yet bid is the active bidder.
	 */
	fun isMyBidTurn(localPlayerId: String): Boolean {
		val s = _state.value
		if (s.phase != GamePhase.Bid) return false
		val n = s.players.size
		for (offset in 0 until n) {
			val player = s.players[(s.leaderIndex + offset) % n]
			if (!player.runtimeFlags.didBid) return player.id == localPlayerId
		}
		return false
	}

	/**
	 * Build a standard 52-card deck (TWO–ACE × all 4 suits), shuffle it,
	 * deal 13 sorted cards to each player, then advance the phase to Deal.
	 *
	 * Player IDs and names are expected in clockwise seat order: South, West, North, East.
	 * Each hand is sorted by suit ordinal (Clubs→Spades), then rank ordinal (2→Ace).
	 */
	fun dealAndStart(ids: List<String>, names: List<String>) {
		require(ids.size == 4 && names.size == 4)

		// 1. Set players (S/W/N/E → teams 0,1,0,1)
		val players = defaultFourPlayers(ids, names)

		// 2. Build and shuffle the 52-card deck (Rank.TWO..Rank.ACE × all suits)
		val standardRanks = listOf(
			Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.SIX, Rank.SEVEN,
			Rank.EIGHT, Rank.NINE, Rank.TEN, Rank.JACK, Rank.QUEEN, Rank.KING, Rank.ACE
		)
		val deck = Suit.entries.flatMap { suit ->
			standardRanks.map { rank -> Card(suit = suit, rank = rank) }
		}.shuffled()

		// 3. Deal 13 cards to each seat, sort by suit then rank
		val dealt: Map<String, List<Card>> = ids.mapIndexed { i, id ->
			id to deck.subList(i * 13, (i + 1) * 13)
				.sortedWith(compareBy({ it.suit.ordinal }, { it.rank.ordinal }))
		}.toMap()

		// 4. Build Hand and store in phaseHands[Deal]
		val perPlayer = dealt.mapValues { (_, cards) -> PlayerHandState(hand = cards) }
		val hand = Hand(
			playerOrder = ids,
			perPlayer = perPlayer
		)
		_state.value = GameState(
			players = players,
			phaseHands = mapOf(GamePhase.Deal to listOf(hand)),
			phase = GamePhase.Deal
		)
	}
}