package com.yoshiki.lifeagent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import com.yoshiki.lifeagent.data.*
import com.yoshiki.lifeagent.ui.LifeAgentApp
import com.yoshiki.lifeagent.ui.theme.LifeAgentTheme
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class FeedbackFlowTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun bottomTabKeepsMemoAndRetriesSameSubmissionBeforeStartingAnother() {
        val goals = object : GoalRepository by Network.createGoalRepository("http://127.0.0.1:1/", { "unused" }) {
            override suspend fun listGoals(): List<GoalSummary> = emptyList()
        }
        val requests = mutableListOf<FeedbackRequest>()
        val feedback = FeedbackRepository {
            requests += it
            if (requests.size == 1) throw IOException("lost response")
            FeedbackResult(40, "https://github.com/example/app/issues/40")
        }
        compose.setContent { LifeAgentTheme { LifeAgentApp(goals, "test-owner", {}, feedback) } }
        compose.onNodeWithText("コーチ").assertIsDisplayed()
        compose.onNodeWithText("新規作成").performClick()
        compose.onNodeWithText("タイトル").performTextInput("入力中の目標")
        compose.onNodeWithText("フィードバック").performClick()
        compose.activityRule.scenario.onActivity {
            it.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithText("入力中の目標").assertExists()
        compose.onNodeWithText("フィードバック").performClick()
        compose.onNodeWithText("改善メモを送信").assertIsNotEnabled()
        compose.onNodeWithText("気づいたこと・修正したいこと").performTextInput("期限を変更しやすくしたい")
        compose.onNodeWithText("目標一覧").performClick()
        compose.onNodeWithText("フィードバック").performClick()
        compose.onNodeWithText("期限を変更しやすくしたい").assertExists()
        compose.onNodeWithText("改善メモを送信").performScrollTo().performClick()
        compose.waitUntil { requests.size == 1 }
        compose.onNodeWithText("気づいたこと・修正したいこと").assertIsNotEnabled()
        compose.onNodeWithText("目標一覧").performClick()
        compose.onNodeWithText("フィードバック").performClick()
        compose.onNodeWithText("同じ内容で送信結果を確認").performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithText("改善メモを送信しました：Issue #40").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(requests[0], requests[1])
        compose.onNodeWithText("GitHubで確認する").assertExists()
        InstrumentationRegistry.getArguments().getString("feedbackScreenshot")?.let { filename ->
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            InstrumentationRegistry.getInstrumentation().targetContext.openFileOutput(filename, 0).use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithText("気づいたこと・修正したいこと").performScrollTo().performTextInput("別の改善点")
        compose.onNodeWithText("改善メモを送信").performScrollTo().performClick()
        compose.waitUntil { requests.size == 3 }
        assertNotEquals(requests[1].clientRequestId, requests[2].clientRequestId)
    }
}
