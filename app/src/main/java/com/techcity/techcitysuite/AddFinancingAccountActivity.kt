package com.techcity.techcitysuite

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Toast
import com.google.android.material.textfield.TextInputEditText
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityAddFinancingAccountBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

class AddFinancingAccountActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityAddFinancingAccountBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val financingCompanies = listOf("Home Credit", "Skyro", "Samsung Finance")

    // Date storage format (yyyy-MM-dd) and display format (MMM dd, yyyy)
    private val storageDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayDateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
    private var selectedDateStorage: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddFinancingAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = Firebase.firestore

        setupBackButton()
        setupDatePicker()
        setupFinancingCompanyDropdown()
        setupCurrencyFormatting()
        setupSaveButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================


    // ============================================================================
    // START OF PART 2: SETUP METHODS
    // ============================================================================

    private fun setupBackButton() {
        binding.backButton.setOnClickListener { finish() }
    }

    private fun setupDatePicker() {
        // Default to current date in GMT+8
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila"))
        updateDateDisplay(calendar)

        binding.dateEditText.setOnClickListener {
            val currentCalendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila"))

            // If a date was previously selected, parse it
            if (selectedDateStorage.isNotEmpty()) {
                try {
                    val date = storageDateFormat.parse(selectedDateStorage)
                    if (date != null) currentCalendar.time = date
                } catch (_: Exception) {}
            }

            val datePickerDialog = DatePickerDialog(
                this,
                R.style.DatePickerSpinnerStyle,
                { _, year, month, dayOfMonth ->
                    val selectedCalendar = Calendar.getInstance()
                    selectedCalendar.set(year, month, dayOfMonth)
                    updateDateDisplay(selectedCalendar)
                },
                currentCalendar.get(Calendar.YEAR),
                currentCalendar.get(Calendar.MONTH),
                currentCalendar.get(Calendar.DAY_OF_MONTH)
            )
            datePickerDialog.show()
        }
    }

    private fun updateDateDisplay(calendar: Calendar) {
        selectedDateStorage = storageDateFormat.format(calendar.time)
        binding.dateEditText.setText(displayDateFormat.format(calendar.time))
    }

    private fun setupFinancingCompanyDropdown() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, financingCompanies)
        binding.financingCompanyDropdown.setAdapter(adapter)
    }

    private fun setupCurrencyFormatting() {
        addCurrencyTextWatcher(binding.monthlyPaymentEditText)
        addCurrencyTextWatcher(binding.downpaymentEditText)
    }

    private fun addCurrencyTextWatcher(editText: TextInputEditText) {
        val formatter = DecimalFormat("#,##0.00")
        var isFormatting = false

        editText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                // Format on focus loss
                val raw = editText.text.toString().replace(",", "")
                val value = raw.toDoubleOrNull()
                if (value != null) {
                    isFormatting = true
                    editText.setText(formatter.format(value))
                    isFormatting = false
                }
            }
        }

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isFormatting) return
                // Remove non-numeric chars except decimal point while typing
                val text = s.toString()
                val dotCount = text.count { it == '.' }
                if (dotCount > 1) {
                    isFormatting = true
                    val lastDot = text.lastIndexOf('.')
                    editText.setText(text.removeRange(lastDot, lastDot + 1))
                    editText.setSelection(editText.text?.length ?: 0)
                    isFormatting = false
                }
            }
        })
    }

    private fun setupSaveButton() {
        binding.saveButton.setOnClickListener { saveAccount() }
    }

    // ============================================================================
    // END OF PART 2: SETUP METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 3: VALIDATION AND SAVE
    // ============================================================================

    private fun saveAccount() {
        // Clear previous errors
        binding.dateInputLayout.error = null
        binding.financingCompanyInputLayout.error = null
        binding.customerNameInputLayout.error = null
        binding.accountNumberInputLayout.error = null
        binding.contactNumberInputLayout.error = null
        binding.termInputLayout.error = null

        // Validate required fields
        var hasErrors = false

        val financingCompany = binding.financingCompanyDropdown.text.toString().trim()
        val customerName = binding.customerNameEditText.text.toString().trim()
        val accountNumber = binding.accountNumberEditText.text.toString().trim()
        val contactNumber = binding.contactNumberEditText.text.toString().trim()
        val term = binding.termEditText.text.toString().trim()

        if (selectedDateStorage.isEmpty()) {
            binding.dateInputLayout.error = getString(R.string.field_required)
            hasErrors = true
        }

        if (financingCompany.isEmpty()) {
            binding.financingCompanyInputLayout.error = getString(R.string.field_required)
            hasErrors = true
        }

        if (customerName.isEmpty()) {
            binding.customerNameInputLayout.error = getString(R.string.field_required)
            hasErrors = true
        }

        if (accountNumber.isEmpty()) {
            binding.accountNumberInputLayout.error = getString(R.string.field_required)
            hasErrors = true
        }

        if (contactNumber.isEmpty()) {
            binding.contactNumberInputLayout.error = getString(R.string.field_required)
            hasErrors = true
        }

        if (term.isEmpty()) {
            binding.termInputLayout.error = getString(R.string.field_required)
            hasErrors = true
        }

        if (hasErrors) return

        // Get optional fields (strip commas from currency fields before parsing)
        val devicePurchased = binding.devicePurchasedEditText.text.toString().trim().ifEmpty { null }
        val monthlyPaymentText = binding.monthlyPaymentEditText.text.toString().trim().replace(",", "")
        val monthlyPayment = if (monthlyPaymentText.isNotEmpty()) monthlyPaymentText.toDoubleOrNull() else null
        val downpaymentText = binding.downpaymentEditText.text.toString().trim().replace(",", "")
        val downpayment = if (downpaymentText.isNotEmpty()) downpaymentText.toDoubleOrNull() else null

        // Get user info from AppSettingsManager
        val settings = AppSettingsManager.getCurrentSettings()
        val createdBy = settings?.user ?: ""
        val storeLocation = settings?.storeLocation ?: ""

        // Build data map
        val data = hashMapOf<String, Any?>(
            "financingCompany" to financingCompany,
            "customerName" to customerName,
            "accountNumber" to accountNumber,
            "purchaseDate" to selectedDateStorage,
            "contactNumber" to contactNumber,
            "devicePurchased" to devicePurchased,
            "monthlyPayment" to monthlyPayment,
            "term" to term,
            "downpayment" to downpayment,
            "createdAt" to FieldValue.serverTimestamp(),
            "createdBy" to createdBy,
            "storeLocation" to storeLocation
        )

        // Disable save button to prevent double-tap
        binding.saveButton.isEnabled = false

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.collection(AppConstants.COLLECTION_FINANCING_ACCOUNTS)
                        .add(data)
                        .await()
                }

                Toast.makeText(
                    this@AddFinancingAccountActivity,
                    getString(R.string.account_saved_successfully),
                    Toast.LENGTH_SHORT
                ).show()
                finish()

            } catch (e: Exception) {
                Toast.makeText(
                    this@AddFinancingAccountActivity,
                    "Error: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
                binding.saveButton.isEnabled = true
            }
        }
    }

    // ============================================================================
    // END OF PART 3: VALIDATION AND SAVE
    // ============================================================================
}
