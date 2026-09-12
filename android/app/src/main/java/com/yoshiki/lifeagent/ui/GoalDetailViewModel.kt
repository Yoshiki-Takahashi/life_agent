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

data class GoalDetailUiState(
    val goal: Goal? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class GoalDetailViewModel(private val repository: GoalRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(GoalDetailUiState())
    val uiState: StateFlow<GoalDetailUiState> = _uiState.asStateFlow()

    fun loadGoal(goalId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching { repository.getGoal(goalId) }
                .onSuccess { goal -> _uiState.value = GoalDetailUiState(goal = goal) }
                .onFailure {
                    _uiState.update {
                        it.copy(isLoading = false, error = "Goalを読み込めませんでした")
                    }
                }
        }
    }

    companion object {
        fun factory(repository: GoalRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { GoalDetailViewModel(repository) } }
    }
}
