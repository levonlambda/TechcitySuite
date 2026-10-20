package com.techcity.techcitysuite

import android.app.Activity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Settings-password gate reusable outside the Main Menu.
 *
 * Shows dialog_password.xml with the same messages and behaviour as
 * MenuActivity.showPasswordDialog (which keeps its own copy).
 * onSuccess runs after the dialog is dismissed with a correct password.
 * onDismiss runs only when the dialog closes without a correct password.
 */
object PasswordDialogHelper {

    fun show(
        activity: Activity,
        scope: CoroutineScope,
        onSuccess: () -> Unit,
        onDismiss: (() -> Unit)? = null
    ) {
        var accepted = false

        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_password, null)

        val passwordInput = dialogView.findViewById<TextInputEditText>(R.id.passwordInput)
        val errorMessage = dialogView.findViewById<TextView>(R.id.errorMessage)
        val submitButton = dialogView.findViewById<Button>(R.id.submitButton)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancelButton)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar)
        val buttonsLayout = dialogView.findViewById<LinearLayout>(R.id.buttonsLayout)

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.setOnDismissListener {
            if (!accepted) onDismiss?.invoke()
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        submitButton.setOnClickListener {
            val password = passwordInput.text.toString()

            if (password.isEmpty()) {
                errorMessage.text = "Please enter a password"
                errorMessage.visibility = View.VISIBLE
                return@setOnClickListener
            }

            errorMessage.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            buttonsLayout.visibility = View.GONE
            passwordInput.isEnabled = false

            scope.launch {
                try {
                    val isValid = AppSettingsManager.verifyPassword(password)

                    if (isValid) {
                        accepted = true
                        dialog.dismiss()
                        onSuccess()
                    } else {
                        progressBar.visibility = View.GONE
                        buttonsLayout.visibility = View.VISIBLE
                        passwordInput.isEnabled = true
                        errorMessage.text = "Incorrect password"
                        errorMessage.visibility = View.VISIBLE
                        passwordInput.setText("")
                        passwordInput.requestFocus()
                    }
                } catch (e: Exception) {
                    progressBar.visibility = View.GONE
                    buttonsLayout.visibility = View.VISIBLE
                    passwordInput.isEnabled = true
                    errorMessage.text = "Error verifying password"
                    errorMessage.visibility = View.VISIBLE
                    Toast.makeText(activity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitButton.performClick()
                true
            } else {
                false
            }
        }

        dialog.show()
    }
}
