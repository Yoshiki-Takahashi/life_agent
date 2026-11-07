package com.yoshiki.lifeagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yoshiki.lifeagent.BuildConfig
import com.yoshiki.lifeagent.data.FeedbackRepository
import com.yoshiki.lifeagent.data.FeedbackRequest
import com.yoshiki.lifeagent.data.FeedbackResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

data class FeedbackUiState(
    val body: String = "",
    val screen: String = "home",
    val isSending: Boolean = false,
    val resultUnknown: Boolean = false,
    val result: FeedbackResult? = null,
    val error: String? = null,
)

class FeedbackViewModel(
    private val repository: FeedbackRepository,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private var pending = saved.get<String>("request")?.let { Json.decodeFromString<FeedbackRequest>(it) }
    private val _uiState = MutableStateFlow(FeedbackUiState(
        body = pending?.body ?: saved["body"] ?: "",
        screen = pending?.screen ?: saved["screen"] ?: "home",
        resultUnknown = pending != null,
        result = saved.get<String>("result")?.let { Json.decodeFromString<FeedbackResult>(it) },
    ))
    val uiState = _uiState.asStateFlow()
    private var generation = 0
    private var sending: Job? = null

    fun openedFrom(screen: String) {
        if (_uiState.value.body.isBlank() && pending == null) {
            saved["screen"] = screen
            _uiState.value = _uiState.value.copy(screen = screen)
        }
    }

    fun updateBody(body: String) {
        if (_uiState.value.isSending || _uiState.value.resultUnknown || body.length > 2000) return
        if (body != _uiState.value.body) {
            pending = null
            saved.remove<String>("request")
        }
        saved["body"] = body
        saved.remove<String>("result")
        _uiState.value = _uiState.value.copy(body = body, result = null, error = null)
    }

    fun send() {
        val state = _uiState.value
        if (state.isSending || state.body.isBlank() || state.result != null) return
        val request = pending ?: FeedbackRequest(
            UUID.randomUUID().toString(), state.body.trim(),
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})", state.screen,
        ).also { pending = it }
        saved["request"] = Json.encodeToString(request)
        val attempt = generation
        _uiState.value = state.copy(isSending = true, error = null)
        sending = viewModelScope.launch {
            try {
                val result = repository.send(request)
                if (generation != attempt) return@launch
                pending = null
                saved.remove<String>("request")
                saved["body"] = ""
                saved["result"] = Json.encodeToString(result)
                _uiState.value = FeedbackUiState(screen = state.screen, result = result)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation != attempt) return@launch
                val detail = (error as? HttpException)?.response()?.errorBody()?.string()?.let {
                    runCatching { Json.parseToJsonElement(it).jsonObject["detail"]?.jsonObject }.getOrNull()
                }
                // A later authentication/configuration failure cannot prove an earlier
                // uncertain submission was not created. Keep its original ID frozen.
                val unknown = state.resultUnknown || (detail?.get("result_unknown")?.jsonPrimitive?.booleanOrNull
                    ?: ((error as? HttpException)?.code() !in listOf(401, 403, 422)))
                _uiState.value = _uiState.value.copy(isSending = false, resultUnknown = unknown,
                    error = if (unknown) "送信結果を確認できません。同じ内容で再確認してください。"
                        else "送信できませんでした。接続やサーバー設定を確認して再試行してください。")
            }
        }
    }

    fun reset() {
        generation++
        sending?.cancel()
        pending = null
        listOf("request", "body", "screen", "result").forEach { saved.remove<String>(it) }
        _uiState.value = FeedbackUiState()
    }

    companion object {
        fun factory(repository: FeedbackRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { FeedbackViewModel(repository, createSavedStateHandle()) } }
    }
}

@Composable
fun FeedbackScreen(state: FeedbackUiState, onBodyChange: (String) -> Unit, onSend: () -> Unit,
    onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    BackHandler { if (!state.isSending) onBack() }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("改善メモ", style = MaterialTheme.typography.headlineMedium)
        Text("使いながら気づいた不具合や、追加したい機能をメモできます。")
        Text("GitHubのLifeAgentリポジトリにIssueとして送信します。公開範囲はリポジトリの設定に従います。",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = state.body, onValueChange = onBodyChange,
            label = { Text("気づいたこと・修正したいこと") },
            placeholder = { Text("例：計画の確認画面でも期限を変更したい") },
            minLines = 6, enabled = !state.isSending && !state.resultUnknown,
            supportingText = { Text("${state.body.length} / 2,000文字") },
            modifier = Modifier.fillMaxWidth())
        Text("送信日時・アプリのバージョン・移動元の画面を添えます。目標や会話の本文は自動添付しません。",
            style = MaterialTheme.typography.bodySmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.result?.let { result ->
            Text("改善メモを送信しました：Issue #${result.issueNumber}")
            TextButton(onClick = { uri.openUri(result.issueUrl) }) { Text("GitHubで確認する") }
        }
        if (state.isSending) CircularProgressIndicator()
        Button(onClick = onSend, enabled = !state.isSending && state.body.isNotBlank() && state.result == null,
            modifier = Modifier.fillMaxWidth()) {
            Text(if (state.resultUnknown) "同じ内容で送信結果を確認" else "改善メモを送信")
        }
    }
}
