package com.yoshiki.lifeagent.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class AuthUser(val uid: String, val email: String?, val googleLinked: Boolean = false)

interface AuthRepository {
    fun currentUser(): AuthUser?
    suspend fun createAccount(email: String, password: String): AuthUser
    suspend fun signIn(email: String, password: String): AuthUser
    suspend fun signInWithGoogle(idToken: String): AuthUser
    suspend fun linkGoogle(idToken: String, expectedUserId: String): AuthUser
    suspend fun signOut()
    suspend fun idToken(): String
}

class FirebaseAuthRepository(private val auth: FirebaseAuth) : AuthRepository {
    private val mutations = Mutex()
    override fun currentUser(): AuthUser? = auth.currentUser?.let {
        AuthUser(it.uid, it.email, it.providerData.any { provider -> provider.providerId == GoogleAuthProvider.PROVIDER_ID })
    }

    override suspend fun createAccount(email: String, password: String): AuthUser = mutate {
        auth.createUserWithEmailAndPassword(email, password).await()
        requireNotNull(currentUser())
    }

    override suspend fun signIn(email: String, password: String): AuthUser = mutate {
        auth.signInWithEmailAndPassword(email, password).await()
        requireNotNull(currentUser())
    }

    override suspend fun signInWithGoogle(idToken: String): AuthUser = mutate {
        try {
            auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
            requireNotNull(currentUser())
        } catch (_: FirebaseAuthUserCollisionException) {
            throw AuthFailure("登録済みのメールアカウントでログインしてから、目標一覧でGoogleを連携してください。")
        }
    }

    override suspend fun linkGoogle(idToken: String, expectedUserId: String): AuthUser = mutate {
        val user = auth.currentUser
        if (user == null || user.uid != expectedUserId) throw AuthFailure("ログイン状態が変わりました。もう一度ログインしてください。")
        try {
            user.linkWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
            requireNotNull(currentUser()).also { check(it.uid == expectedUserId) }
        } catch (_: FirebaseAuthUserCollisionException) {
            throw AuthFailure("このGoogleアカウントは別のアカウントで利用されています。現在の連携は変更していません。")
        }
    }

    override suspend fun signOut() = mutate { auth.signOut() }

    // Firebase Tasks can finish after coroutine cancellation. Serialize their completion with logout.
    private suspend fun <T> mutate(action: suspend () -> T): T = mutations.withLock {
        withContext(NonCancellable) { action() }
    }

    override suspend fun idToken(): String = requireNotNull(auth.currentUser) {
        "ログインが必要です"
    }.getIdToken(false).await().token ?: error("ID Tokenを取得できませんでした")
}
