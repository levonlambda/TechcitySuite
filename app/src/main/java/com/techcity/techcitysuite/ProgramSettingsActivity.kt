package com.techcity.techcitysuite

import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityProgramSettingsBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class ProgramSettingsActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityProgramSettingsBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Store location name -> accessory_locations document ID
    private val locationNameToId = mutableMapOf<String, String>()

    // Inventory status options
    private val inventoryStatusOptions = arrayOf("On-Display", "In-Stock", "Both")

    // SharedPreferences keys
    companion object {
        private const val PREFS_NAME = "TechCitySettings"
        private const val KEY_USER = "user"
        private const val KEY_STORE_LOCATION = "store_location"
        private const val KEY_CASH_ACCOUNT = "cash_account"
        private const val KEY_GCASH_ACCOUNT = "gcash_account"
        private const val KEY_PAYMAYA_ACCOUNT = "paymaya_account"
        private const val KEY_QRPH_ACCOUNT = "qrph_account"
        private const val KEY_CREDIT_CARD_ACCOUNT = "credit_card_account"
        private const val KEY_OTHER_ACCOUNT = "other_account"
        private const val KEY_ACCOUNT_RECEIVABLE_ENABLED = "account_receivable_enabled"
        private const val KEY_END_OF_DAY_ENABLED = "end_of_day_enabled"
        private const val KEY_PHONE_INVENTORY_ENABLED = "phone_inventory_enabled"
        private const val KEY_FINANCING_ACCOUNTS_ENABLED = "financing_accounts_enabled"
        private const val KEY_DEVICE_TRANSACTION_NOTIFICATIONS_ENABLED = "device_transaction_notifications_enabled"
        private const val KEY_INVENTORY_STATUS_FILTER = "inventory_status_filter"
        private const val KEY_STORE_LOCATION_ID = "store_location_id"
    }

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================


    // ============================================================================
    // START OF PART 2: LIFECYCLE METHODS
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize View Binding
        binding = ActivityProgramSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firestore
        db = Firebase.firestore

        // Setup inventory status dropdown
        setupInventoryStatusDropdown()

        // Load saved settings
        loadSettings()

        // Populate the store location dropdown from accessory_locations
        setupStoreLocationDropdown()

        // Setup toggle listener for Phone Inventory
        setupPhoneInventoryToggleListener()

        // Set up button listeners
        setupButtonListeners()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ============================================================================
    // END OF PART 2: LIFECYCLE METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 3: SETTINGS MANAGEMENT METHODS
    // ============================================================================

    private fun setupInventoryStatusDropdown() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, inventoryStatusOptions)
        binding.inventoryStatusDropdown.setAdapter(adapter)
    }

    /**
     * Load active store locations from accessory_locations into the dropdown,
     * keeping a name -> document ID map so the selected locationId can be saved.
     */
    private fun setupStoreLocationDropdown() {
        scope.launch {
            try {
                val names = withContext(Dispatchers.IO) {
                    val snapshot = db.collection(AppConstants.COLLECTION_ACCESSORY_LOCATIONS)
                        .get()
                        .await()

                    locationNameToId.clear()
                    val list = mutableListOf<String>()
                    for (document in snapshot.documents) {
                        val active = document.getBoolean("active") ?: true
                        if (!active) continue
                        val name = document.getString("name") ?: continue
                        locationNameToId[name] = document.id
                        list.add(name)
                    }
                    list
                }

                val adapter = ArrayAdapter(
                    this@ProgramSettingsActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    names
                )
                binding.storeLocationInput.setAdapter(adapter)

                // Pre-select the saved location if it still exists in the list
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val savedName = prefs.getString(KEY_STORE_LOCATION, "") ?: ""
                if (savedName.isNotEmpty() && locationNameToId.containsKey(savedName)) {
                    binding.storeLocationInput.setText(savedName, false)
                }
            } catch (e: Exception) {
                // Offline / load failure: keep the saved name shown and don't block other settings.
                e.printStackTrace()
            }
        }
    }

    private fun setupPhoneInventoryToggleListener() {
        binding.phoneInventorySwitch.setOnCheckedChangeListener { _, isChecked ->
            // Enable/disable the inventory status dropdown based on Phone Inventory toggle
            binding.inventoryStatusLayout.isEnabled = isChecked
            binding.inventoryStatusDropdown.isEnabled = isChecked

            // Update visual appearance
            binding.inventoryStatusLayout.alpha = if (isChecked) 1.0f else 0.5f
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Load saved values into fields
        binding.userInput.setText(prefs.getString(KEY_USER, ""))
        binding.storeLocationInput.setText(prefs.getString(KEY_STORE_LOCATION, ""))
        binding.cashAccountInput.setText(prefs.getString(KEY_CASH_ACCOUNT, ""))
        binding.gcashAccountInput.setText(prefs.getString(KEY_GCASH_ACCOUNT, ""))
        binding.paymayaAccountInput.setText(prefs.getString(KEY_PAYMAYA_ACCOUNT, ""))
        binding.qrphAccountInput.setText(prefs.getString(KEY_QRPH_ACCOUNT, ""))
        binding.creditCardAccountInput.setText(prefs.getString(KEY_CREDIT_CARD_ACCOUNT, ""))
        binding.otherAccountInput.setText(prefs.getString(KEY_OTHER_ACCOUNT, ""))

        // Load feature settings (default to true/enabled)
        binding.accountReceivableSwitch.isChecked = prefs.getBoolean(KEY_ACCOUNT_RECEIVABLE_ENABLED, true)
        binding.endOfDaySwitch.isChecked = prefs.getBoolean(KEY_END_OF_DAY_ENABLED, true)
        binding.phoneInventorySwitch.isChecked = prefs.getBoolean(KEY_PHONE_INVENTORY_ENABLED, false)
        binding.financingAccountsSwitch.isChecked = prefs.getBoolean(KEY_FINANCING_ACCOUNTS_ENABLED, true)
        binding.deviceTransactionNotificationsSwitch.isChecked = prefs.getBoolean(KEY_DEVICE_TRANSACTION_NOTIFICATIONS_ENABLED, true)

        // Load inventory status filter (default to "On-Display")
        val inventoryStatusFilter = prefs.getString(KEY_INVENTORY_STATUS_FILTER, "On-Display") ?: "On-Display"
        binding.inventoryStatusDropdown.setText(inventoryStatusFilter, false)

        // Set initial state of inventory status dropdown based on Phone Inventory toggle
        val phoneInventoryEnabled = binding.phoneInventorySwitch.isChecked
        binding.inventoryStatusLayout.isEnabled = phoneInventoryEnabled
        binding.inventoryStatusDropdown.isEnabled = phoneInventoryEnabled
        binding.inventoryStatusLayout.alpha = if (phoneInventoryEnabled) 1.0f else 0.5f
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // Save all field values
        editor.putString(KEY_USER, binding.userInput.text.toString().trim())
        val selectedLocation = binding.storeLocationInput.text.toString().trim()
        editor.putString(KEY_STORE_LOCATION, selectedLocation)
        editor.putString(KEY_STORE_LOCATION_ID, locationNameToId[selectedLocation] ?: "")
        editor.putString(KEY_CASH_ACCOUNT, binding.cashAccountInput.text.toString().trim())
        editor.putString(KEY_GCASH_ACCOUNT, binding.gcashAccountInput.text.toString().trim())
        editor.putString(KEY_PAYMAYA_ACCOUNT, binding.paymayaAccountInput.text.toString().trim())
        editor.putString(KEY_QRPH_ACCOUNT, binding.qrphAccountInput.text.toString().trim())
        editor.putString(KEY_CREDIT_CARD_ACCOUNT, binding.creditCardAccountInput.text.toString().trim())
        editor.putString(KEY_OTHER_ACCOUNT, binding.otherAccountInput.text.toString().trim())

        // Save feature settings
        editor.putBoolean(KEY_ACCOUNT_RECEIVABLE_ENABLED, binding.accountReceivableSwitch.isChecked)
        editor.putBoolean(KEY_END_OF_DAY_ENABLED, binding.endOfDaySwitch.isChecked)
        editor.putBoolean(KEY_PHONE_INVENTORY_ENABLED, binding.phoneInventorySwitch.isChecked)
        editor.putBoolean(KEY_FINANCING_ACCOUNTS_ENABLED, binding.financingAccountsSwitch.isChecked)
        editor.putBoolean(KEY_DEVICE_TRANSACTION_NOTIFICATIONS_ENABLED, binding.deviceTransactionNotificationsSwitch.isChecked)

        // Save inventory status filter
        editor.putString(KEY_INVENTORY_STATUS_FILTER, binding.inventoryStatusDropdown.text.toString())

        editor.apply()

        // Show confirmation
        Toast.makeText(this, "Settings saved successfully!", Toast.LENGTH_SHORT).show()
    }

    // ============================================================================
    // END OF PART 3: SETTINGS MANAGEMENT METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 4: BUTTON LISTENERS
    // ============================================================================

    private fun setupButtonListeners() {
        // Save button
        binding.saveButton.setOnClickListener {
            saveSettings()
            finish()
        }

        // Cancel button
        binding.cancelButton.setOnClickListener {
            finish()
        }
    }

    // ============================================================================
    // END OF PART 4: BUTTON LISTENERS
    // ============================================================================
}