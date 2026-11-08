package com.yoshiki.lifeagent.ui

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yoshiki.lifeagent.data.GoalAnswer
import com.yoshiki.lifeagent.data.GoalPlan
import com.yoshiki.lifeagent.data.GoalPreviewRequest
import com.yoshiki.lifeagent.data.GoalQuestionsRequest
import com.yoshiki.lifeagent.data.GoalRepository
import com.yoshiki.lifeagent.data.Metric
import com.yoshiki.lifeagent.data.Milestone
import com.yoshiki.lifeagent.data.validProgressValue
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MetricEdit(
    val name: String,
    val targetValue: String,
    val unit: String,
    val id: String = UUID.randomUUID().toString(),
)

data class MilestoneEdit(
    val title: String,
    val targetDate: String,
    val id: String = UUID.randomUUID().toString(),
)

data class QuestionEdit(val question: String, val answer: Boolean? = null)
enum class DraftReplacement { QUESTIONS, PLAN }

data class GoalUiState(
    val title: String = "",
    val description: String = "",
    val targetDate: String = "",
    val questions: List<QuestionEdit> = emptyList(),
    val metrics: List<MetricEdit> = emptyList(),
    val milestones: List<MilestoneEdit> = emptyList(),
    val isLoadingQuestions: Boolean = false,
    val isPreviewing: Boolean = false,
    val isConfirming: Boolean = false,
    val formError: String? = null,
    val questionError: String? = null,
    val planError: String? = null,
    val replacement: DraftReplacement? = null,
    val navigateToQuestions: Boolean = false,
    val navigateToPreview: Boolean = false,
    val navigationGoalId: String? = null,
) {
    val isBusy get() = isLoadingQuestions || isPreviewing || isConfirming
}

