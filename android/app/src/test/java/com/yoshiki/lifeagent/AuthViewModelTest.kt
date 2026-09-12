package com.yoshiki.lifeagent

import com.yoshiki.lifeagent.data.AuthRepository
import com.yoshiki.lifeagent.data.AuthUser
import com.yoshiki.lifeagent.ui.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun invalidInputDoesNotCallFirebase() {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository)
        viewModel.updateEmail("invalid")
        viewModel.updatePassword("short")

        viewModel.signIn()

        assertNotNull(viewModel.uiState.value.error)
        assertEquals(0, repository.signInCalls)
    }

    @Test
    fun successfulSignInUpdatesUser() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository)
        viewModel.updateEmail("user@example.com")
        viewModel.updatePassword("password")

        viewModel.signIn()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("user-a", viewModel.uiState.value.user?.uid)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun signOutClearsUser() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository)
        viewModel.updateEmail("user@example.com")
        viewModel.updatePassword("password")
        viewModel.signIn()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.signOut()

        assertNull(viewModel.uiState.value.user)
        assertEquals(1, repository.signOutCalls)
    }
}

private class FakeAuthRepository : AuthRepository {
    private var user: AuthUser? = null
    var signInCalls = 0
    var signOutCalls = 0

    override fun currentUser(): AuthUser? = user

    override suspend fun createAccount(email: String, password: String): AuthUser = signIn(email, password)

    override suspend fun signIn(email: String, password: String): AuthUser {
        signInCalls++
        return AuthUser("user-a", email).also { user = it }
    }

    override fun signOut() {
        signOutCalls++
        user = null
    }

    override suspend fun idToken(): String = "test-token"
}
