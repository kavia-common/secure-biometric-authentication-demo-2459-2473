package org.example.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.example.app.auth.AuthManager
import org.example.app.auth.models.AuthError
import org.example.app.auth.models.AuthOpResult
import org.example.app.auth.models.AuthState
import org.example.app.auth.models.LockReason
import org.example.app.network.ApiClient
import org.example.app.network.api.LoginRequest
import org.example.app.network.api.RefreshRequest

/**
 * ViewModel that drives the main XML UI.
 *
 * Responsibilities:
 * - Initialize AuthManager from storage.
 * - Expose current AuthState for rendering.
 * - Provide user actions: login, lock, unlock, call protected endpoint, logout.
 * - Maintain a UI-friendly status log.
 */
class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val authManager: AuthManager = AuthManager.create(application)
    private val apiClient: ApiClient = ApiClient.create(application)

    private val _authState = MutableStateFlow<AuthState>(AuthState.LoggedOut)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _statusLog = MutableStateFlow<List<String>>(emptyList())
    val statusLog: StateFlow<List<String>> = _statusLog.asStateFlow()

    init {
        // Observe auth state changes for UI.
        viewModelScope.launch {
            authManager.authStateFlow().collectLatest { state ->
                _authState.value = state
                appendLog("Auth state: ${state.toUiLabel()}")
            }
        }

        // Load persisted session; if tokens exist we start Locked (biometric required).
        viewModelScope.launch {
            appendLog("Initializing from secure storage…")
            when (val res = authManager.initializeFromStorage()) {
                AuthOpResult.Success -> appendLog("Initialized.")
                is AuthOpResult.Failure -> appendLog("Init failed: ${res.error.message}")
            }
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Perform demo login via Retrofit mock API and persist tokens.
     *
     * Note: on success AuthManager transitions to Locked to require user presence verification.
     */
    fun login(username: String, password: String) {
        viewModelScope.launch {
            val u = username.trim()
            val p = password

            // The in-app mock backend accepts ANY non-empty username/password.
            // Validate empties so users get an actionable message instead of a generic failure.
            if (u.isBlank() || p.isBlank()) {
                appendLog("Login blocked: enter any non-empty username and password (mock backend accepts any).")
                return@launch
            }

            appendLog("Login requested for user=$u (mock backend: any credentials accepted)…")
            val res = authManager.login(
                username = u,
                password = p,
                performLogin = { uu, pp ->
                    val tr = apiClient.api.login(LoginRequest(username = uu, password = pp))
                    org.example.app.auth.models.AuthResult(
                        tokens = org.example.app.auth.models.SessionTokens(
                            accessToken = tr.accessToken,
                            refreshToken = tr.refreshToken,
                            accessTokenExpiresAtEpochMs = tr.accessTokenExpiresAtEpochMs,
                        ),
                        serverMessage = "mock_login_ok",
                    )
                },
            )

            when (res) {
                AuthOpResult.Success ->
                    appendLog(
                        "Login success. Session is now unlocked; routing to Home. " +
                            "Biometric/device credential will be required only when returning from background."
                    )

                is AuthOpResult.Failure -> {
                    val detail = res.error.toDebugDetail()
                    appendLog("Login failed (unexpected for mock-any-credentials): ${res.error.message}${detail?.let { " | cause=$it" }.orEmpty()}")
                }
            }
        }
    }

    // PUBLIC_INTERFACE
    /** Lock the current session (tokens remain stored). */
    fun lock(reason: LockReason = LockReason.UserInitiated) {
        viewModelScope.launch {
            appendLog("Lock requested (reason=$reason)…")
            when (val res = authManager.lock(reason = reason)) {
                AuthOpResult.Success -> appendLog("Locked.")
                is AuthOpResult.Failure -> appendLog("Lock failed: ${res.error.message}")
            }
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Mark session unlocked after successful biometric/device-credential verification.
     *
     * UI must call this only after BiometricPrompt success.
     */
    fun unlock() {
        viewModelScope.launch {
            appendLog("Unlock requested…")
            when (val res = authManager.unlock()) {
                AuthOpResult.Success -> appendLog("Unlocked.")
                is AuthOpResult.Failure -> appendLog("Unlock failed: ${res.error.message}")
            }
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Call the protected endpoint using the Retrofit client.
     *
     * OkHttp will:
     * - attach Authorization header (if an access token is stored)
     * - refresh automatically via Authenticator if the backend returns 401
     *
     * Note: This demo does not gate the call on unlocked state; it demonstrates the networking layer.
     */
    fun callProtected() {
        viewModelScope.launch {
            appendLog("Calling /protected …")
            try {
                val response = apiClient.api.protectedResource()
                appendLog("Protected OK: ${response.message}")
            } catch (t: Throwable) {
                appendLog("Protected failed: ${t.message ?: t::class.java.simpleName}")
            }
        }
    }

    // PUBLIC_INTERFACE
    /** Clear stored tokens and set state to LoggedOut. */
    fun logout() {
        viewModelScope.launch {
            appendLog("Logout requested…")
            when (val res = authManager.logout()) {
                AuthOpResult.Success -> appendLog("Logged out; tokens cleared.")
                is AuthOpResult.Failure -> appendLog("Logout failed: ${res.error.message}")
            }
        }
    }

    // PUBLIC_INTERFACE
    /** Clear the on-screen status log (does not affect session). */
    fun clearLog() {
        _statusLog.value = emptyList()
        appendLog("Log cleared.")
    }

    private fun appendLog(message: String) {
        // Keep a short rolling log to avoid unbounded memory usage.
        val now = System.currentTimeMillis()
        val entry = "• [$now] $message"
        val updated = (_statusLog.value + entry).takeLast(200)
        _statusLog.value = updated
    }
}

private fun AuthState.toUiLabel(): String {
    return when (this) {
        AuthState.LoggedOut -> "LoggedOut"
        is AuthState.Locked -> "Locked (${this.reason})"
        is AuthState.Unlocked -> "Unlocked (exp=${this.session.accessTokenExpiresAtEpochMs})"
    }
}

private fun AuthError.toDebugDetail(): String? {
    val cause = when (this) {
        is AuthError.Network -> this.cause
        is AuthError.Storage -> this.cause
        is AuthError.Unknown -> this.cause
        else -> null
    } ?: return null

    val msg = cause.message?.takeIf { it.isNotBlank() }
    return if (msg == null) cause::class.java.simpleName else "${cause::class.java.simpleName}: $msg"
}
