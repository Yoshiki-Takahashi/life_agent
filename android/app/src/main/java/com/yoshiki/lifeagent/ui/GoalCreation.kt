package com.yoshiki.lifeagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import com.yoshiki.lifeagent.data.validProgressValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yoshiki.lifeagent.data.GoalPlan
import com.yoshiki.lifeagent.data.GoalRepository
import com.yoshiki.lifeagent.data.Metric
import com.yoshiki.lifeagent.data.Milestone
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

    fun resetDraft() {
        _uiState.value = GoalUiState()
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
                requireNotNull(parseIsoDate(targetDate)) < LocalDate.now() -> "期限は今日以降にしてください"
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
            if (state.metrics.any { !validProgressValue(it.targetValue, 0.0, 1_000_000_000.0) }) {
                return "Metric目標値は正数（小数2桁まで）で入力してください"
            }
            if (state.metrics.any { it.unit.trim().isEmpty() || it.unit.length > 30 }) {
                return "Metric単位を30文字以内で入力してください"
            }
            if (state.milestones.any { it.title.trim().isEmpty() || it.title.length > 120 }) {
                return "Milestone名を120文字以内で入力してください"
            }
            val goalDate = requireNotNull(parseIsoDate(state.targetDate))
            var previous = LocalDate.now()
            for (milestone in state.milestones) {
                val milestoneDate = parseIsoDate(milestone.targetDate)
                    ?: return "Milestone期限をYYYY-MM-DD形式で入力してください"
                if (milestoneDate < LocalDate.now() || milestoneDate > goalDate || milestoneDate < previous) {
                    return "Milestone期限を今日からGoal期限までの順番で入力してください"
                }
                previous = milestoneDate
            }
            return null
        }

        private fun parseIsoDate(value: String): LocalDate? =
            if (Regex("\\d{4}-\\d{2}-\\d{2}").matches(value))
                runCatching { LocalDate.parse(value) }.getOrNull() else null

        private fun formatNumber(value: Double): String =
            if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalFormScreen(
    state: GoalUiState,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onTargetDateChange: (String) -> Unit,
    onPreview: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新しいGoal") },
                navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("達成したいことを教えてください", style = MaterialTheme.typography.headlineSmall)
            Text("AIが測定できるMetricと、途中のMilestoneを提案します。")
            OutlinedTextField(
                value = state.title,
                onValueChange = onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("タイトル") },
                supportingText = { Text("120文字以内") },
                isError = state.formError != null,
                enabled = !state.isPreviewing,
                singleLine = true,
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = onDescriptionChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("説明（任意）") },
                supportingText = { Text("背景や取り組み方を2,000文字以内で入力") },
                enabled = !state.isPreviewing,
                minLines = 4,
            )
            OutlinedTextField(
                value = state.targetDate,
                onValueChange = onTargetDateChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Goal期限") },
                supportingText = { Text("YYYY-MM-DD") },
                enabled = !state.isPreviewing,
                singleLine = true,
            )
            state.formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = onPreview,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isPreviewing,
            ) {
                if (state.isPreviewing) CircularProgressIndicator() else Text("計画を作る")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalPreviewScreen(
    state: GoalUiState,
    onBack: () -> Unit,
    onMetricNameChange: (Int, String) -> Unit,
    onMetricValueChange: (Int, String) -> Unit,
    onMetricUnitChange: (Int, String) -> Unit,
    onMilestoneTitleChange: (Int, String) -> Unit,
    onMilestoneDateChange: (Int, String) -> Unit,
    onConfirm: () -> Unit,
) {
    BackHandler { onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("計画を確認") },
                navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(state.title, style = MaterialTheme.typography.headlineSmall)
            Text("期限  ${state.targetDate}")
            Text("達成を測るMetric", style = MaterialTheme.typography.titleLarge)
            state.metrics.forEachIndexed { index, metric ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(metric.name, { onMetricNameChange(index, it) }, Modifier.fillMaxWidth(), label = { Text("Metric名") }, enabled = !state.isConfirming, singleLine = true)
                        OutlinedTextField(metric.targetValue, { onMetricValueChange(index, it) }, Modifier.fillMaxWidth(), label = { Text("目標値") }, enabled = !state.isConfirming, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                        OutlinedTextField(metric.unit, { onMetricUnitChange(index, it) }, Modifier.fillMaxWidth(), label = { Text("単位") }, enabled = !state.isConfirming, singleLine = true)
                    }
                }
            }
            Text("達成までのMilestone", style = MaterialTheme.typography.titleLarge)
            state.milestones.forEachIndexed { index, milestone ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("STEP ${index + 1}", color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(milestone.title, { onMilestoneTitleChange(index, it) }, Modifier.fillMaxWidth(), label = { Text("Milestone名") }, enabled = !state.isConfirming, singleLine = true)
                        OutlinedTextField(milestone.targetDate, { onMilestoneDateChange(index, it) }, Modifier.fillMaxWidth(), label = { Text("期限") }, supportingText = { Text("YYYY-MM-DD") }, enabled = !state.isConfirming, singleLine = true)
                    }
                }
            }
            state.planError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth(), enabled = !state.isConfirming) {
                if (state.isConfirming) CircularProgressIndicator() else Text("この計画で保存する")
            }
        }
    }
}
