package com.yoshiki.lifeagent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.yoshiki.lifeagent.data.MetricProgressInput
import com.yoshiki.lifeagent.data.Network
import com.yoshiki.lifeagent.ui.LifeAgentApp
import com.yoshiki.lifeagent.ui.theme.LifeAgentTheme
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit cloud acceptance; credentials arrive through an app-private temporary file. */
class CloudPersistenceTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun confirmedGoalProgressReplanAndRevisit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("cloudPersistenceE2E") == "true")
        val file = File(instrumentation.targetContext.filesDir, "cloud-persistence-check.json")
        val config = try { JSONObject(file.readText()) } finally { file.delete() }
        val uid = config.getString("uid")
        require(uid.isNotBlank() && config.getString("url").startsWith("https://"))
        val api = Network.createGoalRepository(config.getString("url")) { config.getString("token") }
        val saved = runBlocking {
            assertTrue(api.listGoals().isEmpty())
            val goal = api.confirmGoal(api.previewGoal(
                "Firestore Android保存確認", null, LocalDate.now().plusDays(90).toString()
            ))
            val update = listOf(MetricProgressInput(goal.metrics.first().id, 0.1))
            val requestId = UUID.randomUUID().toString()
            val first = api.recordProgress(goal.id, "確認用実績", update, requestId)
            assertEquals(first, api.recordProgress(goal.id, "確認用実績", update, requestId))
            assertEquals(1, first.progressLogs.size)
            assertEquals(0.1, first.metrics.first().currentValue, 0.0)
            assertTrue(api.getAdvice(goal.id).nextActions.isNotEmpty())
            val proposal = api.previewReplan(goal.id, "期限を14日延ばしたい")
            val applied = api.applyReplan(goal.id, proposal.proposalId)
            assertEquals(goal.planRevision + 1, applied.planRevision)
            assertEquals(first.progressLogs.map { it.id }, applied.progressLogs.map { it.id })
            assertEquals(
                first.progressLogs.flatMap { it.metricUpdates }.map { it.metricId to it.value },
                applied.progressLogs.flatMap { it.metricUpdates }.map { it.metricId to it.value },
            )
            assertEquals(applied, api.getGoal(goal.id))
            applied
        }
        compose.setContent { LifeAgentTheme { LifeAgentApp(api, uid, {},
            Network.createFeedbackRepository(BuildConfig.CORE_API_BASE_URL) { error("not used") }) } }
        compose.waitUntil(35000) {
            compose.onAllNodesWithText(saved.title).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(saved.title).performClick()
        compose.waitUntil(35000) {
            compose.onAllNodesWithText("進捗").fetchSemanticsNodes().isNotEmpty()
        }
        listOf("概要", "進捗", "記録", "計画").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }
}
