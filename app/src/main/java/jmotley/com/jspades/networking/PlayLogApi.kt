package jmotley.com.jspades.networking

import io.ktor.client.request.*
import io.ktor.http.*
import jmotley.com.jspades.data.AppConfig
import kotlinx.serialization.Serializable

@Serializable
data class PlayLogGame(
    val gameType: String,
    val scores: Map<String, Int>,
    val handsPlayed: Int
)

@Serializable
data class PlayLogPayload(
    val pk: String = AppConfig.PARTITION_KEY,
    val sk: String,
    val name: String,
    val createDate: String,
    val games: List<PlayLogGame>
)

object PlayLogApi {
    private val client get() = NetworkClient.client

    suspend fun post(payload: PlayLogPayload) {
        client.post("${AppConfig.BASE_API_URL}/playlog") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    }
}
