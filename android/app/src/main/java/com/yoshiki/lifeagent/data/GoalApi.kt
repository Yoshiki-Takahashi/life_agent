package com.yoshiki.lifeagent.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.UUID

interface GoalApi {
    @GET("api/v1/goals")
    suspend fun listGoals(
        @Header("Authorization") authorization: String,
    ): List<GoalSummaryResponse>

    @POST("api/v1/goals/preview")
    suspend fun previewGoal(
        @Header("Authorization") authorization: String,
        @Body request: GoalPreviewRequest,
    ): GoalPlanResponse

    @POST("api/v1/goals/confirm")
    suspend fun confirmGoal(
        @Header("Authorization") authorization: String,
        @Body request: GoalConfirmRequest,
    ): GoalResponse

    @GET("api/v1/goals/{goalId}")
    suspend fun getGoal(
        @Header("Authorization") authorization: String,
        @Path("goalId") goalId: String,
    ): GoalResponse

    @POST("api/v1/goals/{goalId}/progress")
    suspend fun recordProgress(
        @Header("Authorization") authorization: String,
        @Path("goalId") goalId: String,
        @Body request: ProgressLogCreateRequest,
    ): GoalResponse
}

@Serializable
data class GoalSummaryResponse(
    val id: String,
    val title: String,
    @SerialName("target_date") val targetDate: String,
    val status: String,
    @SerialName("metric_count") val metricCount: Int,
    @SerialName("milestone_count") val milestoneCount: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
) {
    fun toGoalSummary() = GoalSummary(
        id = id,
        title = title,
        targetDate = targetDate,
        status = status,
        metricCount = metricCount,
        milestoneCount = milestoneCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
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
    @SerialName("progress_logs") val progressLogs: List<ProgressLogResponse> = emptyList(),
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
        updatedAt = updatedAt,
        progressLogs = progressLogs.map { it.toProgressLog() },
    )
}

@Serializable
data class MetricResponse(
    val id: String,
    val name: String,
    @SerialName("target_value") val targetValue: Double,
    @SerialName("current_value") val currentValue: Double = 0.0,
    val unit: String,
    val position: Int,
    @SerialName("created_at") val createdAt: String,
) {
    fun toMetric() = Metric(name, targetValue, unit, position, id, currentValue)
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

@Serializable
data class ProgressLogCreateRequest(
    val body: String,
    @SerialName("client_request_id") val clientRequestId: String = UUID.randomUUID().toString(),
    @SerialName("metric_updates") val metricUpdates: List<ProgressMetricUpdatePayload>,
)

@Serializable
data class ProgressMetricUpdatePayload(
    @SerialName("metric_id") val metricId: String,
    val value: Double,
)

@Serializable
data class ProgressLogResponse(
    val id: String,
    val body: String,
    @SerialName("recorded_at") val recordedAt: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("metric_updates") val metricUpdates: List<ProgressMetricUpdateResponse>,
) {
    fun toProgressLog() = ProgressLog(
        id = id,
        body = body,
        recordedAt = recordedAt,
        createdAt = createdAt,
        metricUpdates = metricUpdates.map { it.toProgressMetricUpdate() },
    )
}

@Serializable
data class ProgressMetricUpdateResponse(
    @SerialName("metric_id") val metricId: String,
    @SerialName("metric_name") val metricName: String,
    val value: Double,
    val unit: String,
) {
    fun toProgressMetricUpdate() = ProgressMetricUpdate(metricId, metricName, value, unit)
}

private fun MetricPayload.toMetric(position: Int) = Metric(name, targetValue, unit, position)

private fun MilestonePayload.toMilestone(position: Int) = Milestone(title, targetDate, position)
