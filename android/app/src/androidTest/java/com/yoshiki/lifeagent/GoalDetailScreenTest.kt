package com.yoshiki.lifeagent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.yoshiki.lifeagent.data.Goal
import com.yoshiki.lifeagent.data.Metric
import com.yoshiki.lifeagent.data.Milestone
import com.yoshiki.lifeagent.ui.GoalDetailScreen
import com.yoshiki.lifeagent.ui.GoalDetailUiState
import com.yoshiki.lifeagent.ui.MetricProgressEdit
import com.yoshiki.lifeagent.ui.theme.LifeAgentTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GoalDetailScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun progressEntryDispatchesSaveEvents() {
        var saved = false
        var changedBody: String? = null
        var changedMetric: Pair<Int, String>? = null
        var screenState by mutableStateOf(
            GoalDetailUiState(
                goal = goal(),
                metricProgress = listOf(
                    MetricProgressEdit(
                        metricId = "metric-1",
                        name = "読了冊数",
                        currentValue = 0.0,
                        targetValue = 12.0,
                        unit = "冊",
                    )
                ),
            )
        )
        composeRule.setContent {
            LifeAgentTheme {
                GoalDetailScreen(
                    state = screenState,
                    onBack = {},
                    onRetry = {},
                    onProgressBodyChange = {
                        changedBody = it
                        screenState = screenState.copy(progressBody = it)
                    },
                    onMetricProgressChange = { index, value ->
                        changedMetric = index to value
                        screenState = screenState.copy(
                            metricProgress = screenState.metricProgress.mapIndexed { itemIndex, item ->
                                if (itemIndex == index) item.copy(value = value) else item
                            }
                        )
                    },
                    onSaveProgress = { saved = true },
                )
            }
        }

        composeRule.onNodeWithText("進捗メモ").performTextReplacement("1冊読んだ")
        composeRule.onNodeWithText("読了冊数 の今回値").performTextReplacement("1")
        composeRule.onNodeWithText("進捗を保存").performScrollTo().performClick()

        assertEquals("1冊読んだ", changedBody)
        assertEquals(0 to "1", changedMetric)
        assertTrue(saved)
    }
}

private fun goal() = Goal(
    id = "goal-1",
    title = "読書する",
    description = "毎日読む",
    targetDate = "2026-12-31",
    status = "active",
    createdAt = "2026-10-01T00:00:00Z",
    metrics = listOf(
        Metric(
            name = "読了冊数",
            targetValue = 12.0,
            unit = "冊",
            position = 0,
            id = "metric-1",
        )
    ),
    milestones = listOf(Milestone("読み終える", "2026-12-31")),
)
