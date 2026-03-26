package org.example.app.network.auth

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * OkHttp Authenticator that attempts token refresh on HTTP 401 responses.
 *
 * Behavior:
 * - If the request already used the latest token and still got 401, we don't loop indefinitely.
 * - Only one refresh happens concurrently (see TokenRefresher mutex).
 */
class TokenRefreshAuthenticator(
    private val tokenRefresher: TokenRefresher,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Avoid infinite retry loops.
        if (responseCount(response) >= 2) return null

        // Don't try to refresh for refresh/login endpoints.
        val path = response.request.url.encodedPath
        if (path.startsWith("/auth/")) return null

        val newTokens = tokenRefresher.refreshBlocking() ?: return null

        // If request already had this token, give up to avoid loops.
        val priorAuth = response.request.header("Authorization")
        val newAuth = "Bearer ${newTokens.accessToken}"
        if (priorAuth == newAuth) return null

        return response.request.newBuilder()
            .header("Authorization", newAuth)
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var r = response.priorResponse
        while (r != null) {
            count++
            r = r.priorResponse
        }
        return count
    }
}
