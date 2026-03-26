package org.example.app.auth.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.example.app.auth.models.SessionTokens

/**
 * Storage interface for auth tokens.
 *
 * Keep this interface free of Android UI concerns so ViewModels can call into AuthManager
 * without needing to understand the underlying storage mechanism.
 */
interface TokenStore {
    // PUBLIC_INTERFACE
    /**
     * Persist tokens securely.
     */
    suspend fun save(tokens: SessionTokens)

    // PUBLIC_INTERFACE
    /**
     * Load tokens if present; returns null if none are stored.
     */
    suspend fun loadOrNull(): SessionTokens?

    // PUBLIC_INTERFACE
    /**
     * Clear all persisted tokens.
     */
    suspend fun clear()

    // PUBLIC_INTERFACE
    /**
     * Best-effort indicator whether a session exists (may still be invalid/expired).
     */
    suspend fun hasTokens(): Boolean
}

/**
 * EncryptedSharedPreferences-backed TokenStore.
 *
 * Uses Android Keystore via MasterKey to encrypt values at rest.
 */
class EncryptedPrefsTokenStore private constructor(
    private val appContext: Context,
) : TokenStore {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun save(tokens: SessionTokens) {
        try {
            prefs.edit()
                .putString(KEY_ACCESS, tokens.accessToken)
                .putString(KEY_REFRESH, tokens.refreshToken)
                .putLong(KEY_ACCESS_EXPIRY_MS, tokens.accessTokenExpiresAtEpochMs)
                .apply()
        } catch (t: Throwable) {
            // Re-throw; AuthManager will wrap into AuthError.Storage.
            throw t
        }
    }

    override suspend fun loadOrNull(): SessionTokens? {
        return try {
            val access = prefs.getString(KEY_ACCESS, null) ?: return null
            val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
            val expiry = prefs.getLong(KEY_ACCESS_EXPIRY_MS, 0L)
            SessionTokens(
                accessToken = access,
                refreshToken = refresh,
                accessTokenExpiresAtEpochMs = expiry,
            )
        } catch (t: Throwable) {
            // Treat failures as "no session"; AuthManager may choose to clear storage.
            null
        }
    }

    override suspend fun clear() {
        try {
            prefs.edit().clear().apply()
        } catch (t: Throwable) {
            // Best-effort; still rethrow so caller can surface.
            throw t
        }
    }

    override suspend fun hasTokens(): Boolean {
        return prefs.contains(KEY_ACCESS) && prefs.contains(KEY_REFRESH)
    }

    companion object {
        private const val PREFS_NAME = "auth_tokens_secure_prefs"

        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_ACCESS_EXPIRY_MS = "access_token_expiry_ms"

        // PUBLIC_INTERFACE
        /**
         * Factory to ensure we always use applicationContext and avoid leaking an Activity.
         */
        fun create(context: Context): EncryptedPrefsTokenStore {
            return EncryptedPrefsTokenStore(context.applicationContext)
        }
    }
}
