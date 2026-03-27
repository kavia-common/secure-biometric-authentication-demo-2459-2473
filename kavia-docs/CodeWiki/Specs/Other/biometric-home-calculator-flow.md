# Biometric Login to Home + Calculator + Logout (Final Flow, UI Structure, and Key Kotlin Snippets)

## Scope and constraints

This document describes the current implementation of the requested feature set from the authoritative requirements (“fix biometric navigation to Home”, “modernize UI using Material Design 3”, “Home has AppBar + Logout + calculator”, and “handle edge cases like cancel/failure/app restart”). The app is implemented with classic Android Views and XML layouts (no Jetpack Compose), and it uses two Activities: `MainActivity` (launcher/auth) and `HomeActivity` (post-auth Home + calculator).

## Requirements mapping (authoritative)

The attached requirements are implemented as follows.

After successful biometric authentication, the user is navigated to the Home screen. This is done by observing `AuthState.Unlocked` and starting `HomeActivity` with task-clearing flags from `MainActivity`.

The UI is modernized using Material Design 3 components and theme wiring. The app theme is `Theme.Material3.DayNight.NoActionBar`, layouts use Material widgets (for example `MaterialCardView`, `MaterialToolbar`, `MaterialButton`, `TextInputLayout`), and the palette is defined via `md_theme_*` colors.

The Home screen includes a top app bar with title and a Logout action, and the body includes a calculator (two inputs, operations, result). Logout clears the session and returns to the biometric/login screen (`MainActivity`) with the back stack cleared.

Edge cases are handled in the biometric flow and navigation layer: cancellation/errors do not unlock the session; a “prompt in flight” flag prevents duplicate prompts; and an idempotent “route to Home if already unlocked” guard in `onStart()` covers lifecycle transitions and app restarts.

## Updated screen flow (what happens after auth events)

The flow is driven by `MainViewModel.authState` (`StateFlow<AuthState>`) and a small lifecycle policy in `MainActivity`.

On app start, `MainViewModel` initializes from secure storage by calling `AuthManager.initializeFromStorage()`. If encrypted tokens exist, the session enters `AuthState.Locked` with `LockReason.BiometricRequired`; otherwise it remains `AuthState.LoggedOut`.

On login (username/password, mock backend), `MainViewModel.login()` calls `AuthManager.login(...)`. The manager persists tokens using `EncryptedPrefsTokenStore` and sets `AuthState.Unlocked`. In this demo, login routes directly to Home; biometric gating is not forced immediately after login.

Unlocked routes to Home in two places, for resilience. First, `MainActivity.observeAuthState()` navigates when the state becomes `Unlocked`. Second, `MainActivity.onStart()` calls `routeToHomeIfUnlocked()` to handle cases where the state is already unlocked when the Activity starts (for example, lifecycle edge cases during biometric transitions or app task recreation).

Biometric gating is enforced only when returning from background, not on every resume. `MainActivity` sets `wasBackgrounded = true` in `onStop()`. On return (in `onStart()`), if `wasBackgrounded` was true and a session exists, the app locks the session with `LockReason.AppBackgrounded` and prompts for biometric/device credentials. This prevents repeated prompts while the app stays in the foreground and avoids re-prompting during `BiometricPrompt`-induced resume transitions.

Logout clears tokens and returns to `MainActivity`. Logout is available from `HomeActivity` (app bar menu) and from `MainActivity` (button). In either case, `AuthManager.logout()` clears encrypted storage and sets `AuthState.LoggedOut`. `HomeActivity` observes auth state and clears the back stack when returning to `MainActivity`.

## UI structure (XML and menu wiring)

### App theme and Material 3 wiring

The app theme is defined in `res/values/themes.xml` as `Theme.SecureBiometricDemo`, which inherits from `Theme.Material3.DayNight.NoActionBar`. This enables Material 3 styling for the classic Views-based UI while keeping Activities responsible for their own app bars (Home uses a `MaterialToolbar`).

### MainActivity screen (`res/layout/activity_main.xml`)

The `MainActivity` UI is a `NestedScrollView` containing a vertical `LinearLayout` with Material cards for state, login, session actions, and a status log. Inputs use `TextInputLayout` + `TextInputEditText`, and actions use `MaterialButton`.

This screen exists primarily to demonstrate session state transitions, manual lock/unlock, and the mock-protected networking call, while the Home screen is the post-auth destination.

### Home screen (`res/layout/activity_home.xml`)

The Home screen is a `NestedScrollView` with a vertical `LinearLayout` containing:

A top app bar implemented as `com.google.android.material.appbar.MaterialToolbar` with ID `@+id/toolbar`. The title is provided via `app:title="@string/title_home"`.

