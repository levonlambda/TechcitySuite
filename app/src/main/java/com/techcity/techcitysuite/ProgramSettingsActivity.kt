package com.techcity.techcitysuite

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.techcity.techcitysuite.databinding.ActivityProgramSettingsBinding

class ProgramSettingsActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityProgramSettingsBinding

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

        // Load saved settings
        loadSettings()

        // Set up button listeners
        setupButtonListeners()
    }

    // ============================================================================
    // END OF PART 2: LIFECYCLE METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 3: SETTINGS MANAGEMENT METHODS
    // ============================================================================

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
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // Save all field values
        editor.putString(KEY_USER, binding.userInput.text.toString().trim())
        editor.putString(KEY_STORE_LOCATION, binding.storeLocationInput.text.toString().trim())
        editor.putString(KEY_CASH_ACCOUNT, binding.cashAccountInput.text.toString().trim())
        editor.putString(KEY_GCASH_ACCOUNT, binding.gcashAccountInput.text.toString().trim())
        editor.putString(KEY_PAYMAYA_ACCOUNT, binding.paymayaAccountInput.text.toString().trim())
        editor.putString(KEY_QRPH_ACCOUNT, binding.qrphAccountInput.text.toString().trim())
        editor.putString(KEY_CREDIT_CARD_ACCOUNT, binding.creditCardAccountInput.text.toString().trim())
        editor.putString(KEY_OTHER_ACCOUNT, binding.otherAccountInput.text.toString().trim())

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