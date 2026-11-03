package com.yoshiki.lifeagent.data

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

interface GoogleSignIn {
    val configured: Boolean
    suspend fun idToken(): String
    suspend fun clear()
}

class GoogleSignInCancelled : Exception()
class AuthFailure(message: String) : Exception(message)

class CredentialManagerGoogleSignIn(private val context: Context, private val clientId: String) : GoogleSignIn {
    override val configured = clientId.endsWith(".apps.googleusercontent.com")
    private val manager = CredentialManager.create(context)

    override suspend fun idToken(): String {
        if (!configured) throw AuthFailure("この環境ではGoogleログインを利用できません。メールでログインしてください。")
        try {
            val request = GetCredentialRequest.Builder().addCredentialOption(
                GetSignInWithGoogleOption.Builder(clientId).build(),
            ).build()
            val credential = manager.getCredential(context, request).credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                throw AuthFailure("Googleの認証情報を確認できませんでした。もう一度お試しください。")
            return GoogleIdTokenCredential.createFrom(credential.data).idToken.also {
                if (it.isBlank()) throw AuthFailure("Googleの認証情報を確認できませんでした。")
            }
        } catch (_: GetCredentialCancellationException) {
            throw GoogleSignInCancelled()
        } catch (_: NoCredentialException) {
            throw AuthFailure("Googleアカウントを選択できませんでした。端末のアカウント設定を確認するか、メールでログインしてください。")
        } catch (_: GetCredentialException) {
            throw AuthFailure("Googleログインを開始できませんでした。接続を確認し、再試行してください。")
        }
    }

    override suspend fun clear() { manager.clearCredentialState(ClearCredentialStateRequest()) }
}
