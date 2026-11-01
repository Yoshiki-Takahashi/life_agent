package com.yoshiki.lifeagent.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.res.painterResource
import com.yoshiki.lifeagent.R
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
    val coachViewModel: CoachViewModel = viewModel(
        key = "coach-$userId", factory = CoachViewModel.factory(repository),
    )
    DisposableEffect(userId) {
        onDispose { coachViewModel.reset() }
    }
    val coachState by coachViewModel.uiState.collectAsState()
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

    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val group = when (route) {
        "create", "preview" -> "create"
        "coach", "coach-confirm" -> "coach"
        else -> "home"
    }
    val detailBusy = detailState.isSavingProgress || detailState.isParsing ||
        detailState.isApplyingReplan || detailState.isLoadingReplan
    val menuEnabled = when (group) {
        "coach" -> coachState.canLeave
        "create" -> !planState.isPreviewing && !planState.isConfirming
        else -> !detailBusy
    }
    val hasDraft = when (group) {
        "coach" -> coachState.hasDraft
        "create" -> planState.title.isNotBlank() || planState.description.isNotBlank() ||
            planState.targetDate.isNotBlank() || planState.metrics.isNotEmpty()
        else -> route in setOf("goals/{goalId}", "review") && (detailState.progressBody.isNotBlank() ||
            detailState.metricProgress.any { it.value.isNotBlank() } ||
            detailState.replanReason.isNotBlank() || detailState.replanProposal != null)
    }
    var pendingDestination by remember { mutableStateOf<String?>(null) }
    fun navigate(destination: String) {
        navController.navigate(destination) {
            if (destination != "review") popUpTo("home")
            launchSingleTop = true
        }
    }
    fun requestNavigation(destination: String) {
        if (!menuEnabled || route == destination) return
        if (hasDraft) pendingDestination = destination else navigate(destination)
    }
    if (pendingDestination != null) AlertDialog(
        onDismissRequest = { pendingDestination = null },
        title = { Text("未保存の入力があります") },
        text = { Text("入力と候補を破棄して移動しますか？保存済みの会話と記録は残ります。") },
        confirmButton = { TextButton(onClick = {
            when (group) {
                "coach" -> coachViewModel.discardDraft()
                "create" -> planViewModel.resetDraft()
                else -> detailState.goal?.id?.let(detailViewModel::loadGoal)
            }
            val destination = requireNotNull(pendingDestination)
            pendingDestination = null
            navigate(destination)
        }) { Text("破棄して移動") } },
        dismissButton = { TextButton(onClick = { pendingDestination = null }) { Text("編集を続ける") } },
    )
    Scaffold(
        modifier = Modifier.imePadding(),
        bottomBar = {
            NavigationBar {
                listOf(Triple("home", "目標一覧", R.drawable.nav_goals), Triple("create", "新規作成", R.drawable.nav_create),
                    Triple("coach", "コーチ", R.drawable.nav_coach)).forEach { (destination, label, icon) ->
                    NavigationBarItem(
                        selected = group == destination, enabled = menuEnabled,
                        onClick = { requestNavigation(destination) },
                        icon = { Icon(painterResource(icon), contentDescription = null) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController = navController, startDestination = "home",
            modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable("home") {
                LaunchedEffect(Unit) { listViewModel.loadGoals() }
                GoalHomeScreen(
                    state = listState,
                    onCreateGoal = { requestNavigation("create") },
                    onGoalClick = { detailViewModel.loadGoal(it); navController.navigate("goals/$it") },
                    onRetry = listViewModel::loadGoals,
                    onSignOut = onSignOut,
                )
            }
            composable("coach") {
                CoachScreen(
                    state = coachState,
                    onBack = { requestNavigation("home") },
                    onBodyChange = coachViewModel::updateBody,
                    onSend = coachViewModel::sendMessage,
                    onConfirm = { coachViewModel.confirmCandidate(); navController.navigate("coach-confirm") { launchSingleTop = true } },
                    onManual = {
                        coachViewModel.startManualConfirmation()
                        if (coachViewModel.uiState.value.phase == CoachPhase.CONFIRMING) navController.navigate("coach-confirm") { launchSingleTop = true }
                    },
                    onCancel = coachViewModel::cancelCandidate,
                    onAdviceRetry = coachViewModel::loadAdvice,
                )
            }
            composable("coach-confirm") {
                LaunchedEffect(coachState.phase) {
                    if (coachState.phase == CoachPhase.EDITING) navController.navigateUp()
                }
                CoachConfirmationScreen(coachState,
                    onBack = { coachViewModel.cancelCandidate(); navController.navigateUp() },
                    onMetricChange = coachViewModel::updateMetric, onSave = coachViewModel::saveProgress)
            }
            composable("review") {
                ReviewScreen(detailState.goal, userId, onBack = { navController.navigateUp() })
            }
            composable("create") {
                GoalFormScreen(
                    state = planState,
                    onTitleChange = planViewModel::updateTitle,
                    onDescriptionChange = planViewModel::updateDescription,
                    onTargetDateChange = planViewModel::updateTargetDate,
                    onPreview = planViewModel::previewGoal,
                    onBack = { requestNavigation("home") },
                )
            }
            composable("preview") {
                GoalPreviewScreen(
                    state = planState,
                    onBack = { if (menuEnabled) navController.navigateUp() },
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
                LaunchedEffect(goalId) {
                    if (detailViewModel.uiState.value.goal?.id != goalId &&
                        !detailViewModel.uiState.value.isLoading) detailViewModel.loadGoal(goalId)
                }
                GoalDetailScreen(
                    state = detailState,
                    onBack = { requestNavigation("home") },
                    onRetry = { detailViewModel.loadGoal(goalId) },
                    onProgressBodyChange = detailViewModel::updateProgressBody,
                    onMetricProgressChange = detailViewModel::updateMetricProgress,
                    onSaveProgress = detailViewModel::saveProgress,
                    onParseProgress = detailViewModel::parseProgress,
                    onCancelPreview = detailViewModel::cancelPreview,
                    onLoadAdvice = detailViewModel::loadAdvice,
                    onReview = { requestNavigation("review") },
                    onReplanReasonChange = detailViewModel::updateReplanReason,
                    onPreviewReplan = detailViewModel::previewReplan,
                    onCancelReplan = detailViewModel::cancelReplan,
                    onApplyReplan = detailViewModel::applyReplan,
                )
            }
        }
    }
}
