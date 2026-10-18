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

    // True while the branch-swap flow (password dialog or branch picker) is showing
    private var isBranchFlowActive = false

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================


    // ============================================================================
    // START OF PART 2: LIFECYCLE METHODS
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Refuse to show without a signed-in Firebase user
        if (!AuthManager.isSignedIn()) {
            AuthManager.goToLogin(this, AuthManager.MODE_SIGN_IN)
            return
        }

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

        // Set up branch label tap (password-protected branch swap)
        setupBranchLabelClick()

        // Create notification channels
        NotificationHelper.createNotificationChannels(this)

        // Request notification permission (Android 13+)
        requestNotificationPermission()

        // Start listening for device transactions
        setupDeviceTransactionListener()
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        // Re-check feature settings when returning from settings
        updateFeatureVisibility()
        updateBranchLocationLabel()
        checkAccessOnResume()
    }

    /**
     * Throttled online re-verification of the signed-in account (at most once per hour).
     * A revoked account is sent to Login; an offline device past the grace period is
     * sent to the Verification Required state.
     */
    private fun checkAccessOnResume() {
        if (!AuthManager.isSignedIn()) {
            AuthManager.goToLogin(this, AuthManager.MODE_SIGN_IN)
            return
        }
        if (!AuthManager.needsVerification(this)) return

        scope.launch {
            when (AuthManager.verifyAccess(this@MenuActivity)) {
                AuthManager.VerifyResult.VALID -> { /* continue as normal */ }
                AuthManager.VerifyResult.REVOKED -> AuthManager.handleRevoked(this@MenuActivity)
                AuthManager.VerifyResult.OFFLINE -> {
                    if (!AuthManager.isWithinGracePeriod(this@MenuActivity)) {
                        AuthManager.goToLogin(this@MenuActivity, AuthManager.MODE_VERIFICATION_REQUIRED)
                    }
                }
            }
        }
    }

    /**
     * Show the configured branch (store) location under the subtitle.
     * Warning colour when no store location is set.
     */
    private fun updateBranchLocationLabel() {
        binding.branchLocationLabel.text = StoreLocationHelper.getDisplayName(this)
        val color = if (StoreLocationHelper.isConfigured(this)) R.color.gray else R.color.orange
        binding.branchLocationLabel.setTextColor(androidx.core.content.ContextCompat.getColor(this, color))
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

        // Financing Accounts visibility
        val financingAccountsEnabled = prefs.getBoolean(AppConstants.KEY_FINANCING_ACCOUNTS_ENABLED, true)
        if (financingAccountsEnabled) {
            binding.financingAccountsCard.visibility = View.VISIBLE
        } else {
            binding.financingAccountsCard.visibility = View.GONE
        }

        // Expenses visibility
        val expensesEnabled = prefs.getBoolean(AppConstants.KEY_EXPENSES_ENABLED, false)
        if (expensesEnabled) {
            binding.expensesCard.visibility = View.VISIBLE
        } else {
            binding.expensesCard.visibility = View.GONE
        }

        // Stock Transfer visibility
        val stockTransferEnabled = prefs.getBoolean(AppConstants.KEY_STOCK_TRANSFER_ENABLED, false)
        if (stockTransferEnabled) {
            binding.stockTransferCard.visibility = View.VISIBLE
        } else {
            binding.stockTransferCard.visibility = View.GONE
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
                if (AuthManager.isPermissionDenied(e)) {
                    AuthManager.handleRevoked(this@MenuActivity)
                }
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
        // Phone Inventory button - NOW OPENS LIST ACTIVITY
        binding.inventoryButton.setOnClickListener {
            val intent = Intent(this, PhoneInventoryListActivity::class.java)
            startActivity(intent)
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

        // End of Day button
        binding.endOfDayButton.setOnClickListener {
            val intent = Intent(this, EndOfDayListActivity::class.java)
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

        // Financing Accounts button
        binding.financingAccountsButton.setOnClickListener {
            val intent = Intent(this, FinancingAccountListActivity::class.java)
            startActivity(intent)
        }

        // Expenses button
        binding.expensesButton.setOnClickListener {
            val intent = Intent(this, ExpenseListActivity::class.java)
            startActivity(intent)
        }

        // Stock Transfer button
        binding.stockTransferButton.setOnClickListener {
            val intent = Intent(this, StockTransferListActivity::class.java)
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
            showPasswordDialog(onSuccess = {
                val intent = Intent(this@MenuActivity, ProgramSettingsActivity::class.java)
                startActivity(intent)
            })
        }
    }

    /**
     * Branch label tap: ask for the settings password, then open the branch picker.
     * Extra taps are ignored while the flow is already showing.
     */
    private fun setupBranchLabelClick() {
        binding.branchLocationLabel.setOnClickListener {
            if (isBranchFlowActive) return@setOnClickListener
            isBranchFlowActive = true
            showPasswordDialog(
                onSuccess = { showBranchPickerDialog() },
                onDismiss = { isBranchFlowActive = false }
            )
        }
    }

    /**
     * Password gate shared by the kebab menu and the branch label.
     * onSuccess runs after the dialog is dismissed with a correct password.
     * onDismiss runs only when the dialog closes without a correct password.
     */
    private fun showPasswordDialog(onSuccess: () -> Unit, onDismiss: (() -> Unit)? = null) {
        var accepted = false

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

        // Notify the caller when the dialog closes without a correct password
        dialog.setOnDismissListener {
            if (!accepted) onDismiss?.invoke()
        }

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
                        accepted = true
                        dialog.dismiss()
                        onSuccess()
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
                    // Firestore refused the listener: the account was revoked
                    if (AuthManager.isPermissionDenied(error)) {
                        AuthManager.handleRevoked(this)
                    }
                    return@addSnapshotListener
                }

                snapshots?.documentChanges?.forEach { change ->
                    if (change.type == DocumentChange.Type.ADDED) {
                        val data = change.document.data

                        // Get the document creation time
                        val createdAt = data["createdAt"] as? com.google.firebase.Timestamp
                        val createdTimeMillis = createdAt?.toDate()?.time ?: 0

                        // Only notify for documents created AFTER the listener started,
                        // and only for this device's store location
                        val sameStore = StoreLocationHelper.matches(this, data["userLocation"] as? String)
                        if (createdTimeMillis > listenerStartTime && sameStore) {
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


    // ============================================================================
    // START OF PART 7: BRANCH PICKER
    // ============================================================================

    /**
     * Show the branch picker: loads the active store locations and lets the user
     * swap the device's store location. Back or Cancel closes it with no change.
     */
    private fun showBranchPickerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_branch_picker, null)

        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.progressBar)
        val statusMessage = dialogView.findViewById<android.widget.TextView>(R.id.statusMessage)
        val radioGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.branchRadioGroup)
        val cancelButton = dialogView.findViewById<android.widget.Button>(R.id.cancelButton)
        val retryButton = dialogView.findViewById<android.widget.Button>(R.id.retryButton)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.setOnDismissListener {
            isBranchFlowActive = false
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        retryButton.setOnClickListener {
            loadBranches(dialog, progressBar, statusMessage, radioGroup, retryButton)
        }

        loadBranches(dialog, progressBar, statusMessage, radioGroup, retryButton)
        dialog.show()
    }

    /**
     * Load the active store locations into the picker, showing loading / empty / error states.
     */
    private fun loadBranches(
        dialog: androidx.appcompat.app.AlertDialog,
        progressBar: android.widget.ProgressBar,
        statusMessage: android.widget.TextView,
        radioGroup: android.widget.RadioGroup,
        retryButton: android.widget.Button
    ) {
        progressBar.visibility = View.VISIBLE
        statusMessage.visibility = View.GONE
        radioGroup.visibility = View.GONE
        retryButton.visibility = View.GONE

        scope.launch {
            try {
                val locations = withContext(Dispatchers.IO) {
                    StoreLocationHelper.loadActiveLocations(Firebase.firestore)
                }
                progressBar.visibility = View.GONE

                if (locations.isEmpty()) {
                    statusMessage.text = "No store locations available"
                    statusMessage.visibility = View.VISIBLE
                    return@launch
                }

                val currentLocation = StoreLocationHelper.getStoreLocation(this@MenuActivity)

                radioGroup.setOnCheckedChangeListener(null)
                radioGroup.removeAllViews()
                for (location in locations) {
                    val radioButton = android.widget.RadioButton(this@MenuActivity).apply {
                        id = View.generateViewId()
                        text = StoreLocationHelper.getDisplayName(location.name)
                        tag = location
                        textSize = 16f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        minHeight = (48 * resources.displayMetrics.density).toInt()
                    }
                    radioGroup.addView(radioButton)
                    if (location.name == currentLocation) {
                        radioGroup.check(radioButton.id)
                    }
                }
                var listener: android.widget.RadioGroup.OnCheckedChangeListener? = null
                listener = android.widget.RadioGroup.OnCheckedChangeListener { group, checkedId ->
                    if (checkedId == View.NO_ID) return@OnCheckedChangeListener
                    val option = group.findViewById<android.widget.RadioButton>(checkedId)?.tag
                        as? StoreLocationHelper.StoreLocationOption ?: return@OnCheckedChangeListener
                    onBranchPicked(dialog, radioGroup, listener, option)
                }
                radioGroup.setOnCheckedChangeListener(listener)
                radioGroup.visibility = View.VISIBLE
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                statusMessage.text = "Could not load store locations. Check your connection and try again."
                statusMessage.visibility = View.VISIBLE
                retryButton.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Handle a branch selection: no-op for the current branch, immediate save on an
     * unconfigured device, otherwise confirm before switching.
     */
    private fun onBranchPicked(
        pickerDialog: androidx.appcompat.app.AlertDialog,
        radioGroup: android.widget.RadioGroup,
        listener: android.widget.RadioGroup.OnCheckedChangeListener?,
        option: StoreLocationHelper.StoreLocationOption
    ) {
        val current = StoreLocationHelper.getStoreLocation(this)

        if (option.name == current) {
            pickerDialog.dismiss()
            return
        }

        if (current.isBlank()) {
            applyBranch(option)
            pickerDialog.dismiss()
            return
        }

        val currentDisplay = StoreLocationHelper.getDisplayName(current)
        val newDisplay = StoreLocationHelper.getDisplayName(option.name)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Change Store Location?")
            .setMessage(
                "This device will switch from \"$currentDisplay\" to \"$newDisplay\" and will " +
                "only show records for the new store location. Existing records are not moved."
            )
            .setPositiveButton("Confirm") { _, _ ->
                applyBranch(option)
                pickerDialog.dismiss()
            }
            .setNegativeButton("Cancel") { _, _ ->
                restoreCurrentBranchSelection(radioGroup, listener, current)
            }
            .setOnCancelListener {
                restoreCurrentBranchSelection(radioGroup, listener, current)
            }
            .show()
    }

    /**
     * Re-check the radio button for the device's current branch (or clear the selection
     * when the current branch is not in the list) after a cancelled confirmation.
     * The change listener is detached while doing so to avoid re-triggering the picker.
     */
    private fun restoreCurrentBranchSelection(
        radioGroup: android.widget.RadioGroup,
        listener: android.widget.RadioGroup.OnCheckedChangeListener?,
        current: String
    ) {
        radioGroup.setOnCheckedChangeListener(null)
        radioGroup.clearCheck()
        for (i in 0 until radioGroup.childCount) {
            val radioButton = radioGroup.getChildAt(i) as? android.widget.RadioButton ?: continue
            val option = radioButton.tag as? StoreLocationHelper.StoreLocationOption ?: continue
            if (option.name == current) {
                radioGroup.check(radioButton.id)
                break
            }
        }
        radioGroup.setOnCheckedChangeListener(listener)
    }

    /**
     * Save the chosen branch, refresh the label and confirm with a toast.
     */
    private fun applyBranch(option: StoreLocationHelper.StoreLocationOption) {
        StoreLocationHelper.saveStoreLocation(this, option)
        updateBranchLocationLabel()
        Toast.makeText(
            this,
            "Switched to ${StoreLocationHelper.getDisplayName(option.name)}",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ============================================================================
    // END OF PART 7: BRANCH PICKER
    // ============================================================================
}