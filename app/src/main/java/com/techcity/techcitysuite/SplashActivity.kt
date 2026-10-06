package com.techcity.techcitysuite

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.techcity.techcitysuite.databinding.ActivitySplashBinding
import kotlinx.coroutines.*

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val MIN_SPLASH_MS = 2000L       // 2 seconds
        private const val VERIFY_TIMEOUT_MS = 10000L  // 10 seconds
    }

    private lateinit var binding: ActivitySplashBinding
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize View Binding
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Route to Login or Main Menu depending on the Firebase Authentication session
        scope.launch {
            val startTime = System.currentTimeMillis()

            if (!AuthManager.isSignedIn()) {
                waitForMinimumSplash(startTime)
                AuthManager.goToLogin(this@SplashActivity, AuthManager.MODE_SIGN_IN)
                return@launch
            }

            // Online re-verification with a hard cap; timeout is treated as offline
            val result = withTimeoutOrNull(VERIFY_TIMEOUT_MS) {
                AuthManager.verifyAccess(this@SplashActivity)
            }
            waitForMinimumSplash(startTime)

            when (result) {
                AuthManager.VerifyResult.VALID -> openMainMenu()
                AuthManager.VerifyResult.REVOKED -> AuthManager.handleRevoked(this@SplashActivity)
                else -> {
                    // OFFLINE or timeout: honour the 24-hour grace period
                    if (AuthManager.isWithinGracePeriod(this@SplashActivity)) {
                        openMainMenu()
                    } else {
                        AuthManager.goToLogin(this@SplashActivity, AuthManager.MODE_VERIFICATION_REQUIRED)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private suspend fun waitForMinimumSplash(startTime: Long) {
        val remaining = MIN_SPLASH_MS - (System.currentTimeMillis() - startTime)
        if (remaining > 0) delay(remaining)
    }

    private fun openMainMenu() {
        val intent = Intent(this, MenuActivity::class.java)
        startActivity(intent)
        finish()
    }
}