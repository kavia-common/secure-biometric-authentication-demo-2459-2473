package org.example.app

import android.os.Bundle
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.example.app.auth.AuthManager
import org.example.app.auth.biometric.BiometricAuthenticator
import org.example.app.auth.models.AuthOpResult
import org.example.app.auth.models.AuthState
import org.example.app.auth.models.LockReason

/**
 * Demo Activity that wires:
 * - AuthManager session state
 * - BiometricPrompt-based unlocking (biometric + device credential fallback)
 * - Optional lock-on-resume behavior
 *
 * Note: This project intentionally uses classic Views/XML (no Compose).
 */
class MainActivity : FragmentActivity() {

    private lateinit var statusText: TextView

    private lateinit var authManager: AuthManager
    private lateinit var biometricAuthenticator: BiometricAuthenticator

    /**
     * Optional behavior: when enabled, the app will lock when it returns to foreground.
     *
     * This is implemented as "lock onPause" (background) and require unlock again onResume.
     * Keep it as a toggleable flag for demo purposes.
     */
    private val lockOnResumeEnabled: Boolean = true

    private var authStateJob: Job? = null
    private var biometricPromptInFlight: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.textView) as TextView

        authManager = AuthManager.create(this)
        biometricAuthenticator = BiometricAuthenticator(this)

        // Load persisted session and start locked if tokens exist.
        lifecycleScope.launch {
            val init = authManager.initializeFromStorage()
            if (init is AuthOpResult.Failure) {
                render("Init failed: ${init.error.message}")
            }
        }

        // Observe auth state changes and drive UI + biometric prompting.
        authStateJob = lifecycleScope.launch {
            authManager.authStateFlow().collectLatest { state ->
                when (state) {
                    AuthState.LoggedOut -> render("Logged out. (No tokens stored)")
                    is AuthState.Locked -> {
                        render("Locked (${state.reason}). Tap to unlock via biometrics/device credential.")
                        // For the demo, we automatically prompt on entering Locked state.
                        promptUnlockIfPossible()
                    }
                    is AuthState.Unlocked -> {
                        render("Unlocked. Access token expires at ${state.session.accessTokenExpiresAtEpochMs}.")
                    }
                }
            }
        }

        // Allow user to tap the status text to trigger unlock prompt again (demo convenience).
        statusText.setOnClickListener {
            promptUnlockIfPossible()
        }
    }

    override fun onResume() {
        super.onResume()

        // If configured, require unlocking when returning to foreground and we have a session.
        if (lockOnResumeEnabled) {
            lifecycleScope.launch {
                val state = authManager.currentState()
                val hasSession = state is AuthState.Unlocked || state is AuthState.Locked
                if (hasSession && state is AuthState.Unlocked) {
                    authManager.lock(reason = LockReason.AppBackgrounded)
                    // Unlock prompt will be triggered by flow observer when state becomes Locked.
                } else if (hasSession && state is AuthState.Locked) {
                    promptUnlockIfPossible()
                }
            }
        } else {
            // Still ensure we show a prompt if already locked.
            promptUnlockIfPossible()
        }
    }

    override fun onPause() {
        super.onPause()
        // Alternative strategy: lock here when moving to background.
        // In this demo, onResume() handles the lock transition for "lock-on-resume".
        // Keeping this empty avoids double-locking across lifecycle transitions.
    }

    override fun onDestroy() {
        authStateJob?.cancel()
        super.onDestroy()
    }

    private fun render(text: String) {
        statusText.text = text
    }

    private fun promptUnlockIfPossible() {
        val state = authManager.currentState()
        if (state !is AuthState.Locked) return
        if (biometricPromptInFlight) return

        when (val avail = biometricAuthenticator.availability()) {
            BiometricAuthenticator.Availability.Available -> {
                biometricPromptInFlight = true
                biometricAuthenticator.authenticate(
                    title = "Unlock session",
                    subtitle = "Verify it’s you to continue (biometric or device credential).",
                    onSuccess = {
                        biometricPromptInFlight = false
                        lifecycleScope.launch {
                            val res = authManager.unlock()
                            if (res is AuthOpResult.Failure) {
                                render("Unlock failed: ${res.error.message}")
                            }
                        }
                    },
                    onFailure = {
                        // Non-fatal: user can try again without leaving the prompt.
                        // Keep current UI text.
                    },
                    onError = { _, errorMessage ->
                        biometricPromptInFlight = false
                        render("Unlock canceled/failed: $errorMessage")
                    },
                )
            }
            is BiometricAuthenticator.Availability.Unavailable -> {
                render("Cannot unlock on this device: ${avail.reason}")
            }
        }
    }
}
