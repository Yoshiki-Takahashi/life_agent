package com.yoshiki.lifeagent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yoshiki.lifeagent.data.GoalRepository

@Composable
fun LifeAgentApp(repository: GoalRepository, userId: String, onSignOut: () -> Unit) {
    val navController = rememberNavController()
    val planViewModel: GoalViewModel = viewModel(
        key = "plan-$userId",
        factory = GoalViewModel.factory(repository),
    )
    val listViewModel: GoalListViewModel = viewModel(
        key = "list-$userId",
        factory = GoalListViewModel.factory(repository),
    )
    val detailViewModel: GoalDetailViewModel = viewModel(
        key = "detail-$userId",
        factory = GoalDetailViewModel.factory(repository),
    )
    val planState by planViewModel.uiState.collectAsState()
    val listState by listViewModel.uiState.collectAsState()
    val detailState by detailViewModel.uiState.collectAsState()

    LaunchedEffect(planState.navigationGoalId) {
        planState.navigationGoalId?.let { goalId ->
            navController.navigate("goals/$goalId") { popUpTo("home") }
            planViewModel.resetDraft()
        }
    }
    LaunchedEffect(planState.navigateToPreview) {
        if (planState.navigateToPreview) {
            navController.navigate("preview")
            planViewModel.consumePreviewNavigation()
        }
    }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            LaunchedEffect(Unit) { listViewModel.loadGoals() }
            GoalHomeScreen(
                state = listState,
                onCreateGoal = { navController.navigate("create") },
                onGoalClick = { navController.navigate("goals/$it") },
                onRetry = listViewModel::loadGoals,
                onSignOut = onSignOut,
            )
        }
        composable("create") {
            GoalFormScreen(
                state = planState,
                onTitleChange = planViewModel::updateTitle,
                onDescriptionChange = planViewModel::updateDescription,
                onTargetDateChange = planViewModel::updateTargetDate,
                onPreview = planViewModel::previewGoal,
                onBack = navController::navigateUp,
            )
        }
        composable("preview") {
            GoalPreviewScreen(
                state = planState,
                onBack = navController::navigateUp,
                onMetricNameChange = planViewModel::updateMetricName,
                onMetricValueChange = planViewModel::updateMetricValue,
                onMetricUnitChange = planViewModel::updateMetricUnit,
                onMilestoneTitleChange = planViewModel::updateMilestoneTitle,
                onMilestoneDateChange = planViewModel::updateMilestoneDate,
                onConfirm = planViewModel::confirmGoal,
            )
        }
        composable(
            route = "goals/{goalId}",
            arguments = listOf(navArgument("goalId") { type = NavType.StringType }),
        ) { entry ->
            val goalId = requireNotNull(entry.arguments?.getString("goalId"))
            LaunchedEffect(goalId) { detailViewModel.loadGoal(goalId) }
            GoalDetailScreen(
                state = detailState,
                onBack = navController::navigateUp,
                onRetry = { detailViewModel.loadGoal(goalId) },
            )
        }
    }
}
