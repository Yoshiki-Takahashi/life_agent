package com.yoshiki.lifeagent

import android.Manifest
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.yoshiki.lifeagent.speech.SpeechRecognition
import com.yoshiki.lifeagent.ui.LocalSpeechRecognitionFactory
import com.yoshiki.lifeagent.ui.SpeechInputButton
import com.yoshiki.lifeagent.ui.theme.LifeAgentTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Recognition callbacks are the external boundary; no microphone audio or network is used. */
class SpeechInputTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @get:Rule val microphone = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    private var body by mutableStateOf("元のメモ")
    private var visible by mutableStateOf(true)
    private var available = true
    private lateinit var recognition: FakeSpeechRecognition

    private fun setup() {
        compose.setContent {
            CompositionLocalProvider(LocalSpeechRecognitionFactory provides { _, preferOnDevice ->
                FakeSpeechRecognition(available, onDevice = preferOnDevice).also { recognition = it }
            }) {
                LifeAgentTheme {
                    Text(body)
                    if (visible) SpeechInputButton(body, true) { body = it }
                }
            }
        }
    }

    private fun start() {
        compose.onNodeWithText("音声入力").performClick()
        compose.onNodeWithText("録音開始").performClick()
    }

    @Test fun stoppedTranscriptMustBeReviewedAndFitTheExistingBody() {
        setup()
        start()
        compose.onNodeWithText("録音停止").performClick()
        compose.runOnIdle { assertTrue(recognition.stopped); recognition.result("あ".repeat(2000)) }
        compose.onNodeWithText("本文に追加").assertIsNotEnabled()
        compose.runOnIdle { assertEquals("元のメモ", body) }
        compose.onNodeWithText("文字起こし結果").performTextReplacement("1冊読み終えた")
        compose.onNodeWithText("本文に追加").performClick()
        compose.runOnIdle { assertEquals("元のメモ\n1冊読み終えた", body) }
        compose.onNodeWithText("文字起こし結果").assertDoesNotExist()
    }

    @Test fun failureCancellationAndUnsupportedDeviceKeepManualInput() {
        setup()
        start()
        compose.runOnIdle { recognition.error("認識失敗"); assertEquals("元のメモ", body) }
        compose.onNodeWithText("録音開始").performClick()
        val cancelled = recognition
        compose.onNodeWithText("取消・手入力へ").performClick()
        compose.runOnIdle { cancelled.result("遅れた文字起こし"); assertEquals("元のメモ", body) }
        available = false
        compose.onNodeWithText("音声入力").performClick()
        compose.onNodeWithText("録音開始").assertDoesNotExist()
        compose.onNodeWithText("本文に追加").assertIsNotEnabled()
        compose.onNodeWithText("取消・手入力へ").performClick()
        compose.runOnIdle { assertEquals("元のメモ", body) }
    }

    @Test fun backgroundAndScreenRemovalInvalidateRecognitionResults() {
        setup()
        start()
        val background = recognition
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertTrue(background.closed); background.result("古い録音") }
        compose.onNodeWithText("本文に追加").assertIsNotEnabled()
        compose.onNodeWithText("録音開始").performClick()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { recognition.result("別の利用者の録音"); assertTrue(recognition.closed); assertEquals("元のメモ", body) }
    }

    @Test fun unavailableJapaneseModelCanSwitchServiceOnlyAfterExplicitUserAction() {
        setup()
        start()
        val original = recognition
        compose.runOnIdle { recognition.error("日本語モデルがありません") }
        compose.onNodeWithText("既定の音声認識に切り替える").performScrollTo().performClick()
        compose.onNodeWithText("音声がサービス提供元のサーバーへ送信される場合があります。", substring = true).assertExists()
        compose.runOnIdle {
            assertTrue(original.closed)
            assertFalse(recognition.started)
            original.result("切替前の結果")
            assertEquals("元のメモ", body)
        }
        compose.onNodeWithText("本文に追加").assertIsNotEnabled()
        compose.onNodeWithText("録音開始").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(recognition.started); recognition.result("1冊読み終えた") }
        compose.onNodeWithText("本文に追加").performClick()
        compose.runOnIdle { assertEquals("元のメモ\n1冊読み終えた", body) }
    }

}

/** Run separately with microphone permission denied; the test never revokes a granted permission. */
class SpeechPermissionTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun denyingMicrophoneKeepsManualEditingAndGrantingOnRetryStartsRecording() {
        assumeTrue(ContextCompat.checkSelfPermission(compose.activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
        var body by mutableStateOf("元のメモ")
        val recognition = FakeSpeechRecognition()
        compose.setContent {
            CompositionLocalProvider(LocalSpeechRecognitionFactory provides { _, _ -> recognition }) {
                LifeAgentTheme {
                    OutlinedTextField(body, { body = it }, label = { Text("本文") })
                    SpeechInputButton(body, true) { body = it }
                }
            }
        }
        compose.onNodeWithText("音声入力").performClick()
        compose.onNodeWithText("録音開始").performClick()
        clickPermissionButton("permission_deny_button")
        compose.onNodeWithText("本文に追加").assertIsNotEnabled()
        compose.onNodeWithText("取消・手入力へ").performClick()
        compose.onNodeWithText("本文").assertTextContains("元のメモ").performTextReplacement("手入力で記録")
        compose.runOnIdle { assertEquals("手入力で記録", body) }
        compose.onNodeWithText("音声入力").performClick()
        compose.onNodeWithText("録音開始").performClick()
        clickPermissionButton("permission_allow_foreground_only_button")
        compose.onNodeWithText("録音停止").assertExists()
        compose.runOnIdle { recognition.result("1冊読み終えた") }
        compose.onNodeWithText("本文に追加").performClick()
        compose.runOnIdle { assertEquals("手入力で記録\n1冊読み終えた", body) }
    }

    private fun clickPermissionButton(id: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitUntil(10_000) {
            listOf("com.android.permissioncontroller", "com.google.android.permissioncontroller").any { pkg ->
                automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
                    ?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        }
    }
}

internal class FakeSpeechRecognition(override val available: Boolean = true, override val onDevice: Boolean = true) : SpeechRecognition {
    lateinit var result: (String) -> Unit
    lateinit var error: (String) -> Unit
    var stopped = false
    var closed = false
    var started = false
    override fun start(onResult: (String) -> Unit, onError: (String) -> Unit, onEnd: () -> Unit) {
        started = true
        closed = false
        result = onResult
        error = onError
    }
    override fun stop() { stopped = true }
    override fun close() { closed = true }
}
