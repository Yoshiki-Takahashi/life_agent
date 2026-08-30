package com.yoshiki.lifeagent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yoshiki.lifeagent.data.Goal
import com.yoshiki.lifeagent.data.GoalPlan
import com.yoshiki.lifeagent.data.GoalRepository
import com.yoshiki.lifeagent.data.Metric
import com.yoshiki.lifeagent.data.Milestone
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MetricEdit(
    val name: String,
    val targetValue: String,
    val unit: String,
)

data class MilestoneEdit(
    val title: String,
    val targetDate: String,
)

data class GoalUiState(
    val title: String = "",
    val description: String = "",
    val targetDate: String = "",
    val metrics: List<MetricEdit> = emptyList(),
    val milestones: List<MilestoneEdit> = emptyList(),
    val isPreviewing: Boolean = false,
    val isConfirming: Boolean = false,
    val formError: String? = null,
    val planError: String? = null,
    val navigateToPreview: Boolean = false,
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

    fun updateTargetDate(value: String) = _uiState.update {
        it.copy(targetDate = value, formError = null)
    }

    fun previewGoal() {
        val state = _uiState.value
        val title = state.title.trim()
        val description = state.description.trim()
        val error = validateGoalInput(title, description, state.targetDate)
        if (error != null) {
            _uiState.update { it.copy(formError = error) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isPreviewing = true, formError = null) }
            runCatching {
                repository.previewGoal(title, description.ifEmpty { null }, state.targetDate)
            }.onSuccess { plan ->
                _uiState.update {
                    it.copy(
                        title = plan.title,
                        description = plan.description.orEmpty(),
                        targetDate = plan.targetDate,
                        metrics = plan.metrics.map { metric ->
                            MetricEdit(metric.name, formatNumber(metric.targetValue), metric.unit)
                        },
                        milestones = plan.milestones.map { milestone ->
                            MilestoneEdit(milestone.title, milestone.targetDate)
                        },
                        isPreviewing = false,
                        navigateToPreview = true,
                    )
                }
            }.onFailure {
                _uiState.update {
                    it.copy(
                        isPreviewing = false,
                        formError = "計画を作成できませんでした。もう一度お試しください",
                    )
                }
            }
        }
    }

    fun consumePreviewNavigation() = _uiState.update { it.copy(navigateToPreview = false) }

    fun updateMetricName(index: Int, value: String) = updateMetric(index) { it.copy(name = value) }

    fun updateMetricValue(index: Int, value: String) =
        updateMetric(index) { it.copy(targetValue = value) }

    fun updateMetricUnit(index: Int, value: String) = updateMetric(index) { it.copy(unit = value) }

    fun updateMilestoneTitle(index: Int, value: String) =
        updateMilestone(index) { it.copy(title = value) }

    fun updateMilestoneDate(index: Int, value: String) =
        updateMilestone(index) { it.copy(targetDate = value) }

    fun confirmGoal() {
        val state = _uiState.value
        val error = validatePlan(state)
        if (error != null) {
            _uiState.update { it.copy(planError = error) }
            return
        }
        val plan = GoalPlan(
            title = state.title.trim(),
            description = state.description.trim().ifEmpty { null },
            targetDate = state.targetDate,
            metrics = state.metrics.mapIndexed { index, item ->
                Metric(item.name.trim(), requireNotNull(item.targetValue.toDoubleOrNull()), item.unit.trim(), index)
            },
            milestones = state.milestones.mapIndexed { index, item ->
                Milestone(item.title.trim(), item.targetDate, index)
            },
        )
        viewModelScope.launch {
            _uiState.update { it.copy(isConfirming = true, planError = null) }
            runCatching { repository.confirmGoal(plan) }
                .onSuccess { goal ->
                    _uiState.update {
                        it.copy(isConfirming = false, navigationGoalId = goal.id)
                    }
                }.onFailure {
                    _uiState.update {
                        it.copy(
                            isConfirming = false,
                            planError = "保存できませんでした。編集内容を確認して再試行してください",
                        )
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
                }.onFailure {
                    _uiState.update {
                        it.copy(isLoadingDetail = false, detailError = "Goalを読み込めませんでした")
                    }
                }
        }
    }

    private fun updateMetric(index: Int, transform: (MetricEdit) -> MetricEdit) {
        _uiState.update { state ->
            state.copy(
                metrics = state.metrics.mapIndexed { itemIndex, item ->
                    if (itemIndex == index) transform(item) else item
                },
                planError = null,
            )
        }
    }

    private fun updateMilestone(index: Int, transform: (MilestoneEdit) -> MilestoneEdit) {
        _uiState.update { state ->
            state.copy(
                milestones = state.milestones.mapIndexed { itemIndex, item ->
                    if (itemIndex == index) transform(item) else item
                },
                planError = null,
            )
        }
    }

    companion object {
        fun factory(repository: GoalRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { GoalViewModel(repository) } }

        private fun validateGoalInput(title: String, description: String, targetDate: String): String? =
            when {
                title.isEmpty() -> "タイトルを入力してください"
                title.length > 120 -> "タイトルは120文字以内で入力してください"
                description.length > 2000 -> "説明は2,000文字以内で入力してください"
                parseIsoDate(targetDate) == null -> "期限をYYYY-MM-DD形式で入力してください"
                requireNotNull(parseIsoDate(targetDate)) < today() -> "期限は今日以降にしてください"
                else -> null
            }

        private fun validatePlan(state: GoalUiState): String? {
            val goalError = validateGoalInput(
                state.title.trim(),
                state.description.trim(),
                state.targetDate,
            )
            if (goalError != null) return goalError
            if (state.metrics.size !in 1..3 || state.milestones.size !in 3..5) {
                return "計画の項目数が正しくありません"
            }
            if (state.metrics.any { it.name.trim().isEmpty() || it.name.length > 100 }) {
                return "Metric名を100文字以内で入力してください"
            }
            val values = state.metrics.map { it.targetValue.toDoubleOrNull() }
            if (values.any { it == null || !it.isFinite() || it <= 0 || it > 1_000_000_000 }) {
                return "Metric目標値は0より大きい数値で入力してください"
            }
            if (state.metrics.any { it.unit.trim().isEmpty() || it.unit.length > 30 }) {
                return "Metric単位を30文字以内で入力してください"
            }
            if (state.milestones.any { it.title.trim().isEmpty() || it.title.length > 120 }) {
                return "Milestone名を120文字以内で入力してください"
            }
            val goalDate = requireNotNull(parseIsoDate(state.targetDate))
            var previous = today()
            for (milestone in state.milestones) {
                val milestoneDate = parseIsoDate(milestone.targetDate)
                    ?: return "Milestone期限をYYYY-MM-DD形式で入力してください"
                if (milestoneDate < today() || milestoneDate > goalDate || milestoneDate < previous) {
                    return "Milestone期限を今日からGoal期限までの順番で入力してください"
                }
                previous = milestoneDate
            }
            return null
        }

        private fun parseIsoDate(value: String): Long? {
            if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) return null
            return runCatching {
                SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }
                    .parse(value)?.time
            }.getOrNull()
        }

        private fun today(): Long = Calendar.getInstance().run {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            timeInMillis
        }

        private fun formatNumber(value: Double): String =
            if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }
}
