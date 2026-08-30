package com.yoshiki.lifeagent.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface GoalApi {
    @POST("api/v1/goals")
    suspend fun createGoal(@Body request: CreateGoalRequest): GoalResponse

    @GET("api/v1/goals/{goalId}")
    suspend fun getGoal(@Path("goalId") goalId: String): GoalResponse
}

@Serializable
data class CreateGoalRequest(val title: String, val description: String? = null)

@Serializable
data class GoalResponse(
    val id: String,
    val title: String,
    val description: String? = null,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
) {
    fun toGoal() = Goal(id, title, description, status, createdAt)
}
