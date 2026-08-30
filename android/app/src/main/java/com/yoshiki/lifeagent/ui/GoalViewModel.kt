package com.yoshiki.lifeagent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yoshiki.lifeagent.data.Goal
import com.yoshiki.lifeagent.data.GoalRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GoalUiState(
    val title: String = "",
    val description: String = "",
    val isSaving: Boolean = false,
    val formError: String? = null,
    val navigationGoalId: String? = null,
    val detail: Goal? = null,
    val isLoadingDetail: Boolean = false,
    val detailError: String? = null,
)

class GoalViewModel(private val repository: GoalRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(GoalUiState())
    val uiState: StateFlow<GoalUiState> = _uiState.asStateFlow()

    fun updateTitle(value: String) = _uiState.update { it.copy(title = value, formError = null) }

    fun updateDescription(value: String) = _uiState.update { it.copy(description = value) }

    fun createGoal() {
        val title = _uiState.value.title.trim()
        val description = _uiState.value.description.trim()
        val error = when {
            title.isEmpty() -> "タイトルを入力してください"
            title.length > 120 -> "タイトルは120文字以内で入力してください"
            description.length > 2000 -> "説明は2,000文字以内で入力してください"
            else -> null
        }
        if (error != null) {
            _uiState.update { it.copy(formError = error) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, formError = null) }
            runCatching {
                repository.createGoal(title, description.ifEmpty { null })
            }.onSuccess { goal ->
                _uiState.update { it.copy(isSaving = false, navigationGoalId = goal.id) }
            }.onFailure {
                _uiState.update {
                    it.copy(isSaving = false, formError = "保存できませんでした。もう一度お試しください")
                }
            }
        }
    }

    fun consumeNavigation() = _uiState.update { it.copy(navigationGoalId = null) }

    fun loadGoal(goalId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDetail = true, detailError = null) }
            runCatching { repository.getGoal(goalId) }
                .onSuccess { goal ->
                    _uiState.update { it.copy(detail = goal, isLoadingDetail = false) }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(isLoadingDetail = false, detailError = "Goalを読み込めませんでした")
                    }
                }
        }
    }

    companion object {
        fun factory(repository: GoalRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { GoalViewModel(repository) } }
    }
}
