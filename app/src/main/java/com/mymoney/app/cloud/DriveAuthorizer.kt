package com.mymoney.app.cloud

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.mymoney.app.auth.awaitResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DriveAuthResult {
    data class Granted(val accessToken: String) : DriveAuthResult
    /** Google needs the user to consent (first connection, revoked or expired grant). */
    data class NeedsUser(val pendingIntent: PendingIntent) : DriveAuthResult
}

class DriveAuthException(message: String) : Exception(message)

/**
 * Google Drive authorization, deliberately separate from Google Sign-In: signing in never grants Drive
 * access. Only the app-private `drive.appdata` scope is requested. Access tokens are short-lived, kept
 * in memory for a single operation and never written to disk; Google Play services caches the grant.
 */
interface DriveAuthorizer {
    suspend fun authorize(email: String?): DriveAuthResult
    fun tokenFromConsentResult(data: Intent?): String
    /** Drops a token Google rejected (HTTP 401) so the next [authorize] fetches a fresh one. */
    suspend fun invalidate(token: String)
    /** Revokes this app's Drive grant for [email]. */
    suspend fun revoke(email: String?)
}

const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

@Singleton
class PlayServicesDriveAuthorizer @Inject constructor(@ApplicationContext private val context: Context) : DriveAuthorizer {
    private val client get() = Identity.getAuthorizationClient(context)
    private val scopes = listOf(Scope(DRIVE_APPDATA_SCOPE))

    override suspend fun authorize(email: String?): DriveAuthResult {
        val request = AuthorizationRequest.builder().setRequestedScopes(scopes)
            .apply { if (!email.isNullOrBlank()) setAccount(Account(email, "com.google")) }
            .build()
        val result = try { client.authorize(request).awaitResult() } catch (e: ApiException) {
            throw DriveAuthException("Google Drive authorization failed (${e.statusCode}). Check your connection and try again.")
        }
        if (result.hasResolution()) {
            return DriveAuthResult.NeedsUser(result.pendingIntent ?: throw DriveAuthException("Google Drive needs your permission"))
        }
        if (DRIVE_APPDATA_SCOPE !in result.grantedScopes) throw DriveAuthException("Google Drive access wasn't granted")
        return DriveAuthResult.Granted(result.accessToken ?: throw DriveAuthException("Google didn't return an access token"))
    }

    override fun tokenFromConsentResult(data: Intent?): String {
        if (data == null) throw DriveAuthException("Google Drive permission was not granted")
        val result = try { client.getAuthorizationResultFromIntent(data) } catch (e: ApiException) {
            throw DriveAuthException("Google Drive permission was not granted")
        }
        if (DRIVE_APPDATA_SCOPE !in result.grantedScopes) throw DriveAuthException("Google Drive permission was not granted")
        return result.accessToken ?: throw DriveAuthException("Google didn't return an access token")
    }

    override suspend fun invalidate(token: String) {
        try { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).awaitResult() } catch (_: Exception) { }
    }

    override suspend fun revoke(email: String?) {
        if (email.isNullOrBlank()) return
        try {
            client.revokeAccess(RevokeAccessRequest.builder().setAccount(Account(email, "com.google")).setScopes(scopes).build()).awaitResult()
        } catch (e: Exception) {
            throw DriveAuthException("Couldn't revoke Google Drive access: ${e.message ?: "try again when online"}")
        }
    }
}
