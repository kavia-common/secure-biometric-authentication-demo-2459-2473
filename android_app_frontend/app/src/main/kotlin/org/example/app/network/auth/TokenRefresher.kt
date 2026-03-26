package org.example.app.network.auth

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.app.auth.models.SessionTokens
import org.example.app.auth.storage.TokenStore
import org.example.app.network.api.MockApiService
import org.example.app.network.api.RefreshRequest

/**
 * Handles refresh-token flow in a coroutine-safe manner.
 *
 * This object is intended to be used from OkHttp's Authenticator, which is a blocking API.
 * We bridge to suspend work via runBlocking, while still ensuring only one refresh happens at a time.
 */
class TokenRefresher(
    private val tokenStore: TokenStore,
    private val refreshApi: MockApiService,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val refreshMutex = Mutex()

    // PUBLIC_INTERFACE
    /**
     * Refresh tokens using the current refresh token from storage.
     *
     * @return fresh tokens, or null if no session exists or refresh fails.
     */
    fun refreshBlocking(): SessionTokens? = runBlocking {
        refreshMutex.withLock {
            val current = tokenStore.loadOrNull() ?: return@withLock null

            // Another request may have refreshed already; if so, prefer the latest stored token.
            val latest = tokenStore.loadOrNull()
            if (latest != null && latest.accessToken != current.accessToken && !latest.isAccessTokenExpired(clock())) {
                return@withLock latest
            }

            return@withLock try {
                val response = refreshApi.refresh(RefreshRequest(refreshToken = current.refreshToken))
                val updated = SessionTokens(
                    accessToken = response.accessToken,
                    refreshToken = response.refreshToken,
                    accessTokenExpiresAtEpochMs = response.accessTokenExpiresAtEpochMs,
                )
                tokenStore.save(updated)
                updated
            } catch (_: Throwable) {
                null
            }
        }
    }
}
