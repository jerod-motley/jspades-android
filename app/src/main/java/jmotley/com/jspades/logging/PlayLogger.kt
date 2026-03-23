package jmotley.com.jspades.logging

import android.util.Log
import jmotley.com.jspades.data.Card
import jmotley.com.jspades.data.GameState
import jmotley.com.jspades.data.Hand
import jmotley.com.jspades.data.Play

object PlayLogger {
    private const val TAG = "PLAYLOG"

    private fun cardToStr(c: Card): String = "${c.rank.name}_${c.suit.name}"

    fun logGameType(state: GameState) {
        Log.i(TAG, "GAME TYPE: ${state.gameType.label}")
    }

    fun logDealtHands(state: GameState) {
        try {
            val hand = state.phaseHands[jmotley.com.jspades.data.GamePhase.Deal]?.lastOrNull()
            if (hand == null) {
                Log.i(TAG, "DEALT HANDS: none")
                return
            }
            val parts = hand.perPlayer.map { (id, phs) ->
                "$id=[${phs.hand.joinToString(",") { cardToStr(it) }}]"
            }
            Log.i(TAG, "DEALT HANDS: ${parts.joinToString(" ; ")}")
        } catch (e: Exception) {
            Log.w(TAG, "logDealtHands failed: ${e.message}")
        }
    }

    fun logTwoManPick(humanId: String, accepted: Card?, discarded: Card?) {
        val a = accepted?.let { cardToStr(it) } ?: "<none>"
        val d = discarded?.let { cardToStr(it) } ?: "<none>"
        Log.i(TAG, "2-MAN PICK: $humanId accepted=$a discarded=$d")
    }

    fun logKitty(state: GameState) {
        try {
            val kitty = state.kitty?.perPlayer?.values?.flatMap { it.hand }.orEmpty()
            Log.i(TAG, "KITTY: [${kitty.joinToString(",") { cardToStr(it) }}]")
            val winner = state.kittyWinnerId
            if (!winner.isNullOrBlank()) {
                Log.i(TAG, "KITTY WINNER: $winner")
            }
        } catch (e: Exception) {
            Log.w(TAG, "logKitty failed: ${e.message}")
        }
    }

    fun logPostKittyHands(state: GameState) {
        try {
            val hand = state.phaseHands[jmotley.com.jspades.data.GamePhase.Deal]?.lastOrNull()
            if (hand == null) return
            val parts = hand.perPlayer.map { (id, phs) ->
                "$id=[${phs.hand.joinToString(",") { cardToStr(it) }}]"
            }
            Log.i(TAG, "POST-KITTY HANDS: ${parts.joinToString(" ; ")}")
        } catch (e: Exception) {
            Log.w(TAG, "logPostKittyHands failed: ${e.message}")
        }
    }

    fun logBid(playerId: String, bid: Int, isBlind: Boolean) {
        Log.i(TAG, "BID: $playerId -> $bid${if (isBlind) " (blind)" else ""}")
    }

    fun logBids(state: GameState) {
        try {
            val hand = state.phaseHands[jmotley.com.jspades.data.GamePhase.Deal]?.lastOrNull()
            val per = hand?.perPlayer ?: emptyMap()
            val parts = state.players.map { p ->
                val b = per[p.id]?.bid ?: 0
                "${p.id}=$b"
            }
            val teamParts = hand?.teamBids?.mapIndexed { idx, v -> "team$idx=${v ?: 0}" } ?: emptyList()
            Log.i(TAG, "BIDS: ${parts.joinToString(",")} | TEAMS: ${teamParts.joinToString(",")}")
        } catch (e: Exception) {
            Log.w(TAG, "logBids failed: ${e.message}")
        }
    }

    fun logPlay(play: Play) {
        try {
            Log.i(TAG, "PLAY: ${play.playerId} -> ${cardToStr(play.card)}")
        } catch (e: Exception) {
            Log.w(TAG, "logPlay failed: ${e.message}")
        }
    }

    fun logTrick(plays: List<Play>) {
        try {
            val parts = plays.map { "${it.playerId}:${cardToStr(it.card)}" }
            Log.i(TAG, "TRICK: ${parts.joinToString(", ")}")
        } catch (e: Exception) {
            Log.w(TAG, "logTrick failed: ${e.message}")
        }
    }

    fun logHandScore(hand: Hand?, delta: jmotley.com.jspades.data.Score, finalScore: jmotley.com.jspades.data.Score) {
        try {
            val parts = delta.points.map { (k, v) -> "$k=$v" }
            Log.i(TAG, "HAND SCORE DELTA: ${parts.joinToString(",")} | FINAL TOTALS: ${finalScore.points}")
        } catch (e: Exception) {
            Log.w(TAG, "logHandScore failed: ${e.message}")
        }
    }
}