class GoalViewModel(private val repository: GoalRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(GoalUiState())
    val uiState: StateFlow<GoalUiState> = _uiState.asStateFlow()
    private var questionsFor: GoalQuestionsRequest? = null
    private var planFor: GoalPreviewRequest? = null
    private var operation: Job? = null
    private var draftVersion = 0

    fun updateTitle(value: String) = edit { it.copy(title = value, formError = null) }
    fun updateDescription(value: String) = edit { it.copy(description = value, formError = null) }
    fun updateTargetDate(value: String) = edit { it.copy(targetDate = value, formError = null) }

    private fun input() = _uiState.value.let {
        GoalQuestionsRequest(it.title.trim(), it.description.trim().ifEmpty { null }, it.targetDate)
    }

    fun requestQuestions() {
        if (_uiState.value.isBusy) return
        val request = input()
        val error = validateGoalInput(request.title, request.description.orEmpty(), request.targetDate)
        if (error != null) {
            edit { it.copy(formError = error) }
        } else if (request == questionsFor) {
            edit { it.copy(navigateToQuestions = true, formError = null) }
        } else if (_uiState.value.questions.isNotEmpty()) {
            edit { it.copy(replacement = DraftReplacement.QUESTIONS) }
        } else generateQuestions()
    }

    private fun generateQuestions() {
        val request = input()
        val version = draftVersion
        _uiState.update { it.copy(isLoadingQuestions = true, formError = null, replacement = null) }
        operation = viewModelScope.launch {
            try {
                val result = repository.goalQuestions(request.title, request.description, request.targetDate)
                require(result.questions.size in 3..8 && result.questions.distinct().size == result.questions.size)
                require(result.questions.all { it.isNotBlank() && it.length <= 200 })
                if (version != draftVersion) return@launch
                questionsFor = request
                _uiState.update { it.copy(questions = result.questions.map(::QuestionEdit),
                    isLoadingQuestions = false, questionError = null, navigateToQuestions = true) }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                if (version == draftVersion) _uiState.update { it.copy(isLoadingQuestions = false,
                    formError = "質問を生成できませんでした。入力を保持したまま再試行できます") }
            }
        }
    }

    fun answerQuestion(index: Int, answer: Boolean) = edit { state ->
        state.copy(questions = state.questions.mapIndexed { i, item ->
            if (i == index) item.copy(answer = answer) else item
        }, questionError = null)
    }

    fun previewGoal() {
        if (_uiState.value.isBusy) return
        val state = _uiState.value
        if (input() != questionsFor || state.questions.isEmpty() || state.questions.any { it.answer == null }) {
            edit { it.copy(questionError = "すべての質問にYes / Noで回答してください") }
            return
        }
        val request = previewInput()
        if (request == planFor) edit { it.copy(navigateToPreview = true) }
        else if (state.metrics.isNotEmpty()) edit { it.copy(replacement = DraftReplacement.PLAN) }
        else generatePlan()
    }

    private fun previewInput() = input().let { request ->
        GoalPreviewRequest(request.title, request.description, request.targetDate,
            _uiState.value.questions.map { GoalAnswer(it.question, requireNotNull(it.answer)) })
    }

    fun dismissReplacement() = edit { it.copy(replacement = null) }
    fun confirmReplacement() {
        if (_uiState.value.isBusy) return
        when (_uiState.value.replacement) {
            DraftReplacement.QUESTIONS -> generateQuestions()
            DraftReplacement.PLAN -> generatePlan()
            null -> Unit
        }
    }

    private fun generatePlan() {
        val request = previewInput()
        val version = draftVersion
        _uiState.update { it.copy(isPreviewing = true, questionError = null, replacement = null) }
        operation = viewModelScope.launch {
            try {
                val plan = repository.previewGoal(request.title, request.description, request.targetDate, request.answers)
                if (version != draftVersion) return@launch
                planFor = request
                _uiState.update { it.copy(
                    metrics = plan.metrics.map { metric ->
                        MetricEdit(metric.name, formatNumber(metric.targetValue), metric.unit)
                    },
                    milestones = plan.milestones.map { MilestoneEdit(it.title, it.targetDate) },
                    isPreviewing = false, planError = null, navigateToPreview = true,
                ) }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                if (version == draftVersion) _uiState.update { it.copy(isPreviewing = false,
                    questionError = "計画を作成できませんでした。回答と編集内容を保持したまま再試行できます") }
            }
        }
    }

    fun consumeQuestionsNavigation() = edit { it.copy(navigateToQuestions = false) }
    fun consumePreviewNavigation() = edit { it.copy(navigateToPreview = false) }
    fun updateMetricName(index: Int, value: String) = updateMetric(index) { it.copy(name = value) }
    fun updateMetricValue(index: Int, value: String) = updateMetric(index) { it.copy(targetValue = value) }
    fun updateMetricUnit(index: Int, value: String) = updateMetric(index) { it.copy(unit = value) }
    fun updateMilestoneTitle(index: Int, value: String) = updateMilestone(index) { it.copy(title = value) }
    fun updateMilestoneDate(index: Int, value: String) = updateMilestone(index) { it.copy(targetDate = value) }
    fun addMetric() = edit {
        if (it.metrics.size >= 10) it else it.copy(metrics = it.metrics + MetricEdit("", "", ""), planError = null)
    }
    fun removeMetric(index: Int) = edit {
        if (it.metrics.size <= 1) it else it.copy(metrics = it.metrics.filterIndexed { i, _ -> i != index }, planError = null)
    }
    fun addMilestone() = edit {
        if (it.milestones.size >= 10) it else it.copy(
            milestones = it.milestones + MilestoneEdit("", it.milestones.lastOrNull()?.targetDate ?: it.targetDate), planError = null)
    }
    fun removeMilestone(index: Int) = edit {
        if (it.milestones.size <= 1) it else it.copy(milestones = it.milestones.filterIndexed { i, _ -> i != index }, planError = null)
    }

    fun confirmGoal() {
        val state = _uiState.value
        if (state.isBusy) return
        // An old plan is kept during retries, but cannot be saved for changed input/answers.
        if (input() != questionsFor || state.questions.any { it.answer == null } ||
            state.questions.isEmpty() || previewInput() != planFor) {
            edit { it.copy(planError = "質問画面で最新の回答から計画を確認してください") }
            return
        }
        val error = validatePlan(state)
        if (error != null) { edit { it.copy(planError = error) }; return }
        val plan = GoalPlan(
            state.title.trim(), state.description.trim().ifEmpty { null }, state.targetDate,
            state.metrics.mapIndexed { index, item ->
                Metric(item.name.trim(), requireNotNull(item.targetValue.toDoubleOrNull()), item.unit.trim(), index)
            },
            state.milestones.mapIndexed { index, item -> Milestone(item.title.trim(), item.targetDate, index) },
        )
        val version = draftVersion
        _uiState.update { it.copy(isConfirming = true, planError = null) }
        operation = viewModelScope.launch {
            try {
                val goal = repository.confirmGoal(plan)
                if (version == draftVersion) _uiState.update { it.copy(isConfirming = false, navigationGoalId = goal.id) }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                if (version == draftVersion) _uiState.update { it.copy(isConfirming = false,
                    planError = "保存できませんでした。編集内容を確認して再試行してください") }
            }
        }
    }

    fun resetDraft() {
        draftVersion++
        operation?.cancel()
        questionsFor = null
        planFor = null
        _uiState.value = GoalUiState()
    }

    private fun edit(transform: (GoalUiState) -> GoalUiState) {
        if (!_uiState.value.isBusy) _uiState.update(transform)
    }
    private fun updateMetric(index: Int, transform: (MetricEdit) -> MetricEdit) = edit { state ->
        state.copy(metrics = state.metrics.mapIndexed { i, item -> if (i == index) transform(item) else item }, planError = null)
    }
    private fun updateMilestone(index: Int, transform: (MilestoneEdit) -> MilestoneEdit) = edit { state ->
        state.copy(milestones = state.milestones.mapIndexed { i, item -> if (i == index) transform(item) else item }, planError = null)
    }

    companion object {
        fun factory(repository: GoalRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { GoalViewModel(repository) } }

        private fun validateGoalInput(title: String, description: String, targetDate: String): String? = when {
            title.isEmpty() -> "タイトルを入力してください"
            title.length > 120 -> "タイトルは120文字以内で入力してください"
            description.length > 2000 -> "説明は2,000文字以内で入力してください"
            parseDate(targetDate) == null -> "期限をカレンダーから選んでください"
            requireNotNull(parseDate(targetDate)) < LocalDate.now() -> "期限は今日以降にしてください"
            else -> null
        }

        private fun validatePlan(state: GoalUiState): String? {
            validateGoalInput(state.title.trim(), state.description.trim(), state.targetDate)?.let { return it }
            if (state.metrics.size !in 1..10 || state.milestones.size !in 1..10) return "MetricとMilestoneはそれぞれ1〜10件にしてください"
            if (state.metrics.any { it.name.trim().isEmpty() || it.name.length > 100 }) return "Metric名を100文字以内で入力してください"
            if (state.metrics.any { !validProgressValue(it.targetValue) || it.targetValue.toDouble() <= 0 }) return "Metric目標値は正数（小数2桁まで）で入力してください"
            if (state.metrics.any { it.unit.trim().isEmpty() || it.unit.length > 30 }) return "Metric単位を30文字以内で入力してください"
            if (state.milestones.any { it.title.trim().isEmpty() || it.title.length > 120 }) return "Milestone名を120文字以内で入力してください"
            val goalDate = requireNotNull(parseDate(state.targetDate))
            var previous = LocalDate.now()
            for (milestone in state.milestones) {
                val milestoneDate = parseDate(milestone.targetDate) ?: return "Milestone期限をカレンダーから選んでください"
                if (milestoneDate < LocalDate.now() || milestoneDate > goalDate || milestoneDate < previous) return "Milestone期限を今日からGoal期限までの順番で選んでください"
                previous = milestoneDate
            }
            return null
        }
        private fun formatNumber(value: Double): String =
            if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }
}

