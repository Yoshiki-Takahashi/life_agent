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
        compose.setContent { LifeAgentTheme { LifeAgentApp(repository, owner, {}) } }
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
        repeat(2) { field("milestone-0", "Milestoneを削除").performScrollTo().performClick() }
        field("milestone-0", "Milestoneを削除").assertIsNotEnabled()
        compose.onNodeWithText("Milestoneを追加").performScrollTo().performClick()
        field("milestone-1", "Milestone名").performScrollTo().performTextReplacement("読み方を振り返る")
        // Milestone dates use the same calendar form as the Goal date.
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("milestone-1")) and
            hasText("年", substring=true)).performScrollTo().performClick()
        onView(withId(android.R.id.button1)).perform(click())
        runBlocking { assertTrue(api.listGoals().isEmpty()) }
        compose.onNodeWithText("この計画で保存する").performScrollTo().performClick()
        waitFor("保存できませんでした。編集内容を確認して再試行してください")
        field("metric-0", "Metric名").performScrollTo().assertTextContains("読んだ時間")
        compose.onNodeWithText("この計画で保存する").performScrollTo().performClick()
        waitFor("概要")
        runBlocking {
            val saved = api.getGoal(api.listGoals().single().id)
            assertEquals(listOf("読んだ時間"), saved.metrics.map { it.name })
            assertEquals(60.0, saved.metrics.single().targetValue, 0.0)
            assertEquals(2, saved.milestones.size)
            assertEquals("読み方を振り返る", saved.milestones.last().title)
        }
    }

    private fun field(parent: String, text: String) =
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(parent)))

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
            }) { LifeAgentTheme { LifeAgentApp(repository, owner, {}) } }
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
        waitFor("候補を確認する")
        conversation("確認して保存へ").performClick()
        compose.onNodeWithText("目標一覧").assertIsDisplayed()
        compose.onNodeWithText("この内容で保存する").performScrollTo().performClick()
        waitFor("同じ内容で再試行")
        compose.onNodeWithText("同じ内容で再試行").performScrollTo().performClick()
        waitFor("コーチに話す")
        conversation("助言だけ再試行").performClick()
        conversation("保存済み進捗", true)
        assertEquals(requests[0], requests[1])
        send("1回トレーニングした")
        waitFor("候補を確認する")
        conversation("確認して保存へ").performClick()
        compose.onNodeWithText("この内容で保存する").performScrollTo().performClick()
        waitFor("コーチに話す")
        runBlocking { goals.forEach { goal ->
            val saved = api.getGoal(goal.id)
            assertEquals(1, saved.progressLogs.size)
            assertEquals(1.0, saved.metrics.single().currentValue, 0.0)
        } }
        compose.onNodeWithText("目標一覧").performClick()
        waitFor("本を読む")
        compose.onNodeWithText("本を読む").performClick()
        listOf("概要", "進捗", "記録", "計画").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("進捗メモ").assertDoesNotExist()
        compose.onNodeWithText("記録").performClick()
        compose.onNodeWithText("進捗メモ").assertExists()
        compose.onNodeWithText("進捗メモ").performTextReplacement("既存の入力")
        compose.onNodeWithText("音声入力").performScrollTo().performClick()
        compose.onNodeWithText("録音開始").performClick()
        compose.runOnIdle { speech.result("さらに1冊読み終えた") }
        compose.onNodeWithText("本文に追加").performClick()
        compose.onNodeWithText("進捗メモ").assertTextContains("既存の入力\nさらに1冊読み終えた")
        assertEquals(3, requests.size)
        compose.onNodeWithText("計画").performClick()
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
        compose.onNodeWithText("送信").performClick()
    }
    private fun waitFor(text: String, substring: Boolean = false) {
        compose.waitUntil(10000) { compose.onAllNodesWithText(text, substring=substring).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun conversation(text: String, substring: Boolean = false): SemanticsNodeInteraction {
        waitFor(text, substring)
        compose.onNodeWithTag("coach-conversation").performScrollToNode(hasText(text, substring=substring))
        return compose.onNodeWithText(text, substring=substring)
    }
}
