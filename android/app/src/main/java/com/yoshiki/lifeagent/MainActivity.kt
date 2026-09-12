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
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.yoshiki.lifeagent.data.FirebaseAuthRepository
import com.yoshiki.lifeagent.data.Network
import com.yoshiki.lifeagent.ui.AuthScreen
import com.yoshiki.lifeagent.ui.AuthViewModel
import com.yoshiki.lifeagent.ui.GoalDetailScreen
import com.yoshiki.lifeagent.ui.GoalFormScreen
import com.yoshiki.lifeagent.ui.GoalPreviewScreen
import com.yoshiki.lifeagent.ui.GoalViewModel
import com.yoshiki.lifeagent.ui.theme.LifeAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestLocalNetworkAccessForDebug()
        initializeFirebase()
        setContent {
            LifeAgentTheme {
                val authRepository = remember { FirebaseAuthRepository(FirebaseAuth.getInstance()) }
                val authViewModel: AuthViewModel = viewModel(
                    factory = AuthViewModel.factory(authRepository)
                )
                val authState by authViewModel.uiState.collectAsState()
                val repository = remember {
                    Network.createGoalRepository(
                        BuildConfig.CORE_API_BASE_URL,
                        authRepository::idToken,
                    )
                }
                val viewModel: GoalViewModel = viewModel(
                    key = "goals-${authState.user?.uid.orEmpty()}",
                    factory = GoalViewModel.factory(repository),
                )
                val state by viewModel.uiState.collectAsState()
                val navController = rememberNavController()

                if (authState.user == null) {
                    AuthScreen(
                        state = authState,
                        onEmailChange = authViewModel::updateEmail,
                        onPasswordChange = authViewModel::updatePassword,
                        onSignIn = authViewModel::signIn,
                        onCreateAccount = authViewModel::createAccount,
                    )
                    return@LifeAgentTheme
                }

                LaunchedEffect(state.navigationGoalId) {
                    state.navigationGoalId?.let { goalId ->
                        navController.navigate("goals/$goalId")
                        viewModel.consumeNavigation()
                    }
                }

                LaunchedEffect(state.navigateToPreview) {
                    if (state.navigateToPreview) {
                        navController.navigate("preview")
                        viewModel.consumePreviewNavigation()
                    }
                }

                NavHost(navController = navController, startDestination = "create") {
                    composable("create") {
                        GoalFormScreen(
                            state = state,
                            onTitleChange = viewModel::updateTitle,
                            onDescriptionChange = viewModel::updateDescription,
                            onTargetDateChange = viewModel::updateTargetDate,
                            onPreview = viewModel::previewGoal,
                            onSignOut = authViewModel::signOut,
                        )
                    }
                    composable("preview") {
                        GoalPreviewScreen(
                            state = state,
                            onBack = navController::navigateUp,
                            onMetricNameChange = viewModel::updateMetricName,
                            onMetricValueChange = viewModel::updateMetricValue,
                            onMetricUnitChange = viewModel::updateMetricUnit,
                            onMilestoneTitleChange = viewModel::updateMilestoneTitle,
                            onMilestoneDateChange = viewModel::updateMilestoneDate,
                            onConfirm = viewModel::confirmGoal,
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

    private fun initializeFirebase() {
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder()
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
                    .build(),
            )
        }
        if (BuildConfig.USE_FIREBASE_AUTH_EMULATOR) {
            FirebaseAuth.getInstance().useEmulator("10.0.2.2", 9099)
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
