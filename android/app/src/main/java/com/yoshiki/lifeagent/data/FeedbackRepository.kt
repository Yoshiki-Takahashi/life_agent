package com.yoshiki.lifeagent.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

@Serializable
data class FeedbackRequest(
    @SerialName("client_request_id") val clientRequestId: String,
    val body: String,
    @SerialName("app_version") val appVersion: String,
    val screen: String,
)

@Serializable
data class FeedbackResult(
    @SerialName("issue_number") val issueNumber: Int,
    @SerialName("issue_url") val issueUrl: String,
)

fun interface FeedbackRepository {
    suspend fun send(request: FeedbackRequest): FeedbackResult
}

interface FeedbackApi {
    @POST("api/v1/feedback")
    suspend fun send(
        @Header("Authorization") authorization: String,
        @Body request: FeedbackRequest,
    ): FeedbackResult
}

class HttpFeedbackRepository(
    private val api: FeedbackApi,
    private val tokenProvider: suspend () -> String,
) : FeedbackRepository {
    override suspend fun send(request: FeedbackRequest) = api.send("Bearer ${tokenProvider()}", request)
}
