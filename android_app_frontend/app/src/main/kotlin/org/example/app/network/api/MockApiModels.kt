package org.example.app.network.api

/**
 * DTOs used by the mock backend and Retrofit interface.
 *
 * Note: These are intentionally minimal for the demo.
 */
data class LoginRequest(
    val username: String,
    val password: String,
)

data class RefreshRequest(
    val refreshToken: String,
)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    /** Epoch millis */
    val accessTokenExpiresAtEpochMs: Long,
)

data class ProtectedResponse(
    val message: String,
)
