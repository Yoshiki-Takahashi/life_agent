package com.yoshiki.lifeagent.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface GoalApi {
    @POST("api/v1/goals/preview")
    suspend fun previewGoal(@Body request: GoalPreviewRequest): GoalPlanResponse

    @POST("api/v1/goals/confirm")
    suspend fun confirmGoal(@Body request: GoalConfirmRequest): GoalResponse

    @GET("api/v1/goals/{goalId}")
    suspend fun getGoal(@Path("goalId") goalId: String): GoalResponse
}

@Serializable
data class GoalPreviewRequest(
    val title: String,
    val description: String? = null,
    @SerialName("target_date") val targetDate: String,
)

@Serializable
data class GoalConfirmRequest(
    val title: String,
    val description: String? = null,
    @SerialName("target_date") val targetDate: String,
    val metrics: List<MetricPayload>,
    val milestones: List<MilestonePayload>,
)

@Serializable
data class MetricPayload(
    val name: String,
    @SerialName("target_value") val targetValue: Double,
    val unit: String,
)

@Serializable
data class MilestonePayload(
    val title: String,
    @SerialName("target_date") val targetDate: String,
)

@Serializable
data class GoalPlanResponse(
    val title: String,
    val description: String? = null,
    @SerialName("target_date") val targetDate: String,
    val metrics: List<MetricPayload>,
    val milestones: List<MilestonePayload>,
) {
    fun toGoalPlan() = GoalPlan(
        title = title,
        description = description,
        targetDate = targetDate,
        metrics = metrics.mapIndexed { index, item -> item.toMetric(index) },
        milestones = milestones.mapIndexed { index, item -> item.toMilestone(index) },
    )
}

@Serializable
data class GoalResponse(
    val id: String,
    val title: String,
    val description: String? = null,
    @SerialName("target_date") val targetDate: String,
    val status: String,
    val metrics: List<MetricResponse>,
    val milestones: List<MilestoneResponse>,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
) {
    fun toGoal() = Goal(
        id = id,
        title = title,
        description = description,
        targetDate = targetDate,
        status = status,
        createdAt = createdAt,
        metrics = metrics.map { it.toMetric() },
        milestones = milestones.map { it.toMilestone() },
    )
}

@Serializable
data class MetricResponse(
    val id: String,
    val name: String,
    @SerialName("target_value") val targetValue: Double,
    val unit: String,
    val position: Int,
    @SerialName("created_at") val createdAt: String,
) {
    fun toMetric() = Metric(name, targetValue, unit, position)
}

@Serializable
data class MilestoneResponse(
    val id: String,
    val title: String,
    @SerialName("target_date") val targetDate: String,
    val position: Int,
    @SerialName("created_at") val createdAt: String,
) {
    fun toMilestone() = Milestone(title, targetDate, position)
}

private fun MetricPayload.toMetric(position: Int) = Metric(name, targetValue, unit, position)

private fun MilestonePayload.toMilestone(position: Int) = Milestone(title, targetDate, position)
