package com.yoshiki.lifeagent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yoshiki.lifeagent.data.Goal
import com.yoshiki.lifeagent.data.GoalRepository
import com.yoshiki.lifeagent.data.MetricProgressInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GoalDetailUiState(
    val goal: Goal? = null,
    val isLoading: Boolean = false,
    val isSavingProgress: Boolean = false,
    val progressBody: String = "",
    val metricProgress: List<MetricProgressEdit> = emptyList(),
    val error: String? = null,
    val progressError: String? = null,
)

data class MetricProgressEdit(
    val metricId: String,
    val name: String,
    val currentValue: Double,
    val targetValue: Double,
    val unit: String,
    val value: String = "",
)

class GoalDetailViewModel(private val repository: GoalRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(GoalDetailUiState())
    val uiState: StateFlow<GoalDetailUiState> = _uiState.asStateFlow()

    fun loadGoal(goalId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching { repository.getGoal(goalId) }
                .onSuccess { goal -> _uiState.value = stateForGoal(goal) }
                .onFailure {
                    _uiState.update {
                        it.copy(isLoading = false, error = "Goalを読み込めませんでした")
                    }
                }
        }
    }

    fun updateProgressBody(value: String) {
        _uiState.update { it.copy(progressBody = value, progressError = null) }
    }

    fun updateMetricProgress(index: Int, value: String) {
        _uiState.update { state ->
            state.copy(
                metricProgress = state.metricProgress.mapIndexed { itemIndex, item ->
                    if (itemIndex == index) item.copy(value = value) else item
                },
                progressError = null,
            )
        }
    }

    fun saveProgress() {
        val state = _uiState.value
        val goal = state.goal ?: return
        val body = state.progressBody.trim()
        val updates = state.metricProgress.mapNotNull { item ->
            val value = item.value.toDoubleOrNull()
            if (value != null && value > 0) MetricProgressInput(item.metricId, value) else null
        }
        if (body.isBlank()) {
            _uiState.update { it.copy(progressError = "進捗メモを入力してください") }
            return
        }
        if (updates.isEmpty()) {
            _uiState.update { it.copy(progressError = "今回進んだMetricを1つ以上入力してください") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSavingProgress = true, progressError = null) }
            runCatching { repository.recordProgress(goal.id, body, updates) }
                .onSuccess { updatedGoal ->
                    _uiState.value = stateForGoal(updatedGoal)
                }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            isSavingProgress = false,
                            progressError = "進捗を保存できませんでした",
                        )
                    }
                }
        }
    }

    private fun stateForGoal(goal: Goal) = GoalDetailUiState(
        goal = goal,
        metricProgress = goal.metrics.sortedBy { it.position }.map { metric ->
            MetricProgressEdit(
                metricId = metric.id,
                name = metric.name,
                currentValue = metric.currentValue,
                targetValue = metric.targetValue,
                unit = metric.unit,
            )
        },
    )

    companion object {
        fun factory(repository: GoalRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { GoalDetailViewModel(repository) } }
    }
}
