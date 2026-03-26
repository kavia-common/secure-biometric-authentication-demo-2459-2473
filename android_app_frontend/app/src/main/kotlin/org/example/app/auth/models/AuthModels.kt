package org.example.app.auth.models

/**
 * Represents the overall authentication lifecycle state used by UI/ViewModels.
 */
sealed class AuthState {
    /**
     * Not authenticated and no persisted session is available.
     */
    data object LoggedOut : AuthState()

    /**
     * A session exists but is locked (requires user presence / biometric / device credential).
     */
    data class Locked(
        val session: SessionTokens,
        val reason: LockReason = LockReason.UserInitiated,
    ) : AuthState()

    /**
     * A session exists and is currently usable.
     */
    data class Unlocked(
        val session: SessionTokens,
    ) : AuthState()
}

/**
 * Reasons why the session is locked. Useful for UI messaging/analytics.
 */
enum class LockReason {
    UserInitiated,
    AppBackgrounded,
    BiometricRequired,
    TokenExpired,
}

/**
 * Token bundle stored securely.
 *
 * Note: For the demo we store plaintext token strings in encrypted-at-rest storage
 * (EncryptedSharedPreferences). In a real app, consider minimizing token lifetime and scope.
 */
data class SessionTokens(
    val accessToken: String,
    val refreshToken: String,
    /**
     * Epoch millis when access token is considered expired (client-side hint).
     */
    val accessTokenExpiresAtEpochMs: Long,
) {
    fun isAccessTokenExpired(nowEpochMs: Long): Boolean = nowEpochMs >= accessTokenExpiresAtEpochMs
}

/**
 * Returned from login/refresh operations.
 */
data class AuthResult(
    val tokens: SessionTokens,
    val serverMessage: String? = null,
)

/**
 * A lightweight result wrapper used by AuthManager public APIs.
 */
sealed class AuthOpResult {
    data object Success : AuthOpResult()
    data class Failure(val error: AuthError) : AuthOpResult()
}

/**
 * Error type for AuthManager failures; safe for UI display (avoid leaking secrets).
 */
sealed class AuthError(open val message: String) {
    data class Storage(override val message: String, val cause: Throwable? = null) : AuthError(message)
    data class Network(override val message: String, val cause: Throwable? = null) : AuthError(message)
    data class Unauthorized(override val message: String = "Unauthorized") : AuthError(message)
    data class Locked(override val message: String = "Session is locked") : AuthError(message)
    data class NoSession(override val message: String = "No active session") : AuthError(message)
    data class Unknown(override val message: String, val cause: Throwable? = null) : AuthError(message)
}
