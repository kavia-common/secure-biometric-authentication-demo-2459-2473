package org.example.app

import android.app.Application
import org.example.app.auth.AuthManager

/**
 * Application class that owns singletons used across the app process.
 *
 * Root cause for "Home navigation not happening":
 * - Each Activity creates its own MainViewModel.
 * - MainViewModel previously created its own AuthManager, and AuthManager owns its own SessionRepository.
 * - That meant MainActivity and HomeActivity were observing different auth state flows.
 *
 * By centralizing AuthManager here, both Activities/ViewModels share a single SessionRepository/StateFlow,
 * making auth transitions (Unlocked/Locked/LoggedOut) consistent and navigation reliable.
 */
class SecureBiometricDemoApp : Application() {

    /**
     * Single AuthManager instance for the whole app process.
     *
     * This MUST be process-wide to ensure auth state is shared between MainActivity and HomeActivity.
     */
    lateinit var authManager: AuthManager
        private set

    override fun onCreate() {
        super.onCreate()
        authManager = AuthManager.create(this)
    }
}
