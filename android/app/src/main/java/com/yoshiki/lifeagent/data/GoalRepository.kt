package com.yoshiki.lifeagent.data

interface GoalRepository {
    suspend fun listGoals(): List<GoalSummary>
    suspend fun previewGoal(title: String, description: String?, targetDate: String): GoalPlan
    suspend fun confirmGoal(plan: GoalPlan): Goal
    suspend fun getGoal(goalId: String): Goal
    suspend fun previewProgress(goalId: String, body: String): ProgressPreview
    suspend fun getAdvice(goalId: String): Advice
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
    private suspend fun authorization() = "Bearer ${tokenProvider()}"

    override suspend fun listGoals(): List<GoalSummary> =
        api.listGoals(authorization()).map(GoalSummaryResponse::toGoalSummary)

    override suspend fun previewGoal(
        title: String,
        description: String?,
        targetDate: String,
    ): GoalPlan = api.previewGoal(
        authorization(), GoalPreviewRequest(title, description, targetDate)
    ).toGoalPlan()

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
