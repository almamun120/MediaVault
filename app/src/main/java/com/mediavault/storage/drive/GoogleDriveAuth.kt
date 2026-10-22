package com.mediavault.storage.drive

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Google Drive authorization through Google Identity Services. No client secret or key is embedded: the
 * OAuth *Android* client is matched by package name + signing-certificate SHA-1 in Google Cloud Console.
 * Only the narrow drive.file scope is requested (files this app creates or the user opens with it).
 */
object GoogleDriveAuth {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Returns a fresh access token, or null when the user must grant access again (interactive step needed). */
    suspend fun silentToken(context: Context): String? {
        val result = Identity.getAuthorizationClient(context).authorize(request()).await()
        return if (result.hasResolution()) null else result.accessToken
    }
}
