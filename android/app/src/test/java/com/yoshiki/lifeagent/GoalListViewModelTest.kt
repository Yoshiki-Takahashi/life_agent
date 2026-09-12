package com.yoshiki.lifeagent

import com.yoshiki.lifeagent.data.Goal
import com.yoshiki.lifeagent.data.GoalPlan
import com.yoshiki.lifeagent.data.GoalRepository
import com.yoshiki.lifeagent.data.GoalSummary
import com.yoshiki.lifeagent.ui.GoalListViewModel
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
class GoalListViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadGoalsShowsRepositoryItems() = runTest(dispatcher) {
        val viewModel = GoalListViewModel(ListRepository(listOf(summary("goal-1"))))

        viewModel.loadGoals()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("goal-1", viewModel.uiState.value.goals.single().id)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun emptyListIsAValidLoadedState() = runTest(dispatcher) {
        val viewModel = GoalListViewModel(ListRepository(emptyList()))

        viewModel.loadGoals()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList<GoalSummary>(), viewModel.uiState.value.goals)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun failureKeepsExistingItemsAndCanRetry() = runTest(dispatcher) {
        val repository = ListRepository(listOf(summary("goal-1")))
        val viewModel = GoalListViewModel(repository)
        viewModel.loadGoals()
        dispatcher.scheduler.advanceUntilIdle()
        repository.fails = true

        viewModel.loadGoals()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.goals.size)
        assertNotNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoading)
    }
}

private class ListRepository(private val goals: List<GoalSummary>) : GoalRepository {
    var fails = false
    override suspend fun listGoals(): List<GoalSummary> {
        if (fails) error("network error")
        return goals
    }
    override suspend fun previewGoal(title: String, description: String?, targetDate: String): GoalPlan = error("unused")
    override suspend fun confirmGoal(plan: GoalPlan): Goal = error("unused")
    override suspend fun getGoal(goalId: String): Goal = error("unused")
}

private fun summary(id: String) = GoalSummary(
    id = id,
    title = "読書する",
    targetDate = "2026-12-31",
    status = "active",
    metricCount = 1,
    milestoneCount = 3,
    createdAt = "2026-09-12T00:00:00Z",
    updatedAt = "2026-09-12T00:00:00Z",
)
