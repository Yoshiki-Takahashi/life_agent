package com.yoshiki.lifeagent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yoshiki.lifeagent.data.Network
import com.yoshiki.lifeagent.ui.GoalDetailScreen
import com.yoshiki.lifeagent.ui.GoalFormScreen
import com.yoshiki.lifeagent.ui.GoalViewModel
import com.yoshiki.lifeagent.ui.theme.LifeAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestLocalNetworkAccessForDebug()
        setContent {
            LifeAgentTheme {
                val repository = remember {
                    Network.createGoalRepository(BuildConfig.CORE_API_BASE_URL)
                }
                val viewModel: GoalViewModel = viewModel(factory = GoalViewModel.factory(repository))
                val state by viewModel.uiState.collectAsState()
                val navController = rememberNavController()

                LaunchedEffect(state.navigationGoalId) {
                    state.navigationGoalId?.let { goalId ->
                        navController.navigate("goals/$goalId")
                        viewModel.consumeNavigation()
                    }
                }

                NavHost(navController = navController, startDestination = "create") {
                    composable("create") {
                        GoalFormScreen(
                            state = state,
                            onTitleChange = viewModel::updateTitle,
                            onDescriptionChange = viewModel::updateDescription,
                            onSave = viewModel::createGoal,
                        )
                    }
                    composable(
                        route = "goals/{goalId}",
                        arguments = listOf(navArgument("goalId") { type = NavType.StringType }),
                    ) { entry ->
                        val goalId = requireNotNull(entry.arguments?.getString("goalId"))
                        LaunchedEffect(goalId) { viewModel.loadGoal(goalId) }
                        GoalDetailScreen(state = state, onRetry = { viewModel.loadGoal(goalId) })
                    }
                }
            }
        }
    }

    private fun requestLocalNetworkAccessForDebug() {
        if (
            BuildConfig.DEBUG &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), 1)
        }
    }
}
