package com.yoshiki.lifeagent.data

import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface GoalRepository {
    suspend fun coachMessage(body: String, history: List<CoachMessage>): CoachReply
    suspend fun listGoals(): List<GoalSummary>
    suspend fun goalQuestions(title: String, description: String?, targetDate: String): GoalQuestions
    suspend fun previewGoal(title: String, description: String?, targetDate: String,
        answers: List<GoalAnswer> = emptyList()): GoalPlan
    suspend fun confirmGoal(plan: GoalPlan): Goal
    suspend fun completeGoal(goalId: String): Goal = error("Completion is not supported")
    suspend fun getGoal(goalId: String): Goal
    suspend fun previewProgress(goalId: String, body: String): ProgressPreview
    suspend fun getAdvice(goalId: String): Advice
    suspend fun previewReplan(goalId: String, reason: String): ReplanProposal
    suspend fun applyReplan(goalId: String, proposalId: String): Goal
    suspend fun recordProgress(
        goalId: String,
        body: String,
        metricUpdates: List<MetricProgressInput>,
        clientRequestId: String,
    ): Goal
}

class HttpGoalRepository(
    private val api: GoalApi,
    private val tokenProvider: suspend () -> String,
) : GoalRepository {
    override suspend fun coachMessage(body: String, history: List<CoachMessage>): CoachReply =
        api.coachMessage(authorization(), CoachMessageRequest(body, history))

    private suspend fun authorization() = "Bearer ${tokenProvider()}"

    override suspend fun completeGoal(goalId: String): Goal =
        api.completeGoal(authorization(), goalId).toGoal()

    override suspend fun listGoals(): List<GoalSummary> =
        api.listGoals(authorization()).map(GoalSummaryResponse::toGoalSummary)

    override suspend fun previewGoal(
        title: String,
        description: String?,
        targetDate: String,
        answers: List<GoalAnswer>,
    ): GoalPlan = api.previewGoal(
        authorization(), GoalPreviewRequest(title, description, targetDate, answers)
    ).toGoalPlan()

    override suspend fun goalQuestions(title: String, description: String?, targetDate: String): GoalQuestions =
        api.goalQuestions(authorization(), GoalQuestionsRequest(title, description, targetDate))

    override suspend fun confirmGoal(plan: GoalPlan): Goal = api.confirmGoal(
        authorization(),
        GoalConfirmRequest(
            title = plan.title,
            description = plan.description,
            targetDate = plan.targetDate,
            metrics = plan.metrics.map { MetricPayload(it.name, it.targetValue, it.unit) },
            milestones = plan.milestones.map { MilestonePayload(it.title, it.targetDate) },
        )
    ).toGoal()

    override suspend fun getGoal(goalId: String): Goal = api.getGoal(authorization(), goalId).toGoal()

    override suspend fun previewProgress(goalId: String, body: String): ProgressPreview =
        api.previewProgress(authorization(), goalId, ProgressPreviewRequest(body))

    override suspend fun getAdvice(goalId: String): Advice = api.getAdvice(authorization(), goalId)

    override suspend fun previewReplan(goalId: String, reason: String): ReplanProposal =
        api.previewReplan(authorization(), goalId, ReplanPreviewRequest(reason)).toReplanProposal()

    override suspend fun applyReplan(goalId: String, proposalId: String): Goal =
        api.applyReplan(authorization(), goalId, ReplanApplyRequest(proposalId)).toGoal()

    override suspend fun recordProgress(
        goalId: String,
        body: String,
        metricUpdates: List<MetricProgressInput>,
        clientRequestId: String,
    ): Goal = api.recordProgress(
        authorization(),
        goalId,
        ProgressLogCreateRequest(
            body = body,
            clientRequestId = clientRequestId,
            metricUpdates = metricUpdates.map { ProgressMetricUpdatePayload(it.metricId, it.value) },
        ),
    ).toGoal()
}

interface GoalApi {
    @POST("api/v1/goals/{goalId}/complete")
    suspend fun completeGoal(@Header("Authorization") authorization: String, @Path("goalId") goalId: String): GoalResponse

    @POST("api/v1/goals/questions")
    suspend fun goalQuestions(
        @Header("Authorization") authorization: String,
        @Body request: GoalQuestionsRequest,
    ): GoalQuestions

    @POST("api/v1/coach/messages")
    suspend fun coachMessage(
        @Header("Authorization") authorization: String,
        @Body request: CoachMessageRequest,
    ): CoachReply

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

