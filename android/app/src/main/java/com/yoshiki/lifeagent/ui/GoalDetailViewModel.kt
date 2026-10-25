package com.yoshiki.lifeagent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yoshiki.lifeagent.data.Advice
import com.yoshiki.lifeagent.data.Goal
import com.yoshiki.lifeagent.data.GoalRepository
import com.yoshiki.lifeagent.data.MetricProgressInput
import com.yoshiki.lifeagent.data.ReplanProposal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.UUID

data class GoalDetailUiState(
    val goal: Goal? = null,
    val isLoading: Boolean = false,
    val isSavingProgress: Boolean = false,
    val progressBody: String = "",
    val metricProgress: List<MetricProgressEdit> = emptyList(),
    val error: String? = null,
    val progressError: String? = null,
    val isParsing: Boolean = false,
    val hasPreview: Boolean = false,
    val parserWarnings: List<String> = emptyList(),
    val advice: Advice? = null,
    val isLoadingAdvice: Boolean = false,
    val adviceError: String? = null,
    val progressSaved: Boolean = false,
    val replanReason: String = "",
    val replanProposal: ReplanProposal? = null,
    val isLoadingReplan: Boolean = false,
    val isApplyingReplan: Boolean = false,
    val replanError: String? = null,
    val replanApplied: Boolean = false,
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
        inputRevision++
        adviceRevision++
        requestId = null
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

    private var requestId: String? = null
    private var inputRevision = 0
    private var adviceRevision = 0

    fun updateProgressBody(value: String) {
        if (_uiState.value.isSavingProgress) return
        inputRevision++
        requestId = null
        cancelPreview()
        _uiState.update { it.copy(progressBody = value, progressError = null) }
    }

    fun updateReplanReason(value: String) {
        if (_uiState.value.isLoadingReplan || _uiState.value.isApplyingReplan) return
        _uiState.update {
            it.copy(
                replanReason = value,
                replanError = null,
                replanProposal = null,
                replanApplied = false,
            )
        }
    }

    fun updateMetricProgress(index: Int, value: String) {
        if (_uiState.value.isSavingProgress || _uiState.value.isParsing) return
        requestId = null
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
        if (state.isSavingProgress || state.isParsing) return
        val body = state.progressBody.trim()
        val updates = state.metricProgress.mapNotNull { item ->
            val value = item.value.toDoubleOrNull()
            if (value != null && value > 0) MetricProgressInput(item.metricId, value) else null
        }
        if (body.isBlank()) {
            _uiState.update { it.copy(progressError = "進捗メモを入力してください") }
            return
        }
        val invalid = state.metricProgress.any { item ->
            if (item.value.isBlank()) false else {
                val value = item.value.toBigDecimalOrNull()
                value == null || value <= BigDecimal.ZERO ||
                    value > BigDecimal("1000000000") ||
                    value + BigDecimal.valueOf(item.currentValue) > BigDecimal.valueOf(item.targetValue) ||
                    value.stripTrailingZeros().scale() > 2
            }
        }
        if (invalid) {
            _uiState.update { it.copy(progressError = "今回値は目標以内の正の数（小数2桁まで）で入力してください") }
            return
        }
        if (updates.isEmpty()) {
            _uiState.update { it.copy(progressError = "今回進んだMetricを1つ以上入力してください") }
            return
        }

        val id = requestId ?: UUID.randomUUID().toString().also { requestId = it }
        adviceRevision++
        _uiState.update { it.copy(isSavingProgress = true, progressError = null,
            isLoadingAdvice = false, advice = null, adviceError = null) }
        viewModelScope.launch {
            runCatching { repository.recordProgress(goal.id, body, updates, id) }
                .onSuccess { updatedGoal ->
                    requestId = null
                    _uiState.value = stateForGoal(updatedGoal).copy(progressSaved = true)
                    loadAdvice()
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

    fun parseProgress() {
        val state = _uiState.value
        val goal = state.goal ?: return
        if (state.isParsing || state.isSavingProgress) return
        if (state.progressBody.isBlank()) {
            _uiState.update { it.copy(progressError = "進捗メモを入力してください") }
            return
        }
        val revision = inputRevision
        _uiState.update { it.copy(isParsing = true, progressError = null) }
        viewModelScope.launch {
            runCatching { repository.previewProgress(goal.id, state.progressBody.trim()) }
                .onSuccess { preview ->
                    if (revision == inputRevision) {
                        requestId = null
                        _uiState.update { current -> current.copy(
                            hasPreview = true,
                            parserWarnings = preview.warnings,
                            metricProgress = current.metricProgress.map { item ->
                                item.copy(value = preview.metricUpdates.find { it.metricId == item.metricId }
                                    ?.value?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "")
                            },
                        ) }
                    }
                }.onFailure {
                    if (revision == inputRevision) _uiState.update {
                        it.copy(progressError = "解析できませんでした。再試行または手入力してください")
                    }
                }
            _uiState.update { it.copy(isParsing = false) }
        }
    }

    fun cancelPreview() {
        if (_uiState.value.isSavingProgress) return
        requestId = null
        _uiState.update { state -> state.copy(
            hasPreview = false, parserWarnings = emptyList(),
            metricProgress = if (state.hasPreview) state.metricProgress.map { it.copy(value = "") }
                else state.metricProgress,
        ) }
    }

    fun loadAdvice() {
        val state = _uiState.value
        val goal = state.goal ?: return
        if (state.isLoadingAdvice || state.isSavingProgress) return
        val revision = ++adviceRevision
        _uiState.update { it.copy(isLoadingAdvice = true, adviceError = null) }
        viewModelScope.launch {
            runCatching { repository.getAdvice(goal.id) }
                .onSuccess { advice ->
                    if (revision == adviceRevision) _uiState.update { it.copy(advice = advice) }
                }
                .onFailure {
                    if (revision == adviceRevision) _uiState.update {
                        it.copy(adviceError = "助言を取得できませんでした。進捗は保存済みです")
                    }
                }
            if (revision == adviceRevision) _uiState.update { it.copy(isLoadingAdvice = false) }
        }
    }

    fun previewReplan() {
        val state = _uiState.value
        val goal = state.goal ?: return
        if (state.isLoadingReplan || state.isApplyingReplan) return
        val reason = state.replanReason.trim()
        if (reason.isBlank()) {
            _uiState.update { it.copy(replanError = "見直し理由を入力してください") }
            return
        }
        _uiState.update {
            it.copy(
                isLoadingReplan = true,
                replanError = null,
                replanProposal = null,
                replanApplied = false,
            )
        }
        viewModelScope.launch {
            runCatching { repository.previewReplan(goal.id, reason) }
                .onSuccess { proposal ->
                    _uiState.update {
                        it.copy(replanProposal = proposal, replanReason = reason)
                    }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(replanError = "再計画候補を生成できませんでした。再試行してください")
                    }
                }
            _uiState.update { it.copy(isLoadingReplan = false) }
        }
    }

    fun cancelReplan() {
        if (_uiState.value.isApplyingReplan) return
        _uiState.update { it.copy(replanProposal = null, replanError = null, replanApplied = false) }
    }

    fun applyReplan() {
        val state = _uiState.value
        val goal = state.goal ?: return
        val proposal = state.replanProposal ?: return
        if (state.isLoadingReplan || state.isApplyingReplan) return
        _uiState.update { it.copy(isApplyingReplan = true, replanError = null) }
        viewModelScope.launch {
            runCatching { repository.applyReplan(goal.id, proposal.proposalId) }
                .onSuccess { updatedGoal ->
                    _uiState.value = stateForGoal(updatedGoal).copy(
                        replanApplied = true,
                        replanReason = "",
                    )
                }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            isApplyingReplan = false,
                            replanError = "再計画を保存できませんでした。候補を作り直してください",
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
