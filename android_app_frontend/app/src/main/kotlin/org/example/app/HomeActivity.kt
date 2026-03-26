package org.example.app

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.example.app.auth.models.AuthState
import org.example.app.ui.MainViewModel

/**
 * Home screen shown after successful unlock/login.
 *
 * Displays a simple toolbar/app bar with a Logout action.
 * Logout clears stored tokens via AuthManager and returns user to the login screen (MainActivity).
 *
 * Note: This app uses classic Views/XML (no Compose).
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel

    private var observeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // Use the Activity label as the toolbar title (set in manifest).
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        // Optional: show a simple message on the screen.
        findViewById<TextView>(R.id.textHomeBody).text = getString(R.string.home_body)

        observeJob = lifecycleScope.launch {
            observeAuthAndRoute()
        }
    }

    override fun onDestroy() {
        observeJob?.cancel()
        super.onDestroy()
    }

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

    private suspend fun observeAuthAndRoute() {
        viewModel.authState.collectLatest { state ->
            when (state) {
                AuthState.LoggedOut -> {
                    // Ensure we return to login and clear back stack.
                    val intent = Intent(this@HomeActivity, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    startActivity(intent)
                    finish()
                }
                is AuthState.Locked -> {
                    // If session becomes locked again, send user back to MainActivity
                    // which owns the biometric prompt + unlock flow in this demo.
                    val intent = Intent(this@HomeActivity, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(intent)
                    finish()
                }
                is AuthState.Unlocked -> Unit // Stay on Home
            }
        }
    }
}
