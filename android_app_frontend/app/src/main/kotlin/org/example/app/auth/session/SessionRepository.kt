package org.example.app.auth.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.example.app.auth.models.AuthState
import org.example.app.auth.models.LockReason
import org.example.app.auth.models.SessionTokens

/**
 * In-memory session state holder.
 *
 * This does NOT persist; persistence is handled by TokenStore. This repository is intended to be
 * the single source of truth for UI (via StateFlow), while AuthManager coordinates persistence and state.
 */
class SessionRepository {
    private val _state = MutableStateFlow<AuthState>(AuthState.LoggedOut)

    // PUBLIC_INTERFACE
    /**
     * Observable authentication/session state for UI/ViewModels.
     */
    fun stateFlow(): StateFlow<AuthState> = _state.asStateFlow()

    fun setLoggedOut() {
        _state.value = AuthState.LoggedOut
    }

    fun setLocked(tokens: SessionTokens, reason: LockReason) {
        _state.value = AuthState.Locked(session = tokens, reason = reason)
    }

    fun setUnlocked(tokens: SessionTokens) {
        _state.value = AuthState.Unlocked(session = tokens)
    }

    fun currentState(): AuthState = _state.value

    fun currentTokensOrNull(): SessionTokens? {
        return when (val s = _state.value) {
            is AuthState.Unlocked -> s.session
            is AuthState.Locked -> s.session
            AuthState.LoggedOut -> null
        }
    }
}
