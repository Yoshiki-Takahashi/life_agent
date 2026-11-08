package com.yoshiki.lifeagent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.yoshiki.lifeagent.data.*
import com.yoshiki.lifeagent.ui.LifeAgentApp
import com.yoshiki.lifeagent.ui.theme.LifeAgentTheme
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit live check. Secret input and cleanup journal stay in app-private storage. */
class GcpAccountCheckTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun sdkLoginProgressAndRedisplay() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gcpAccountCheck") == "true")
        check(!BuildConfig.USE_FIREBASE_AUTH_EMULATOR)
        check(BuildConfig.CORE_API_BASE_URL.startsWith("https://"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.filesDir, "gcp-account-check.json")
        val config = try { JSONObject(input.readText()) } finally { input.delete() }
        check(BuildConfig.CORE_API_BASE_URL.trimEnd('/') == config.getString("url").trimEnd('/')) {
            "APK and verification service URLs must match"
        }
        val runId = config.getString("run_id")
        require(runId.matches(Regex("[a-f0-9]{32}")))
        val journal = File(context.filesDir, "gcp-account-check-result.json")
        val result = JSONObject().put("run_id", runId).put("goal_ids", JSONArray())
        journal.writeText(result.toString())
        val app = FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID).build(), "gcp-check-$runId")
        val firebase = FirebaseAuth.getInstance(app)
        val auth = FirebaseAuthRepository(firebase)
        try {
            val user = try {
                runBlocking { auth.signIn(config.getString("email"), config.getString("password")) }
            } catch (_: Exception) {
                // Do not attach Firebase exception details to instrumentation reports.
                error("Dedicated account login failed")
            }
            assertTrue("Unexpected verification account", user.uid == config.getString("uid"))
            config.remove("password")
            config.remove("email")
            val api = Network.createGoalRepository(BuildConfig.CORE_API_BASE_URL, auth::idToken)
            val goal = runBlocking {
                val date = LocalDate.now().plusDays(90).toString()
                val preview = api.previewGoal("読書の習慣を作る", null, date)
                assertTrue(preview.metrics.isNotEmpty())
                api.confirmGoal(preview.copy(title = "gcp-check-$runId", targetDate = date,
                    metrics = listOf(Metric("読了冊数", 12.0, "冊"), Metric("読書時間", 60.0, "分")),
                    milestones = listOf(Milestone("振り返る", date))))
            }
            // Journal immediately after creation; host also finds the exact run title on lost responses.
            result.getJSONArray("goal_ids").put(goal.id)
            journal.writeText(result.toString())
            compose.setContent { LifeAgentTheme { LifeAgentApp(api, user.uid, {},
                Network.createFeedbackRepository(BuildConfig.CORE_API_BASE_URL, auth::idToken)) } }
            waitFor(goal.title)
            compose.onNodeWithText(goal.title).performClick()
            waitFor("記録")
            compose.onNodeWithText("記録").performClick()
            for ((index, value) in listOf("13", "0").withIndex()) {
                compose.onNodeWithText("進捗メモ").performScrollTo().performTextReplacement("GCP確認${index + 1}")
                compose.onNodeWithText("読了冊数 の今回値").performScrollTo().performTextReplacement(value)
                compose.onNodeWithText("読書時間 の今回値").performScrollTo().performTextReplacement("0")
                compose.onNodeWithText("進捗を保存").performScrollTo().performClick()
                compose.waitUntil(60000) { runBlocking { api.getGoal(goal.id).progressLogs.size == index + 1 } }
                waitFor("進捗を保存しました")
            }
            runBlocking {
                val saved = api.getGoal(goal.id)
                assertEquals(listOf(13.0, 0.0), saved.metrics.map { it.currentValue })
                assertEquals(2, saved.progressLogs.size)
                assertEquals(listOf(0.0, 0.0), saved.progressLogs.last().metricUpdates.map { it.value })
            }
            compose.onNodeWithText("目標一覧").performClick()
            waitFor(goal.title)
            compose.onNodeWithText(goal.title).performClick()
            waitFor("進捗")
            compose.onNodeWithText("進捗").performClick()
            compose.onNodeWithText("現在 13.0 冊").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("読書時間: +0 分").onFirst().performScrollTo().assertIsDisplayed()
            result.put("passed", true)
            journal.writeText(result.toString())
        } finally {
            config.remove("password")
            config.remove("email")
            firebase.signOut()
            app.delete()
            input.delete()
        }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(60000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}
