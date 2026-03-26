package org.example.app.network.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit API for the demo.
 *
 * These calls are served by a local OkHttp interceptor (MockBackendInterceptor),
 * not a real server.
 */
interface MockApiService {
    @POST("/auth/login")
    suspend fun login(@Body body: LoginRequest): TokenResponse

    @POST("/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): TokenResponse

    @GET("/protected")
    suspend fun protectedResource(): ProtectedResponse
}
