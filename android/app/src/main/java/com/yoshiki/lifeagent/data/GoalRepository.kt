package com.yoshiki.lifeagent.data

interface GoalRepository {
    suspend fun createGoal(title: String, description: String?): Goal
    suspend fun getGoal(goalId: String): Goal
}

class HttpGoalRepository(private val api: GoalApi) : GoalRepository {
    override suspend fun createGoal(title: String, description: String?): Goal =
        api.createGoal(CreateGoalRequest(title, description)).toGoal()

    override suspend fun getGoal(goalId: String): Goal = api.getGoal(goalId).toGoal()
}
