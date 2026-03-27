# Biometric Login to Home + Calculator + Logout (Updated Flow and Key Snippets)

## Scope and constraints

This document explains the current (updated) screen flow, UI structure, and the key Kotlin snippets involved in routing from biometric unlock to Home, performing calculator operations, and logging out (clearing session/tokens). It reflects the existing app implementation, which uses classic Android Views with XML layouts (not Jetpack Compose).

## Updated screen flow (what happens after auth events)

The app has two Activities:

1. `MainActivity` is the entry point (launcher) and owns the authentication UI plus biometric prompting.
2. `HomeActivity` is the post-auth Home screen containing an app bar (Toolbar) with a Logout action and a basic calculator.

The flow is driven by `MainViewModel.authState` (`StateFlow<AuthState>`) and Activity lifecycle:

1. **App start**  
   `MainViewModel` calls `AuthManager.initializeFromStorage()` at initialization. If tokens exist, the app starts in `AuthState.Locked` (reason `BiometricRequired`); otherwise it is `LoggedOut`.

2. **Login (username/password, mock backend)**  
   The login button calls `viewModel.login()`. On success, `AuthManager.login()` persists tokens and sets state to `AuthState.Unlocked`. The UI does not force a biometric prompt immediately after login.

3. **Navigate to Home (Unlocked -> Home)**  
   `MainActivity` observes `authState`. When it becomes `AuthState.Unlocked`, it navigates to `HomeActivity` using `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` and then finishes `MainActivity`. These flags make the navigation idempotent and prevent back-stack issues.

4. **Biometric gating policy (prompt only when returning from background)**  
   `MainActivity` tracks `wasBackgrounded`. When the app returns from background (`onStart` after `onStop`), it locks the session and prompts for biometric/device credentials if needed. This avoids repeated prompts during normal Activity resumes, including the resume transitions caused by `BiometricPrompt`.

5. **Logout (from Home or Main)**  
   Logout invokes `viewModel.logout()`, which calls `AuthManager.logout()` to clear encrypted token storage and set the state to `AuthState.LoggedOut`. `HomeActivity` observes the same `authState` flow; when it becomes `LoggedOut`, it routes back to `MainActivity` and clears the task/back stack.

## UI structure (XML and menu wiring)

### MainActivity screen (`activity_main.xml`)

The main screen provides:

- Auth state and session info text
- Username/password fields and Login button
- Lock/Unlock controls (Unlock triggers biometric prompt)
- Protected endpoint call (network demo)
- Logout and a status log

The layout is a single vertical `LinearLayout`.

### Home screen (`activity_home.xml`)

The Home screen is a `ScrollView` with a vertical `LinearLayout` that contains:

1. A top app bar implemented as `androidx.appcompat.widget.Toolbar` with ID `@+id/toolbar`
2. A “signed in” message (`textHomeBody`)
3. A calculator section:
   - Two `EditText` inputs (`editNumberA`, `editNumberB`)
   - Buttons for Add/Subtract/Multiply/Divide and Clear
   - A result `TextView` (`textCalcResult`)

### Logout menu (`home_menu.xml`)

`HomeActivity` inflates a menu resource with a single action:

- `@id/action_logout` shown always in the Toolbar.

## Key Kotlin snippets

### 1) Unlocked -> Home navigation (fix for “not navigating after biometric auth”)

`MainActivity.observeAuthState()` performs the routing when `AuthState.Unlocked` is emitted:

```kotlin
is AuthState.Unlocked -> {
    val intent = Intent(this@MainActivity, HomeActivity::class.java).apply {
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
    }
    startActivity(intent)
    finish()
}
```

This ensures that when biometric authentication succeeds (and `viewModel.unlock()` transitions to `Unlocked`), the app reliably opens Home and removes `MainActivity` from the back stack.

### 2) Biometric prompt and unlock signal

`MainActivity.maybePromptUnlock()` shows the prompt and, on success, calls `viewModel.unlock()`:

```kotlin
biometricAuthenticator.authenticate(
    title = "Unlock session",
    subtitle = "Verify it’s you to continue (biometric or device credential).",
    onSuccess = {
        biometricPromptInFlight = false
        viewModel.unlock()
    },
    onFailure = { /* prompt remains */ },
    onError = { _, _ -> biometricPromptInFlight = false },
)
```

The biometric wrapper uses `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` so the user can authenticate via biometrics or device PIN/pattern/password.

### 3) Home Toolbar + Logout menu handling

`HomeActivity` wires the Toolbar as the app bar and handles the menu click:

```kotlin
val toolbar: Toolbar = findViewById(R.id.toolbar)
setSupportActionBar(toolbar)

override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.home_menu, menu)
    return true
}

override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
        R.id.action_logout -> {
            viewModel.logout()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
```

### 4) Calculator logic (parse, validate, compute, render)

The calculator is implemented inside `HomeActivity` and is intentionally simple and local to the Activity.

The compute routine:

```kotlin
private fun compute(op: Operation) {
    val a = editA.text?.toString()?.trim().orEmpty()
    val b = editB.text?.toString()?.trim().orEmpty()

    val x = a.toDoubleOrNull()
    val y = b.toDoubleOrNull()

    if (x == null || y == null) {
        textResult.text = getString(R.string.error_invalid_number)
        return
    }

    if (op == Operation.Divide && y == 0.0) {
        textResult.text = getString(R.string.error_divide_by_zero)
        return
    }

    val result = when (op) {
        Operation.Add -> x + y
        Operation.Subtract -> x - y
        Operation.Multiply -> x * y
        Operation.Divide -> x / y
    }

    renderResult(label = getString(R.string.calculator_result_prefix), value = result)
}
```

And the “pretty” rendering (integers without a trailing `.0`):

```kotlin
private fun renderResult(label: String, value: Double?) {
    textResult.text = if (value == null) {
        label
    } else {
        val pretty = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        "$label $pretty"
    }
}
```

### 5) Logout/session clearing (storage + state)

Logout is ultimately implemented in `AuthManager.logout()`, which clears encrypted token storage and sets `LoggedOut`:

```kotlin
suspend fun logout(): AuthOpResult = withContext(ioDispatcher) {
    tokenStore.clear()
    sessionRepo.setLoggedOut()
    AuthOpResult.Success
}
```

`MainViewModel.logout()` is the UI-facing action that calls into `AuthManager`:

```kotlin
fun logout() {
    viewModelScope.launch {
        when (authManager.logout()) {
            AuthOpResult.Success -> appendLog("Logged out; tokens cleared.")
            is AuthOpResult.Failure -> appendLog("Logout failed.")
        }
    }
}
```

Finally, `HomeActivity` reacts to `AuthState.LoggedOut` and clears the back stack when returning to `MainActivity`:

```kotlin
val intent = Intent(this@HomeActivity, MainActivity::class.java).apply {
    addFlags(
        Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TASK
    )
}
startActivity(intent)
finish()
```

## Notes on “Material 3” vs current theme

The current project theme is based on `Theme.MaterialComponents.DayNight.NoActionBar` (Material Components), and the Home app bar is an AppCompat `Toolbar` with a MaterialComponents action bar overlay. This is consistent with the repository constraint of XML-based Views and avoids introducing Compose.

If a future iteration needs full Material 3 components (`com.google.android.material:material` already exists), that would typically involve migrating themes/styles toward Material 3 and updating widgets to Material equivalents, but that is outside the scope of this “flow + snippet” explanation.
