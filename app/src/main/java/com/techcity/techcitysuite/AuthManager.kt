package com.techcity.techcitysuite

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.tasks.await

/**
 * Singleton manager for Firebase Authentication.
 *
 * Owns sign-in, online re-verification (hourly throttle + 24-hour offline grace period),
 * sign-out with in-memory state clearing, permission-denied detection, and routing back to
 * the Login screen. Activities call into this object and keep no auth logic of their own.
 */
object AuthManager {

    // ============================================================================
    // START OF PART 1: CONSTANTS AND RESULT TYPES
    // ============================================================================

    /** Re-verify online at most once per hour of active use. */
    private const val VERIFY_INTERVAL_MS = 60L * 60L * 1000L

    /** Offline grace period measured from the last successful sign-in or verification. */
    private const val GRACE_PERIOD_MS = 24L * 60L * 60L * 1000L

    // Login screen intent extras
    const val EXTRA_MODE = "auth_mode"
    const val MODE_SIGN_IN = "sign_in"
    const val MODE_VERIFICATION_REQUIRED = "verification_required"
    const val EXTRA_NOTICE = "auth_notice"

    // User-facing messages
    const val NOTICE_REVOKED = "Your access has been revoked. Please contact the administrator."
    const val VERIFICATION_REQUIRED_MESSAGE =
        "This device has been offline for more than 24 hours. Connect to the internet to continue."
    const val MESSAGE_EMPTY_FIELDS = "Please enter your email and password"
    const val MESSAGE_STILL_OFFLINE = "Still offline. Please check your connection and try again."
    private const val MESSAGE_INVALID_EMAIL = "Please enter a valid email address."
    private const val MESSAGE_INCORRECT = "Incorrect email or password."
    private const val MESSAGE_DISABLED = "This account has been disabled. Please contact the administrator."
    private const val MESSAGE_TOO_MANY = "Too many attempts. Please try again later."
    private const val MESSAGE_NO_NETWORK = "No internet connection. Please check your connection and try again."
    private const val MESSAGE_GENERIC = "Sign in failed. Please try again."

    // Firebase Auth error codes
    private const val CODE_INVALID_EMAIL = "ERROR_INVALID_EMAIL"
    private const val CODE_USER_DISABLED = "ERROR_USER_DISABLED"

    sealed class SignInResult {
        object Success : SignInResult()
        data class Failure(val message: String) : SignInResult()
    }

    enum class VerifyResult { VALID, REVOKED, OFFLINE }

    // ============================================================================
    // END OF PART 1: CONSTANTS AND RESULT TYPES
    // ============================================================================


    // ============================================================================
    // START OF PART 2: SESSION QUERIES
    // ============================================================================

    fun currentUser(): FirebaseUser? = FirebaseAuth.getInstance().currentUser

    fun isSignedIn(): Boolean = currentUser() != null

    fun currentEmail(): String = currentUser()?.email ?: ""

    // ============================================================================
    // END OF PART 2: SESSION QUERIES
    // ============================================================================


    // ============================================================================
    // START OF PART 3: SIGN IN
    // ============================================================================

