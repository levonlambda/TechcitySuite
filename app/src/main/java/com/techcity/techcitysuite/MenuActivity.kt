package com.techcity.techcitysuite

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityMenuBinding
import kotlinx.coroutines.*

class MenuActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityMenuBinding
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Firestore listener for device transactions
    private var deviceTransactionListener: ListenerRegistration? = null
    private var listenerStartTime: Long = 0

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

        // Create notification channels
        NotificationHelper.createNotificationChannels(this)

        // Request notification permission (Android 13+)
        requestNotificationPermission()

        // Start listening for device transactions
        setupDeviceTransactionListener()
    }

    override fun onResume() {
        super.onResume()
        // Re-check feature settings when returning from settings
        updateFeatureVisibility()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        // Remove the device transaction listener
        deviceTransactionListener?.remove()
    }

    private fun updateFeatureVisibility() {
        val prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE)

        // Account Receivable visibility
        val accountReceivableEnabled = prefs.getBoolean(AppConstants.KEY_ACCOUNT_RECEIVABLE_ENABLED, true)
        if (accountReceivableEnabled) {
            binding.accountReceivableCard.visibility = View.VISIBLE
        } else {
            binding.accountReceivableCard.visibility = View.GONE
        }

        // End of Day visibility
        val endOfDayEnabled = prefs.getBoolean(AppConstants.KEY_END_OF_DAY_ENABLED, true)
        if (endOfDayEnabled) {
            binding.endOfDayCard.visibility = View.VISIBLE
        } else {
            binding.endOfDayCard.visibility = View.GONE
        }

        // Phone Inventory visibility
        val phoneInventoryEnabled = prefs.getBoolean(AppConstants.KEY_PHONE_INVENTORY_ENABLED, false)
        if (phoneInventoryEnabled) {
            binding.inventoryCard.visibility = View.VISIBLE
            binding.inventoryButton.isEnabled = true
            binding.inventoryButton.alpha = 1.0f
        } else {
            binding.inventoryCard.visibility = View.GONE
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

        // ============================================================================
        // CODE TO ADD IN MenuActivity.kt - PART 4: MENU BUTTONS SETUP
        // Add this click listener after the ledgerButton click listener
        // and before the accountReceivableButton click listener
        // ============================================================================

        // End of Day button
        binding.endOfDayButton.setOnClickListener {
            val intent = Intent(this, EndOfDayListActivity::class.java)
            startActivity(intent)
        }

        // ============================================================================
        // END OF EOD BUTTON CODE
        // ============================================================================

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

    // ============================================================================
    // START OF PART 6: NOTIFICATION METHODS
    // ============================================================================

    /**
     * Request notification permission for Android 13+
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationHelper.hasNotificationPermission(this)) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }

    /**
     * Setup Firestore listener for device transactions
     */
    private fun setupDeviceTransactionListener() {
        val db = Firebase.firestore

        // Record when listener starts - only notify for documents created after this
        listenerStartTime = System.currentTimeMillis()

        deviceTransactionListener = db.collection(AppConstants.COLLECTION_DEVICE_TRANSACTIONS)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    return@addSnapshotListener
                }

                snapshots?.documentChanges?.forEach { change ->
                    if (change.type == DocumentChange.Type.ADDED) {
                        val data = change.document.data

                        // Get the document creation time
                        val createdAt = data["createdAt"] as? com.google.firebase.Timestamp
                        val createdTimeMillis = createdAt?.toDate()?.time ?: 0

                        // Only notify for documents created AFTER the listener started
                        if (createdTimeMillis > listenerStartTime) {
                            val model = data["model"] as? String ?: "Unknown"
                            val price = (data["finalPrice"] as? Number)?.toDouble() ?: 0.0
                            val transactionType = data["transactionType"] as? String ?: "Unknown"

                            NotificationHelper.showDeviceTransactionNotification(
                                this,
                                model,
                                price,
                                transactionType
                            )
                        }
                    }
                }
            }
    }

    // ============================================================================
    // END OF PART 6: NOTIFICATION METHODS
    // ============================================================================
}