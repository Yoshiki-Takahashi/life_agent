package com.yoshiki.lifeagent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.yoshiki.lifeagent.data.FirebaseAuthRepository
import com.yoshiki.lifeagent.data.CredentialManagerGoogleSignIn
import com.yoshiki.lifeagent.data.Network
import com.yoshiki.lifeagent.ui.AuthViewModel
import com.yoshiki.lifeagent.ui.LifeAgentSession
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
                val google = remember { CredentialManagerGoogleSignIn(this@MainActivity, BuildConfig.GOOGLE_WEB_CLIENT_ID) }
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
                androidx.compose.runtime.LaunchedEffect(authState.user?.uid) {
                    com.yoshiki.lifeagent.reminders.ReviewReminder(this@MainActivity).setUser(authState.user?.uid)
                }
                LifeAgentSession(
                    repository = repository,
                    auth = authViewModel,
                    google = google,
                    onSignOut = {
                        com.yoshiki.lifeagent.reminders.ReviewReminder(this@MainActivity).setUser(null)
                        authViewModel.signOut { if (google.configured) google.clear() }
                    },
                )
            }
        }
    }

    private fun initializeFirebase() {
        if (FirebaseApp.getApps(this).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
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