A “signed in” card with body text `@+id/textHomeBody` and a short hint (`@+id/textHomeHint`) describing the “relock on background” policy.

A calculator card containing:

- Two numeric inputs implemented using `TextInputLayout` + `TextInputEditText` (`@+id/editNumberA` and `@+id/editNumberB`).
- Operation buttons implemented using `MaterialButton` (`btnAdd`, `btnSubtract`, `btnMultiply`, `btnDivide`) plus a Clear button (`btnClearCalc`).
- A result `TextView` (`@+id/textCalcResult`) that displays either the “Result:” label or a computed value.

### Logout menu (`res/menu/home_menu.xml`)

Home provides Logout via an app bar menu item:

- ID: `@id/action_logout`
- Icon: `@android:drawable/ic_lock_power_off`
- Display: `app:showAsAction="always"`

`HomeActivity` inflates this menu and calls `viewModel.logout()` when it is selected.

## Key Kotlin snippets

### 1) Unlocked -> Home navigation (reliable routing)

The primary navigation happens in `MainActivity.observeAuthState()` when the state becomes `AuthState.Unlocked`:

```kotlin
is AuthState.Unlocked -> {
    val intent = android.content.Intent(this@MainActivity, HomeActivity::class.java).apply {
        addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
    }
    startActivity(intent)
    finish()
}
```

To handle lifecycle edge cases (for example, the Activity being recreated or started while already unlocked), `MainActivity.onStart()` calls an idempotent helper:

```kotlin
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
```

### 2) Biometric gating and edge cases (failure, cancellation, restart)

The biometric prompt is shown from `MainActivity.maybePromptUnlock()`. It is guarded by two booleans:

`biometricPromptInFlight` prevents launching multiple prompts (including across rotation, since it is saved/restored via `onSaveInstanceState`).

`wasBackgrounded` enforces the policy “only prompt on return from background”.

The authenticate call does not unlock on failure/cancel; it unlocks only on success:

```kotlin
biometricAuthenticator.authenticate(
    title = "Unlock session",
    subtitle = "Verify it’s you to continue (biometric or device credential).",
    onSuccess = {
        biometricPromptInFlight = false
        viewModel.unlock()
    },
    onFailure = {
        // Non-fatal; user can try again inside prompt.
    },
    onError = { _, message ->
        biometricPromptInFlight = false
        @Suppress("UNUSED_VARIABLE")
        val unused = message
        if (force) {
            // No-op: user initiated unlock; remaining locked is expected on cancel.
        }
    },
)
```

The wrapper (`BiometricAuthenticator`) uses:

- `BIOMETRIC_STRONG` for biometrics
- `DEVICE_CREDENTIAL` for PIN/pattern/password fallback

This satisfies the requirement for biometric with device-credential fallback.

### 3) Home app bar + Logout (menu wiring)

Home wires the `MaterialToolbar` as the Activity’s app bar, inflates the logout menu, and triggers `logout()`:

```kotlin
val toolbar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar)
setSupportActionBar(toolbar)

override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
    menuInflater.inflate(R.menu.home_menu, menu)
    return true
}

override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
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

The calculator is implemented in `HomeActivity`. Inputs are parsed as `Double`, invalid inputs show a friendly error string, division by zero is blocked, and results are rendered “pretty” (integers without trailing `.0`):

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

private fun renderResult(label: String, value: Double?) {
    textResult.text = if (value == null) {
        label
    } else {
        val pretty = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        "$label $pretty"
    }
}
```

### 5) Logout/session clearing (secure storage + state + routing)

Token clearing and state transition are implemented in `AuthManager.logout()`:

```kotlin
suspend fun logout(): AuthOpResult = withContext(ioDispatcher) {
    tokenStore.clear()
    sessionRepo.setLoggedOut()
    AuthOpResult.Success
}
```

`MainViewModel.logout()` is the UI-facing call:

```kotlin
fun logout() {
    viewModelScope.launch {
        appendLog("Logout requested…")
        when (val res = authManager.logout()) {
            AuthOpResult.Success -> appendLog("Logged out; tokens cleared.")
            is AuthOpResult.Failure -> appendLog("Logout failed: ${res.error.message}")
        }
    }
}
```

When `HomeActivity` sees `AuthState.LoggedOut`, it routes back to `MainActivity` and clears the task/back stack:

```kotlin
val intent = Intent(this@HomeActivity, MainActivity::class.java).apply {
    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}
startActivity(intent)
finish()
```

## Build verification output

Verification builds were reported green for:

- `./gradlew build`
- `./gradlew assembleDebug`

A debug APK was produced at:

- `android_app_frontend/app/build/outputs/apk/debug/app-debug.apk`
