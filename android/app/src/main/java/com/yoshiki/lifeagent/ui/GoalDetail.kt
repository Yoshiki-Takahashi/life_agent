package com.yoshiki.lifeagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import retrofit2.HttpException
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yoshiki.lifeagent.data.Advice
import com.yoshiki.lifeagent.data.Goal
import com.yoshiki.lifeagent.data.GoalRepository
import com.yoshiki.lifeagent.data.MetricProgressInput
import com.yoshiki.lifeagent.data.ReplanProposal
import com.yoshiki.lifeagent.data.Metric
import com.yoshiki.lifeagent.data.metricInput
import com.yoshiki.lifeagent.data.progressEditValue
import com.yoshiki.lifeagent.data.progressLabel
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GoalDetailUiState(
    val goal: Goal? = null,
    val isLoading: Boolean = false,
    val isSavingProgress: Boolean = false,
    val isCompleting: Boolean = false,
    val completionError: String? = null,
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
    val kind: String = "cumulative",
    val checklistItems: List<String> = emptyList(),
    val completedItems: List<String> = emptyList(),
) {
    fun metric() = Metric(name, targetValue, unit, id = metricId, currentValue = currentValue,
        kind = kind, checklistItems = checklistItems, completedItems = completedItems)
}

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

    fun completeGoal() {
        val state = _uiState.value
        val goal = state.goal ?: return
        if (state.isLoading || state.isCompleting || state.isSavingProgress || state.isParsing ||
            state.isApplyingReplan || state.isLoadingReplan || goal.status != "active") return
        inputRevision++
        adviceRevision++
        _uiState.update { it.copy(isCompleting = true, completionError = null) }
        viewModelScope.launch {
            runCatching { repository.completeGoal(goal.id) }
                .onSuccess { _uiState.value = stateForGoal(it) }
                .onFailure { _uiState.update { it.copy(isCompleting = false,
                    completionError = "完了を保存できませんでした。同じ目標で再試行してください。") } }
        }
    }

    private var pendingUpdates: List<MetricProgressInput>? = null
    private var requestId: String? = null
        set(value) { field = value; if (value == null) pendingUpdates = null }
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
        if (body.isBlank() || body.length > 2000) {
            _uiState.update { it.copy(progressError = "進捗メモを1〜2,000文字で入力してください") }
            return
        }
        val updates = runCatching { state.metricProgress.mapNotNull { metricInput(it.metric(), it.value) } }
            .getOrElse {
                _uiState.update { it.copy(progressError = "種別に合う数値・チェック項目を入力してください。数値は0以上10億以下・小数2桁までです") }
                return
            }
        if (updates.isEmpty()) {
            _uiState.update { it.copy(progressError = "今回記録するMetricを1つ以上入力してください") }
            return
        }

        val frozenUpdates = pendingUpdates ?: updates.also { pendingUpdates = it }
        val id = requestId ?: UUID.randomUUID().toString().also { requestId = it }
        adviceRevision++
        _uiState.update { it.copy(isSavingProgress = true, progressError = null,
            isLoadingAdvice = false, advice = null, adviceError = null) }
        viewModelScope.launch {
            runCatching { repository.recordProgress(goal.id, body, frozenUpdates, id) }
                .onSuccess { updatedGoal ->
                    requestId = null
                    _uiState.value = stateForGoal(updatedGoal).copy(progressSaved = true)
                    loadAdvice()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSavingProgress = false,
                            progressError = if (error is HttpException && error.code() == 409)
                                "同日の実行は記録済みか、チェック状態が更新されています。目標を開き直してください。"
                                else "進捗を保存できませんでした",
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
                                item.copy(value = progressEditValue(item.metric(), preview.metricUpdates.find { it.metricId == item.metricId }))
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
                kind = metric.kind, checklistItems = metric.checklistItems, completedItems = metric.completedItems,
            )
        },
    )

    companion object {
        fun factory(repository: GoalRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { GoalDetailViewModel(repository) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalDetailScreen(
    state: GoalDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onProgressBodyChange: (String) -> Unit,
    onMetricProgressChange: (Int, String) -> Unit,
    onSaveProgress: () -> Unit,
    onParseProgress: () -> Unit = {},
    onCancelPreview: () -> Unit = {},
    onLoadAdvice: () -> Unit = {},
    onReview: () -> Unit = {},
    onReplanReasonChange: (String) -> Unit = {},
    onPreviewReplan: () -> Unit = {},
    onCancelReplan: () -> Unit = {},
    onApplyReplan: () -> Unit = {},
    onComplete: () -> Unit = {},
) {
    var confirmCompletion by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (confirmCompletion) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmCompletion = false },
        title = { Text("目標を完了しますか？") },
        text = { Text("完了済みの目標へ移動し、記録と計画は閲覧専用になります。未保存の入力は破棄します。") },
        confirmButton = { TextButton(onClick = { confirmCompletion = false; onComplete() }) { Text("完了する") } },
        dismissButton = { TextButton(onClick = { confirmCompletion = false }) { Text("キャンセル") } },
    )
    var selectedTab by rememberSaveable(state.goal?.id) { mutableIntStateOf(0) }
    val scrollStates = List(4) { rememberScrollState() }
    BackHandler { if (!state.isCompleting) onBack() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("目標詳細") },
                navigationIcon = { TextButton(onClick = onBack, enabled = !state.isCompleting) { Text("戻る") } },
                actions = { if (state.goal?.status == "active") TextButton(
                    onClick = { confirmCompletion = true },
                    enabled = !state.isLoading && !state.isCompleting && !state.isSavingProgress && !state.isParsing &&
                        !state.isApplyingReplan && !state.isLoadingReplan,
                ) { Text(if (state.isCompleting) "保存中…" else "完了") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.completionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.goal != null) TabRow(selectedTabIndex = selectedTab) {
                listOf("概要", "進捗", "記録", "計画").forEachIndexed { index, label ->
                    Tab(selected = selectedTab == index,
                        enabled = !state.isCompleting,
                        onClick = { focusManager.clearFocus(); keyboard?.hide(); selectedTab = index },
                        text = { Text(label) })
                }
            }
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scrollStates[selectedTab]).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when {
                    state.isLoading -> CircularProgressIndicator()
                    state.error != null -> {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                        Button(onClick = onRetry) { Text("再読み込み") }
                    }
                    state.goal != null -> {
                        GoalDetail(
                            state = state, selectedTab = selectedTab, onReview = onReview,
                            onProgressBodyChange = onProgressBodyChange,
                            onMetricProgressChange = onMetricProgressChange,
                            onSaveProgress = { focusManager.clearFocus(); keyboard?.hide(); onSaveProgress() },
                            onParseProgress = { focusManager.clearFocus(); keyboard?.hide(); onParseProgress() },
                            onCancelPreview = onCancelPreview,
                            onLoadAdvice = onLoadAdvice,
                            onReplanReasonChange = onReplanReasonChange,
                            onPreviewReplan = { focusManager.clearFocus(); keyboard?.hide(); onPreviewReplan() },
                            onCancelReplan = onCancelReplan,
                            onApplyReplan = { focusManager.clearFocus(); keyboard?.hide(); onApplyReplan() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalDetail(
    state: GoalDetailUiState,
    selectedTab: Int,
    onReview: () -> Unit,
    onProgressBodyChange: (String) -> Unit,
    onMetricProgressChange: (Int, String) -> Unit,
    onSaveProgress: () -> Unit,
    onParseProgress: () -> Unit = {},
    onCancelPreview: () -> Unit = {},
    onLoadAdvice: () -> Unit = {},
    onReplanReasonChange: (String) -> Unit = {},
    onPreviewReplan: () -> Unit = {},
    onCancelReplan: () -> Unit = {},
    onApplyReplan: () -> Unit = {},
) {
    val goal = requireNotNull(state.goal)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(goal.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        Text(if (goal.status == "active") "進行中" else if (goal.status == "completed") "完了済み" else goal.status, color = MaterialTheme.colorScheme.primary)
    }
    if (goal.status == "completed" && selectedTab >= 2) {
        Text("完了済みの目標です。記録と計画の変更はできません。")
        if (selectedTab == 3) goal.milestones.forEach { Text("${it.title}  ${it.targetDate}") }
        return
    }
    if (state.isCompleting) { CircularProgressIndicator(); return }
    if (selectedTab == 0) {
        Text(goal.description ?: "説明はありません")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Goal期限", style = MaterialTheme.typography.labelLarge)
                Text(goal.targetDate, style = MaterialTheme.typography.titleLarge)
            }
        }
        Text("達成を測るMetric", style = MaterialTheme.typography.titleLarge)
        goal.metrics.sortedBy { it.position }.forEach { metric ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(metric.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (metric.kind == "measurement" && goal.progressLogs.none { log -> log.metricUpdates.any { it.metricId == metric.id } }) "未測定 / 目標 ${formatNumber(metric.targetValue)} ${metric.unit}"
                        else "${formatNumber(metric.currentValue)} / ${formatNumber(metric.targetValue)} ${metric.unit}",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Text("作成日時  ${goal.createdAt}", style = MaterialTheme.typography.bodySmall)
    }
    if (selectedTab == 1) {
        TextButton(onClick = onReview) { Text("振り返り・通知設定") }
        Text("進捗の推移", style = MaterialTheme.typography.titleLarge)
        Text("種別に合う保存済みの記録を表示します。未記録期間の活動は推定しません。")
        goal.metrics.sortedBy { it.position }.forEach { metric ->
            MetricTrendCard(metric, goal.progressLogs)
        }
        ProgressHistory(goal)
    }
    if (selectedTab == 2) {
        ProgressEntry(
            state = state,
            onProgressBodyChange = onProgressBodyChange,
            onMetricProgressChange = onMetricProgressChange,
            onSaveProgress = onSaveProgress,
            onParseProgress = onParseProgress,
            onCancelPreview = onCancelPreview,
            onLoadAdvice = onLoadAdvice,
        )
        if (state.progressSaved) Text("進捗を保存しました", color = MaterialTheme.colorScheme.primary)
        if (goal.progressLogs.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("次の一歩", style = MaterialTheme.typography.titleLarge)
                    if (state.isLoadingAdvice) CircularProgressIndicator()
                    state.advice?.let { advice ->
                        Text(advice.summary)
                        advice.nextActions.forEach { Text("• $it") }
                    }
                    state.adviceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onLoadAdvice, enabled = !state.isLoadingAdvice && !state.isSavingProgress) {
                        Text(if (state.adviceError != null) "助言だけ再試行" else "助言を取得")
                    }
                }
            }
        }
    }
    if (selectedTab == 3) {
        if (state.replanApplied) Text("再計画を保存しました", color = MaterialTheme.colorScheme.primary)
        ReplanEntry(
            state = state,
            onReplanReasonChange = onReplanReasonChange,
            onPreviewReplan = onPreviewReplan,
            onCancelReplan = onCancelReplan,
            onApplyReplan = onApplyReplan,
        )
        Text("達成までのMilestone", style = MaterialTheme.typography.titleLarge)
        goal.milestones.sortedBy { it.position }.forEachIndexed { index, milestone ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("${index + 1}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                    Column {
                        Text(milestone.title, style = MaterialTheme.typography.titleMedium)
                        Text(milestone.targetDate)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplanEntry(
    state: GoalDetailUiState,
    onReplanReasonChange: (String) -> Unit,
    onPreviewReplan: () -> Unit,
    onCancelReplan: () -> Unit,
    onApplyReplan: () -> Unit,
) {
    Text("計画を見直す", style = MaterialTheme.typography.titleLarge)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.replanReason,
                onValueChange = onReplanReasonChange,
                label = { Text("見直し理由") },
                supportingText = { Text("例：予定より忙しく、期限と冊数を調整したい") },
                enabled = !state.isLoadingReplan && !state.isApplyingReplan,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onPreviewReplan,
                enabled = !state.isLoadingReplan && !state.isApplyingReplan,
            ) {
                Text(if (state.isLoadingReplan) "生成中…" else "再計画候補を作る")
            }
            state.replanProposal?.let { proposal ->
                Text("変更候補を確認", style = MaterialTheme.typography.titleMedium)
                Text("まだ保存されていません。変更内容を確認してから適用してください。")
                proposal.diff.forEach { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.targetLabel, style = MaterialTheme.typography.titleMedium)
                            Text("${item.before ?: "なし"} → ${item.after ?: "なし"}")
                            Text(item.rationale)
                        }
                    }
                }
                Text("新しい期限 ${proposal.proposedPlan.targetDate}")
                proposal.proposedPlan.metrics.forEach { metric ->
                    Text("${metric.name}: ${formatNumber(metric.targetValue)} ${metric.unit}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onCancelReplan, enabled = !state.isApplyingReplan) {
                        Text("候補を取り消す")
                    }
                    Button(onClick = onApplyReplan, enabled = !state.isApplyingReplan) {
                        Text(if (state.isApplyingReplan) "保存中…" else "この変更を保存")
                    }
                }
            }
            state.replanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ProgressEntry(
    state: GoalDetailUiState,
    onProgressBodyChange: (String) -> Unit,
    onMetricProgressChange: (Int, String) -> Unit,
    onSaveProgress: () -> Unit,
    onParseProgress: () -> Unit = {},
    onCancelPreview: () -> Unit = {},
    onLoadAdvice: () -> Unit = {},
) {
    Text("進捗を記録", style = MaterialTheme.typography.titleLarge)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.progressBody,
                onValueChange = onProgressBodyChange,
                label = { Text("進捗メモ") },
                enabled = !state.isSavingProgress,
                supportingText = { Text("例：今日は2冊読み終えた") },
                modifier = Modifier.fillMaxWidth(),
            )
            SpeechInputButton(body = state.progressBody, enabled = !state.isParsing && !state.isSavingProgress,
                onBodyChange = onProgressBodyChange)
            Button(onClick = onParseProgress, enabled = !state.isParsing && !state.isSavingProgress) {
                Text(if (state.isParsing) "解析中…" else "進捗メモを解析")
            }
            if (state.hasPreview) {
                Text("更新候補を確認", style = MaterialTheme.typography.titleMedium)
                Text("まだ保存されていません。種別に合う記録内容を確認・修正してください。")
                TextButton(onClick = onCancelPreview, enabled = !state.isSavingProgress && !state.isParsing) {
                    Text("候補を取り消して手入力")
                }
            }
            state.parserWarnings.forEach { Text(it) }
            state.metricProgress.forEachIndexed { index, item ->
                MetricValueField(item.metric(), item.value, !state.isSavingProgress && !state.isParsing,
                    onChange = { onMetricProgressChange(index, it) })
            }
            state.progressError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onSaveProgress, enabled = !state.isSavingProgress && !state.isParsing) {
                if (state.isSavingProgress) CircularProgressIndicator() else Text("進捗を保存")
            }
        }
    }
}

@Composable
private fun ProgressHistory(goal: Goal) {
    Text("進捗履歴", style = MaterialTheme.typography.titleLarge)
    if (goal.progressLogs.isEmpty()) {
        Text("進捗履歴はまだありません")
    } else {
        goal.progressLogs.forEach { log ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(log.body, style = MaterialTheme.typography.titleMedium)
                    log.metricUpdates.forEach { update ->
                        Text(
                            progressLabel(update),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(log.recordedAt, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
