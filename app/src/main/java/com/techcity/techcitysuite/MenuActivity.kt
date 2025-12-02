package com.techcity.techcitysuite

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.techcity.techcitysuite.databinding.ActivityMenuBinding
import kotlinx.coroutines.*

class MenuActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityMenuBinding
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================


    // ============================================================================
    // START OF PART 2: LIFECYCLE METHODS
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize View Binding
        binding = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Clear all ledgers on app start (for testing purposes)
        LedgerManager.clearAll()

        // Load app settings from Firebase
        loadAppSettings()

        // Set up click listeners for menu options
        setupMenuButtons()

        // Set up kebab menu
        setupKebabMenu()
    }

    override fun onResume() {
        super.onResume()
        // Re-check feature settings when returning from settings
        updateFeatureVisibility()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun updateFeatureVisibility() {
        val prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE)
        val accountReceivableEnabled = prefs.getBoolean(AppConstants.KEY_ACCOUNT_RECEIVABLE_ENABLED, true)

        if (accountReceivableEnabled) {
            binding.accountReceivableCard.visibility = View.VISIBLE
        } else {
            binding.accountReceivableCard.visibility = View.GONE
        }
    }

    // ============================================================================
    // END OF PART 2: LIFECYCLE METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 3: SETTINGS LOADING
    // ============================================================================

    private fun loadAppSettings() {
        scope.launch {
            try {
                AppSettingsManager.loadSettings(this@MenuActivity)
            } catch (e: Exception) {
                // Settings will use defaults if loading fails
            }
        }
    }

    // ============================================================================
    // END OF PART 3: SETTINGS LOADING
    // ============================================================================


    // ============================================================================
    // START OF PART 4: MENU BUTTONS SETUP
    // ============================================================================

    private fun setupMenuButtons() {
        // Phone Inventory Checker button - DISABLED
        binding.inventoryButton.isEnabled = false
        binding.inventoryButton.alpha = 0.5f
        binding.inventoryButton.setOnClickListener {
            Toast.makeText(this, "Phone Inventory is currently disabled", Toast.LENGTH_SHORT).show()
        }

        // Device Transactions button - NOW OPENS THE LIST
        binding.devicesTransactionsButton.setOnClickListener {
            val intent = Intent(this, DeviceTransactionListActivity::class.java)
            startActivity(intent)
        }

        // Service Transactions button (Cash In/Out, etc.)
        binding.transactionsButton.setOnClickListener {
            val intent = Intent(this, ServiceTransactionListActivity::class.java)
            startActivity(intent)
        }

        // Ledger button
        binding.ledgerButton.setOnClickListener {
            val intent = Intent(this, LedgerViewActivity::class.java)
            startActivity(intent)
        }

        // Accessories Transactions button - NOW OPENS LIST ACTIVITY
        binding.accessoriesTransactionsButton.setOnClickListener {
            val intent = Intent(this, AccessoryTransactionListActivity::class.java)
            startActivity(intent)
        }

        // Account Receivable button - NOW ENABLED
        binding.accountReceivableButton.setOnClickListener {
            val intent = Intent(this, AccountReceivableActivity::class.java)
            startActivity(intent)
        }
    }

    // ============================================================================
    // END OF PART 4: MENU BUTTONS SETUP
    // ============================================================================


    // ============================================================================
    // START OF PART 5: KEBAB MENU AND PASSWORD DIALOG
    // ============================================================================

    private fun setupKebabMenu() {
        binding.kebabMenuButton.setOnClickListener {
            // Show password dialog before opening settings
            showPasswordDialog()
        }
    }

    private fun showPasswordDialog() {
        // Inflate the dialog layout
        val dialogView = layoutInflater.inflate(R.layout.dialog_password, null)

        // Get references to dialog views
        val passwordInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.passwordInput)
        val errorMessage = dialogView.findViewById<android.widget.TextView>(R.id.errorMessage)
        val submitButton = dialogView.findViewById<android.widget.Button>(R.id.submitButton)
        val cancelButton = dialogView.findViewById<android.widget.Button>(R.id.cancelButton)
        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.progressBar)
        val buttonsLayout = dialogView.findViewById<android.widget.LinearLayout>(R.id.buttonsLayout)

        // Create the dialog
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // Cancel button
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        // Submit button
        submitButton.setOnClickListener {
            val password = passwordInput.text.toString()

            // Validate password is not empty
            if (password.isEmpty()) {
                errorMessage.text = "Please enter a password"
                errorMessage.visibility = View.VISIBLE
                return@setOnClickListener
            }

            // Hide error and show progress
            errorMessage.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            buttonsLayout.visibility = View.GONE
            passwordInput.isEnabled = false

            // Verify password
            scope.launch {
                try {
                    val isValid = AppSettingsManager.verifyPassword(password)

                    if (isValid) {
                        dialog.dismiss()
                        // Open settings activity
                        val intent = Intent(this@MenuActivity, ProgramSettingsActivity::class.java)
                        startActivity(intent)
                    } else {
                        // Show error
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
                    Toast.makeText(this@MenuActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Handle Enter key on password input
        passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                submitButton.performClick()
                true
            } else {
                false
            }
        }

        // Show the dialog
        dialog.show()
    }

    // ============================================================================
    // END OF PART 5: KEBAB MENU AND PASSWORD DIALOG
    // ============================================================================
}