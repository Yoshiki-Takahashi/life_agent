package com.yoshiki.lifeagent.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** OS boundary. Audio is owned by the recognition service, never stored by LifeAgent. */
interface SpeechRecognition {
    val available: Boolean
    val onDevice: Boolean
    fun start(onResult: (String) -> Unit, onError: (String) -> Unit, onEnd: () -> Unit)
    fun stop()
    fun close()
}

class AndroidSpeechRecognition(private val context: Context, preferOnDevice: Boolean = true) : SpeechRecognition {
    override val onDevice = preferOnDevice && Build.VERSION.SDK_INT >= 31 &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    override val available = onDevice || SpeechRecognizer.isRecognitionAvailable(context)
    private var recognizer: SpeechRecognizer? = null

    override fun start(onResult: (String) -> Unit, onError: (String) -> Unit, onEnd: () -> Unit) {
        close()
        val current = if (onDevice && Build.VERSION.SDK_INT >= 31)
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = current
        current.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onEndOfSpeech() {
                if (recognizer === current) onEnd()
            }
            override fun onResults(results: Bundle?) {
                if (recognizer !== current) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull { it.isNotBlank() }.orEmpty()
                close()
                if (text.isBlank()) onError("聞き取れませんでした。もう一度話すか、手入力してください。")
                else onResult(text)
            }
            override fun onError(error: Int) {
                if (recognizer !== current) return
                close()
                onError(when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "マイクの使用が許可されていません。端末の設定で許可するか、手入力してください。"
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "聞き取れませんでした。もう一度話すか、手入力してください。"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "日本語の音声認識を利用できません。端末の音声認識設定を確認するか、手入力してください。"
                    else -> "音声認識に失敗しました。接続やマイクを確認し、再試行するか、手入力してください。"
                })
            }
        })
        current.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        })
    }

    override fun stop() { recognizer?.stopListening() }

    override fun close() {
        val current = recognizer
        // Invalidate before cancel: callbacks can arrive after the screen or user has changed.
        recognizer = null
        if (current != null) {
            try { current.cancel() } finally { current.destroy() }
        }
    }
}
