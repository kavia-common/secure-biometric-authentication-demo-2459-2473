package org.example.app.network.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import org.example.app.auth.storage.TokenStore

/**
 * OkHttp interceptor that attaches "Authorization: Bearer <token>" if tokens exist.
 *
 * This is a demo implementation that reads from TokenStore synchronously via runBlocking
 * because OkHttp interceptors are not suspend functions.
 */
class AuthHeaderInterceptor(
    private val tokenStore: TokenStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // Don't attach auth headers to auth endpoints themselves.
        val path = original.url.encodedPath
        if (path.startsWith("/auth/")) {
            return chain.proceed(original)
        }

        val tokens = runBlocking { tokenStore.loadOrNull() }
        val accessToken = tokens?.accessToken

        if (accessToken.isNullOrBlank()) {
            return chain.proceed(original)
        }

        val authed = original.newBuilder()
            .header("Authorization", "Bearer $accessToken")
            .build()

        return chain.proceed(authed)
    }
}
