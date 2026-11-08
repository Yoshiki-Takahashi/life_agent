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

class MetricTypesFlowTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun typedEntriesPersistAndCoachRecordsAnObservation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("networkE2E") == "true")
        val owner = "types-${UUID.randomUUID()}"
        val api = Network.createGoalRepository("http://127.0.0.1:18007/") { owner }
        val deadline = LocalDate.now().plusDays(90).toString()
        val goal = runBlocking { api.confirmGoal(GoalPlan("種別テスト", null, deadline, listOf(
            Metric("ページ", 100.0, "ページ"),
            Metric("ジム", 10.0, "日", kind = "occurrence"),
            Metric("体脂肪率", 15.0, "%", kind = "measurement"),
            Metric("旅行準備", 2.0, "項目", kind = "checklist", checklistItems = listOf("日付を決める", "宿を予約する")),
        ), listOf(Milestone("振り返る", deadline)))) }
        compose.setContent { LifeAgentTheme { LifeAgentApp(api, owner, {},
            Network.createFeedbackRepository("http://127.0.0.1:18007/") { owner }) } }
        waitFor("種別テスト")
        compose.onNodeWithText("種別テスト").performClick()
        waitFor("概要")
        compose.onNodeWithText("記録", useUnmergedTree = true).performClick()
        compose.onNodeWithText("進捗メモ").performTextReplacement("今日は読書とジム、測定と旅行準備をした")
        compose.onNodeWithText("ページ の今回値").performScrollTo().performTextReplacement("2")
        compose.onNodeWithText("今日（${LocalDate.now()}）実行した").performScrollTo().performClick()
        compose.onNodeWithText("体脂肪率 の測定値").performScrollTo().performTextReplacement("25")
        compose.onNodeWithText("日付を決める").performScrollTo().performClick()
        compose.onNodeWithText("進捗を保存").performScrollTo().performClick()
        waitFor("進捗を保存しました")
        runBlocking {
            val saved = api.getGoal(goal.id)
            assertEquals(listOf(2.0, 1.0, 25.0, 1.0), saved.metrics.map { it.currentValue })
            assertEquals(listOf("日付を決める"), saved.metrics[3].completedItems)
        }
        compose.onNodeWithText("進捗", useUnmergedTree = true).performClick()
        compose.onNodeWithText("色付きは実行を記録した日です。空欄は未記録で、未実施とは限りません。").performScrollTo().assertIsDisplayed()
        screenshot("issue47-calendar.png")
        compose.onNodeWithText("縦軸: 測定値（%）、横軸: 記録日時").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 / 2 項目完了").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("宿を予約する").performScrollTo().assertIsDisplayed()
        screenshot("issue47-measurement-checklist.png")
        compose.onNodeWithText("コーチ").performClick()
        compose.onNodeWithText("コーチに話す").performTextReplacement("種別テスト 体脂肪率20%を記録したい")
        compose.onNodeWithText("送信").performClick()
        waitFor("確認して保存へ")
        compose.onNodeWithText("確認して保存へ").performScrollTo().performClick()
        waitFor("今回値を確認")
        compose.onNodeWithText("この内容で保存する").performScrollTo().performClick()
        waitFor("コーチに話す")
        compose.waitUntil(10000) { runBlocking { api.getGoal(goal.id).metrics[2].currentValue == 20.0 } }
        runBlocking { assertEquals(2, api.getGoal(goal.id).progressLogs.size) }
    }

    @Test fun realAiPlanCanBeEditedSavedAndReopened() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realAI") == "true")
        val owner = "real-creation-${UUID.randomUUID()}"
        val api = Network.createGoalRepository("http://127.0.0.1:18007/") { owner }
        compose.setContent { LifeAgentTheme { LifeAgentApp(api, owner, {},
            Network.createFeedbackRepository("http://127.0.0.1:18007/") { owner }) } }
        compose.onNodeWithText("新規作成").performClick()
        compose.onNodeWithText("タイトル").performTextReplacement("体脂肪率の測定値の推移を記録する")
        compose.onNodeWithText("Goal期限を選ぶ").performScrollTo().performClick()
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1))
            .perform(androidx.test.espresso.action.ViewActions.click())
        compose.onNodeWithText("質問へ進む").performScrollTo().performClick()
        waitFor("目標を具体化")
        val count = compose.onAllNodesWithText("No").fetchSemanticsNodes().size
        assertTrue(count in 3..8)
        repeat(count) { index ->
            compose.onNode(hasText("No") and hasAnyAncestor(hasTestTag("question-$index"))).performScrollTo().performClick()
        }
        compose.onNodeWithText("回答から計画を作る").performScrollTo().performClick()
        waitFor("計画を確認")
        fun field(label: String) = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("metric-0")))
        field("Metric名").performScrollTo().performTextReplacement("体脂肪率（確認済み）")
        field("測定値").performScrollTo().performClick()
        field("目標の測定値").performScrollTo().performTextReplacement("15")
        field("単位").performScrollTo().performTextReplacement("%")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        field("Metric名").performScrollTo()
        screenshot("issue47-plan-confirmation.png")
        compose.onNodeWithText("この計画で保存する").performScrollTo().performClick()
        waitFor("概要")
        runBlocking {
            val saved = api.getGoal(api.listGoals().single().id)
            assertEquals("measurement", saved.metrics.first().kind)
            assertEquals("体脂肪率（確認済み）", saved.metrics.first().name)
            assertEquals(15.0, saved.metrics.first().targetValue, 0.0)
            assertEquals("%", saved.metrics.first().unit)
        }
        compose.onNodeWithText("戻る").performClick()
        waitFor("体脂肪率の測定値の推移を記録する")
        compose.onNodeWithText("体脂肪率の測定値の推移を記録する").performClick()
        waitFor("体脂肪率（確認済み）")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(45000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}
