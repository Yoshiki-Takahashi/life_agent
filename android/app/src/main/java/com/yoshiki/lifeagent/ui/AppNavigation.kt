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
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
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
import com.yoshiki.lifeagent.data.FeedbackRepository

@Composable
fun LifeAgentApp(repository: GoalRepository, userId: String, onSignOut: () -> Unit,
    feedbackRepository: FeedbackRepository,
    sessionKey: String = userId, accountAction: @Composable () -> Unit = {}) {
    val navController = rememberNavController()
    val planViewModel: GoalViewModel = viewModel(
        key = "plan-$sessionKey",
        factory = GoalViewModel.factory(repository),
    )
    val listViewModel: GoalListViewModel = viewModel(
        key = "list-$sessionKey",
        factory = GoalListViewModel.factory(repository),
    )
    val detailViewModel: GoalDetailViewModel = viewModel(
        key = "detail-$sessionKey",
        factory = GoalDetailViewModel.factory(repository),
    )
    val coachViewModel: CoachViewModel = viewModel(
        key = "coach-$sessionKey", factory = CoachViewModel.factory(repository),
    )
    val feedbackViewModel: FeedbackViewModel = viewModel(
        key = "feedback-$sessionKey", factory = FeedbackViewModel.factory(feedbackRepository),
    )
    val activity = LocalContext.current as? Activity
    DisposableEffect(sessionKey) {
        onDispose {
            coachViewModel.reset(); planViewModel.resetDraft()
            if (activity?.isChangingConfigurations != true) feedbackViewModel.reset()
        }
    }
    val feedbackState by feedbackViewModel.uiState.collectAsState()
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
            navController.navigate("preview") { launchSingleTop = true }
            planViewModel.consumePreviewNavigation()
        }
    }
    LaunchedEffect(planState.navigateToQuestions) {
        if (planState.navigateToQuestions) {
            navController.navigate("questions") { launchSingleTop = true }
            planViewModel.consumeQuestionsNavigation()
        }
    }

    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val group = when (route) {
        "create", "questions", "preview" -> "create"
        "coach", "coach-confirm" -> "coach"
        "feedback" -> "feedback"
        else -> "home"
    }
    val detailBusy = detailState.isCompleting || detailState.isSavingProgress || detailState.isParsing ||
        detailState.isApplyingReplan || detailState.isLoadingReplan
    val menuEnabled = when (group) {
        "coach" -> coachState.canLeave
        "create" -> !planState.isBusy
        "feedback" -> !feedbackState.isSending
        else -> !detailBusy
    }
    val hasDraft = when (group) {
        "coach" -> coachState.hasDraft
        "create" -> planState.title.isNotBlank() || planState.description.isNotBlank() ||
            planState.targetDate.isNotBlank() || planState.metrics.isNotEmpty()
        "feedback" -> false // Keep the memo and its delivery ID when switching tabs.
        else -> route in setOf("goals/{goalId}", "review") && (detailState.progressBody.isNotBlank() ||
            detailState.metricProgress.any { it.value.isNotBlank() } ||
            detailState.replanReason.isNotBlank() || detailState.replanProposal != null)
    }
    var pendingDestination by remember { mutableStateOf<String?>(null) }
    GoalReplacementDialog(planState, planViewModel::confirmReplacement, planViewModel::dismissReplacement)
    fun navigate(destination: String) {
        if (destination == "feedback") feedbackViewModel.openedFrom(
            if (route == "goals/{goalId}") "goal-detail" else if (route == "completed") "home" else route.ifBlank { "home" }
        )
        navController.navigate(destination) {
            if (destination != "review" && destination != "feedback") popUpTo("home")
            launchSingleTop = true
        }
    }
    fun requestNavigation(destination: String) {
        if (!menuEnabled || route == destination) return
        if (hasDraft && destination != "feedback") pendingDestination = destination else navigate(destination)
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
                    Triple("coach", "コーチ", R.drawable.nav_coach),
                    Triple("feedback", "フィードバック", R.drawable.nav_feedback)).forEach { (destination, label, icon) ->
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
                    state = listState.copy(goals = listState.goals.filter { it.status == "active" }),
                    onCreateGoal = { requestNavigation("create") },
                    onGoalClick = { detailViewModel.loadGoal(it); navController.navigate("goals/$it") },
                    onRetry = listViewModel::loadGoals,
                    onSignOut = onSignOut,
                    accountAction = accountAction,
                    onCompletedGoals = { navController.navigate("completed") },
                )
            }
            composable("completed") {
                LaunchedEffect(Unit) { listViewModel.loadGoals() }
                GoalHomeScreen(
                    state = listState.copy(goals = listState.goals.filter { it.status == "completed" }),
                    onCreateGoal = {}, onGoalClick = { detailViewModel.loadGoal(it); navController.navigate("goals/$it") },
                    onRetry = listViewModel::loadGoals, onSignOut = onSignOut,
                    completed = true, onBack = { navController.navigateUp() },
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
            composable("feedback") {
                FeedbackScreen(feedbackState, feedbackViewModel::updateBody, feedbackViewModel::send,
                    onBack = { navController.navigateUp() })
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
                    onPreview = planViewModel::requestQuestions,
                    onBack = { requestNavigation("home") },
                )
            }
            composable("questions") {
                GoalQuestionsScreen(
                    planState, planViewModel::answerQuestion, planViewModel::previewGoal,
                    onBack = { if (menuEnabled) navController.navigateUp() },
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
                    onAddMetric = planViewModel::addMetric,
                    onRemoveMetric = planViewModel::removeMetric,
                    onAddMilestone = planViewModel::addMilestone,
                    onRemoveMilestone = planViewModel::removeMilestone,
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
                    onComplete = detailViewModel::completeGoal,
                )
            }
        }
    }
}
