package com.yoshiki.lifeagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yoshiki.lifeagent.data.Advice
import com.yoshiki.lifeagent.data.CoachMessage
import com.yoshiki.lifeagent.data.Goal
import com.yoshiki.lifeagent.data.GoalRepository
import com.yoshiki.lifeagent.data.Metric
import com.yoshiki.lifeagent.data.MetricProgressInput
import com.yoshiki.lifeagent.data.metricInput
import com.yoshiki.lifeagent.data.progressEditValue
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

enum class CoachPhase { EDITING, GENERATING, CANDIDATE, CONFIRMING, SAVING, RESULT_UNKNOWN }

data class CoachMetricEdit(val metric: Metric, val value: String = "") {
    fun savedMetric(input: MetricProgressInput) = CoachSavedMetric(
        metric.name, input.value, metric.unit, metric.kind, input.completedItems.orEmpty(),
    )
}
data class CoachSavedMetric(val name: String, val value: Double, val unit: String,
    val kind: String = "cumulative", val completedItems: List<String> = emptyList()) {
    fun label() = when (kind) {
        "checklist" -> "$name: 完了 ${completedItems.joinToString("、").ifEmpty { "なし" }}"
        "occurrence" -> "$name: 実行を記録"
        "measurement" -> "$name: ${coachNumber(value)} $unit"
        else -> "$name: +${coachNumber(value)} $unit"
    }
}
data class CoachTurn(
    val id: String,
    val body: String,
    val updates: List<CoachSavedMetric>,
    val reply: String? = null,
    val goalId: String? = null,
    val goalTitle: String? = null,
    val saved: Boolean = true,
    val advice: Advice? = null,
    val adviceLoading: Boolean = false,
    val adviceError: String? = null,
)

data class CoachUiState(
    val goal: Goal? = null,
    val phase: CoachPhase = CoachPhase.EDITING,
    val body: String = "",
    val recordBody: String? = null,
    val reply: String? = null,
    val metrics: List<CoachMetricEdit> = emptyList(),
    val warnings: List<String> = emptyList(),
    val turns: List<CoachTurn> = emptyList(),
    val error: String? = null,
    val resolvedMetricIds: Set<String> = emptySet(),
    val savedCandidates: List<CoachSavedMetric> = emptyList(),
    val showCompletion: Boolean = false,
) {
    val busy get() = phase in setOf(CoachPhase.GENERATING, CoachPhase.SAVING)
    val resultUnknown get() = phase == CoachPhase.RESULT_UNKNOWN
    val hasDraft get() = body.isNotBlank()
    val canLeave get() = !busy && !resultUnknown
    val canEditBody get() = phase != CoachPhase.SAVING && !resultUnknown && resolvedMetricIds.isEmpty()
    val reviewing get() = phase in setOf(CoachPhase.CANDIDATE, CoachPhase.SAVING, CoachPhase.RESULT_UNKNOWN)
    val candidates get() = metrics.filter { it.value.isNotBlank() && it.metric.id !in resolvedMetricIds }
}

private fun CoachUiState.clearCandidate() = copy(goal = null, phase = CoachPhase.EDITING, recordBody = null,
    reply = null, metrics = emptyList(), warnings = emptyList(), error = null,
    resolvedMetricIds = emptySet(), savedCandidates = emptyList())

private data class CoachSaveRequest(
    val id: String, val goalId: String, val body: String,
    val updates: List<MetricProgressInput>, val labels: List<CoachSavedMetric>,
    val candidateId: String?,
)

class CoachViewModel(private val repository: GoalRepository) : ViewModel() {
    private val state = MutableStateFlow(CoachUiState())
    val uiState = state.asStateFlow()
    private var session = 0L
    private var inputRevision = 0L
    private var pendingSave: CoachSaveRequest? = null

    fun reset() {
        session++
        inputRevision++
        pendingSave = null
        state.value = CoachUiState()
    }

    fun discardDraft() {
        if (!state.value.canLeave) return
        cancelCandidate()
        state.update { it.copy(body = "") }
    }

