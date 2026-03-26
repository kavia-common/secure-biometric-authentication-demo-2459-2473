package org.example.app.auth.biometric

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Small BiometricPrompt wrapper for the demo app.
 *
 * This supports biometric authentication with device credential fallback (PIN/pattern/passcode)
 * via [BiometricPrompt.PromptInfo.setAllowedAuthenticators].
 */
class BiometricAuthenticator(
    private val activity: FragmentActivity,
) {

    /**
     * Represents whether we can attempt user-presence verification on this device right now.
     */
    sealed class Availability {
        data object Available : Availability()
        data class Unavailable(val reason: String) : Availability()
    }

    // PUBLIC_INTERFACE
    /**
     * Check whether biometric/device-credential authentication is available.
     *
     * @param context Any context; application context is fine.
     */
    fun availability(context: Context = activity.applicationContext): Availability {
        val mgr = BiometricManager.from(context)
        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        return when (mgr.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.Available
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                Availability.Unavailable("No biometrics enrolled and device credential not set up.")
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                Availability.Unavailable("No biometric hardware available.")
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                Availability.Unavailable("Biometric hardware currently unavailable.")
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                Availability.Unavailable("Security update required for biometric auth.")
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED ->
                Availability.Unavailable("Biometric auth unsupported on this device.")
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN ->
                Availability.Unavailable("Biometric status unknown.")
            else -> Availability.Unavailable("Biometric auth not available.")
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Show the biometric/device-credential prompt.
     *
     * @param title Prompt title shown to the user.
     * @param subtitle Prompt subtitle shown to the user.
     * @param onSuccess Called when user presence is verified.
     * @param onFailure Called for non-fatal failures (e.g., bad biometric). The prompt remains.
     * @param onError Called when the prompt ends with an error/cancel.
     */
    fun authenticate(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
        onError: (errorCode: Int, errorMessage: String) -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationFailed() {
                onFailure()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errorCode, errString.toString())
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)

        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            // Using DEVICE_CREDENTIAL means we must not set a negative button text.
            .setAllowedAuthenticators(authenticators)
            .build()

        prompt.authenticate(promptInfo)
    }
}
