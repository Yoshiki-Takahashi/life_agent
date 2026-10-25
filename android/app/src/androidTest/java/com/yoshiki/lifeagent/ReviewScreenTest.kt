package com.yoshiki.lifeagent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.yoshiki.lifeagent.data.Goal
import com.yoshiki.lifeagent.ui.ReviewScreen
import com.yoshiki.lifeagent.ui.theme.LifeAgentTheme
import org.junit.Rule
import org.junit.Test

class ReviewScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun manualReviewWorksWithoutNotificationPermission() {
        compose.setContent {
            LifeAgentTheme {
                ReviewScreen(Goal("test", "振り返りテスト", null, "2026-12-31", "active",
                    "2026-10-25T00:00:00Z", emptyList(), emptyList()),
                    "review-screen-test", {})
            }
        }
        compose.onNodeWithText("直近30日").performClick()
        compose.onAllNodesWithText("直近30日", substring = true).assertCountEquals(2)
        compose.onNodeWithText("この期間の記録はありません。", substring = true).assertExists()
        compose.onNodeWithText("通知設定を保存").performScrollTo().assertExists()
        compose.onNodeWithText("タイムゾーン（例: Asia/Tokyo）").performTextReplacement("invalid-zone")
        compose.onNodeWithText("通知設定を保存").performClick()
        compose.onNodeWithText("有効なタイムゾーンを入力してください").assertExists()
    }
}
