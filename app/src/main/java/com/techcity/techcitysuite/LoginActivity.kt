package com.techcity.techcitysuite

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.techcity.techcitysuite.databinding.ActivityLoginBinding
import kotlinx.coroutines.*

/**
 * Login screen with two states:
 *  - Sign In: email + password against Firebase Authentication.
 *  - Verification Required: shown when the device has been offline for more than the
 *    grace period; keeps the session and only needs a successful online re-check.
 */
class LoginActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityLoginBinding
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var mode: String = AuthManager.MODE_SIGN_IN

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================


    // ============================================================================
    // START OF PART 2: LIFECYCLE METHODS
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize View Binding
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        applyIntent(intent)

        // Back exits the app; there is no screen behind Login
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishAffinity()
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ============================================================================
    // END OF PART 2: LIFECYCLE METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 3: MODE HANDLING
    // ============================================================================

    private fun applyIntent(intent: Intent?) {
        mode = intent?.getStringExtra(AuthManager.EXTRA_MODE) ?: AuthManager.MODE_SIGN_IN
        val notice = intent?.getStringExtra(AuthManager.EXTRA_NOTICE)

        if (mode == AuthManager.MODE_VERIFICATION_REQUIRED && AuthManager.isSignedIn()) {
            showVerificationRequiredMode()
        } else {
            mode = AuthManager.MODE_SIGN_IN
            showSignInMode(notice)
        }
    }

    private fun showSignInMode(notice: String?) {
        binding.emailInputLayout.isEnabled = true
        binding.emailInput.isEnabled = true
        binding.emailInput.setText("")
        binding.passwordInputLayout.visibility = View.VISIBLE
        binding.passwordInput.setText("")
        binding.verificationMessage.visibility = View.GONE
        binding.signInButton.visibility = View.VISIBLE
        binding.retryButton.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        setFormEnabled(true)

        if (notice.isNullOrEmpty()) {
            binding.errorMessage.visibility = View.GONE
        } else {
            showError(notice)
        }
    }

    private fun showVerificationRequiredMode() {
        binding.emailInput.setText(AuthManager.currentEmail())
        binding.emailInputLayout.isEnabled = false
        binding.emailInput.isEnabled = false
        binding.passwordInputLayout.visibility = View.GONE
        binding.verificationMessage.text = AuthManager.VERIFICATION_REQUIRED_MESSAGE
        binding.verificationMessage.visibility = View.VISIBLE
        binding.errorMessage.visibility = View.GONE
        binding.signInButton.visibility = View.GONE
        binding.retryButton.visibility = View.VISIBLE
        binding.retryButton.isEnabled = true
        binding.progressBar.visibility = View.GONE
    }

    // ============================================================================
    // END OF PART 3: MODE HANDLING
    // ============================================================================


    // ============================================================================
    // START OF PART 4: ACTIONS
    // ============================================================================

    private fun setupButtons() {
        binding.signInButton.setOnClickListener { attemptSignIn() }
        binding.retryButton.setOnClickListener { attemptVerification() }

        binding.passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptSignIn()
                true
            } else {
                false
            }
        }
    }

    private fun attemptSignIn() {
        val email = binding.emailInput.text.toString().trim()
        val password = binding.passwordInput.text.toString()

        if (email.isEmpty() || password.isEmpty()) {
            showError(AuthManager.MESSAGE_EMPTY_FIELDS)
            return
        }

        binding.errorMessage.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        setFormEnabled(false)

        scope.launch {
            when (val result = AuthManager.signIn(this@LoginActivity, email, password)) {
                is AuthManager.SignInResult.Success -> openMainMenu()
                is AuthManager.SignInResult.Failure -> {
                    binding.progressBar.visibility = View.GONE
                    setFormEnabled(true)
                    showError(result.message)
                    binding.passwordInput.setText("")
                    binding.passwordInput.requestFocus()
                }
            }
        }
    }

    private fun attemptVerification() {
        binding.errorMessage.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        binding.retryButton.isEnabled = false

        scope.launch {
            when (AuthManager.verifyAccess(this@LoginActivity)) {
                AuthManager.VerifyResult.VALID -> openMainMenu()
                AuthManager.VerifyResult.REVOKED -> {
                    AuthManager.signOut(this@LoginActivity)
                    mode = AuthManager.MODE_SIGN_IN
                    showSignInMode(AuthManager.NOTICE_REVOKED)
                }
                AuthManager.VerifyResult.OFFLINE -> {
                    binding.progressBar.visibility = View.GONE
                    binding.retryButton.isEnabled = true
                    showError(AuthManager.MESSAGE_STILL_OFFLINE)
                }
            }
        }
    }

    private fun openMainMenu() {
        val intent = Intent(this, MenuActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun setFormEnabled(enabled: Boolean) {
        if (mode == AuthManager.MODE_SIGN_IN) {
            binding.emailInput.isEnabled = enabled
        }
        binding.passwordInput.isEnabled = enabled
        binding.signInButton.isEnabled = enabled
    }

    private fun showError(message: String) {
        binding.errorMessage.text = message
        binding.errorMessage.visibility = View.VISIBLE
    }

    // ============================================================================
    // END OF PART 4: ACTIONS
    // ============================================================================
}
