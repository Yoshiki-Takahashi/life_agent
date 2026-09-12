package com.yoshiki.lifeagent.data

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

data class AuthUser(val uid: String, val email: String?)

interface AuthRepository {
    fun currentUser(): AuthUser?
    suspend fun createAccount(email: String, password: String): AuthUser
    suspend fun signIn(email: String, password: String): AuthUser
    fun signOut()
    suspend fun idToken(): String
}

class FirebaseAuthRepository(private val auth: FirebaseAuth) : AuthRepository {
    override fun currentUser(): AuthUser? = auth.currentUser?.let { AuthUser(it.uid, it.email) }

    override suspend fun createAccount(email: String, password: String): AuthUser {
        auth.createUserWithEmailAndPassword(email, password).await()
        return requireNotNull(currentUser())
    }

    override suspend fun signIn(email: String, password: String): AuthUser {
        auth.signInWithEmailAndPassword(email, password).await()
        return requireNotNull(currentUser())
    }

    override fun signOut() = auth.signOut()

    override suspend fun idToken(): String = requireNotNull(auth.currentUser) {
        "ログインが必要です"
    }.getIdToken(false).await().token ?: error("ID Tokenを取得できませんでした")
}