    @POST("api/v1/goals/{goalId}/progress/preview")
    suspend fun previewProgress(
        @Header("Authorization") authorization: String,
        @Path("goalId") goalId: String,
        @Body request: ProgressPreviewRequest,
    ): ProgressPreview

    @POST("api/v1/goals/{goalId}/advice")
    suspend fun getAdvice(
        @Header("Authorization") authorization: String,
        @Path("goalId") goalId: String,
    ): Advice

    @POST("api/v1/goals/{goalId}/progress")
    suspend fun recordProgress(
        @Header("Authorization") authorization: String,
        @Path("goalId") goalId: String,
        @Body request: ProgressLogCreateRequest,
    ): GoalResponse

    @POST("api/v1/goals/{goalId}/replan/preview")
    suspend fun previewReplan(
        @Header("Authorization") authorization: String,
        @Path("goalId") goalId: String,
        @Body request: ReplanPreviewRequest,
    ): ReplanProposalResponse

    @POST("api/v1/goals/{goalId}/replan/apply")
    suspend fun applyReplan(
        @Header("Authorization") authorization: String,
        @Path("goalId") goalId: String,
        @Body request: ReplanApplyRequest,
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
data class GoalQuestionsRequest(
    val title: String,
    val description: String? = null,
    @SerialName("target_date") val targetDate: String,
)

@Serializable
data class GoalQuestions(val questions: List<String>)

@Serializable
data class GoalAnswer(val question: String, val answer: Boolean)

@Serializable
data class GoalPreviewRequest(
    val title: String,
    val description: String? = null,
    @SerialName("target_date") val targetDate: String,
    val answers: List<GoalAnswer> = emptyList(),
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
    @SerialName("plan_revision") val planRevision: Int = 1,
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
        planRevision = planRevision,
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
    @SerialName("client_request_id") val clientRequestId: String,
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

@Serializable
data class ProgressPreviewRequest(val body: String)

@Serializable
data class ProgressPreview(
    @SerialName("metric_updates") val metricUpdates: List<ProgressMetricUpdatePayload>,
    val warnings: List<String>,
)

@Serializable
data class Advice(
    val summary: String,
    @SerialName("next_actions") val nextActions: List<String>,
)

@Serializable
data class ReplanPreviewRequest(val reason: String)

@Serializable
data class ReplanApplyRequest(@SerialName("proposal_id") val proposalId: String)

@Serializable
data class ReplanProposalResponse(
    @SerialName("proposal_id") val proposalId: String,
    @SerialName("base_plan_revision") val basePlanRevision: Int,
    val reason: String,
    @SerialName("proposed_plan") val proposedPlan: GoalPlanResponse,
    val diff: List<ReplanDiffItemResponse>,
    @SerialName("created_at") val createdAt: String,
) {
    fun toReplanProposal() = ReplanProposal(
        proposalId = proposalId,
        basePlanRevision = basePlanRevision,
        reason = reason,
        proposedPlan = proposedPlan.toGoalPlan(),
        diff = diff.map { it.toReplanDiffItem() },
        createdAt = createdAt,
    )
}

@Serializable
data class ReplanDiffItemResponse(
    @SerialName("change_type") val changeType: String,
    @SerialName("target_type") val targetType: String,
    @SerialName("target_label") val targetLabel: String,
    val before: String? = null,
    val after: String? = null,
    val rationale: String,
) {
    fun toReplanDiffItem() = ReplanDiffItem(
        changeType = changeType,
        targetType = targetType,
        targetLabel = targetLabel,
        before = before,
        after = after,
        rationale = rationale,
    )
}

@Serializable
data class CoachMessage(val role: String, val content: String)

@Serializable
data class CoachMessageRequest(val body: String, val history: List<CoachMessage>)

@Serializable
data class CoachReply(
    val reply: String,
    val intent: String,
    @SerialName("goal_id") val goalId: String? = null,
    @SerialName("record_body") val recordBody: String? = null,
)

object Network {
    fun createFeedbackRepository(
        baseUrl: String,
        tokenProvider: suspend () -> String,
    ): FeedbackRepository = HttpFeedbackRepository(
        retrofit(baseUrl).create(FeedbackApi::class.java), tokenProvider,
    )

    fun createGoalRepository(
        baseUrl: String,
        tokenProvider: suspend () -> String,
    ): GoalRepository {
        return HttpGoalRepository(retrofit(baseUrl).create(GoalApi::class.java), tokenProvider)
    }

    private fun retrofit(baseUrl: String): Retrofit {
        val json = Json { ignoreUnknownKeys = true }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(OkHttpClient.Builder().readTimeout(35, TimeUnit.SECONDS).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }
}
