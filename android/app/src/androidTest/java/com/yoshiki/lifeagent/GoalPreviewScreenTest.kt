package com.yoshiki.lifeagent

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yoshiki.lifeagent.ui.GoalPreviewScreen
import com.yoshiki.lifeagent.ui.GoalUiState
import com.yoshiki.lifeagent.ui.MetricEdit
import com.yoshiki.lifeagent.ui.MilestoneEdit
import com.yoshiki.lifeagent.ui.theme.LifeAgentTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GoalPreviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun displaysPlanAndDispatchesEditAndConfirmEvents() {
        var changedMetric: Pair<Int, String>? = null
        var confirmed = false
        var screenState by mutableStateOf(
            GoalUiState(
                title = "読書する",
                targetDate = "2026-12-31",
                metrics = listOf(MetricEdit("読了冊数", "12", "冊")),
                milestones = listOf(
                    MilestoneEdit("本を選ぶ", "2026-10-01"),
                    MilestoneEdit("半分読む", "2026-11-01"),
                    MilestoneEdit("読み終える", "2026-12-31"),
                ),
            )
        )
        composeRule.setContent {
            LifeAgentTheme {
                GoalPreviewScreen(
                    state = screenState,
                    onBack = {},
                    onMetricNameChange = { index, value ->
                        changedMetric = index to value
                        screenState = screenState.copy(
                            metrics = screenState.metrics.mapIndexed { itemIndex, item ->
                                if (index == itemIndex) item.copy(name = value) else item
                            }
                        )
                    },
                    onMetricValueChange = { _, _ -> },
                    onMetricUnitChange = { _, _ -> },
                    onMilestoneTitleChange = { _, _ -> },
                    onMilestoneDateChange = { _, _ -> },
                    onConfirm = { confirmed = true },
                )
            }
        }

        composeRule.onNodeWithText("読了冊数").performTextReplacement("読書数")
        composeRule.onNodeWithText("この計画で保存する").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(0 to "読書数", changedMetric)
        assertTrue(confirmed)
    }
}
