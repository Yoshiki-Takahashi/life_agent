package com.yoshiki.lifeagent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yoshiki.lifeagent.data.AuthRepository
import com.yoshiki.lifeagent.data.AuthUser
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
)

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState(user = repository.currentUser()))
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun updateEmail(value: String) = _uiState.update { it.copy(email = value, error = null) }
    fun updatePassword(value: String) = _uiState.update { it.copy(password = value, error = null) }
    fun signIn() = authenticate { email, password -> repository.signIn(email, password) }
    fun createAccount() = authenticate { email, password -> repository.createAccount(email, password) }

    fun signOut() {
        repository.signOut()
        _uiState.value = AuthUiState()
    }

    private fun authenticate(action: suspend (String, String) -> AuthUser) {
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching { action(email, password) }
                .onSuccess { user -> _uiState.update { it.copy(user = user, isLoading = false) } }
                .onFailure {
                    _uiState.update {
                        it.copy(isLoading = false, error = "認証できませんでした。入力内容を確認してください")
                    }
                }
        }
    }

    companion object {
        fun factory(repository: AuthRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { AuthViewModel(repository) } }
    }
}
