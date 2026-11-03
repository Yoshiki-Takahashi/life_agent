package com.yoshiki.lifeagent

import android.util.Base64
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.yoshiki.lifeagent.data.*
import com.yoshiki.lifeagent.ui.AuthViewModel
import com.yoshiki.lifeagent.ui.LifeAgentSession
import com.yoshiki.lifeagent.ui.theme.LifeAgentTheme
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.HttpException

/** Real Firebase SDK + local Auth Emulator + isolated Core API/DB. Google picker is substituted. */
class GoogleAuthFlowTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun explicitLinkKeepsGoalsAndPasswordAndReloginClearsDrafts() {
        localAuth { _, auth, email, password, firstToken, secondToken ->
            val owner = runBlocking { auth.createAccount(email, password) }
            val goals = Network.createGoalRepository("http://127.0.0.1:18007/", auth::idToken)
            val goal = runBlocking { goals.confirmGoal(goals.previewGoal("本を読む", null,
                LocalDate.now().plusDays(30).toString())) }
            val google = PickerStub(firstToken)
            val vm = AuthViewModel(auth)
            compose.setContent { LifeAgentTheme { LifeAgentSession(goals, vm, google) } }
            waitFor("本を読む")
            compose.onNodeWithText("Googleを連携").performClick()
            assertEquals(0, google.requests)
            compose.onNodeWithText("Googleアカウントを選ぶ").performClick()
            waitFor("Googleを連携しました。", true)
            compose.onNodeWithText("閉じる").performClick()
            runBlocking {
                assertEquals(owner.uid, auth.currentUser()?.uid)
                assertTrue(auth.currentUser()!!.googleLinked)
                assertEquals(owner.uid, auth.signIn(email, password).uid)
                assertEquals(goal.id, goals.getGoal(goal.id).id)
            }

            compose.onNodeWithText("新規作成").performClick()
            compose.onNodeWithText("タイトル").performTextReplacement("前のセッションの未保存入力")
            compose.runOnIdle { vm.signOut(google::clear) }
            waitFor("Googleでログイン")
            compose.onNodeWithText("Googleでログイン").performScrollTo().performClick()
            waitFor("本を読む")
            assertEquals(owner.uid, auth.currentUser()?.uid)
            compose.onNodeWithText("新規作成").performClick()
            compose.onNodeWithText("タイトル").assertExists()
            compose.onNodeWithText("前のセッションの未保存入力").assertDoesNotExist()
            compose.onNodeWithText("目標一覧").performClick()
            compose.onNodeWithText("ログアウト").performClick()
            waitFor("Googleでログイン")
            google.cancelNext = true
            compose.onNodeWithText("Googleでログイン").performScrollTo().performClick()
            waitFor("Googleログインを取り消しました。")
            google.token = secondToken
            compose.onNodeWithText("Googleでログイン").performScrollTo().performClick()
            waitFor("最初のGoalを作りましょう")
            compose.onNodeWithText("本を読む").assertDoesNotExist()
            runBlocking {
                val rejected = runCatching { goals.getGoal(goal.id) }.exceptionOrNull()
                assertTrue(rejected is HttpException && rejected.code() == 404)
            }
            assertTrue(google.clears >= 2)
        }
    }

    @Test fun googleAlreadyOwnedByAnotherAccountCannotBeLinkedOrMoveTheCurrentUser() {
        localAuth { _, auth, email, password, _, secondToken -> runBlocking {
            val owner = auth.createAccount(email, password)
            val other = auth.signInWithGoogle(secondToken)
            assertNotEquals(owner.uid, other.uid)
            auth.signIn(email, password)
            val rejected = runCatching { auth.linkGoogle(secondToken, owner.uid) }.exceptionOrNull()
            assertTrue(rejected is AuthFailure)
            assertEquals(owner.uid, auth.currentUser()?.uid)
            assertFalse(auth.currentUser()!!.googleLinked)
            assertTrue(runCatching { auth.linkGoogle(secondToken, other.uid) }.exceptionOrNull() is AuthFailure)
            assertEquals(owner.uid, auth.currentUser()?.uid)
        } }
    }

    private fun localAuth(action: (FirebaseAuth, FirebaseAuthRepository, String, String, String, String) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("authEmulator") == "true")
        val suffix = UUID.randomUUID().toString()
        val app = FirebaseApp.initializeApp(compose.activity, FirebaseOptions.Builder()
            .setProjectId("life-agent-local").setApiKey("local-emulator-api-key")
            .setApplicationId("1:1234567890:android:lifeagentlocal").build(), "google-test-$suffix")
        val firebase = FirebaseAuth.getInstance(app).apply { useEmulator("10.0.2.2", 9099) }
        val auth = FirebaseAuthRepository(firebase)
        val email = "google-owner-$suffix@example.test"
        val password = "local-test-$suffix"
        val firstToken = token("google-$suffix", email)
        val secondToken = token("other-$suffix", "other-$suffix@example.test")
        try { action(firebase, auth, email, password, firstToken, secondToken) }
        finally {
            runBlocking {
                runCatching { auth.signInWithGoogle(secondToken); firebase.currentUser!!.delete().await() }
                runCatching { auth.signIn(email, password); firebase.currentUser!!.delete().await() }
                auth.signOut()
            }
            // Auth's emulator connection caches the app until the instrumentation process exits.
        }
    }

    private fun waitFor(text: String, substring: Boolean = false) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun token(subject: String, email: String): String {
        fun encode(json: JSONObject) = Base64.encodeToString(json.toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val now = System.currentTimeMillis() / 1000
        return encode(JSONObject().put("alg", "none")) + "." + encode(JSONObject()
            .put("iss", "https://accounts.google.com").put("sub", subject).put("aud", "local-test")
            .put("email", email).put("email_verified", true).put("iat", now).put("exp", now + 3600)) + "."
    }
}

private class PickerStub(var token: String) : GoogleSignIn {
    override val configured = true
    var cancelNext = false
    var requests = 0
    var clears = 0
    override suspend fun idToken(): String {
        requests++
        if (cancelNext) { cancelNext = false; throw GoogleSignInCancelled() }
        return token
    }
    override suspend fun clear() { clears++ }
}
