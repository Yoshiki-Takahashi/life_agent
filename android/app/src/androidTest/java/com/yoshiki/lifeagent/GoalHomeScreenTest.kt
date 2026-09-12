package com.yoshiki.lifeagent

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yoshiki.lifeagent.data.GoalSummary
import com.yoshiki.lifeagent.ui.GoalHomeScreen
import com.yoshiki.lifeagent.ui.GoalListUiState
import com.yoshiki.lifeagent.ui.theme.LifeAgentTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GoalHomeScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun goalCardDisplaysSummaryAndDispatchesSelection() {
        var selectedId: String? = null
        composeRule.setContent {
            LifeAgentTheme {
                GoalHomeScreen(
                    state = GoalListUiState(goals = listOf(summary())),
                    onCreateGoal = {},
                    onGoalClick = { selectedId = it },
                    onRetry = {},
                    onSignOut = {},
                )
            }
        }

        composeRule.onNodeWithText("読書する").performClick()

        assertEquals("goal-1", selectedId)
    }

    @Test
    fun emptyStateOffersGoalCreation() {
        var createClicked = false
        composeRule.setContent {
            LifeAgentTheme {
                GoalHomeScreen(
                    state = GoalListUiState(),
                    onCreateGoal = { createClicked = true },
                    onGoalClick = {},
                    onRetry = {},
                    onSignOut = {},
                )
            }
        }

        composeRule.onNodeWithText("Goalを作る").performClick()

        assertTrue(createClicked)
    }
}

private fun summary() = GoalSummary(
    id = "goal-1",
    title = "読書する",
    targetDate = "2026-12-31",
    status = "active",
    metricCount = 1,
    milestoneCount = 3,
    createdAt = "2026-09-12T00:00:00Z",
    updatedAt = "2026-09-12T00:00:00Z",
)