private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

/** LocalDate is converted in the device zone; dates never shift through UTC. */
@Composable
private fun GoalDateField(label: String, value: String, enabled: Boolean,
    onChange: (String) -> Unit, min: LocalDate = LocalDate.now(), max: LocalDate? = null) {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { showPicker = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(parseDate(value)?.format(DateTimeFormatter.ofPattern("yyyy年M月d日")) ?: "${label}を選ぶ")
        }
    }
    if (showPicker) DisposableEffect(Unit) {
        val initial = (parseDate(value) ?: min).coerceAtLeast(min).let { if (max != null) it.coerceAtMost(max) else it }
        val dialog = DatePickerDialog(context, { _, year, month, day ->
            onChange(LocalDate.of(year, month + 1, day).toString())
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
        dialog.datePicker.minDate = min.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        max?.let { dialog.datePicker.maxDate = it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        dialog.setOnDismissListener { showPicker = false }
        dialog.show()
        onDispose { dialog.dismiss() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalFormScreen(state: GoalUiState, onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit, onTargetDateChange: (String) -> Unit,
    onPreview: () -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    Scaffold(topBar = { TopAppBar(title = { Text("新しいGoal") },
        navigationIcon = { TextButton(onClick = onBack, enabled = !state.isBusy) { Text("戻る") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("達成したいことを教えてください", style = MaterialTheme.typography.headlineSmall)
            Text("目標に合わせた質問に答えてから、AIが計画を提案します。")
            OutlinedTextField(state.title, onTitleChange, Modifier.fillMaxWidth(), label = { Text("タイトル") },
                supportingText = { Text("120文字以内") }, enabled = !state.isBusy, singleLine = true)
            OutlinedTextField(state.description, onDescriptionChange, Modifier.fillMaxWidth(), label = { Text("説明（任意）") },
                supportingText = { Text("背景や取り組み方を2,000文字以内で入力") }, enabled = !state.isBusy, minLines = 4)
            GoalDateField("Goal期限", state.targetDate, !state.isBusy, onTargetDateChange)
            state.formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onPreview, Modifier.fillMaxWidth(), enabled = !state.isBusy) {
                if (state.isLoadingQuestions) CircularProgressIndicator() else Text("質問へ進む")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalQuestionsScreen(state: GoalUiState, onAnswer: (Int, Boolean) -> Unit,
    onPreview: () -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    Scaffold(topBar = { TopAppBar(title = { Text("目標を具体化") },
        navigationIcon = { TextButton(onClick = onBack, enabled = !state.isBusy) { Text("戻る") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(state.title, style = MaterialTheme.typography.headlineSmall)
            Text("AIからの質問です。Yes / Noで答えると、測定項目と中間目標を具体化できます。")
            Text("${state.questions.count { it.answer != null }} / ${state.questions.size} 回答済み")
            state.questions.forEachIndexed { index, item ->
                Card(Modifier.fillMaxWidth().testTag("question-$index")) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${index + 1}. ${item.question}")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilterChip(item.answer == true, { onAnswer(index, true) }, label = { Text("Yes") }, enabled = !state.isBusy)
                            FilterChip(item.answer == false, { onAnswer(index, false) }, label = { Text("No") }, enabled = !state.isBusy)
                        }
                    }
                }
            }
            state.questionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onPreview, Modifier.fillMaxWidth(), enabled = !state.isBusy &&
                state.questions.isNotEmpty() && state.questions.all { it.answer != null }) {
                if (state.isPreviewing) CircularProgressIndicator() else Text("回答から計画を作る")
            }
        }
    }
}

@Composable
fun GoalReplacementDialog(state: GoalUiState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    state.replacement?.let { replacement ->
        AlertDialog(onDismissRequest = onDismiss, title = { Text("再生成しますか？") },
            text = { Text(if (replacement == DraftReplacement.QUESTIONS)
                "目標の変更に合わせて質問と回答を置き換えます。計画の編集内容は、新しい計画の生成が成功するまで保持します。"
                else "最新の回答で計画を作り直します。成功すると、追加・削除を含む計画の編集内容が置き換わります。") },
            confirmButton = { TextButton(onClick = onConfirm) { Text("再生成する") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("戻って確認") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalPreviewScreen(state: GoalUiState, onBack: () -> Unit,
    onMetricNameChange: (Int, String) -> Unit, onMetricValueChange: (Int, String) -> Unit,
    onMetricUnitChange: (Int, String) -> Unit, onMilestoneTitleChange: (Int, String) -> Unit,
    onMilestoneDateChange: (Int, String) -> Unit, onAddMetric: () -> Unit,
    onRemoveMetric: (Int) -> Unit, onAddMilestone: () -> Unit, onRemoveMilestone: (Int) -> Unit,
    onConfirm: () -> Unit) {
    BackHandler { onBack() }
    Scaffold(topBar = { TopAppBar(title = { Text("計画を確認") },
        navigationIcon = { TextButton(onClick = onBack, enabled = !state.isBusy) { Text("戻る") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(state.title, style = MaterialTheme.typography.headlineSmall)
            Text("期限  ${parseDate(state.targetDate)?.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))}")
            Text("達成を測るMetric（${state.metrics.size} / 10）", style = MaterialTheme.typography.titleLarge)
            Text("MetricとMilestoneは、それぞれ1〜10件に調整できます。")
            state.metrics.forEachIndexed { index, metric -> key(metric.id) {
                Card(Modifier.fillMaxWidth().testTag("metric-$index")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(metric.name, { onMetricNameChange(index, it) }, Modifier.fillMaxWidth(), label = { Text("Metric名") }, enabled = !state.isBusy, singleLine = true)
                        OutlinedTextField(metric.targetValue, { onMetricValueChange(index, it) }, Modifier.fillMaxWidth(), label = { Text("目標値") }, enabled = !state.isBusy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                        OutlinedTextField(metric.unit, { onMetricUnitChange(index, it) }, Modifier.fillMaxWidth(), label = { Text("単位") }, enabled = !state.isBusy, singleLine = true)
                        TextButton({ onRemoveMetric(index) }, enabled = !state.isBusy && state.metrics.size > 1) { Text("Metricを削除") }
                    }
                }
            } }
            OutlinedButton(onAddMetric, Modifier.fillMaxWidth(), enabled = !state.isBusy && state.metrics.size < 10) { Text("Metricを追加") }
            Text("達成までのMilestone（${state.milestones.size} / 10）", style = MaterialTheme.typography.titleLarge)
            state.milestones.forEachIndexed { index, milestone -> key(milestone.id) {
                Card(Modifier.fillMaxWidth().testTag("milestone-$index")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("STEP ${index + 1}", color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(milestone.title, { onMilestoneTitleChange(index, it) }, Modifier.fillMaxWidth(), label = { Text("Milestone名") }, enabled = !state.isBusy, singleLine = true)
                        GoalDateField("期限", milestone.targetDate, !state.isBusy, { onMilestoneDateChange(index, it) },
                            max = parseDate(state.targetDate)?.coerceAtLeast(LocalDate.now()))
                        TextButton({ onRemoveMilestone(index) }, enabled = !state.isBusy && state.milestones.size > 1) { Text("Milestoneを削除") }
                    }
                }
            } }
            OutlinedButton(onAddMilestone, Modifier.fillMaxWidth(), enabled = !state.isBusy && state.milestones.size < 10) { Text("Milestoneを追加") }
            state.planError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onConfirm, Modifier.fillMaxWidth(), enabled = !state.isBusy) {
                if (state.isConfirming) CircularProgressIndicator() else Text("この計画で保存する")
            }
        }
    }
}