    /**
     * Sign in with Firebase Email/Password and map failures to user-facing messages.
     * Unknown email and wrong password deliberately produce the same message.
     */
    suspend fun signIn(context: Context, email: String, password: String): SignInResult {
        return try {
            FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password).await()
            recordVerified(context)
            SignInResult.Success
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            if (e.errorCode == CODE_INVALID_EMAIL) {
                SignInResult.Failure(MESSAGE_INVALID_EMAIL)
            } else {
                SignInResult.Failure(MESSAGE_INCORRECT)
            }
        } catch (e: FirebaseAuthInvalidUserException) {
            if (e.errorCode == CODE_USER_DISABLED) {
                SignInResult.Failure(MESSAGE_DISABLED)
            } else {
                SignInResult.Failure(MESSAGE_INCORRECT)
            }
        } catch (e: FirebaseTooManyRequestsException) {
            SignInResult.Failure(MESSAGE_TOO_MANY)
        } catch (e: FirebaseNetworkException) {
            SignInResult.Failure(MESSAGE_NO_NETWORK)
        } catch (e: Exception) {
            e.printStackTrace()
            SignInResult.Failure(MESSAGE_GENERIC)
        }
    }

    // ============================================================================
    // END OF PART 3: SIGN IN
    // ============================================================================


    // ============================================================================
    // START OF PART 4: ONLINE RE-VERIFICATION
    // ============================================================================

    /**
     * Reload the signed-in user and force a token refresh.
     * A deleted, disabled, or password-changed account is reported as REVOKED;
     * a network failure as OFFLINE. Success records the last verified time.
     */
    suspend fun verifyAccess(context: Context): VerifyResult {
        val user = currentUser() ?: return VerifyResult.REVOKED

        return try {
            user.reload().await()
            user.getIdToken(true).await()
            // The SDK can sign the user out on its own when the account is gone
            if (currentUser() == null) {
                VerifyResult.REVOKED
            } else {
                recordVerified(context)
                VerifyResult.VALID
            }
        } catch (e: FirebaseAuthInvalidUserException) {
            VerifyResult.REVOKED
        } catch (e: FirebaseNetworkException) {
            VerifyResult.OFFLINE
        } catch (e: Exception) {
            e.printStackTrace()
            if (currentUser() == null) VerifyResult.REVOKED else VerifyResult.OFFLINE
        }
    }

    /** True when there is no recorded verification or it is an hour or more old. */
    fun needsVerification(context: Context): Boolean {
        val last = getLastVerified(context)
        return last <= 0L || System.currentTimeMillis() - last >= VERIFY_INTERVAL_MS
    }

    /** True when a verification is recorded and it is less than 24 hours old. */
    fun isWithinGracePeriod(context: Context): Boolean {
        val last = getLastVerified(context)
        return last > 0L && System.currentTimeMillis() - last < GRACE_PERIOD_MS
    }

    private fun getLastVerified(context: Context): Long {
        val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(AppConstants.KEY_LAST_VERIFIED_TIME, 0L)
    }

    private fun recordVerified(context: Context) {
        val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(AppConstants.KEY_LAST_VERIFIED_TIME, System.currentTimeMillis()).apply()
    }

    private fun clearVerified(context: Context) {
        val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(AppConstants.KEY_LAST_VERIFIED_TIME).apply()
    }

    // ============================================================================
    // END OF PART 4: ONLINE RE-VERIFICATION
    // ============================================================================


    // ============================================================================
    // START OF PART 5: SIGN OUT AND REVOCATION
    // ============================================================================

    /**
     * End the device session and clear in-memory global state.
     * Per-device Program Settings are intentionally kept.
     */
    fun signOut(context: Context) {
        FirebaseAuth.getInstance().signOut()
        clearVerified(context)
        LedgerManager.clearAll()
        AppSettingsManager.clearCache()
    }

    /** True when the error (or its cause) is a Firestore PERMISSION_DENIED. */
    fun isPermissionDenied(e: Throwable?): Boolean {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < 5) {
            if (current is FirebaseFirestoreException &&
                current.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
            ) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * Send the user to the Login screen, clearing the back stack, and finish the caller.
     */
    fun goToLogin(activity: Activity, mode: String = MODE_SIGN_IN, notice: String? = null) {
        val intent = Intent(activity, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_MODE, mode)
            if (notice != null) putExtra(EXTRA_NOTICE, notice)
        }
        activity.startActivity(intent)
        activity.finish()
    }

    /** Sign out and return to Login with the revoked-access notice. */
    fun handleRevoked(activity: Activity) {
        signOut(activity)
        goToLogin(activity, MODE_SIGN_IN, NOTICE_REVOKED)
    }

    // ============================================================================
    // END OF PART 5: SIGN OUT AND REVOCATION
    // ============================================================================
}
