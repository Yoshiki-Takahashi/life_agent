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
import com.yoshiki.lifeagent.data.ReplanDiffItem
import com.yoshiki.lifeagent.data.ReplanProposal
import com.yoshiki.lifeagent.ui.GoalDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    @Test fun retryRetainsIdAndAdviceFailureDoesNotResave() = runTest(dispatcher) {
        val repo = FakeDetailRepository()
        val vm = GoalDetailViewModel(repo)
        vm.loadGoal("goal-1")
        dispatcher.scheduler.advanceUntilIdle()
        vm.updateProgressBody("1冊読んだ")
        vm.updateMetricProgress(0, "1")
        repo.failSave = true
        vm.saveProgress()
        vm.saveProgress()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, repo.recordProgressCalls)
        repo.failSave = false
        repo.failAdvice = true
        vm.saveProgress()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(repo.requestIds[0], repo.requestIds[1])
        assertNotNull(vm.uiState.value.adviceError)
        assertTrue(vm.uiState.value.progressSaved)
        repo.failAdvice = false
        vm.loadAdvice()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, repo.recordProgressCalls)
        assertNotNull(vm.uiState.value.advice)
    }

    @Test fun previewIsEditableAndBodyChangesInvalidateIt() = runTest(dispatcher) {
        val repo = FakeDetailRepository()
        val vm = GoalDetailViewModel(repo)
        vm.loadGoal("goal-1")
        dispatcher.scheduler.advanceUntilIdle()
        vm.updateProgressBody("2冊読んだ")
        vm.parseProgress()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("2", vm.uiState.value.metricProgress.single().value)
        assertEquals(0, repo.recordProgressCalls)
        vm.updateMetricProgress(0, "1")
        assertEquals("1", vm.uiState.value.metricProgress.single().value)
        vm.updateProgressBody("少し読んだ")
        assertFalse(vm.uiState.value.hasPreview)
        assertEquals("", vm.uiState.value.metricProgress.single().value)
        vm.parseProgress()
        vm.updateProgressBody("新しい本文")
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.hasPreview)
    }

    @Test fun invalidNumericInputIsNotSilentlyDropped() = runTest(dispatcher) {
        val repo = FakeDetailRepository()
        val vm = GoalDetailViewModel(repo)
        vm.loadGoal("goal-1")
        dispatcher.scheduler.advanceUntilIdle()
        vm.updateProgressBody("読んだ")
        for (value in listOf("NaN", "Infinity", "-1", "13", "0.001", "abc")) {
            vm.updateMetricProgress(0, value)
            vm.saveProgress()
            assertNotNull(vm.uiState.value.progressError)
        }
        assertEquals(0, repo.recordProgressCalls)
    }

    @Test fun replanPreviewDoesNotUpdateGoalUntilApplied() = runTest(dispatcher) {
        val repo = FakeDetailRepository()
        val vm = GoalDetailViewModel(repo)
        vm.loadGoal("goal-1")
        dispatcher.scheduler.advanceUntilIdle()

        vm.updateReplanReason("期限を調整したい")
        vm.previewReplan()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repo.previewReplanCalls)
        assertEquals("2026-12-31", vm.uiState.value.goal?.targetDate)
        assertNotNull(vm.uiState.value.replanProposal)
        assertFalse(vm.uiState.value.replanApplied)

        vm.applyReplan()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repo.applyReplanCalls)
        assertEquals("2027-01-31", vm.uiState.value.goal?.targetDate)
        assertTrue(vm.uiState.value.replanApplied)
        assertEquals(null, vm.uiState.value.replanProposal)
    }

    @Test fun cancelReplanDropsProposalWithoutApiCall() = runTest(dispatcher) {
        val repo = FakeDetailRepository()
        val vm = GoalDetailViewModel(repo)
        vm.loadGoal("goal-1")
        dispatcher.scheduler.advanceUntilIdle()
        vm.updateReplanReason("見直したい")
        vm.previewReplan()
        dispatcher.scheduler.advanceUntilIdle()

        vm.cancelReplan()
        vm.applyReplan()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, vm.uiState.value.replanProposal)
        assertEquals(0, repo.applyReplanCalls)
    }
}

private class FakeDetailRepository : GoalRepository {
    var failSave = false
    var failAdvice = false
    val requestIds = mutableListOf<String>()
    var recordProgressCalls = 0
    var previewReplanCalls = 0
    var applyReplanCalls = 0
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

    override suspend fun previewProgress(goalId: String, body: String): com.yoshiki.lifeagent.data.ProgressPreview =
        com.yoshiki.lifeagent.data.ProgressPreview(listOf(com.yoshiki.lifeagent.data.ProgressMetricUpdatePayload("metric-1", 2.0)), emptyList())
    override suspend fun getAdvice(goalId: String): com.yoshiki.lifeagent.data.Advice =
        if (failAdvice) error("unavailable") else com.yoshiki.lifeagent.data.Advice("保存済み進捗", listOf("次の行動"))
    override suspend fun previewReplan(goalId: String, reason: String): ReplanProposal {
        previewReplanCalls++
        return ReplanProposal(
            proposalId = "proposal-1",
            basePlanRevision = 1,
            reason = reason,
            proposedPlan = GoalPlan(
                title = "読書する",
                description = "毎日読む",
                targetDate = "2027-01-31",
                metrics = listOf(Metric("読了冊数", 10.0, "冊", 0)),
                milestones = listOf(
                    Milestone("前半を読む", "2026-12-15"),
                    Milestone("後半を読む", "2027-01-15"),
                    Milestone("読み終える", "2027-01-31"),
                ),
            ),
            diff = listOf(
                ReplanDiffItem(
                    changeType = "update",
                    targetType = "goal",
                    targetLabel = "Goal期限",
                    before = "2026-12-31",
                    after = "2027-01-31",
                    rationale = "予定に合わせます。",
                )
            ),
            createdAt = "2026-10-02T00:00:00Z",
        )
    }

    override suspend fun applyReplan(goalId: String, proposalId: String): Goal {
        applyReplanCalls++
        return goal(currentValue = 0.0).copy(
            targetDate = "2027-01-31",
            planRevision = 2,
            metrics = listOf(
                Metric(
                    name = "読了冊数",
                    targetValue = 10.0,
                    unit = "冊",
                    position = 0,
                    id = "metric-1",
                    currentValue = 0.0,
                )
            ),
        )
    }

    override suspend fun recordProgress(
        goalId: String,
        body: String,
        metricUpdates: List<MetricProgressInput>,
        clientRequestId: String,
    ): Goal {
        recordProgressCalls++
        requestIds.add(clientRequestId)
        if (failSave) error("response lost")
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
