package com.yoshiki.lifeagent

import com.yoshiki.lifeagent.data.*
import com.yoshiki.lifeagent.ui.AuthViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleAuthStateTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    @Test fun cancellationAndFailureAllowRetryWithoutEmailOrPassword() = runTest(dispatcher) {
        val repository = AuthStub()
        val source = GoogleStub()
        val vm = AuthViewModel(repository)
        for (failure in listOf(GoogleSignInCancelled(), AuthFailure("接続失敗"))) {
            source.failure = failure
            vm.googleSignIn(source)
            advanceUntilIdle()
            assertNull(vm.uiState.value.user)
            assertNotNull(vm.uiState.value.error)
            assertFalse(vm.uiState.value.isLoading)
            assertEquals(0, repository.googleCalls)
        }
        source.failure = null
        vm.googleSignIn(source)
        vm.googleSignIn(source)
        advanceUntilIdle()
        assertEquals("owner", vm.uiState.value.user?.uid)
        assertEquals(1, repository.googleCalls)
    }

    @Test fun explicitLinkPreservesTheSessionAndCollisionPreservesTheUser() = runTest(dispatcher) {
        val repository = AuthStub().apply { user = AuthUser("owner", "owner@example.test") }
        val vm = AuthViewModel(repository)
        val source = GoogleStub()
        val session = vm.uiState.value.sessionId
        vm.requestGoogleLink()
        assertEquals(0, repository.linkCalls)
        repository.linkFailure = AuthFailure("別のアカウントに連携済み")
        vm.googleSignIn(source, link = true)
        advanceUntilIdle()
        assertEquals("owner", vm.uiState.value.user?.uid)
        assertFalse(vm.uiState.value.user!!.googleLinked)
        repository.linkFailure = null
        vm.googleSignIn(source, link = true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.user!!.googleLinked)
        assertEquals(session, vm.uiState.value.sessionId)
    }

    @Test fun logoutRejectsLatePickerOrFirebaseResultsAndReloginGetsANewSession() = runTest(dispatcher) {
        for (lateFirebase in listOf(false, true)) {
            val repository = AuthStub()
            val source = GoogleStub()
            val pending = CompletableDeferred<Unit>()
            if (lateFirebase) repository.pending = pending else source.pending = pending
            val vm = AuthViewModel(repository)
            vm.googleSignIn(source)
            runCurrent()
            vm.signOut(source::clear)
            pending.complete(Unit)
            advanceUntilIdle()
            assertNull(vm.uiState.value.user)
            assertEquals(1, source.clears)
            assertEquals(if (lateFirebase) 1 else 0, repository.googleCalls)
            val loggedOutSession = vm.uiState.value.sessionId
            repository.pending = null
            source.pending = null
            vm.googleSignIn(source)
            advanceUntilIdle()
            assertEquals("owner", vm.uiState.value.user?.uid)
            assertTrue(vm.uiState.value.sessionId > loggedOutSession)
        }
    }
}

private class GoogleStub : GoogleSignIn {
    override val configured = true
    var failure: Exception? = null
    var pending: CompletableDeferred<Unit>? = null
    var clears = 0
    override suspend fun idToken(): String {
        // Simulate a provider that delivers even after the requesting coroutine was cancelled.
        withContext(NonCancellable) { pending?.await() }
        failure?.let { throw it }
        return "synthetic-token"
    }
    override suspend fun clear() { clears++ }
}

private class AuthStub : AuthRepository {
    var user: AuthUser? = null
    var pending: CompletableDeferred<Unit>? = null
    var linkFailure: Exception? = null
    var googleCalls = 0
    var linkCalls = 0
    override fun currentUser() = user
    override suspend fun createAccount(email: String, password: String) = AuthUser("owner", email)
    override suspend fun signIn(email: String, password: String) = AuthUser("owner", email)
    override suspend fun signInWithGoogle(idToken: String): AuthUser {
        googleCalls++
        withContext(NonCancellable) { pending?.await() }
        return AuthUser("owner", "owner@example.test", true)
    }
    override suspend fun linkGoogle(idToken: String, expectedUserId: String): AuthUser {
        linkCalls++
        linkFailure?.let { throw it }
        return requireNotNull(user).copy(googleLinked = true)
    }
    override suspend fun signOut() { user = null }
    override suspend fun idToken() = "unused"
}
