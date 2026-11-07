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

class GoalCompletionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun completeAndReopenArchivedGoalThroughRealApi() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("networkE2E") == "true")
        val owner = "completion-e2e-${UUID.randomUUID()}"
        val api = Network.createGoalRepository("http://127.0.0.1:18007/") { owner }
        val goal = runBlocking {
            val plan = api.previewGoal("読書の完了確認", null, LocalDate.now().plusDays(30).toString())
            val saved = api.confirmGoal(plan)
            api.recordProgress(saved.id, "一冊読んだ", listOf(MetricProgressInput(saved.metrics[0].id, 1.0)), "initial")
        }
        compose.setContent { LifeAgentTheme { LifeAgentApp(api, owner, {},
            Network.createFeedbackRepository("http://127.0.0.1:18007/") { owner }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(goal.title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(goal.title).performClick()
        compose.onNodeWithText("完了", useUnmergedTree = true).performClick()
        compose.onNodeWithText("キャンセル").performClick()
        runBlocking { assertEquals("active", api.getGoal(goal.id).status) }
        compose.onNodeWithText("完了", useUnmergedTree = true).performClick()
        compose.onNodeWithText("完了する").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("完了済み").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("戻る").performClick()
        compose.onNodeWithText("完了済みの目標").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(goal.title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(goal.title).performClick()
        compose.onNodeWithText("記録", useUnmergedTree = true).performClick()
        compose.onNodeWithText("進捗を保存").assertDoesNotExist()
        runBlocking {
            val archived = api.getGoal(goal.id)
            assertEquals("completed", archived.status)
            assertEquals(1, archived.progressLogs.size)
        }
    }
}
