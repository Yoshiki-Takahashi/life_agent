package com.yoshiki.lifeagent

import com.yoshiki.lifeagent.data.Goal
import com.yoshiki.lifeagent.data.GoalPlan
import com.yoshiki.lifeagent.data.GoalRepository
import com.yoshiki.lifeagent.data.Metric
import com.yoshiki.lifeagent.data.Milestone
import com.yoshiki.lifeagent.ui.GoalViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
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
import org.junit.Assert.assertTrue
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
        viewModel.updateTargetDate(futureDate())

        viewModel.previewGoal()

        assertNotNull(viewModel.uiState.value.formError)
        assertEquals(0, repository.previewCalls)
    }

    @Test
    fun invalidTargetDateIsRejectedWithoutApiCall() {
        val repository = FakeGoalRepository()
        val viewModel = GoalViewModel(repository)
        viewModel.updateTitle("読書する")
        viewModel.updateTargetDate("2026-99-99")

        viewModel.previewGoal()

        assertNotNull(viewModel.uiState.value.formError)
        assertEquals(0, repository.previewCalls)
    }

    @Test
    fun successfulPreviewProvidesEditablePlanAndNavigation() = runTest(dispatcher) {
        val viewModel = preparedViewModel(FakeGoalRepository())

        viewModel.previewGoal()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.navigateToPreview)
        assertEquals("読了冊数", state.metrics.single().name)
        assertEquals(3, state.milestones.size)
        assertFalse(state.isPreviewing)
    }

    @Test
    fun editedPlanIsConfirmedAndProvidesNavigationId() = runTest(dispatcher) {
        val repository = FakeGoalRepository()
        val viewModel = preparedViewModel(repository)
        viewModel.previewGoal()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.updateMetricName(0, "読書数")
        viewModel.updateMetricValue(0, "8")
        viewModel.updateMilestoneTitle(0, "候補を選ぶ")

        viewModel.confirmGoal()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("goal-id", viewModel.uiState.value.navigationGoalId)
        assertEquals("読書数", repository.confirmedPlan?.metrics?.single()?.name)
        assertEquals(8.0, repository.confirmedPlan?.metrics?.single()?.targetValue)
        assertEquals("候補を選ぶ", repository.confirmedPlan?.milestones?.first()?.title)
    }

    @Test
    fun previewFailureKeepsInputAndShowsError() = runTest(dispatcher) {
        val viewModel = preparedViewModel(FakeGoalRepository(previewFails = true))

        viewModel.previewGoal()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("読書する", viewModel.uiState.value.title)
        assertNotNull(viewModel.uiState.value.formError)
        assertFalse(viewModel.uiState.value.isPreviewing)
    }

    @Test
    fun confirmFailureKeepsEditedPlanForRetry() = runTest(dispatcher) {
        val viewModel = preparedViewModel(FakeGoalRepository(confirmFails = true))
        viewModel.previewGoal()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.updateMetricName(0, "編集したMetric")

        viewModel.confirmGoal()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("編集したMetric", viewModel.uiState.value.metrics.single().name)
        assertNotNull(viewModel.uiState.value.planError)
        assertFalse(viewModel.uiState.value.isConfirming)
    }

    @Test
    fun invalidMetricValueIsRejectedWithoutConfirmCall() = runTest(dispatcher) {
        val repository = FakeGoalRepository()
        val viewModel = preparedViewModel(repository)
        viewModel.previewGoal()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.updateMetricValue(0, "0")

        viewModel.confirmGoal()

        assertNotNull(viewModel.uiState.value.planError)
        assertEquals(0, repository.confirmCalls)
    }

    private fun preparedViewModel(repository: FakeGoalRepository): GoalViewModel =
        GoalViewModel(repository).also {
            it.updateTitle("読書する")
            it.updateDescription("毎日読む")
            it.updateTargetDate(futureDate())
        }
}

private class FakeGoalRepository(
    private val previewFails: Boolean = false,
    private val confirmFails: Boolean = false,
) : GoalRepository {
    var previewCalls = 0
    var confirmCalls = 0
    var confirmedPlan: GoalPlan? = null

    override suspend fun previewGoal(
        title: String,
        description: String?,
        targetDate: String,
    ): GoalPlan {
        previewCalls++
        if (previewFails) error("network error")
        return GoalPlan(
            title = title,
            description = description,
            targetDate = targetDate,
            metrics = listOf(Metric("読了冊数", 12.0, "冊")),
            milestones = listOf(
                Milestone("読む本を決める", targetDate, 0),
                Milestone("半分読む", targetDate, 1),
                Milestone("読み終える", targetDate, 2),
            ),
        )
    }

    override suspend fun confirmGoal(plan: GoalPlan): Goal {
        confirmCalls++
        confirmedPlan = plan
        if (confirmFails) error("network error")
        return goal(plan)
    }

    override suspend fun getGoal(goalId: String): Goal = goal(
        GoalPlan(
            title = "読書する",
            description = null,
            targetDate = futureDate(),
            metrics = listOf(Metric("読了冊数", 12.0, "冊")),
            milestones = listOf(Milestone("読み終える", futureDate())),
        )
    )

    private fun goal(plan: GoalPlan) = Goal(
        id = "goal-id",
        title = plan.title,
        description = plan.description,
        targetDate = plan.targetDate,
        status = "active",
        createdAt = "2026-08-30T00:00:00Z",
        metrics = plan.metrics,
        milestones = plan.milestones,
    )
}

private fun futureDate(): String {
    val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 30) }
    return SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(calendar.time)
}
