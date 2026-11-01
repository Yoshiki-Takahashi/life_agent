package com.yoshiki.lifeagent.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yoshiki.lifeagent.speech.AndroidSpeechRecognition
import com.yoshiki.lifeagent.speech.SpeechRecognition
import kotlinx.coroutines.delay

val LocalSpeechRecognitionFactory = staticCompositionLocalOf<(Context, Boolean) -> SpeechRecognition> {
    { context, preferOnDevice -> AndroidSpeechRecognition(context, preferOnDevice) }
}

private enum class SpeechPhase { READY, PERMISSION, LISTENING, TRANSCRIBING, RESULT }

@Composable
fun SpeechInputButton(body: String, enabled: Boolean, onBodyChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, enabled = enabled) { Text("音声入力") }
    if (open && enabled) SpeechInputDialog(body, onBodyChange) { open = false }
    LaunchedEffect(enabled) { if (!enabled) open = false }
}

@Composable
private fun SpeechInputDialog(body: String, onBodyChange: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val factory = LocalSpeechRecognitionFactory.current
    var preferOnDevice by remember { mutableStateOf(true) }
    val recognition = remember(context, factory, preferOnDevice) { factory(context, preferOnDevice) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var phase by remember { mutableStateOf(SpeechPhase.READY) }
    var transcript by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableStateOf(0) }
    val currentBody by rememberUpdatedState(body)
    val currentOnBodyChange by rememberUpdatedState(onBodyChange)

    fun cancelRecognition() {
        generation++
        phase = SpeechPhase.READY
        runCatching { recognition.close() }
    }
    fun fail(message: String) {
        cancelRecognition()
        error = message
    }
    fun start() {
        val attempt = ++generation
        error = null
        transcript = ""
        phase = SpeechPhase.LISTENING
        try {
            recognition.start(
                onResult = { text ->
                    if (generation == attempt) {
                        generation++
                        phase = SpeechPhase.RESULT
                        transcript = text.trim()
                    }
                },
                onError = { message -> if (generation == attempt) fail(message) },
                onEnd = { if (generation == attempt) phase = SpeechPhase.TRANSCRIBING },
            )
        } catch (_: Exception) {
            fail("音声入力を開始できませんでした。マイクの権限や端末の音声認識設定を確認してください。")
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (phase == SpeechPhase.PERMISSION && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (granted) start()
            else fail("マイクの使用が許可されていません。端末の設定で許可するか、手入力してください。")
        }
    }
    DisposableEffect(recognition, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            val recording = phase == SpeechPhase.LISTENING || phase == SpeechPhase.TRANSCRIBING
            if (event == Lifecycle.Event.ON_STOP || (event == Lifecycle.Event.ON_PAUSE && recording)) {
                cancelRecognition()
                error = "音声入力を中断しました。再開するには録音開始を選んでください。"
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            cancelRecognition()
        }
    }
    LaunchedEffect(phase, generation) {
        if (phase == SpeechPhase.LISTENING || phase == SpeechPhase.TRANSCRIBING) {
            delay(if (phase == SpeechPhase.LISTENING) 30_000 else 15_000)
            fail("音声入力がタイムアウトしました。短く区切って再試行するか、手入力してください。")
        }
    }
    val active = phase in setOf(SpeechPhase.PERMISSION, SpeechPhase.LISTENING, SpeechPhase.TRANSCRIBING)
    val combined = listOf(currentBody, transcript.trim()).filter { it.isNotBlank() }.joinToString("\n")
    AlertDialog(
        onDismissRequest = { cancelRecognition(); onDismiss() },
        title = { Text("音声で入力") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (recognition.onDevice) "端末内の音声認識を使います。日本語モデルが必要です。"
                    else "端末の既定の音声認識サービスを使います。音声がサービス提供元のサーバーへ送信される場合があります。保持期間・削除方法は提供元の設定とプライバシーポリシーをご確認ください。")
                Text("LifeAgentは音声を保存しません。文字起こしを本文に追加し、編集・確認してから送信してください。")
                if (!recognition.available) Text("この端末では音声認識を利用できません。手入力してください。")
                Text(when (phase) {
                    SpeechPhase.PERMISSION -> "マイクの許可を確認しています"
                    SpeechPhase.LISTENING -> "録音中です。話し終えたら停止してください。"
                    SpeechPhase.TRANSCRIBING -> "文字起こし中です"
                    SpeechPhase.RESULT -> "文字起こしを確認・修正してください"
                    SpeechPhase.READY -> "録音開始を選ぶとマイクを使用します"
                }, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (phase == SpeechPhase.RESULT) {
                    OutlinedTextField(value = transcript, onValueChange = { transcript = it },
                        label = { Text("文字起こし結果") }, modifier = Modifier.fillMaxWidth())
                    if (combined.length > 2000) Text("本文と合わせて2,000文字以内に修正してください。",
                        color = MaterialTheme.colorScheme.error)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if (phase == SpeechPhase.LISTENING) Button(onClick = {
                    phase = SpeechPhase.TRANSCRIBING
                    try { recognition.stop() } catch (_: Exception) { fail("録音を停止できませんでした。再試行してください。") }
                }) { Text("録音停止") }
                if (!active && recognition.available) TextButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        start()
                    else {
                        phase = SpeechPhase.PERMISSION
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) { Text(if (phase == SpeechPhase.RESULT) "録音し直す" else "録音開始") }
                if (!active && recognition.onDevice) TextButton(onClick = {
                    cancelRecognition()
                    transcript = ""
                    error = null
                    preferOnDevice = false
                }) { Text("既定の音声認識に切り替える") }
            }
        },
        confirmButton = {
            TextButton(enabled = phase == SpeechPhase.RESULT && transcript.isNotBlank() && combined.length <= 2000,
                onClick = { cancelRecognition(); currentOnBodyChange(combined); onDismiss() }) { Text("本文に追加") }
        },
        dismissButton = { TextButton(onClick = { cancelRecognition(); onDismiss() }) { Text("取消・手入力へ") } },
    )
}
