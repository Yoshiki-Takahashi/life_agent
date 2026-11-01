package com.yoshiki.lifeagent

import com.yoshiki.lifeagent.data.*
import com.yoshiki.lifeagent.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class AppStateTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    @Test fun oneConversationRecordsDifferentGoalsOnlyAfterConfirmation() = runTest(dispatcher) {
        val repository = TestGoals()
        val coach = CoachViewModel(repository)
        coach.updateBody("相談")
        coach.sendMessage()
        advanceUntilIdle()
        for (body in listOf("1冊読んだ", "1回トレーニングした")) {
            coach.updateBody(body)
            coach.sendMessage()
            advanceUntilIdle()
            val before = repository.saves.size
            coach.saveProgress()
            assertEquals(before, repository.saves.size)
            coach.confirmCandidate()
            coach.saveProgress()
            advanceUntilIdle()
        }
        assertEquals(listOf("reading", "training"), repository.saves.map { it.goalId })
        assertEquals(3, coach.uiState.value.turns.size)
        assertTrue(repository.history.any { it.content == "相談" })
        coach.updateBody("未保存")
        coach.discardDraft()
        assertEquals("", coach.uiState.value.body)
        assertEquals(3, coach.uiState.value.turns.size)
    }

    @Test fun lostSaveResponseFreezesPayloadAndAdviceRetryNeverResaves() = runTest(dispatcher) {
        val repository = TestGoals().apply { loseSave = true; failAdvice = true }
        val coach = CoachViewModel(repository)
        coach.updateBody("1冊読んだ")
        coach.sendMessage()
        advanceUntilIdle()
        coach.confirmCandidate()
        coach.saveProgress()
        advanceUntilIdle()
        assertTrue(coach.uiState.value.resultUnknown)
        coach.updateBody("変更できない本文")
        coach.updateMetric("books", "2")
        coach.cancelCandidate()
        coach.saveProgress()
        advanceUntilIdle()
        assertEquals(repository.saves[0], repository.saves[1])
        assertNotNull(coach.uiState.value.turns.single().adviceError)
        coach.loadAdvice(coach.uiState.value.turns.single().id)
        advanceUntilIdle()
        assertNotNull(coach.uiState.value.turns.single().advice)
        assertEquals(2, repository.saves.size)
    }

    @Test fun staleReplyCannotReplaceEditedInputOrRestoreLoggedOutSession() = runTest(dispatcher) {
        for (reset in listOf(false, true)) {
            val pending = CompletableDeferred<CoachReply>()
            val repository = TestGoals().apply { pendingReply = pending }
            val coach = CoachViewModel(repository)
            coach.updateBody("古い質問")
            coach.sendMessage()
            runCurrent()
            if (reset) coach.reset() else coach.updateBody("新しい質問")
            pending.complete(CoachReply("古い返答", "discuss"))
            advanceUntilIdle()
            assertEquals(if (reset) "" else "新しい質問", coach.uiState.value.body)
            assertTrue(coach.uiState.value.turns.isEmpty())
            assertTrue(repository.saves.isEmpty())
        }
    }

    @Test fun creationAndReplanBothRequireApproval() = runTest(dispatcher) {
        val repository = TestGoals()
        val creation = GoalViewModel(repository)
        creation.updateTitle("本を読む")
        creation.updateTargetDate(LocalDate.now().plusDays(90).toString())
        creation.previewGoal()
        advanceUntilIdle()
        assertEquals(0, repository.confirmCalls)
        creation.confirmGoal()
        advanceUntilIdle()
        assertEquals(1, repository.confirmCalls)
        val detail = GoalDetailViewModel(repository)
        detail.loadGoal("reading")
        advanceUntilIdle()
        detail.updateReplanReason("無理なく続けたい")
        detail.previewReplan()
        advanceUntilIdle()
        assertEquals(0, repository.applyCalls)
        detail.applyReplan()
        advanceUntilIdle()
        assertEquals(1, repository.applyCalls)
        assertEquals(2, detail.uiState.value.goal!!.planRevision)
    }

    @Test fun logoutClearsUserAndCredentials() = runTest(dispatcher) {
        var signedOut = false
        val auth = object : AuthRepository {
            override fun currentUser() = AuthUser("a", "a@example.com")
            override suspend fun signIn(email: String, password: String) = AuthUser("a", email)
            override suspend fun createAccount(email: String, password: String) = signIn(email, password)
            override suspend fun idToken() = "test"
            override suspend fun signInWithGoogle(idToken: String) = error("unused")
            override suspend fun linkGoogle(idToken: String, expectedUserId: String) = error("unused")
            override suspend fun signOut() { signedOut = true }
        }
        val vm = AuthViewModel(auth)
        vm.updatePassword("private")
        vm.signOut()
        advanceUntilIdle()
        assertTrue(signedOut)
        assertNull(vm.uiState.value.user)
        assertEquals("", vm.uiState.value.password)
    }

    @Test fun progressValuesRespectPrecisionAndTarget() {
        for (invalid in listOf("NaN", "Infinity", "-1", "0", "0.001", "9")) {
            assertFalse(validProgressValue(invalid, 4.0, 12.0))
        }
        assertTrue(validProgressValue("0.5", 4.0, 12.0))
    }
}

