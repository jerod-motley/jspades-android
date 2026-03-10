package jmotley.com.jspades.networking

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import jmotley.com.jspades.data.AppConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.UUID

// ── Data models ────────────────────────────────────────────────────────────────

@Serializable
data class Suggestion(
    val sk: String = "",
    val description: String = "",
    val user: String = "",
    val createDate: String = "",
    val likes: Int = 0,
    val flags: Int = 0
)

@Serializable
data class Message(
    val sk: String = "",
    val author: String = "",
    val text: String = "",
    val parentPk: String = AppConfig.PARTITION_KEY,
    val parentSk: String = "",
    val createDate: String = "",
    val metadata: String? = null
)

@Serializable
data class RenegeJoke(
    val sk: String = "",
    val text: String = "",
    val user: String = "",
    val createDate: String = "",
    val likes: Int = 0
)

// ── API ────────────────────────────────────────────────────────────────────────

object OnlineApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val client get() = NetworkClient.client

    /** Parses a response body that may be a bare JSON array or `{ "items": [...] }`. */
    private inline fun <reified T> parseList(body: String): List<T> {
        val el = json.parseToJsonElement(body)
        val arr = when (el) {
            is JsonArray  -> el
            is JsonObject -> el["items"] as? JsonArray ?: return emptyList()
            else          -> return emptyList()
        }
        return json.decodeFromJsonElement(arr)
    }

    private fun idempotencyKey() = UUID.randomUUID().toString()

    // ── Suggestions ────────────────────────────────────────────────────────────

    suspend fun getSuggestions(): List<Suggestion> {
        val body = client.get("${AppConfig.BASE_API_URL}/suggestions") {
            parameter("pk", AppConfig.PARTITION_KEY)
        }.bodyAsText()
        return parseList(body)
    }

    suspend fun createSuggestion(suggestion: Suggestion) {
        client.post("${AppConfig.BASE_API_URL}/suggestions") {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", idempotencyKey())
            setBody(suggestion)
        }
    }

    suspend fun likeSuggestion(suggestion: Suggestion, userId: String) {
        client.put("${AppConfig.BASE_API_URL}/suggestions") {
            parameter("pk", AppConfig.PARTITION_KEY)
            parameter("id", userId)
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", idempotencyKey())
            setBody(suggestion.copy(likes = suggestion.likes + 1))
        }
    }

    // ── Messages ───────────────────────────────────────────────────────────────

    suspend fun getMessages(): List<Message> {
        val body = client.get("${AppConfig.BASE_API_URL}/message") {
            parameter("pk", AppConfig.PARTITION_KEY)
        }.bodyAsText()
        return parseList(body)
    }

    suspend fun createMessage(message: Message) {
        client.post("${AppConfig.BASE_API_URL}/message") {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", idempotencyKey())
            setBody(message)
        }
    }

    // ── Renege Jokes ───────────────────────────────────────────────────────────

    suspend fun getRenegeJokes(): List<RenegeJoke> {
        val body = client.get("${AppConfig.BASE_API_URL}/renegejokes") {
            parameter("pk", AppConfig.PARTITION_KEY)
        }.bodyAsText()
        return parseList(body)
    }

    suspend fun createRenegeJoke(joke: RenegeJoke) {
        client.post("${AppConfig.BASE_API_URL}/renegejokes") {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", idempotencyKey())
            setBody(joke)
        }
    }

    suspend fun likeRenegeJoke(joke: RenegeJoke, userId: String) {
        client.put("${AppConfig.BASE_API_URL}/renegejokes") {
            parameter("pk", AppConfig.PARTITION_KEY)
            parameter("id", userId)
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", idempotencyKey())
            setBody(joke.copy(likes = joke.likes + 1))
        }
    }
}
