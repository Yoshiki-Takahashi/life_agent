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
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class AppStateTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    @Test fun oneConversationRecordsDifferentGoalsOnlyAfterTheCandidateIsAccepted() = runTest(dispatcher) {
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
            coach.decideCandidate(coach.uiState.value.candidates.single().metric.id, save = true)
            advanceUntilIdle()
            assertTrue(coach.uiState.value.showCompletion)
            coach.dismissCompletion()
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
        coach.decideCandidate("books", save = true)
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

    @Test fun coachKeepsManualSaveAvailableWhenAiLimitIsReachedDuringParsing() = runTest(dispatcher) {
        val store = TestGoals()
        val quotaError = HttpException(Response.error<Any>(429, "".toResponseBody()))
        val repository = object : GoalRepository by store {
            override suspend fun previewProgress(goalId: String, body: String): ProgressPreview = throw quotaError
            override suspend fun getAdvice(goalId: String): Advice = throw quotaError
        }
        val coach = CoachViewModel(repository)
        coach.updateBody("1冊読んだ")
        coach.sendMessage()
        advanceUntilIdle()
        assertEquals("1冊読んだ", coach.uiState.value.body)
        assertTrue(coach.uiState.value.candidates.isEmpty())
        assertNotNull(coach.uiState.value.error)
        coach.startManualConfirmation()
        coach.updateMetric("books", "1")
        coach.saveProgress()
        advanceUntilIdle()
        assertEquals(listOf(MetricProgressInput("books", 1.0)), store.saves.single().updates)
        assertTrue(coach.uiState.value.turns.single().saved)
        assertNotNull(coach.uiState.value.turns.single().adviceError)
    }

    @Test fun candidatesSaveIndividuallyAndCompletionWaitsForAllDecisions() = runTest(dispatcher) {
        val repository = TestGoals(extraMetrics = true).apply {
            previewUpdates = listOf(ProgressMetricUpdatePayload("books", 2.0),
                ProgressMetricUpdatePayload("minutes", 0.0), ProgressMetricUpdatePayload("pages", 5.0))
        }
        val coach = CoachViewModel(repository)
        coach.updateBody("2冊と5ページを読んだ。読書時間は0分")
        coach.sendMessage()
        advanceUntilIdle()
        assertTrue(repository.saves.isEmpty())
        coach.decideCandidate("books", save = true)
        advanceUntilIdle()
        assertEquals(listOf(MetricProgressInput("books", 2.0)), repository.saves.single().updates)
        assertFalse(coach.uiState.value.showCompletion)
        assertEquals(0, repository.adviceCalls)
        coach.decideCandidate("books", save = true)
        coach.decideCandidate("minutes", save = true)
        advanceUntilIdle()
        assertFalse(coach.uiState.value.showCompletion)
        coach.decideCandidate("pages", save = false)
        advanceUntilIdle()
        assertEquals(listOf(listOf(MetricProgressInput("books", 2.0)), listOf(MetricProgressInput("minutes", 0.0))),
            repository.saves.map { it.updates })
        assertEquals(listOf(2.0, 0.0), coach.uiState.value.turns.single().updates.map { it.value })
        assertTrue(coach.uiState.value.showCompletion)
        assertEquals(1, repository.adviceCalls)
    }

    @Test fun decliningEveryCandidateDoesNotSaveOrShowCompletion() = runTest(dispatcher) {
        val repository = TestGoals()
        val coach = CoachViewModel(repository)
        coach.updateBody("1冊読んだ")
        coach.sendMessage()
        advanceUntilIdle()
        coach.decideCandidate("books", save = false)
        advanceUntilIdle()
        assertFalse(coach.uiState.value.showCompletion)
        assertEquals(CoachPhase.EDITING, coach.uiState.value.phase)
        assertTrue(repository.saves.isEmpty())
        assertEquals(0, repository.adviceCalls)
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
        creation.requestQuestions()
        advanceUntilIdle()
        creation.uiState.value.questions.indices.forEach { creation.answerQuestion(it, false) }
        creation.previewGoal()
        advanceUntilIdle()
        assertEquals(0, repository.confirmCalls)
        creation.updateMetricValue(0, "0")
        creation.confirmGoal()
        advanceUntilIdle()
        assertEquals(0, repository.confirmCalls)
        creation.updateMetricValue(0, "12")
        creation.confirmGoal()
        advanceUntilIdle()
        assertEquals(1, repository.confirmCalls)
        val detail = GoalDetailViewModel(repository)
        detail.loadGoal("reading")
        advanceUntilIdle()
        detail.updateReplanReason("無理なく続けたい")
        detail.requestReplanQuestions()
        advanceUntilIdle()
        val questions = detail.uiState.value.replanQuestions.map { it.question }
        detail.answerReplanQuestion(0, true)
        detail.previewReplan()
        advanceUntilIdle()
        assertTrue(repository.replanAnswers.isEmpty())
        questions.indices.drop(1).forEach { detail.answerReplanQuestion(it, false) }
        repository.failReplanPreview = true
        detail.previewReplan()
        advanceUntilIdle()
        assertNull(detail.uiState.value.replanProposal)
        detail.previewReplan()
        advanceUntilIdle()
        val answers = questions.mapIndexed { index, question -> GoalAnswer(question, index == 0) }
        assertEquals(listOf(answers, answers), repository.replanAnswers)
        assertEquals(0, repository.applyCalls)
        detail.applyReplan()
        advanceUntilIdle()
        assertEquals(1, repository.applyCalls)
        assertEquals(2, detail.uiState.value.goal!!.planRevision)
    }

    @Test fun questionsAndEditedPlanSurviveBackNavigationAndGenerationFailure() = runTest(dispatcher) {
        val repository = TestGoals()
        val creation = GoalViewModel(repository)
        creation.updateTitle("読書")
        creation.updateTargetDate(LocalDate.now().plusDays(90).toString())
        repository.failQuestions = true
        creation.requestQuestions()
        advanceUntilIdle()
        assertNotNull(creation.uiState.value.formError)
        assertEquals("読書", creation.uiState.value.title)
        creation.requestQuestions()
        advanceUntilIdle()
        creation.previewGoal()
        assertTrue(repository.previewRequests.isEmpty()) // Unanswered is never No.
        creation.uiState.value.questions.indices.forEach { creation.answerQuestion(it, false) }
        creation.previewGoal()
        advanceUntilIdle()
        assertTrue(repository.previewRequests.single().answers.all { !it.answer })
        creation.updateMetricName(0, "編集した項目")
        val edited = creation.uiState.value.metrics
        creation.requestQuestions() // Returning through the form reuses the same questions.
        creation.previewGoal() // Unchanged answers reopen the edited plan without regenerating it.
        advanceUntilIdle()
        assertEquals(1, repository.previewRequests.size)
        assertEquals(2, repository.questionCalls)
        creation.answerQuestion(0, true)
        creation.previewGoal()
        assertEquals(1, repository.previewRequests.size)
        assertEquals(DraftReplacement.PLAN, creation.uiState.value.replacement)
        creation.dismissReplacement()
        assertEquals(edited, creation.uiState.value.metrics)
        creation.previewGoal()
        repository.failPreview = true
        creation.confirmReplacement()
        advanceUntilIdle()
        assertNotNull(creation.uiState.value.questionError)
        assertEquals(edited, creation.uiState.value.metrics)
        creation.confirmGoal()
        assertEquals(0, repository.confirmCalls) // A stale plan cannot be saved.
        creation.previewGoal()
        creation.confirmReplacement()
        advanceUntilIdle()
        assertTrue(repository.previewRequests.last().answers.first().answer)
        assertNotEquals(edited, creation.uiState.value.metrics)
        creation.updateTitle("別の目標")
        creation.requestQuestions()
        assertEquals(DraftReplacement.QUESTIONS, creation.uiState.value.replacement)
        assertEquals(2, repository.questionCalls)
    }

    @Test fun addedAndDeletedItemsKeepTheirIdentityAndSaveInDisplayedOrder() = runTest(dispatcher) {
        val repository = TestGoals()
        val creation = GoalViewModel(repository)
        creation.updateTitle("読書")
        val date = LocalDate.now().plusDays(90).toString()
        creation.updateTargetDate(date)
        creation.requestQuestions()
        advanceUntilIdle()
        creation.uiState.value.questions.indices.forEach { creation.answerQuestion(it, true) }
        creation.previewGoal()
        advanceUntilIdle()
        repeat(12) { creation.addMetric() }
        assertEquals(10, creation.uiState.value.metrics.size)
        val remainingId = creation.uiState.value.metrics[1].id
        creation.updateMetricName(1, "残す項目")
        creation.removeMetric(0)
        assertEquals(remainingId, creation.uiState.value.metrics[0].id)
        assertEquals("残す項目", creation.uiState.value.metrics[0].name)
        creation.confirmGoal()
        assertEquals(0, repository.confirmCalls) // Added rows require valid content.
        creation.uiState.value.metrics.indices.forEach {
            creation.updateMetricName(it, "項目$it")
            creation.updateMetricUnit(it, "件")
            creation.updateMetricValue(it, "12")
        }
        repository.failConfirm = true
        val edited = creation.uiState.value.metrics
        creation.confirmGoal()
        advanceUntilIdle()
        assertNotNull(creation.uiState.value.planError)
        assertEquals(edited, creation.uiState.value.metrics)
        creation.confirmGoal()
        advanceUntilIdle()
        assertEquals((0..8).toList(), repository.confirmed!!.metrics.map { it.position })
        repeat(12) { creation.removeMetric(0) }
        assertEquals(1, creation.uiState.value.metrics.size)
    }

    @Test fun resetDiscardsDelayedQuestionResults() = runTest(dispatcher) {
        val pending = CompletableDeferred<GoalQuestions>()
        val repository = TestGoals().apply { pendingQuestions = pending }
        val creation = GoalViewModel(repository)
        creation.updateTitle("読書")
        creation.updateTargetDate(LocalDate.now().plusDays(90).toString())
        creation.requestQuestions()
        runCurrent()
        creation.resetDraft()
        pending.complete(GoalQuestions(listOf("古い質問1", "古い質問2", "古い質問3")))
        advanceUntilIdle()
        assertEquals("", creation.uiState.value.title)
        assertTrue(creation.uiState.value.questions.isEmpty())
        assertFalse(creation.uiState.value.navigateToQuestions)
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

    @Test fun progressValuesAllowZeroAndRespectPrecisionAndNumericLimit() {
        for (invalid in listOf("", "NaN", "Infinity", "-1", "0.001", "1000000000.01")) {
            assertFalse(validProgressValue(invalid))
        }
        for (valid in listOf("0", "0.5", "13", "1000000000")) {
            assertTrue(validProgressValue(valid))
        }
    }

    @Test fun detailAndCoachKeepExplicitZerosAndOverTargetValuesButOmitBlanks() = runTest(dispatcher) {
        for (value in listOf("13.25", "0")) {
            val repository = TestGoals(extraMetrics = true)
            val detail = GoalDetailViewModel(repository)
            detail.loadGoal("reading")
            advanceUntilIdle()
            detail.updateProgressBody("今回の実績")
            detail.saveProgress()
            advanceUntilIdle()
            assertTrue(repository.saves.isEmpty())
            detail.updateMetricProgress(0, value)
            detail.updateMetricProgress(1, "0")
            detail.saveProgress()
            advanceUntilIdle()
            assertNull(detail.uiState.value.progressError)
            val expected = listOf(MetricProgressInput("books", value.toDouble()),
                MetricProgressInput("minutes", 0.0))
            assertEquals(expected, repository.saves.single().updates)

            repository.previewUpdates = emptyList()
            val coach = CoachViewModel(repository)
            coach.updateBody("1冊読み終えた")
            coach.sendMessage()
            advanceUntilIdle()
            coach.startManualConfirmation()
            coach.updateMetric("books", value)
            coach.updateMetric("minutes", "0")
            coach.saveProgress()
            advanceUntilIdle()
            assertNull(coach.uiState.value.error)
            assertEquals(expected, repository.saves.last().updates)
            assertEquals(2, repository.saves.size)
        }
    }
}

private data class Saved(val goalId: String, val body: String,
    val updates: List<MetricProgressInput>, val requestId: String)

private class TestGoals(private val extraMetrics: Boolean = false) : GoalRepository {
    private val date = LocalDate.now().plusDays(90).toString()
    private val goals = mutableMapOf("reading" to goal("reading", "books", "冊"),
        "training" to goal("training", "sessions", "回"))
    val saves = mutableListOf<Saved>()
    var history = emptyList<CoachMessage>()
    var pendingReply: CompletableDeferred<CoachReply>? = null
    var loseSave = false
    var failAdvice = false
    var adviceCalls = 0
    var previewUpdates: List<ProgressMetricUpdatePayload>? = null
    var confirmCalls = 0
    var applyCalls = 0
    var questionCalls = 0
    var failQuestions = false
    var failPreview = false
    var failReplanPreview = false
    var failConfirm = false
    var pendingQuestions: CompletableDeferred<GoalQuestions>? = null
    val previewRequests = mutableListOf<GoalPreviewRequest>()
    val replanAnswers = mutableListOf<List<GoalAnswer>>()
    var confirmed: GoalPlan? = null
    private fun goal(id: String, metricId: String, unit: String) = Goal(id, id, null, date,
        "active", "2026-01-01T00:00:00Z", listOf(Metric(metricId, 12.0, unit, id=metricId)) +
            if (extraMetrics) listOf(Metric("読書時間", 60.0, "分", id="minutes"),
                Metric("読んだページ", 100.0, "ページ", id="pages")) else emptyList())
    override suspend fun coachMessage(body: String, history: List<CoachMessage>): CoachReply {
        this.history = history
        pendingReply?.let { return it.await() }
        if (body == "相談") return CoachReply("全目標を相談しましょう", "discuss")
        return CoachReply("今回値を確認", "record", if (body.contains("回")) "training" else "reading", body)
    }
    override suspend fun getGoal(goalId: String) = goals.getValue(goalId)
    override suspend fun listGoals() = goals.values.map {
        GoalSummary(it.id, it.title, date, it.status, 1, it.createdAt, it.updatedAt) }
    override suspend fun previewProgress(goalId: String, body: String) = ProgressPreview(
        previewUpdates ?: listOf(ProgressMetricUpdatePayload(getGoal(goalId).metrics.first().id, 1.0)), emptyList())
    override suspend fun recordProgress(goalId: String, body: String,
        metricUpdates: List<MetricProgressInput>, clientRequestId: String): Goal {
        saves += Saved(goalId, body, metricUpdates, clientRequestId)
        if (loseSave) { loseSave = false; error("response lost") }
        val updated = getGoal(goalId).let { it.copy(metrics=it.metrics.map { metric ->
            metric.copy(currentValue=metric.currentValue +
                (metricUpdates.find { it.metricId == metric.id }?.value ?: 0.0)) }) }
        goals[goalId] = updated
        return updated
    }
    override suspend fun getAdvice(goalId: String): Advice {
        adviceCalls++
        if (failAdvice) { failAdvice = false; error("unavailable") }
        return Advice("次の一歩", listOf("無理のない量を選ぶ"))
    }
    override suspend fun goalQuestions(title: String, description: String?, targetDate: String): GoalQuestions {
        questionCalls++
        pendingQuestions?.let { return it.await() }
        if (failQuestions) { failQuestions = false; error("unavailable") }
        return GoalQuestions(listOf("時間も測りますか？", "段階を増やしますか？", "経験がありますか？"))
    }
    override suspend fun previewGoal(title: String, description: String?, targetDate: String,
        answers: List<GoalAnswer>): GoalPlan {
        previewRequests += GoalPreviewRequest(title, description, targetDate, answers)
        if (failPreview) { failPreview = false; error("unavailable") }
        return GoalPlan(title, description, targetDate, getGoal("reading").metrics)
    }
    override suspend fun confirmGoal(plan: GoalPlan): Goal {
        confirmCalls++
        if (failConfirm) { failConfirm = false; error("unavailable") }
        confirmed = plan
        return getGoal("reading")
    }
    override suspend fun replanQuestions(goalId: String, reason: String): GoalQuestions =
        GoalQuestions(listOf("読書時間を減らしたいですか？", "読書メモを続けたいですか？", "期限を延ばしたいですか？"))

    override suspend fun previewReplan(goalId: String, reason: String, answers: List<GoalAnswer>): ReplanProposal {
        replanAnswers += answers
        if (failReplanPreview) { failReplanPreview = false; error("unavailable") }
        return ReplanProposal("proposal", 1, reason, previewGoal(goalId, null, date), emptyList(), "2026-01-01T00:00:00Z")
    }
    override suspend fun applyReplan(goalId: String, proposalId: String, excludedChangeIds: List<String>): Goal {
        applyCalls++; return getGoal(goalId).copy(planRevision=2)
    }
}