private data class Saved(val goalId: String, val body: String,
    val updates: List<MetricProgressInput>, val requestId: String)

private class TestGoals : GoalRepository {
    private val date = LocalDate.now().plusDays(90).toString()
    private val goals = mutableMapOf("reading" to goal("reading", "books", "冊"),
        "training" to goal("training", "sessions", "回"))
    val saves = mutableListOf<Saved>()
    var history = emptyList<CoachMessage>()
    var pendingReply: CompletableDeferred<CoachReply>? = null
    var loseSave = false
    var failAdvice = false
    var confirmCalls = 0
    var applyCalls = 0
    private fun goal(id: String, metricId: String, unit: String) = Goal(id, id, null, date,
        "active", "2026-01-01T00:00:00Z", listOf(Metric(metricId, 12.0, unit, id=metricId)),
        List(3) { Milestone("中間目標", date) })
    override suspend fun coachMessage(body: String, history: List<CoachMessage>): CoachReply {
        this.history = history
        pendingReply?.let { return it.await() }
        if (body == "相談") return CoachReply("全目標を相談しましょう", "discuss")
        return CoachReply("今回値を確認", "record", if (body.contains("回")) "training" else "reading", body)
    }
    override suspend fun getGoal(goalId: String) = goals.getValue(goalId)
    override suspend fun listGoals() = goals.values.map {
        GoalSummary(it.id, it.title, date, it.status, 1, 3, it.createdAt, it.updatedAt) }
    override suspend fun previewProgress(goalId: String, body: String) = ProgressPreview(
        listOf(ProgressMetricUpdatePayload(getGoal(goalId).metrics.single().id, 1.0)), emptyList())
    override suspend fun recordProgress(goalId: String, body: String,
        metricUpdates: List<MetricProgressInput>, clientRequestId: String): Goal {
        saves += Saved(goalId, body, metricUpdates, clientRequestId)
        if (loseSave) { loseSave = false; error("response lost") }
        val updated = getGoal(goalId).let { it.copy(metrics=it.metrics.map { metric ->
            metric.copy(currentValue=metric.currentValue + metricUpdates.single().value) }) }
        goals[goalId] = updated
        return updated
    }
    override suspend fun getAdvice(goalId: String): Advice {
        if (failAdvice) { failAdvice = false; error("unavailable") }
        return Advice("次の一歩", listOf("無理のない量を選ぶ"))
    }
    override suspend fun previewGoal(title: String, description: String?, targetDate: String) =
        getGoal("reading").let { GoalPlan(title, description, targetDate, it.metrics, it.milestones) }
    override suspend fun confirmGoal(plan: GoalPlan): Goal { confirmCalls++; return getGoal("reading") }
    override suspend fun previewReplan(goalId: String, reason: String) = ReplanProposal("proposal", 1, reason,
        previewGoal(goalId, null, date), emptyList(), "2026-01-01T00:00:00Z")
    override suspend fun applyReplan(goalId: String, proposalId: String): Goal {
        applyCalls++; return getGoal(goalId).copy(planRevision=2)
    }
}
