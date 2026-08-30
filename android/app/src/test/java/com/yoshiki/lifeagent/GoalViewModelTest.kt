package com.yoshiki.lifeagent

import com.yoshiki.lifeagent.data.Goal
import com.yoshiki.lifeagent.data.GoalRepository
import com.yoshiki.lifeagent.ui.GoalViewModel
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
class GoalViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun blankTitleIsRejectedWithoutApiCall() {
        val repository = FakeGoalRepository()
        val viewModel = GoalViewModel(repository)
        viewModel.updateTitle("   ")
        viewModel.createGoal()
        assertNotNull(viewModel.uiState.value.formError)
        assertEquals(0, repository.createCalls)
    }

    @Test
    fun longTitleIsRejectedWithoutApiCall() {
        val repository = FakeGoalRepository()
        val viewModel = GoalViewModel(repository)
        viewModel.updateTitle("x".repeat(121))
        viewModel.createGoal()
        assertNotNull(viewModel.uiState.value.formError)
        assertEquals(0, repository.createCalls)
    }

    @Test
    fun successfulCreateProvidesNavigationId() = runTest(dispatcher) {
        val viewModel = GoalViewModel(FakeGoalRepository())
        viewModel.updateTitle("読書する")
        viewModel.updateDescription("毎日読む")
        viewModel.createGoal()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("goal-id", viewModel.uiState.value.navigationGoalId)
        assertFalse(viewModel.uiState.value.isSaving)
    }

    @Test
    fun apiFailureKeepsInputAndShowsError() = runTest(dispatcher) {
        val viewModel = GoalViewModel(FakeGoalRepository(shouldFail = true))
        viewModel.updateTitle("読書する")
        viewModel.createGoal()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("読書する", viewModel.uiState.value.title)
        assertNotNull(viewModel.uiState.value.formError)
    }
}

private class FakeGoalRepository(private val shouldFail: Boolean = false) : GoalRepository {
    var createCalls = 0

    override suspend fun createGoal(title: String, description: String?): Goal {
        createCalls++
        if (shouldFail) error("network error")
        return goal(title, description)
    }

    override suspend fun getGoal(goalId: String): Goal = goal("読書する", null)

    private fun goal(title: String, description: String?) = Goal(
        id = "goal-id",
        title = title,
        description = description,
        status = "active",
        createdAt = "2026-08-30T00:00:00Z",
    )
}
