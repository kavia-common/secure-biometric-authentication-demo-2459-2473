package org.example.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
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
 * Displays a toolbar/app bar with a Logout action.
 * Logout clears stored tokens via AuthManager and returns user to the login screen (MainActivity).
 *
 * This screen also contains a basic calculator (add/subtract/multiply/divide).
 *
 * Note: This app uses classic Views/XML (no Compose).
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel

    private var observeJob: Job? = null

    private lateinit var editA: EditText
    private lateinit var editB: EditText
    private lateinit var textResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // Wire the Toolbar as the Activity's app bar so menu actions (Logout) show in the app bar.
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        findViewById<TextView>(R.id.textHomeBody).text = getString(R.string.home_body)

        bindCalculatorViews()
        wireCalculatorActions()

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

    private fun bindCalculatorViews() {
        editA = findViewById(R.id.editNumberA)
        editB = findViewById(R.id.editNumberB)
        textResult = findViewById(R.id.textCalcResult)
    }

    private fun wireCalculatorActions() {
        findViewById<Button>(R.id.btnAdd).setOnClickListener { compute(Operation.Add) }
        findViewById<Button>(R.id.btnSubtract).setOnClickListener { compute(Operation.Subtract) }
        findViewById<Button>(R.id.btnMultiply).setOnClickListener { compute(Operation.Multiply) }
        findViewById<Button>(R.id.btnDivide).setOnClickListener { compute(Operation.Divide) }

        findViewById<Button>(R.id.btnClearCalc).setOnClickListener {
            editA.text?.clear()
            editB.text?.clear()
            renderResult(label = getString(R.string.calculator_result_prefix), value = null)
        }
    }

    private enum class Operation { Add, Subtract, Multiply, Divide }

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
            // Keep output tidy: show integers without trailing .0
            val pretty = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
            "$label $pretty"
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
