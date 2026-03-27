package org.example.app

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.example.app.auth.biometric.BiometricAuthenticator
import org.example.app.auth.models.AuthState
import org.example.app.auth.models.LockReason
import org.example.app.ui.MainViewModel

/**
 * Main XML-based demo screen showcasing:
 * - Login (stores tokens via mock Retrofit API, then locks)
 * - Lock/Unlock (unlock gated by BiometricPrompt + device credential fallback)
 * - Call protected endpoint (Retrofit + OkHttp auth header + automatic refresh)
 * - Logout
 * - Status log
 *
 * Note: This project intentionally uses classic Views/XML (no Compose).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var biometricAuthenticator: BiometricAuthenticator

    private lateinit var textAuthState: TextView
    private lateinit var textSessionInfo: TextView
    private lateinit var editUsername: EditText
    private lateinit var editPassword: EditText

    private lateinit var btnLogin: Button
    private lateinit var btnLock: Button
    private lateinit var btnUnlock: Button
    private lateinit var btnCallProtected: Button
    private lateinit var btnLogout: Button
    private lateinit var btnClearLog: Button

    private lateinit var textLog: TextView
    private lateinit var scrollLog: ScrollView

    private var observeJob: Job? = null

    /**
     * True while a BiometricPrompt is currently showing.
     *
     * We persist this across configuration changes so we don't accidentally launch multiple prompts
     * on rotation; and we also defensively clear it in [onStart] because the prompt can be
     * dismissed due to process death / app restart / task switching where we won't receive callbacks.
     */
    private var biometricPromptInFlight: Boolean = false

    /**
     * True once this Activity has been backgrounded (onStop), and not yet returned to foreground.
     *
     * We use this to enforce the policy:
     * - Only re-lock when the app is backgrounded.
     * - Only prompt biometrics when returning from background.
     *
     * This avoids repeated biometric prompts while the app remains active and the Activity
     * goes through normal resume transitions (including those induced by BiometricPrompt).
     */
    private var wasBackgrounded: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        biometricAuthenticator = BiometricAuthenticator(this)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        biometricPromptInFlight =
            savedInstanceState?.getBoolean(STATE_KEY_BIOMETRIC_IN_FLIGHT, false) ?: false

        bindViews()
        wireClicks()

        observeJob = lifecycleScope.launch {
            launch { observeAuthState() }
            launch { observeStatusLog() }
        }
    }

    override fun onStart() {
        super.onStart()

        // If we are already unlocked (e.g., biometric succeeded during an Activity transition),
        // force routing to Home. This makes "Unlocked -> Home" idempotent and resilient to
        // lifecycle edge cases.
        routeToHomeIfUnlocked()

        // The biometric prompt can be dismissed without us receiving a callback if the app
        // process is killed/restarted. Clear this so users can retry unlock.
        biometricPromptInFlight = false

        // Only enforce biometric unlock when we are *returning from background*.
        // Do not prompt repeatedly while active.
        if (!wasBackgrounded) return
        wasBackgrounded = false

        when (viewModel.authState.value) {
            is AuthState.Unlocked -> {
                // We were actively using the app, then went to background.
                // On return, lock and require user presence verification.
                viewModel.lock(reason = LockReason.AppBackgrounded)
                maybePromptUnlock()
            }

            is AuthState.Locked -> {
                // Session is already locked; returning from background should prompt unlock.
                maybePromptUnlock()
            }

            AuthState.LoggedOut -> Unit
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_KEY_BIOMETRIC_IN_FLIGHT, biometricPromptInFlight)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        // Mark that we are leaving foreground. Actual lock will be applied on return (onStart),
        // so we only ever prompt biometrics *on return from background*.
        wasBackgrounded = true
        super.onStop()
    }

    override fun onDestroy() {
        observeJob?.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        textAuthState = findViewById(R.id.textAuthState)
        textSessionInfo = findViewById(R.id.textSessionInfo)
        editUsername = findViewById(R.id.editUsername)
        editPassword = findViewById(R.id.editPassword)

        btnLogin = findViewById(R.id.btnLogin)
        btnLock = findViewById(R.id.btnLock)
        btnUnlock = findViewById(R.id.btnUnlock)
        btnCallProtected = findViewById(R.id.btnCallProtected)
        btnLogout = findViewById(R.id.btnLogout)
        btnClearLog = findViewById(R.id.btnClearLog)

        textLog = findViewById(R.id.textLog)
        scrollLog = findViewById(R.id.scrollLog)

        // Allow basic scrolling if content grows.
        textLog.movementMethod = ScrollingMovementMethod()
    }

    private fun wireClicks() {
        btnLogin.setOnClickListener {
            val username = editUsername.text?.toString()?.trim().orEmpty()
            val password = editPassword.text?.toString().orEmpty()
            viewModel.login(username = username, password = password)
        }

        btnLock.setOnClickListener {
            viewModel.lock()
        }

        btnUnlock.setOnClickListener {
            maybePromptUnlock(force = true)
        }

        btnCallProtected.setOnClickListener {
            viewModel.callProtected()
        }

        btnLogout.setOnClickListener {
            viewModel.logout()
        }

        btnClearLog.setOnClickListener {
            viewModel.clearLog()
        }
    }

    private suspend fun observeAuthState() {
        viewModel.authState.collectLatest { state ->
            when (state) {
                AuthState.LoggedOut -> {
                    textAuthState.text = "Auth state: LoggedOut"
                    textSessionInfo.text = "No session stored."
                    setButtonsEnabled(
                        canLogin = true,
                        canLock = false,
                        canUnlock = false,
                        canCallProtected = true,
                        canLogout = false,
                    )
                }

                is AuthState.Locked -> {
                    textAuthState.text = "Auth state: Locked (${state.reason})"
                    textSessionInfo.text = "Stored session exists. Unlock required."
                    setButtonsEnabled(
                        canLogin = false,
                        canLock = true,
                        canUnlock = true,
                        canCallProtected = true,
                        canLogout = true,
                    )
                    // IMPORTANT:
                    // Do NOT auto-prompt biometrics merely because we entered Locked state.
                    // Policy for this demo:
                    // - After successful username/password login, route to Home immediately (no prompt).
                    // - Require biometric/device credential only when returning from background
                    //   while a session is active (handled in onStart()).
                    // - Users can also manually unlock via the Unlock button.
                }

                is AuthState.Unlocked -> {
                    // Navigate to Home once the user has successfully unlocked.
                    // Use CLEAR_TASK/NEW_TASK to make this idempotent and avoid back-stack issues.
                    val intent = android.content.Intent(this@MainActivity, HomeActivity::class.java).apply {
                        addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                        )
                    }
                    startActivity(intent)
                    finish()
                }
            }
        }
    }

    private suspend fun observeStatusLog() {
        viewModel.statusLog.collectLatest { lines ->
            val text = if (lines.isEmpty()) {
                getString(R.string.status_log_empty)
            } else {
                lines.joinToString(separator = "\n")
            }
            textLog.text = text
            // Keep scrolled to bottom.
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun setButtonsEnabled(
        canLogin: Boolean,
        canLock: Boolean,
        canUnlock: Boolean,
        canCallProtected: Boolean,
        canLogout: Boolean,
    ) {
        btnLogin.isEnabled = canLogin
        btnLock.isEnabled = canLock
        btnUnlock.isEnabled = canUnlock
        btnCallProtected.isEnabled = canCallProtected
        btnLogout.isEnabled = canLogout
        btnClearLog.isEnabled = true
    }

    private fun maybePromptUnlock(force: Boolean = false) {
        val state = viewModel.authState.value
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
                        // Only after successful user-presence verification do we unlock.
                        viewModel.unlock()
                    },
                    onFailure = {
                        // Non-fatal; user can try again inside prompt.
                    },
                    onError = { _, message ->
                        // Clear in-flight so the user can retry via Unlock button.
                        biometricPromptInFlight = false
                        // Stay locked on cancel/error. Message is available for logging if desired.
                        // (We intentionally do not toast here to keep demo UI simple.)
                        @Suppress("UNUSED_VARIABLE")
                        val unused = message
                        if (force) {
                            // No-op: user initiated unlock; remaining locked is expected on cancel.
                        }
                    },
                )
            }

            is BiometricAuthenticator.Availability.Unavailable -> {
                // If we cannot show biometric prompt, keep session locked.
                // Status log will still show state; user can logout.
                if (force) {
                    // No-op: UI already indicates inability by staying locked.
                }
            }
        }
    }

    private fun routeToHomeIfUnlocked() {
        if (viewModel.authState.value !is AuthState.Unlocked) return

        val intent = android.content.Intent(this@MainActivity, HomeActivity::class.java).apply {
            addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        }
        startActivity(intent)
        finish()
    }

    private companion object {
        private const val STATE_KEY_BIOMETRIC_IN_FLIGHT = "state_biometric_in_flight"
    }
}
