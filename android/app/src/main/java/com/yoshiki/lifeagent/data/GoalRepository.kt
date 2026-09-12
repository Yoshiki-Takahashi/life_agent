package com.yoshiki.lifeagent.data

interface GoalRepository {
    suspend fun listGoals(): List<GoalSummary>
    suspend fun previewGoal(title: String, description: String?, targetDate: String): GoalPlan
    suspend fun confirmGoal(plan: GoalPlan): Goal
    suspend fun getGoal(goalId: String): Goal
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
}
