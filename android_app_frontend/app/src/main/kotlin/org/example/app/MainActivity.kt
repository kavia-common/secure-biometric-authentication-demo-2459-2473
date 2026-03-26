package org.example.app

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
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
class MainActivity : FragmentActivity() {

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

    /**
     * Optional behavior: when enabled, the app will lock when it returns to foreground.
     *
     * Implemented as: onResume() if currently Unlocked -> lock(AppBackgrounded) then prompt unlock.
     */
    private val lockOnResumeEnabled: Boolean = true

    private var observeJob: Job? = null
    private var biometricPromptInFlight: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        biometricAuthenticator = BiometricAuthenticator(this)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        bindViews()
        wireClicks()

        observeJob = lifecycleScope.launch {
            launch { observeAuthState() }
            launch { observeStatusLog() }
        }
    }

    override fun onResume() {
        super.onResume()

        if (!lockOnResumeEnabled) {
            maybePromptUnlock()
            return
        }

        lifecycleScope.launch {
            when (viewModel.authState.value) {
                is AuthState.Unlocked -> {
                    // ViewModel doesn't currently expose lock reason control; call lock() and log in VM.
                    // We still convey backgrounding by triggering a lock and then prompting unlock.
                    viewModel.lock()
                    // Prompt will run if we end up locked.
                    maybePromptUnlock()
                }

                is AuthState.Locked -> maybePromptUnlock()
                AuthState.LoggedOut -> Unit
            }
        }
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
                    // Auto prompt when entering locked state.
                    maybePromptUnlock()
                }

                is AuthState.Unlocked -> {
                    textAuthState.text = "Auth state: Unlocked"
                    textSessionInfo.text =
                        "Access token expires at: ${state.session.accessTokenExpiresAtEpochMs}"
                    setButtonsEnabled(
                        canLogin = false,
                        canLock = true,
                        canUnlock = false,
                        canCallProtected = true,
                        canLogout = true,
                    )
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
                    onError = { _, _ ->
                        biometricPromptInFlight = false
                        // If user canceled and forced unlock was requested, remain locked.
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
}
