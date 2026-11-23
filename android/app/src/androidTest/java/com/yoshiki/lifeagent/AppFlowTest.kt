package com.yoshiki.lifeagent

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import com.yoshiki.lifeagent.data.*
import com.yoshiki.lifeagent.ui.LifeAgentApp
import com.yoshiki.lifeagent.ui.LocalSpeechRecognitionFactory
import com.yoshiki.lifeagent.ui.theme.LifeAgentTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** One user journey through real HTTP and Firestore; no screenshots or internal UI snapshots. */
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @get:Rule val microphone = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test fun goalQuestionsEditingAndDateSelectionSaveOnlyAfterConfirmation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("networkE2E") == "true")
        val owner = "creation-e2e-${UUID.randomUUID()}"
        val api = Network.createGoalRepository("http://127.0.0.1:18007/") { owner }
        var failSave = true
        val repository = object : GoalRepository by api {
            override suspend fun confirmGoal(plan: GoalPlan): Goal {
                if (failSave) { failSave = false; error("temporary failure") }
                return api.confirmGoal(plan)
            }
        }
        compose.setContent { LifeAgentTheme { LifeAgentApp(repository, owner, {},
            Network.createFeedbackRepository("http://127.0.0.1:18007/") { owner }) } }
        compose.onNodeWithText("新規作成").performClick()
        compose.onNodeWithText("タイトル").performTextReplacement("読書の習慣")
        compose.onNodeWithText("Goal期限を選ぶ").performScrollTo().performClick()
        onView(withId(android.R.id.button1)).perform(click())
        compose.onNodeWithText("質問へ進む").performScrollTo().performClick()
        waitFor("目標を具体化")
        compose.onNodeWithText("回答から計画を作る").performScrollTo().assertIsNotEnabled()
        repeat(3) { index ->
            compose.onNode(hasText("No") and hasAnyAncestor(hasTestTag("question-$index")))
                .performScrollTo().performClick()
        }
        runBlocking { assertTrue(api.listGoals().isEmpty()) }
        compose.onNodeWithText("回答から計画を作る").performScrollTo().performClick()
        waitFor("計画を確認")
        field("metric-0", "Metric名").performTextReplacement("読了冊数（確認後）")
        compose.onNodeWithText("戻る").performClick()
        compose.onNode(hasText("Yes") and hasAnyAncestor(hasTestTag("question-0")))
            .performScrollTo().performClick()
        compose.onNodeWithText("回答から計画を作る").performScrollTo().performClick()
        compose.onNodeWithText("再生成しますか？").assertIsDisplayed()
        compose.onNodeWithText("戻って確認").performClick()
        compose.onNode(hasText("No") and hasAnyAncestor(hasTestTag("question-0")))
            .performScrollTo().performClick()
        compose.onNodeWithText("回答から計画を作る").performScrollTo().performClick()
        waitFor("計画を確認")
        field("metric-0", "Metric名").assertTextContains("読了冊数（確認後）")
        compose.onNodeWithText("Metricを追加").performScrollTo().performClick()
        field("metric-1", "Metric名").performScrollTo().performTextReplacement("読んだ時間")
        field("metric-1", "目標値").performScrollTo().performTextReplacement("60")
        field("metric-1", "単位").performScrollTo().performTextReplacement("分")
        field("metric-0", "Metricを削除").performScrollTo().performClick()
        field("metric-0", "Metric名").assertTextContains("読んだ時間")
        field("metric-0", "測定値").performScrollTo().performClick()
        compose.onNodeWithText("Metricを追加").performScrollTo().performClick()
        field("metric-1", "Metric名").performScrollTo().performTextReplacement("読書準備")
        field("metric-1", "チェックリスト").performScrollTo().performClick()
        field("metric-1", "チェック項目（1行に1項目）").performScrollTo().performTextReplacement("本を選ぶ\n本を用意する")
        runBlocking { assertTrue(api.listGoals().isEmpty()) }
        compose.onNodeWithText("この計画で保存する").performScrollTo().performClick()
        waitFor("保存できませんでした。編集内容を確認して再試行してください")
        field("metric-0", "Metric名").performScrollTo().assertTextContains("読んだ時間")
        compose.onNodeWithText("この計画で保存する").performScrollTo().performClick()
        waitFor("概要")
        runBlocking {
            val summary = api.listGoals().single()
            val saved = api.getGoal(summary.id)
            assertEquals(listOf("読んだ時間", "読書準備"), saved.metrics.map { it.name })
            assertEquals(listOf("measurement", "checklist"), saved.metrics.map { it.kind })
            assertEquals(listOf("本を選ぶ", "本を用意する"), saved.metrics[1].checklistItems)
            assertEquals(60.0, saved.metrics.first().targetValue, 0.0)
            assertEquals(0.0, requireNotNull(summary.achievementPercent), 0.0)
        }
        compose.onNodeWithText("本を選ぶ").performScrollTo().assertIsOff()
        compose.onNodeWithText("本を用意する").performScrollTo().assertIsOff()
    }

    @Test fun replanCardsRejectRestoreAndSaveOnlyRemainingChanges() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("networkE2E") == "true")
        val owner = "replan-selection-e2e-${UUID.randomUUID()}"
        val api = Network.createGoalRepository("http://127.0.0.1:18007/") { owner }
        val goal = runBlocking {
            val plan = api.previewGoal("本を読む", null, LocalDate.now().plusDays(90).toString())
            api.confirmGoal(plan.copy(metrics = plan.metrics + Metric("読書メモ", 10.0, "件")))
        }
        var proposal: ReplanProposal? = null
        var questions: GoalQuestions? = null
        val repository = object : GoalRepository by api {
            override suspend fun replanQuestions(goalId: String, reason: String): GoalQuestions =
                api.replanQuestions(goalId, reason).also { questions = it }
            override suspend fun previewReplan(goalId: String, reason: String, answers: List<GoalAnswer>): ReplanProposal =
                api.previewReplan(goalId, reason, answers).also { proposal = it }
        }
        compose.setContent { LifeAgentTheme { LifeAgentApp(repository, owner, {},
            Network.createFeedbackRepository("http://127.0.0.1:18007/") { owner }) } }
        waitFor(goal.title)
        compose.onNodeWithText(goal.title).performClick()
        waitFor("概要")
        compose.onNodeWithText("マイルストーン").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("再計画").performClick()
        compose.onNodeWithText("マイルストーン").assertDoesNotExist()
        compose.onNodeWithText("見直し理由").performScrollTo().performTextReplacement("無理なく続けたい")
        compose.onNodeWithText("質問へ進む").performScrollTo().performClick()
        waitFor("見直しのための質問")
        compose.onNodeWithText("回答から再計画候補を作る").performScrollTo().assertIsNotEnabled()
        requireNotNull(questions).questions.indices.forEach { index ->
            field("question-$index", if (index == 2) "いいえ" else "はい").performScrollTo().performClick()
        }
        compose.onNodeWithText("回答から再計画候補を作る").performScrollTo().performClick()
        waitFor("変更候補を確認")
        compose.onNodeWithText("メトリクス（測定項目）").performScrollTo().assertIsDisplayed()
        val changes = requireNotNull(proposal).diff
        assertTrue(changes.none { it.targetType == "milestone" })
        compose.onNode(hasText("削除") and hasAnyAncestor(hasTestTag("replan-metric:1")))
            .performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("変更") and hasAnyAncestor(hasTestTag("replan-metric:0")))
            .performScrollTo().assertIsDisplayed()
        changes.forEach { change ->
            compose.onNodeWithContentDescription("${change.targetLabel}の変更を棄却")
                .performScrollTo().performClick()
        }
        compose.onNodeWithText("この変更を保存").performScrollTo().assertIsNotEnabled()
        runBlocking { assertEquals(goal, api.getGoal(goal.id)) }
        changes.forEach { change ->
            compose.onNode(hasText("元に戻す") and hasAnyAncestor(hasTestTag("replan-${change.changeId}")))
                .performScrollTo().performClick()
        }
        // Exclude both the first Metric's update and the second Metric's removal.
        changes.filter { it.targetType == "metric" }.forEach { change ->
            compose.onNodeWithContentDescription("${change.targetLabel}の変更を棄却")
                .performScrollTo().performClick()
        }
        compose.onNodeWithText("この変更を保存").performScrollTo().performClick()
        waitFor("再計画を保存しました")
        runBlocking {
            val saved = api.getGoal(goal.id)
            assertEquals(goal.metrics, saved.metrics)
            assertEquals(goal.milestoneChecklist, saved.milestoneChecklist)
            assertEquals(goal.planRevision + 1, saved.planRevision)
        }
        compose.onNodeWithText("目標一覧").performClick()
        waitFor(goal.title)
        compose.onNodeWithText(goal.title).performClick()
        compose.onNodeWithText("概要").performClick()
        compose.onNodeWithText("読書メモ").performScrollTo().assertIsDisplayed()
    }

    private fun field(parent: String, text: String) =
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(parent)))

    @Test fun zeroAndOverTargetProgressSaveAndRedisplayThroughDetailAndCoach() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("networkE2E") == "true")
        val owner = "progress-values-e2e-${UUID.randomUUID()}"
        val api = Network.createGoalRepository("http://127.0.0.1:18007/") { owner }
        val date = LocalDate.now().plusDays(90).toString()
        val goal = runBlocking { api.confirmGoal(GoalPlan("読書の実績を残す", null, date,
            listOf(Metric("読了冊数", 12.0, "冊"), Metric("読書時間", 60.0, "分"),
                Metric("読んだページ", 100.0, "ページ")))) }
        compose.setContent { LifeAgentTheme { LifeAgentApp(api, owner, {},
            Network.createFeedbackRepository("http://127.0.0.1:18007/") { owner }) } }
        waitFor(goal.title)
        compose.onNodeWithText(goal.title).performClick()
        compose.onNodeWithText("記録").performClick()
        for ((index, value) in listOf("13", "0").withIndex()) {
            if (index == 1) {
                compose.onNodeWithText("進捗メモ").performScrollTo().performTextReplacement("確認した実績")
            }
            compose.onNodeWithText("読了冊数 の今回値").performScrollTo().performTextReplacement(value)
            compose.onNodeWithText("読書時間 の今回値").performScrollTo().performTextReplacement("0")
            compose.onNodeWithText("進捗を保存").performScrollTo().performClick()
            compose.waitUntil(20000) { runBlocking { api.getGoal(goal.id).progressLogs.size == index + 1 } }
            waitFor("進捗を保存しました")
            runBlocking {
                val latest = api.getGoal(goal.id).progressLogs.first()
                assertEquals(if (index == 0) "" else "確認した実績", latest.body)
            }
        }
        compose.onNodeWithText("進捗").performClick()
        compose.onNodeWithText("履歴と現在値を確認できないため推移を表示できません").assertDoesNotExist()
        compose.onNodeWithText("コーチ").performClick()
        send("今回は2冊と5ページを読んだ。読書時間は0分でした")
        val (books, minutes, pages) = goal.metrics
        waitFor("右にスワイプで記録、左で見送る", timeoutMs = 60000)
        val accepted = candidate(books.id).assertTextContains("読了冊数: +2 冊")
        val actionLabels = accepted.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
            .map { it.label }
        assertEquals(listOf("この候補を記録", "この候補を見送る"), actionLabels)
        accepted.performTouchInput { swipeRight() }
        compose.waitUntil(20000) { compose.onAllNodesWithTag("coach-candidate-${books.id}").fetchSemanticsNodes().isEmpty() }
        runBlocking { assertEquals(15.0, api.getGoal(goal.id).metrics[0].currentValue, 0.0) }
        compose.onNodeWithText("記録が完了しました").assertDoesNotExist()
        candidate(pages.id).performTouchInput { swipeLeft() }
        compose.onNodeWithText("記録が完了しました").assertDoesNotExist()
        candidate(minutes.id).performTouchInput { swipeRight() }
        dismissCoachCompletion()
        runBlocking {
            val saved = api.getGoal(goal.id)
            assertEquals(listOf(15.0, 0.0, 0.0), saved.metrics.map { it.currentValue })
            assertEquals(4, saved.progressLogs.size)
            assertEquals(listOf(0.0, 0.0), saved.progressLogs.filter { it.metricUpdates.size == 2 }
                .first().metricUpdates.map { it.value })
            assertEquals(mapOf(books.id to 2.0, minutes.id to 0.0), saved.progressLogs.map { it.metricUpdates }
                .filter { it.size == 1 }.associate { it.single().metricId to it.single().value })
        }
        compose.onNodeWithText("目標一覧").performClick()
        waitFor(goal.title)
        compose.onNodeWithText(goal.title).performClick()
        compose.onNodeWithText("進捗").performClick()
        compose.onNodeWithText("現在 15.0 冊").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("読書時間: +0 分").onFirst().performScrollTo().assertIsDisplayed()
    }

    @Test fun globalConversationConfirmationRetryNavigationAndTabs() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("networkE2E") == "true")
        val owner = "app-e2e-${UUID.randomUUID()}"
        val api = Network.createGoalRepository("http://127.0.0.1:18007/") { owner }
        val goals = runBlocking { listOf("本を読む", "筋トレを続ける").map {
            api.confirmGoal(api.previewGoal(it, null, LocalDate.now().plusDays(90).toString())) } }
        var loseResponse = true
        var failAdvice = true
        val requests = mutableListOf<String>()
        val repository = object : GoalRepository by api {
            override suspend fun recordProgress(goalId: String, body: String,
                metricUpdates: List<MetricProgressInput>, clientRequestId: String): Goal {
                requests += clientRequestId
                val saved = api.recordProgress(goalId, body, metricUpdates, clientRequestId)
                if (loseResponse) { loseResponse = false; throw java.io.IOException("lost after commit") }
                return saved
            }
            override suspend fun getAdvice(goalId: String): Advice {
                if (failAdvice) { failAdvice = false; error("temporary failure") }
                return api.getAdvice(goalId)
            }
        }
        lateinit var speech: FakeSpeechRecognition
        compose.setContent {
            CompositionLocalProvider(LocalSpeechRecognitionFactory provides { _, _ ->
                FakeSpeechRecognition().also { speech = it }
            }) { LifeAgentTheme { LifeAgentApp(repository, owner, {},
                Network.createFeedbackRepository("http://127.0.0.1:18007/") { owner }) } }
        }
        waitFor("本を読む")
        compose.onNodeWithText("コーチ").performClick()
        send("全部の目標を相談したい")
        waitFor("保存済みの全目標", true)
        assertTrue(compose.onNodeWithText("保存済みの全目標", substring=true)
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString().contains("筋トレを続ける"))
        compose.onNodeWithText("音声入力").performScrollTo().performClick()
        compose.onNodeWithText("録音開始").performClick()
        compose.runOnIdle { speech.result("2冊読み終えた") }
        compose.onNodeWithText("文字起こし結果").performTextReplacement("1冊読み終えた")
        compose.onNodeWithText("本文に追加").performClick()
        assertTrue(requests.isEmpty())
        runBlocking { assertTrue(api.getGoal(goals.first().id).progressLogs.isEmpty()) }
        compose.onNodeWithText("送信").performScrollTo().performClick()
        compose.onNodeWithText("目標一覧").assertIsDisplayed()
        waitFor("右にスワイプで記録、左で見送る", timeoutMs = 60000)
        candidate(goals[0].metrics.single().id).performTouchInput { swipeRight() }
        waitFor("同じ内容で再試行")
        compose.onNodeWithText("今回値を確認").assertDoesNotExist()
        compose.onNodeWithText("記録が完了しました").assertDoesNotExist()
        compose.onNodeWithText("同じ内容で再試行").performScrollTo().performClick()
        dismissCoachCompletion()
        conversation("助言だけ再試行").performClick()
        conversation("保存済み進捗", true)
        assertEquals(requests[0], requests[1])
        send("1回トレーニングした")
        waitFor("右にスワイプで記録、左で見送る", timeoutMs = 60000)
        candidate(goals[1].metrics.single().id).performTouchInput { swipeRight() }
        dismissCoachCompletion()
        runBlocking { goals.forEach { goal ->
            val saved = api.getGoal(goal.id)
            assertEquals(1, saved.progressLogs.size)
            assertEquals(1.0, saved.metrics.single().currentValue, 0.0)
        } }
        compose.onNodeWithText("目標一覧").performClick()
        waitFor("本を読む")
        compose.onNodeWithText("本を読む").performClick()
        listOf("概要", "進捗", "記録", "再計画").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("進捗メモ").assertDoesNotExist()
        compose.onNodeWithText("記録").performClick()
        compose.onNodeWithText("進捗メモ").assertExists()
        compose.onNodeWithText("音声入力").assertDoesNotExist()
        compose.onNodeWithText("進捗メモを解析").assertDoesNotExist()
        compose.onNodeWithText("進捗メモ").performTextReplacement("既存の入力")
        assertEquals(3, requests.size)
        compose.onNodeWithText("再計画").performClick()
        compose.onNodeWithText("計画を見直す").assertExists()
        compose.onNodeWithText("コーチ").performClick()
        compose.onNodeWithText("破棄して移動").performClick()
        compose.onNodeWithText("コーチに話す").performTextReplacement("未保存の相談")
        compose.onNodeWithText("新規作成").performClick()
        compose.onNodeWithText("編集を続ける").performClick()
        compose.onNodeWithText("未保存の相談").assertExists()
        compose.onNodeWithText("新規作成").performClick()
        compose.onNodeWithText("破棄して移動").performClick()
        compose.onNodeWithText("タイトル").assertExists()
        compose.onNodeWithText("コーチ").assertIsDisplayed()
    }

    private fun send(body: String) {
        compose.onNodeWithText("コーチに話す").performTextReplacement(body)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("送信").performScrollTo().assertIsDisplayed().performClick()
    }

    private fun candidate(metricId: String): SemanticsNodeInteraction {
        val tag = "coach-candidate-$metricId"
        compose.onNodeWithTag("coach-conversation").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun dismissCoachCompletion() {
        waitFor("記録が完了しました")
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("コーチに話す").assertIsDisplayed()
        compose.onNodeWithText("今回値を確認").assertDoesNotExist()
    }

    private fun waitFor(text: String, substring: Boolean = false, timeoutMs: Long = 10000) {
        try {
            compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring=substring).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("Waiting for '$text':\n${compose.onRoot().printToString()}", error)
        }
    }
    private fun conversation(text: String, substring: Boolean = false): SemanticsNodeInteraction {
        waitFor(text, substring)
        compose.onNodeWithTag("coach-conversation").performScrollToNode(hasText(text, substring=substring))
        return compose.onNodeWithText(text, substring=substring)
    }
}
