package com.yoshiki.lifeagent

import com.yoshiki.lifeagent.data.Goal
import com.yoshiki.lifeagent.data.GoalPlan
import com.yoshiki.lifeagent.data.GoalRepository
import com.yoshiki.lifeagent.data.GoalSummary
import com.yoshiki.lifeagent.data.Metric
import com.yoshiki.lifeagent.data.MetricProgressInput
import com.yoshiki.lifeagent.data.Milestone
import com.yoshiki.lifeagent.data.ProgressLog
import com.yoshiki.lifeagent.data.ProgressMetricUpdate
import com.yoshiki.lifeagent.ui.GoalDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoalDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadGoalBuildsProgressInputsFromMetrics() = runTest(dispatcher) {
        val viewModel = GoalDetailViewModel(FakeDetailRepository())

        viewModel.loadGoal("goal-1")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("goal-1", viewModel.uiState.value.goal?.id)
        assertEquals("読了冊数", viewModel.uiState.value.metricProgress.single().name)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun blankProgressBodyIsRejectedWithoutApiCall() = runTest(dispatcher) {
        val repository = FakeDetailRepository()
        val viewModel = GoalDetailViewModel(repository)
        viewModel.loadGoal("goal-1")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.updateMetricProgress(0, "1")

        viewModel.saveProgress()

        assertNotNull(viewModel.uiState.value.progressError)
        assertEquals(0, repository.recordProgressCalls)
    }

    @Test
    fun saveProgressUpdatesGoalAndClearsInputs() = runTest(dispatcher) {
        val repository = FakeDetailRepository()
        val viewModel = GoalDetailViewModel(repository)
        viewModel.loadGoal("goal-1")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.updateProgressBody("1冊読んだ")
        viewModel.updateMetricProgress(0, "1")

        viewModel.saveProgress()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repository.recordProgressCalls)
        assertEquals("1冊読んだ", repository.lastBody)
        assertEquals(MetricProgressInput("metric-1", 1.0), repository.lastUpdates.single())
        assertEquals(1.0, viewModel.uiState.value.goal?.metrics?.single()?.currentValue)
        assertEquals("", viewModel.uiState.value.progressBody)
        assertEquals("", viewModel.uiState.value.metricProgress.single().value)
    }
}

private class FakeDetailRepository : GoalRepository {
    var recordProgressCalls = 0
    var lastBody: String? = null
    var lastUpdates: List<MetricProgressInput> = emptyList()

    override suspend fun listGoals(): List<GoalSummary> = emptyList()
    override suspend fun previewGoal(
        title: String,
        description: String?,
        targetDate: String,
    ): GoalPlan = error("unused")
    override suspend fun confirmGoal(plan: GoalPlan): Goal = error("unused")
    override suspend fun getGoal(goalId: String): Goal = goal(currentValue = 0.0)

    override suspend fun recordProgress(
        goalId: String,
        body: String,
        metricUpdates: List<MetricProgressInput>,
    ): Goal {
        recordProgressCalls++
        lastBody = body
        lastUpdates = metricUpdates
        return goal(currentValue = metricUpdates.single().value)
    }

    private fun goal(currentValue: Double) = Goal(
        id = "goal-1",
        title = "読書する",
        description = "毎日読む",
        targetDate = "2026-12-31",
        status = "active",
        createdAt = "2026-10-01T00:00:00Z",
        metrics = listOf(
            Metric(
                name = "読了冊数",
                targetValue = 12.0,
                unit = "冊",
                position = 0,
                id = "metric-1",
                currentValue = currentValue,
            )
        ),
        milestones = listOf(Milestone("読み終える", "2026-12-31")),
        progressLogs = if (currentValue > 0) {
            listOf(
                ProgressLog(
                    id = "progress-1",
                    body = "1冊読んだ",
                    recordedAt = "2026-10-02T00:00:00Z",
                    createdAt = "2026-10-02T00:00:00Z",
                    metricUpdates = listOf(
                        ProgressMetricUpdate("metric-1", "読了冊数", currentValue, "冊")
                    ),
                )
            )
        } else {
            emptyList()
        },
    )
}
