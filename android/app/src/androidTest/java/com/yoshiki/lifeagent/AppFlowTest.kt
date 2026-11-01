package com.yoshiki.lifeagent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.yoshiki.lifeagent.data.*
import com.yoshiki.lifeagent.ui.LifeAgentApp
import com.yoshiki.lifeagent.ui.theme.LifeAgentTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** One user journey through real HTTP and PostgreSQL; no screenshots or internal UI snapshots. */
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

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
        compose.setContent { LifeAgentTheme { LifeAgentApp(repository, owner, {}) } }
        waitFor("本を読む")
        compose.onNodeWithText("コーチ").performClick()
        send("全部の目標を相談したい")
        waitFor("保存済みの全目標", true)
        assertTrue(compose.onNodeWithText("保存済みの全目標", substring=true)
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString().contains("筋トレを続ける"))
        send("1冊読み終えた")
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
        compose.onNodeWithText("計画").performClick()
        compose.onNodeWithText("計画を見直す").assertExists()
        compose.onNodeWithText("コーチ").performClick()
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
