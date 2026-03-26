package org.example.app.network

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.example.app.auth.storage.EncryptedPrefsTokenStore
import org.example.app.auth.storage.TokenStore
import org.example.app.network.api.MockApiService
import org.example.app.network.auth.AuthHeaderInterceptor
import org.example.app.network.auth.TokenRefreshAuthenticator
import org.example.app.network.auth.TokenRefresher
import org.example.app.network.mock.MockBackendInterceptor
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory

/**
 * Retrofit/OkHttp wiring for the demo.
 *
 * Uses:
 * - MockBackendInterceptor to avoid needing a real server
 * - AuthHeaderInterceptor to attach access token
 * - TokenRefreshAuthenticator to refresh on 401 with coroutine-safe synchronization
 */
class ApiClient private constructor(
    val api: MockApiService,
    val tokenStore: TokenStore,
) {
    companion object {
        private const val BASE_URL = "https://mock.local/"

        // PUBLIC_INTERFACE
        /**
         * Create a fully-wired API client.
         *
         * Note: This uses an in-process mock backend interceptor; no network access required.
         */
        fun create(context: Context): ApiClient {
            val tokenStore = EncryptedPrefsTokenStore.create(context)

            // Client used ONLY for refresh/login; must not use the authenticator to avoid recursion.
            val refreshOkHttp = OkHttpClient.Builder()
                .addInterceptor(MockBackendInterceptor())
                .addInterceptor(loggingInterceptor())
                .build()

            val refreshRetrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(refreshOkHttp)
                // Scalars converter is present as dependency; we aren't using JSON converters in this mock.
                .addConverterFactory(ScalarsConverterFactory.create())
                .build()

            val refreshApi = refreshRetrofit.create(MockApiService::class.java)
            val tokenRefresher = TokenRefresher(
                tokenStore = tokenStore,
                refreshApi = refreshApi,
            )

            val mainOkHttp = OkHttpClient.Builder()
                // Mock backend acts like a server.
                .addInterceptor(MockBackendInterceptor())
                // Attach auth header.
                .addInterceptor(AuthHeaderInterceptor(tokenStore))
                // Refresh on 401 (thread-safe).
                .authenticator(TokenRefreshAuthenticator(tokenRefresher))
                .addInterceptor(loggingInterceptor())
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(mainOkHttp)
                .addConverterFactory(ScalarsConverterFactory.create())
                .build()

            val api = retrofit.create(MockApiService::class.java)
            return ApiClient(api = api, tokenStore = tokenStore)
        }

        private fun loggingInterceptor(): HttpLoggingInterceptor {
            return HttpLoggingInterceptor().apply {
                // BODY is OK for demo; avoid in production with sensitive tokens.
                level = HttpLoggingInterceptor.Level.BODY
            }
        }
    }
}
