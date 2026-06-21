package jmotley.com.jspades.data

import jmotley.com.jspades.networking.OnlineSession

/**
 * App-scoped reference to the active multiplayer session.
 * Set by [OnlineLobbyViewModel] when the session is created; cleared on disconnect.
 * Allows PlayScreen to observe session flows (allBids, etc.) without coupling to the lobby VM.
 */
object MPSession {
    @Volatile var session: OnlineSession? = null
    @Volatile var isHost: Boolean = false

    fun clear() {
        session = null
        isHost = false
    }
}
