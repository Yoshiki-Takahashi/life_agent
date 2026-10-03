package com.yoshiki.lifeagent

import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.yoshiki.lifeagent.data.Advice
import com.yoshiki.lifeagent.data.GoalRepository
import com.yoshiki.lifeagent.data.Network
import com.yoshiki.lifeagent.ui.GoalDetailScreen
import com.yoshiki.lifeagent.ui.GoalDetailViewModel
import com.yoshiki.lifeagent.ui.theme.LifeAgentTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Opt-in network test against the isolated local Core API and PostgreSQL. */
class ProgressFlowTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun parseConfirmSaveAdviceAndRevisit() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("networkE2E") == "true")
        // A stable owner is required across requests.
        val owner = "e2e-${UUID.randomUUID()}"
        val api = Network.createGoalRepository("http://127.0.0.1:18007/") { owner }
        val goal = runBlocking {
            val deadline = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() + 90L * 86400000))
            api.confirmGoal(api.previewGoal("12冊の本を読む", "毎日の読書を少しずつ積み重ねる", deadline))
        }
        var failAdvice = true
        val faultInjection = object : GoalRepository by api {
            override suspend fun getAdvice(goalId: String): Advice {
                if (failAdvice) { failAdvice = false; error("Simulated advice failure") }
                return api.getAdvice(goalId)
            }
        }
        val vm = GoalDetailViewModel(faultInjection)
        composeRule.setContent {
            val state by vm.uiState.collectAsState()
            LifeAgentTheme {
                GoalDetailScreen(state, {}, { vm.loadGoal(goal.id) }, vm::updateProgressBody,
                    vm::updateMetricProgress, vm::saveProgress, vm::parseProgress,
                    vm::cancelPreview, vm::loadAdvice)
            }
        }
        composeRule.runOnIdle { vm.loadGoal(goal.id) }
        composeRule.waitUntil(10000) { vm.uiState.value.goal != null }
        composeRule.onNodeWithText("進捗メモ").performScrollTo().performTextReplacement("今日は2冊読み終えた")
        composeRule.onNodeWithText("進捗メモを解析").performScrollTo().performClick()
        composeRule.waitUntil(10000) { vm.uiState.value.hasPreview }
        assertEquals("2", vm.uiState.value.metricProgress.single().value)
        assertEquals(0, runBlocking { api.getGoal(goal.id) }.progressLogs.size)
        composeRule.onNodeWithText("進捗を保存").performScrollTo()
        screenshot("weekend7-candidates.png")
        composeRule.onNodeWithText("候補を取り消して手入力").performScrollTo().performClick()
        assertEquals("", vm.uiState.value.metricProgress.single().value)
        composeRule.onNodeWithText("進捗メモを解析").performScrollTo().performClick()
        composeRule.waitUntil(10000) { vm.uiState.value.hasPreview }
        composeRule.onNodeWithText("読了冊数 の今回値").performScrollTo().performTextReplacement("1")
        composeRule.onNodeWithText("進捗を保存").performScrollTo().performClick()
        composeRule.waitUntil(10000) { vm.uiState.value.adviceError != null }
        composeRule.onNodeWithText("助言だけ再試行").performScrollTo()
        screenshot("weekend7-advice-retry.png")
        assertEquals(1, runBlocking { api.getGoal(goal.id) }.progressLogs.size)
        composeRule.onNodeWithText("助言だけ再試行").performClick()
        composeRule.waitUntil(35000) { vm.uiState.value.advice != null }
        assertEquals(1.0, vm.uiState.value.goal!!.metrics.single().currentValue, 0.0)
        composeRule.onNodeWithText("助言を取得").performScrollTo()
        screenshot("weekend7-advice.png")
        val saved = runBlocking { api.getGoal(goal.id) }
        assertEquals(1, saved.progressLogs.size)
        assertEquals(1.0, saved.metrics.single().currentValue, 0.0)
        composeRule.runOnIdle { vm.loadGoal(goal.id) }
        composeRule.waitUntil(10000) { !vm.uiState.value.isLoading }
        composeRule.onNodeWithText("読了冊数: +1 冊").performScrollTo()
        screenshot("weekend7-history.png")
    }

    private fun screenshot(name: String) {
        composeRule.waitForIdle()
        android.os.SystemClock.sleep(300)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
