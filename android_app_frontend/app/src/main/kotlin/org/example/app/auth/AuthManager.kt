package org.example.app.auth

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.app.auth.models.AuthError
import org.example.app.auth.models.AuthOpResult
import org.example.app.auth.models.AuthResult
import org.example.app.auth.models.AuthState
import org.example.app.auth.models.LockReason
import org.example.app.auth.models.SessionTokens
import org.example.app.auth.session.SessionRepository
import org.example.app.auth.storage.EncryptedPrefsTokenStore
import org.example.app.auth.storage.TokenStore

/**
 * Core authentication/session manager.
 *
 * Responsibilities:
 * - Own the in-memory auth state (via SessionRepository/StateFlow)
 * - Load/persist tokens with TokenStore (EncryptedSharedPreferences)
 * - Provide a ViewModel/UI-consumable API for:
 *   login, lock, unlock, logout, refresh
 *
 * This demo implementation intentionally keeps network operations abstracted behind lambdas
 * so the architecture can be used with Retrofit/OkHttp later (step 04+).
 */
class AuthManager private constructor(
    private val tokenStore: TokenStore,
    private val sessionRepo: SessionRepository,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
) {

    // PUBLIC_INTERFACE
    /**
     * Observe authentication state changes.
     */
    fun authStateFlow() = sessionRepo.stateFlow()

    // PUBLIC_INTERFACE
    /**
     * Returns current auth state snapshot.
     */
    fun currentState(): AuthState = sessionRepo.currentState()

    // PUBLIC_INTERFACE
    /**
     * Load any persisted session into memory.
     *
     * Intended to be called at app startup (e.g., in Application or first Activity/ViewModel).
     * Default behavior: if tokens exist, we start in Locked state, requiring user to unlock.
     */
    suspend fun initializeFromStorage(): AuthOpResult = withContext(ioDispatcher) {
        try {
            val stored = tokenStore.loadOrNull()
            if (stored == null) {
                sessionRepo.setLoggedOut()
            } else {
                sessionRepo.setLocked(stored, reason = LockReason.BiometricRequired)
            }
            AuthOpResult.Success
        } catch (t: Throwable) {
            AuthOpResult.Failure(AuthError.Storage("Failed to initialize auth storage", t))
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Perform "login" by exchanging credentials for tokens.
     *
     * For the demo app, the caller provides [performLogin] that returns tokens (e.g., Retrofit call).
     * On success: persist tokens and set state to Locked (requiring biometric/device credential unlock).
     */
    suspend fun login(
        username: String,
        password: String,
        performLogin: suspend (username: String, password: String) -> AuthResult,
    ): AuthOpResult = withContext(ioDispatcher) {
        try {
            val result = performLogin(username, password)
            tokenStore.save(result.tokens)
            sessionRepo.setLocked(result.tokens, reason = LockReason.BiometricRequired)
            AuthOpResult.Success
        } catch (t: Throwable) {
            AuthOpResult.Failure(AuthError.Network("Login failed", t))
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Lock the current session (tokens remain persisted).
     */
    suspend fun lock(reason: LockReason = LockReason.UserInitiated): AuthOpResult = withContext(ioDispatcher) {
        val tokens = sessionRepo.currentTokensOrNull()
            ?: return@withContext AuthOpResult.Failure(AuthError.NoSession())

        sessionRepo.setLocked(tokens, reason = reason)
        AuthOpResult.Success
    }

    // PUBLIC_INTERFACE
    /**
     * Unlock the current session after successful user-presence verification (biometric/device credential).
     *
     * UI should call this only after BiometricPrompt success.
     */
    suspend fun unlock(): AuthOpResult = withContext(ioDispatcher) {
        val tokens = sessionRepo.currentTokensOrNull()
            ?: return@withContext AuthOpResult.Failure(AuthError.NoSession())

        sessionRepo.setUnlocked(tokens)
        AuthOpResult.Success
    }

    // PUBLIC_INTERFACE
    /**
     * Logout: clear storage and set state to LoggedOut.
     */
    suspend fun logout(): AuthOpResult = withContext(ioDispatcher) {
        try {
            tokenStore.clear()
            sessionRepo.setLoggedOut()
            AuthOpResult.Success
        } catch (t: Throwable) {
            AuthOpResult.Failure(AuthError.Storage("Failed to clear stored session", t))
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Get an access token for API calls, if currently unlocked.
     */
    suspend fun getAccessTokenIfUnlocked(): Result<String> = withContext(ioDispatcher) {
        when (val state = sessionRepo.currentState()) {
            is AuthState.Unlocked -> Result.success(state.session.accessToken)
            is AuthState.Locked -> Result.failure(IllegalStateException("Session locked"))
            AuthState.LoggedOut -> Result.failure(IllegalStateException("No session"))
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Ensure we have a fresh access token.
     *
     * If session is logged out: fails.
     * If locked: fails (UI must unlock first).
     * If unlocked:
     *   - If access token not expired (client-side), returns Success without changing anything.
     *   - If expired, uses [performRefresh] to obtain new tokens, persists them, updates state (unlocked).
     */
    suspend fun ensureFreshAccessToken(
        performRefresh: suspend (refreshToken: String) -> AuthResult,
    ): AuthOpResult = withContext(ioDispatcher) {
        val state = sessionRepo.currentState()
        if (state is AuthState.LoggedOut) {
            return@withContext AuthOpResult.Failure(AuthError.NoSession())
        }
        if (state is AuthState.Locked) {
            return@withContext AuthOpResult.Failure(AuthError.Locked())
        }

        val unlocked = state as AuthState.Unlocked
        val now = clock()
        if (!unlocked.session.isAccessTokenExpired(now)) {
            return@withContext AuthOpResult.Success
        }

        try {
            val refreshed = performRefresh(unlocked.session.refreshToken)
            tokenStore.save(refreshed.tokens)
            sessionRepo.setUnlocked(refreshed.tokens)
            AuthOpResult.Success
        } catch (t: Throwable) {
            // If refresh fails, lock the session to force re-auth/user intervention.
            sessionRepo.setLocked(unlocked.session, reason = LockReason.TokenExpired)
            AuthOpResult.Failure(AuthError.Network("Token refresh failed", t))
        }
    }

    companion object {
        // PUBLIC_INTERFACE
        /**
         * Factory wiring for production usage.
         */
        fun create(context: Context): AuthManager {
            val tokenStore = EncryptedPrefsTokenStore.create(context)
            val repo = SessionRepository()
            return AuthManager(
                tokenStore = tokenStore,
                sessionRepo = repo,
                ioDispatcher = Dispatchers.IO,
                clock = { System.currentTimeMillis() },
            )
        }

        // PUBLIC_INTERFACE
        /**
         * Factory for tests/integration that want to inject a custom TokenStore or dispatcher.
         */
        fun createForTesting(
            tokenStore: TokenStore,
            sessionRepository: SessionRepository = SessionRepository(),
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            clock: () -> Long = { System.currentTimeMillis() },
        ): AuthManager {
            return AuthManager(
                tokenStore = tokenStore,
                sessionRepo = sessionRepository,
                ioDispatcher = ioDispatcher,
                clock = clock,
            )
        }
    }
}