    fun sendMessage() {
        val current = state.value
        if (current.phase != CoachPhase.EDITING || !validBody()) return
        val revision = ++inputRevision
        val activeSession = session
        val history = current.turns.flatMap { turn ->
            listOf(CoachMessage("user", turn.body)) +
                listOfNotNull(turn.reply?.let { CoachMessage("assistant", it) })
        }.takeLast(12)
        state.update { it.copy(phase = CoachPhase.GENERATING, error = null, showCompletion = false) }
        viewModelScope.launch {
            try {
                val response = repository.coachMessage(current.body.trim(), history)
                if (session != activeSession || inputRevision != revision) return@launch
                if (response.intent == "record") {
                    val target = requireNotNull(response.goalId)
                    val source = requireNotNull(response.recordBody)
                    require(source in listOf(current.body.trim()) +
                        history.filter { it.role == "user" }.map { it.content })
                    val goal = repository.getGoal(target)
                    if (session != activeSession || inputRevision != revision) return@launch
                    require(goal.id == target)
                    val preview = repository.previewProgress(goal.id, source)
                    if (session != activeSession || inputRevision != revision) return@launch
                    require(preview.metricUpdates.all { update -> goal.metrics.any { it.id == update.metricId } })
                    require(preview.metricUpdates.map { it.metricId }.distinct().size == preview.metricUpdates.size)
                    state.update { it.copy(goal = goal, recordBody = source, reply = response.reply,
                        phase = CoachPhase.CANDIDATE, warnings = preview.warnings,
                        metrics = goal.metrics.sortedBy { it.position }.map { metric -> CoachMetricEdit(
                            metric, progressEditValue(metric, preview.metricUpdates.find { it.metricId == metric.id })) }) }
                } else {
                    require(response.intent == "discuss" && response.goalId == null)
                    state.update { it.copy(body = "", phase = CoachPhase.EDITING, reply = null,
                        turns = it.turns + CoachTurn(UUID.randomUUID().toString(), current.body.trim(),
                            emptyList(), reply = response.reply, saved = false)) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (session == activeSession && inputRevision == revision) state.update {
                    it.copy(phase = CoachPhase.EDITING, error = "返答を作れませんでした。本文を残しています。再試行してください")
                }
            }
        }
    }

    fun updateBody(body: String) {
        if (!state.value.canEditBody) return
        inputRevision++
        state.update { it.clearCandidate().copy(body = body) }
    }

    private fun validBody(): Boolean {
        if (state.value.body.isBlank() || state.value.body.trim().length > 2000) {
            state.update { it.copy(error = "メッセージを1〜2,000文字で入力してください") }
            return false
        }
        return true
    }

    fun decideCandidate(metricId: String, save: Boolean) {
        val current = state.value
        if (current.phase != CoachPhase.CANDIDATE) return
        val candidate = current.candidates.find { it.metric.id == metricId } ?: return
        if (save) {
            saveRequest(listOf(candidate), metricId)?.let(::submitProgress)
        } else {
            resolveCandidate(current, metricId)
        }
    }

    private fun resolveCandidate(current: CoachUiState, metricId: String) {
        val next = current.copy(phase = CoachPhase.CANDIDATE, error = null,
            resolvedMetricIds = current.resolvedMetricIds + metricId)
        if (next.candidates.isEmpty()) finishCandidate(next, showCompletion = true)
        else state.value = next
    }

    private fun finishCandidate(current: CoachUiState, showCompletion: Boolean) {
        val goal = requireNotNull(current.goal)
        val saved = current.savedCandidates.isNotEmpty()
        val turn = CoachTurn(UUID.randomUUID().toString(), current.body.trim(), current.savedCandidates,
            goalId = goal.id, goalTitle = goal.title, saved = saved)
        state.value = current.clearCandidate().copy(body = "", turns = current.turns + turn,
            showCompletion = saved && showCompletion)
        if (saved) loadAdvice(turn.id)
    }

    fun dismissCompletion() {
        state.update { it.copy(showCompletion = false) }
    }

    fun startManualConfirmation() {
        if (state.value.phase != CoachPhase.CANDIDATE || state.value.resolvedMetricIds.isNotEmpty()) return
        inputRevision++
        state.update { it.copy(phase = CoachPhase.CONFIRMING, error = null) }
    }

    fun updateMetric(metricId: String, value: String) {
        if (state.value.phase != CoachPhase.CONFIRMING) return
        state.update { it.copy(metrics = it.metrics.map { edit ->
            if (edit.metric.id == metricId) edit.copy(value = value) else edit
        }, error = null) }
    }

    fun cancelCandidate() {
        val current = state.value
        if (!current.canLeave) return
        inputRevision++
        if (current.savedCandidates.isNotEmpty()) finishCandidate(current, showCompletion = false)
        else state.value = current.clearCandidate()
    }

    private fun saveRequest(filled: List<CoachMetricEdit>, candidateId: String? = null): CoachSaveRequest? {
        val current = state.value
        val goal = current.goal ?: return null
        if (!validBody()) return null
        if (filled.isEmpty()) {
            state.update { it.copy(error = "今回進んだ測定項目を1つ以上入力してください") }
            return null
        }
        val updates = runCatching { filled.map { requireNotNull(metricInput(it.metric, it.value)) } }.getOrElse {
            state.update { it.copy(error = "種別に合う数値・チェック項目を入力してください。数値は0以上10億以下・小数2桁までです") }
            return null
        }
        return CoachSaveRequest(UUID.randomUUID().toString(), goal.id, (current.recordBody ?: current.body).trim(),
            updates, filled.zip(updates) { edit, input -> edit.savedMetric(input) }, candidateId)
    }

    fun saveProgress() {
        val current = state.value
        val request = when (current.phase) {
            CoachPhase.CONFIRMING -> saveRequest(current.metrics.filter { it.value.isNotBlank() })
            CoachPhase.RESULT_UNKNOWN -> pendingSave
            else -> null
        } ?: return
        submitProgress(request)
    }

    private fun submitProgress(request: CoachSaveRequest) {
        pendingSave = request
        val activeSession = session
        state.update { it.copy(phase = CoachPhase.SAVING, error = null) }
        viewModelScope.launch {
            try {
                val updated = repository.recordProgress(request.goalId, request.body, request.updates, request.id)
                if (session != activeSession) return@launch
                pendingSave = null
                val current = state.value.let { it.copy(goal = updated, savedCandidates = it.savedCandidates + request.labels) }
                if (request.candidateId == null) finishCandidate(current, showCompletion = false)
                else resolveCandidate(current, request.candidateId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (session != activeSession) return@launch
                // A transport/5xx failure may follow a committed write. Keep the exact request frozen.
                val rejected = error is HttpException && error.code() in 400..499
                if (rejected) pendingSave = null
                val retryPhase = if (request.candidateId == null) CoachPhase.CONFIRMING else CoachPhase.CANDIDATE
                state.update { it.copy(
                    phase = if (rejected) retryPhase else CoachPhase.RESULT_UNKNOWN,
                    error = if (error is HttpException && error.code() == 409)
                        "同日の実行は記録済みか、チェック状態が更新されています。候補を取り消して目標を確認してください。"
                        else if (rejected) "保存が拒否されました。入力内容とログイン状態を確認してください"
                        else "保存結果を確認できません。同じ内容で再試行してください",
                ) }
            }
        }
    }

    fun loadAdvice(turnId: String) {
        val current = state.value
        val turn = current.turns.find { it.id == turnId } ?: return
        val goalId = turn.goalId ?: return
        if (turn.adviceLoading || !turn.saved) return
        val activeSession = session
        updateTurn(turnId) { it.copy(adviceLoading = true, adviceError = null) }
        viewModelScope.launch {
            try {
                val advice = repository.getAdvice(goalId)
                if (session == activeSession) updateTurn(turnId) {
                    it.copy(advice = advice, adviceLoading = false)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (session == activeSession) updateTurn(turnId) {
                    it.copy(adviceLoading = false, adviceError = "記録は保存済みです。助言だけ再試行できます")
                }
            }
        }
    }

    private fun updateTurn(id: String, update: (CoachTurn) -> CoachTurn) {
        state.update { it.copy(turns = it.turns.map { turn -> if (turn.id == id) update(turn) else turn }) }
    }

    companion object {
        fun factory(repository: GoalRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { CoachViewModel(repository) } }
    }
}

internal fun coachNumber(value: Double): String =
    if (value.isFinite()) BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() else value.toString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachScreen(
    state: CoachUiState,
    onBack: () -> Unit,
    onBodyChange: (String) -> Unit,
    onSend: () -> Unit,
    onCandidateDecision: (String, Boolean) -> Unit,
    onManual: () -> Unit,
    onCancel: () -> Unit,
    onSaveRetry: () -> Unit,
    onCompletionDismiss: () -> Unit,
    onAdviceRetry: (String) -> Unit,
) {
    val conversation = rememberLazyListState()
    val generating = state.phase == CoachPhase.GENERATING
    val candidates = state.candidates
    LaunchedEffect(state.turns.size, generating, state.recordBody) {
        // Bring the latest reply or swipe instructions into view without jumping after each decision.
        conversation.animateScrollToItem(state.turns.size + if (generating || state.reviewing) 2 else 0)
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler { if (state.canLeave) onBack() }
    if (state.showCompletion) AlertDialog(
        onDismissRequest = onCompletionDismiss,
        title = { Text("記録が完了しました") },
        confirmButton = { TextButton(onClick = onCompletionDismiss) { Text("OK") } },
    )
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = { TopAppBar(title = { Text("コーチ") }, navigationIcon = {
            TextButton(onClick = onBack, enabled = state.canLeave) { Text("戻る") }
        }) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val composerHeight = maxHeight * 0.55f
            Column(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = conversation,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("coach-conversation"),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        CoachCard("コーチ") {
                            Text("いま、何を相談したいですか？", style = MaterialTheme.typography.titleLarge)
                            Text("すべての目標を一緒に考えます。進め方の相談も、今日の実績も、ここで話してください。")
                        }
                    }
                    items(state.turns, key = { it.id }) { turn ->
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CoachCard("あなた") { Text(turn.body) }
                            turn.reply?.let { reply -> CoachCard("コーチ") { Text(reply) } }
                            if (turn.saved) CoachCard("保存済み") {
                                turn.goalTitle?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                                turn.updates.forEach { Text(it.label()) }
                            }
                            if (turn.adviceLoading) CoachCard("現在の状況へのAI助言") {
                                CircularProgressIndicator()
                                Text("助言を取得中です。記録は保存済みです。")
                            }
                            turn.advice?.let { advice -> CoachCard("現在の状況へのAI助言") {
                                Text(advice.summary)
                                advice.nextActions.forEach { Text("• $it") }
                            } }
                            turn.adviceError?.let { error -> CoachCard("助言の取得失敗") {
                                Text(error)
                                TextButton(onClick = { onAdviceRetry(turn.id) }) { Text("助言だけ再試行") }
                            } }
                        }
                    }
                    if (generating || state.reviewing) item {
                        CoachCard("あなた") { Text(state.body) }
                    }
                    if (state.reviewing) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (candidates.isEmpty()) {
                                    state.reply?.let { reply -> CoachCard("コーチ") { Text(reply) } }
                                } else {
                                    Text("右にスワイプで記録、左で見送る", style = MaterialTheme.typography.bodyMedium)
                                }
                                state.warnings.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                        items(candidates, key = { "coach-candidate-${it.metric.id}" }) { candidate ->
                            CoachCandidateCard(state.goal?.title.orEmpty(), candidate,
                                enabled = state.phase == CoachPhase.CANDIDATE,
                                onDecision = { save -> onCandidateDecision(candidate.metric.id, save) })
                        }
                        if (state.phase == CoachPhase.CANDIDATE && state.resolvedMetricIds.isEmpty()) item {
                            TextButton(onClick = onManual) { Text("今回値を手入力する") }
                            if (candidates.isEmpty()) TextButton(onClick = onCancel) { Text("候補を取り消す") }
                        }
                    }
                    if (generating) item {
                        CoachCard("返答を考えています") { CircularProgressIndicator() }
                    }

                }
                Surface(
                    modifier = Modifier.heightIn(max = composerHeight).verticalScroll(rememberScrollState()),
                    tonalElevation = 2.dp) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedTextField(
                            value = state.body, onValueChange = onBodyChange,
                            label = { Text("コーチに話す") }, modifier = Modifier.fillMaxWidth(),
                            enabled = state.canEditBody, maxLines = 3,
                        )
                        SpeechInputButton(body = state.body, enabled = !state.busy && state.canEditBody,
                            onBodyChange = onBodyChange)
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                        when (state.phase) {
                            CoachPhase.EDITING -> Button(onClick = {
                                focus.clearFocus()
                                keyboard?.hide()
                                onSend()
                            }, modifier = Modifier.fillMaxWidth()) { Text("送信") }
                            CoachPhase.SAVING -> Text("記録を保存中…", modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            })
                            CoachPhase.RESULT_UNKNOWN -> Button(onClick = onSaveRetry,
                                modifier = Modifier.fillMaxWidth()) { Text("同じ内容で再試行") }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoachCandidateCard(goalTitle: String, candidate: CoachMetricEdit, enabled: Boolean,
    onDecision: (Boolean) -> Unit) {
    val swipe = rememberSwipeToDismissBoxState()
    val right = if (LocalLayoutDirection.current == LayoutDirection.Ltr) SwipeToDismissBoxValue.StartToEnd
        else SwipeToDismissBoxValue.EndToStart
    val shape = RoundedCornerShape(16.dp)
    val label = runCatching { metricInput(candidate.metric, candidate.value) }.getOrNull()
        ?.let { candidate.savedMetric(it).label() } ?: candidate.metric.name
    LaunchedEffect(swipe.settledValue) {
        val direction = swipe.settledValue
        if (direction != SwipeToDismissBoxValue.Settled) {
            if (enabled) onDecision(direction == right)
            swipe.reset()
        }
    }
    SwipeToDismissBox(
        state = swipe,
        gesturesEnabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("coach-candidate-${candidate.metric.id}")
            .semantics(mergeDescendants = true) {
                if (enabled) customActions = listOf(
                    CustomAccessibilityAction("この候補を記録") { onDecision(true); true },
                    CustomAccessibilityAction("この候補を見送る") { onDecision(false); true },
                )
            },
        backgroundContent = {
            val direction = swipe.dismissDirection
            if (direction != SwipeToDismissBoxValue.Settled) {
                val save = direction == right
                Surface(shape = shape, modifier = Modifier.fillMaxSize(), color =
                    if (save) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    Box(Modifier.padding(horizontal = 16.dp), contentAlignment =
                        if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd) {
                        Text(if (save) "記録" else "見送る")
                    }
                }
            }
        },
    ) {
        Card(modifier = Modifier.fillMaxWidth(), shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(goalTitle, style = MaterialTheme.typography.titleMedium)
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun CoachCard(label: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachConfirmationScreen(
    state: CoachUiState,
    onBack: () -> Unit,
    onMetricChange: (String, String) -> Unit,
    onSave: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler { if (state.canLeave) onBack() }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = { TopAppBar(title = { Text("今回値を確認") }, navigationIcon = {
            TextButton(onClick = onBack, enabled = state.canLeave) { Text("入力へ") }
        }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(state.goal?.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
            Text(if (state.resultUnknown) "保存結果は未確認です。同じ内容でのみ再試行できます。"
                else if (state.phase == CoachPhase.SAVING) "保存結果を確認中です。"
                else "更新候補・未保存", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text(state.recordBody ?: state.body)
            state.warnings.forEach { Text(it) }
            state.metrics.forEach { edit ->
                CoachCard(edit.metric.name) {
                    Text("現在 ${coachNumber(edit.metric.currentValue)} / 目標 ${coachNumber(edit.metric.targetValue)} ${edit.metric.unit}")
                    MetricValueField(edit.metric, edit.value, state.phase == CoachPhase.CONFIRMING,
                        onChange = { onMetricChange(edit.metric.id, it) })
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { focus.clearFocus(); keyboard?.hide(); onSave() },
                enabled = state.phase in setOf(CoachPhase.CONFIRMING, CoachPhase.RESULT_UNKNOWN),
                modifier = Modifier.fillMaxWidth()) {
                Text(if (state.resultUnknown) "同じ内容で再試行" else if (state.phase == CoachPhase.SAVING) "保存中…" else "この内容で保存する")
            }
            TextButton(onClick = onBack, enabled = state.canLeave) { Text("保存せず入力へ戻る") }
        }
    }
}
