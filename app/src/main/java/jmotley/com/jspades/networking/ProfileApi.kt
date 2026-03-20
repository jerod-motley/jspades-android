package jmotley.com.jspades.networking

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import jmotley.com.jspades.data.AchievementItem
import jmotley.com.jspades.data.AppConfig
import jmotley.com.jspades.data.CompletedChallengeItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ProfileResponse(
    val verified: Boolean? = false,
    val stats: Map<String, Int>? = null,
    val achievements: List<AchievementItem>? = null,
    val challenges: List<CompletedChallengeItem>? = null,
    val updated_at: String? = null
)

@Serializable
private data class RegisterRequest(
    val pk: String,
    val sk: String,
    val userName: String
)

@Serializable
private data class UpdateProfileRequest(
    val pk: String,
    val sk: String,
    val userName: String,
    val stats: Map<String, Int>,
    val achievements: List<AchievementItem>,
    val challenges: List<CompletedChallengeItem>
)

object ProfileApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val client get() = NetworkClient.client

    suspend fun register(email: String, userName: String) {
        client.post("${AppConfig.BASE_API_URL}/profile") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(pk = AppConfig.PARTITION_KEY, sk = email, userName = userName))
        }
    }

    suspend fun fetch(email: String): ProfileResponse {
        val response = client.get("${AppConfig.BASE_API_URL}/profile") {
            parameter("pk", AppConfig.PARTITION_KEY)
            parameter("sk", email)
        }
        return json.decodeFromString<ProfileResponse>(response.bodyAsText())
    }

    suspend fun updateProfile(
        email: String,
        userName: String,
        stats: Map<String, Int>,
        achievements: List<AchievementItem>,
        challenges: List<CompletedChallengeItem>
    ) {
        client.put("${AppConfig.BASE_API_URL}/profile") {
            contentType(ContentType.Application.Json)
            setBody(UpdateProfileRequest(
                pk = AppConfig.PARTITION_KEY,
                sk = email,
                userName = userName,
                stats = stats,
                achievements = achievements,
                challenges = challenges
            ))
        }
    }
}
