package com.yoshiki.lifeagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yoshiki.lifeagent.data.AuthRepository
import com.yoshiki.lifeagent.data.AuthUser
import com.yoshiki.lifeagent.data.GoalRepository
import com.yoshiki.lifeagent.R
import com.yoshiki.lifeagent.data.AuthFailure
import com.yoshiki.lifeagent.data.GoogleSignIn
import com.yoshiki.lifeagent.data.GoogleSignInCancelled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val user: AuthUser? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val confirmLink: Boolean = false,
    val sessionId: Long = 0,
)

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState(user = repository.currentUser()))
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()
    private var generation = 0L
    private var authentication: Job? = null

    fun updateEmail(value: String) = _uiState.update { it.copy(email = value, error = null) }
    fun updatePassword(value: String) = _uiState.update { it.copy(password = value, error = null) }
    fun signIn() = authenticate { email, password -> repository.signIn(email, password) }
    fun createAccount() = authenticate { email, password -> repository.createAccount(email, password) }

    fun signOut() = signOut {}

    fun signOut(clearGoogle: suspend () -> Unit) {
        val attempt = ++generation
        authentication?.cancel()
        _uiState.value = AuthUiState(isLoading = true, sessionId = attempt)
        authentication = viewModelScope.launch {
            try {
                repository.signOut()
                clearGoogle()
                if (attempt == generation) _uiState.update { it.copy(isLoading = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (attempt == generation) _uiState.update { it.copy(isLoading = false,
                    error = "ログアウト後の認証状態の消去を完了できませんでした。再試行してください。") }
            }
        }
    }

    fun requestGoogleLink() {
        if (_uiState.value.user != null && !_uiState.value.isLoading)
            _uiState.update { it.copy(confirmLink = true, message = null, error = null) }
    }

    fun dismissMessage() { if (!_uiState.value.isLoading) _uiState.update {
        it.copy(confirmLink = false, error = null, message = null)
    } }

    fun googleSignIn(source: GoogleSignIn, link: Boolean = false) {
        val user = _uiState.value.user
        if (!source.configured || _uiState.value.isLoading || (link && user == null) || (!link && user != null)) return
        runAuthentication(link) {
            val attempt = generation
            val token = source.idToken()
            if (generation != attempt) throw CancellationException()
            if (link) repository.linkGoogle(token, requireNotNull(user).uid)
            else repository.signInWithGoogle(token)
        }
    }

    private fun authenticate(action: suspend (String, String) -> AuthUser) {
        if (_uiState.value.isLoading || _uiState.value.user != null) return
        val email = _uiState.value.email.trim()
        val password = _uiState.value.password
        val validationError = when {
            email.isEmpty() || !email.contains('@') -> "有効なメールアドレスを入力してください"
            password.length < 6 -> "パスワードは6文字以上で入力してください"
            else -> null
        }
        if (validationError != null) {
            _uiState.update { it.copy(error = validationError) }
            return
        }
        runAuthentication { action(email, password) }
    }

    private fun runAuthentication(link: Boolean = false, action: suspend () -> AuthUser) {
        val attempt = ++generation
        _uiState.update { it.copy(isLoading = true, error = null, message = null, confirmLink = false) }
        authentication = viewModelScope.launch {
            try {
                val user = action()
                if (attempt == generation) _uiState.update { it.copy(user = user, password = "", isLoading = false,
                    sessionId = if (link) it.sessionId else attempt,
                    message = if (link) "Googleを連携しました。今の目標をそのまま利用できます。" else null) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (attempt == generation) _uiState.update {
                    it.copy(isLoading = false, error = when (error) {
                        is GoogleSignInCancelled -> "Googleログインを取り消しました。"
                        is AuthFailure -> error.message
                        else -> "認証できませんでした。入力内容や接続を確認して、再試行してください。"
                    })
                }
            }
        }
    }

    companion object {
        fun factory(repository: AuthRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { AuthViewModel(repository) } }
    }
}

@Composable
fun AuthScreen(
    state: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
    googleAvailable: Boolean = false,
    onGoogleSignIn: () -> Unit = {},
) {
    Surface {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text("LifeAgent", style = MaterialTheme.typography.displaySmall)
            Text("目標を、今日から動かせる計画へ。", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.email,
                onValueChange = onEmailChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("メールアドレス") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                enabled = !state.isLoading,
                singleLine = true,
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("パスワード") },
                visualTransformation = PasswordVisualTransformation(),
                enabled = !state.isLoading,
                singleLine = true,
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading,
            ) {
                if (state.isLoading) CircularProgressIndicator() else Text("ログイン")
            }
            TextButton(
                onClick = onCreateAccount,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading,
            ) { Text("新しいアカウントを作る") }
            OutlinedButton(onClick = onGoogleSignIn, enabled = googleAvailable && !state.isLoading,
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color(0xFF1F1F1F)),
                modifier = Modifier.fillMaxWidth()) {
                Image(painterResource(R.drawable.google_g), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Googleでログイン")
            }
            Text(if (googleAvailable)
                "メールで登録済みの方は、パスワードでのログインも残すため、先にメールでログインしてGoogleを連携してください。"
                else "この環境ではGoogleログインを利用できません。メールでログインできます。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun LifeAgentSession(repository: GoalRepository, auth: AuthViewModel, google: GoogleSignIn? = null,
    onSignOut: () -> Unit = { auth.signOut { if (google?.configured == true) google.clear() } }) {
    val state by auth.uiState.collectAsState()
    val user = state.user
    if (user == null) {
        AuthScreen(state, auth::updateEmail, auth::updatePassword, auth::signIn, auth::createAccount,
            googleAvailable = google?.configured == true, onGoogleSignIn = { google?.let { auth.googleSignIn(it) } })
    } else {
        key(user.uid, state.sessionId) {
            LifeAgentApp(repository, user.uid, onSignOut, sessionKey = "${user.uid}-${state.sessionId}",
                accountAction = {
                    TextButton(onClick = auth::requestGoogleLink,
                        enabled = google?.configured == true && !user.googleLinked && !state.isLoading) {
                        Text(if (user.googleLinked) "Google連携済み" else "Googleを連携")
                    }
                })
        }
        GoogleLinkDialog(state, onLink = { google?.let { auth.googleSignIn(it, link = true) } }, onDismiss = auth::dismissMessage)
    }
}

@Composable
fun GoogleLinkDialog(state: AuthUiState, onLink: () -> Unit, onDismiss: () -> Unit) {
    if (state.user == null || (!state.confirmLink && !state.isLoading && state.message == null && state.error == null)) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Googleアカウントの連携") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.confirmLink) {
                Text("${state.user.email.orEmpty()} にGoogleを連携します。今の目標を引き続き利用できます。")
                Text("別のLifeAgentアカウントで利用中のGoogleアカウントは連携できません。")
            }
            if (state.isLoading) { CircularProgressIndicator(); Text("認証を確認しています") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.message?.let { Text(it) }
        } },
        confirmButton = { TextButton(onClick = if (state.confirmLink) onLink else onDismiss,
            enabled = !state.isLoading) { Text(if (state.confirmLink) "Googleアカウントを選ぶ" else "閉じる") } },
        dismissButton = { if (state.confirmLink) TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
