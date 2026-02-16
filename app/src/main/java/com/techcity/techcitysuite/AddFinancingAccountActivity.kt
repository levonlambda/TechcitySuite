package com.techcity.techcitysuite

import android.app.DatePickerDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
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

    // Edit mode properties
    private var isEditMode = false
    private var editDocumentId = ""
    private var originalCreatedBy = ""
    private var originalStoreLocation = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddFinancingAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = Firebase.firestore

        setupBackButton()
        setupDatePicker()
        setupFinancingCompanyDropdown()
        setupCurrencyFormatting()
        checkEditMode()
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

            // Style dialog buttons — programmatic override since theme attributes
            // don't reliably apply to platform DatePickerDialog buttons
            val dp8 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
            val dp16 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
            val cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics)

            // Add padding to the button bar container so buttons don't get clipped
            datePickerDialog.getButton(DatePickerDialog.BUTTON_POSITIVE)?.let { button ->
                (button.parent as? LinearLayout)?.setPadding(dp8, dp16, dp8, dp16)
            }

            fun styleButton(button: android.widget.Button) {
                val bg = GradientDrawable().apply {
                    setColor(getColor(R.color.techcity_blue_dark))
                    setCornerRadius(cornerRadius)
                }
                button.background = bg
                button.setTextColor(getColor(R.color.white))
                button.isAllCaps = false
                val params = button.layoutParams as LinearLayout.LayoutParams
                params.setMargins(dp8, 0, dp8, 0)
                params.weight = 1f
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                button.layoutParams = params
                button.setPadding(dp16, dp8, dp16, dp8)
            }

            datePickerDialog.getButton(DatePickerDialog.BUTTON_POSITIVE)?.let { styleButton(it) }
            datePickerDialog.getButton(DatePickerDialog.BUTTON_NEGATIVE)?.let { styleButton(it) }
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
        addCurrencyTextWatcher(binding.financedAmountEditText)
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

    private fun checkEditMode() {
        isEditMode = intent.getBooleanExtra("edit_mode", false)
        if (!isEditMode) return

        editDocumentId = intent.getStringExtra("document_id") ?: ""
        originalCreatedBy = intent.getStringExtra("created_by") ?: ""
        originalStoreLocation = intent.getStringExtra("store_location") ?: ""

        // Update header and button text
        binding.titleText.text = getString(R.string.edit_financing_account)
        binding.saveButton.text = getString(R.string.update_account)

        // Pre-fill financing company dropdown (false prevents filter from activating)
        val financingCompany = intent.getStringExtra("financing_company") ?: ""
        binding.financingCompanyDropdown.setText(financingCompany, false)

        // Pre-fill text fields
        binding.customerNameEditText.setText(intent.getStringExtra("customer_name") ?: "")
        binding.accountNumberEditText.setText(intent.getStringExtra("account_number") ?: "")
        binding.contactNumberEditText.setText(intent.getStringExtra("contact_number") ?: "")
        binding.devicePurchasedEditText.setText(intent.getStringExtra("device_purchased") ?: "")
        binding.termEditText.setText(intent.getStringExtra("term") ?: "")

        // Pre-fill date
        val purchaseDate = intent.getStringExtra("purchase_date") ?: ""
        if (purchaseDate.isNotEmpty()) {
            selectedDateStorage = purchaseDate
            try {
                val date = storageDateFormat.parse(purchaseDate)
                if (date != null) {
                    binding.dateEditText.setText(displayDateFormat.format(date))
                }
            } catch (_: Exception) {}
        }

        // Pre-fill currency fields with formatting
        val currencyFormatter = DecimalFormat("#,##0.00")
        if (intent.getBooleanExtra("has_monthly_payment", false)) {
            val monthlyPayment = intent.getDoubleExtra("monthly_payment", 0.0)
            binding.monthlyPaymentEditText.setText(currencyFormatter.format(monthlyPayment))
        }
        if (intent.getBooleanExtra("has_downpayment", false)) {
            val downpayment = intent.getDoubleExtra("downpayment", 0.0)
            binding.downpaymentEditText.setText(currencyFormatter.format(downpayment))
        }
        if (intent.getBooleanExtra("has_financed_amount", false)) {
            val financedAmount = intent.getDoubleExtra("financed_amount", 0.0)
            binding.financedAmountEditText.setText(currencyFormatter.format(financedAmount))
        }
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
        val financedAmountText = binding.financedAmountEditText.text.toString().trim().replace(",", "")
        val financedAmount = if (financedAmountText.isNotEmpty()) financedAmountText.toDoubleOrNull() else null

        // Get user info from AppSettingsManager
        val settings = AppSettingsManager.getCurrentSettings()
        val createdBy = if (isEditMode) originalCreatedBy else (settings?.user ?: "")
        val storeLocation = if (isEditMode) originalStoreLocation else (settings?.storeLocation ?: "")

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
            "financedAmount" to financedAmount,
            "createdBy" to createdBy,
            "storeLocation" to storeLocation
        )

        if (!isEditMode) {
            data["createdAt"] = FieldValue.serverTimestamp()
        }

        // In edit mode, use FieldValue.delete() for null optional fields
        if (isEditMode) {
            if (devicePurchased == null) data["devicePurchased"] = FieldValue.delete()
            if (monthlyPayment == null) data["monthlyPayment"] = FieldValue.delete()
            if (downpayment == null) data["downpayment"] = FieldValue.delete()
            if (financedAmount == null) data["financedAmount"] = FieldValue.delete()
        }

        // Disable save button to prevent double-tap
        binding.saveButton.isEnabled = false

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (isEditMode) {
                        db.collection(AppConstants.COLLECTION_FINANCING_ACCOUNTS)
                            .document(editDocumentId)
                            .update(data as Map<String, Any>)
                            .await()
                    } else {
                        db.collection(AppConstants.COLLECTION_FINANCING_ACCOUNTS)
                            .add(data)
                            .await()
                    }
                }

                Toast.makeText(
                    this@AddFinancingAccountActivity,
                    getString(if (isEditMode) R.string.account_updated_successfully else R.string.account_saved_successfully),
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
