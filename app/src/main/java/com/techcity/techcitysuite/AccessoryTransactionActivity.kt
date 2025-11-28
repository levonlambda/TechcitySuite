package com.techcity.techcitysuite

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityAccessoryTransactionBinding
import kotlinx.coroutines.*

class AccessoryTransactionActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityAccessoryTransactionBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Accessory and price information
    private var accessoryName: String = ""
    private var price: Double = 0.0
    private var discount: Double = 0.0

    // Transaction type
    private var transactionType: String = "Cash Transaction"
    private val transactionTypes = arrayOf(
        "Cash Transaction",
        "Home Credit Transaction",
        "Skyro Transaction",
        "In-House Installment"
    )

    // Payment sources
    private val paymentSources = arrayOf(
        "Cash",
        "GCash",
        "PayMaya",
        "Bank Transfer",
        "Credit Card",
        "Others"
    )

    // Down payment sources (same as payment sources)
    private val downPaymentSources = arrayOf(
        "Cash",
        "GCash",
        "PayMaya",
        "Bank Transfer",
        "Credit Card",
        "Others"
    )

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================


    // ============================================================================
    // START OF PART 2: LIFECYCLE METHODS
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize View Binding
        binding = ActivityAccessoryTransactionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firestore
        db = Firebase.firestore

        // Set up UI
        setupDropdowns()
        setupAccessoryNameListener()
        setupPriceListeners()
        setupDownPaymentListeners()
        setupInHouseListeners()
        setupButtonListeners()

        // Initially disable save button until form is valid
        binding.saveButton.isEnabled = false
        binding.saveButton.alpha = 0.5f

        // Show Cash Transaction card by default
        showAppropriateCard()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ============================================================================
    // END OF PART 2: LIFECYCLE METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 3: DROPDOWN SETUP METHODS
    // ============================================================================

    private fun setupDropdowns() {
        // Transaction Type Dropdown
        val transactionAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, transactionTypes)
        binding.purchaseTypeDropdown.setAdapter(transactionAdapter)
        binding.purchaseTypeDropdown.setText(transactionTypes[0], false)
        transactionType = transactionTypes[0]
        updateTransactionTypeColor(transactionTypes[0])

        binding.purchaseTypeDropdown.setOnItemClickListener { _, _, position, _ ->
            transactionType = transactionTypes[position]
            updateTransactionTypeColor(transactionType)
            showAppropriateCard()
            // Validate form when transaction type changes
            validateForm()
        }

        // Payment Source Dropdown (for Cash Transaction)
        val paymentAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, paymentSources)
        binding.paymentSourceDropdown.setAdapter(paymentAdapter)
        binding.paymentSourceDropdown.setText(paymentSources[0], false)
        updatePaymentSourceColor(paymentSources[0])

        binding.paymentSourceDropdown.setOnItemClickListener { _, _, position, _ ->
            updatePaymentSourceColor(paymentSources[position])
        }

        // Home Credit Down Payment Source Dropdown
        val hcDownPaymentAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, downPaymentSources)
        binding.hcDownPaymentSourceDropdown.setAdapter(hcDownPaymentAdapter)
        binding.hcDownPaymentSourceDropdown.setText(downPaymentSources[0], false)
        updateHCDownPaymentSourceColor(downPaymentSources[0])

        binding.hcDownPaymentSourceDropdown.setOnItemClickListener { _, _, position, _ ->
            updateHCDownPaymentSourceColor(downPaymentSources[position])
        }

        // In-House Down Payment Source Dropdown
        val ihDownPaymentAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, downPaymentSources)
        binding.ihDownPaymentSourceDropdown.setAdapter(ihDownPaymentAdapter)
        binding.ihDownPaymentSourceDropdown.setText(downPaymentSources[0], false)
        updateIHDownPaymentSourceColor(downPaymentSources[0])

        binding.ihDownPaymentSourceDropdown.setOnItemClickListener { _, _, position, _ ->
            updateIHDownPaymentSourceColor(downPaymentSources[position])
        }
    }

    private fun updateTransactionTypeColor(type: String) {
        val color = when (type) {
            "Cash Transaction" -> ContextCompat.getColor(this, R.color.cash_dark_green)
            "Home Credit Transaction" -> ContextCompat.getColor(this, android.R.color.holo_red_dark)
            "Skyro Transaction" -> ContextCompat.getColor(this, android.R.color.holo_blue_dark)
            "In-House Installment" -> ContextCompat.getColor(this, R.color.mobile_loading_purple)
            else -> ContextCompat.getColor(this, android.R.color.black)
        }
        binding.purchaseTypeDropdown.setTextColor(color)
    }

    private fun updatePaymentSourceColor(source: String) {
        val color = when (source) {
            "Cash" -> ContextCompat.getColor(this, R.color.cash_dark_green)
            "GCash" -> ContextCompat.getColor(this, android.R.color.holo_blue_dark)
            "PayMaya" -> ContextCompat.getColor(this, android.R.color.holo_green_light)
            "Bank Transfer" -> ContextCompat.getColor(this, R.color.mobile_loading_purple)
            "Credit Card" -> ContextCompat.getColor(this, android.R.color.holo_orange_dark)
            "Others" -> ContextCompat.getColor(this, android.R.color.holo_red_dark)
            else -> ContextCompat.getColor(this, android.R.color.black)
        }
        binding.paymentSourceDropdown.setTextColor(color)
    }

    private fun updateHCDownPaymentSourceColor(source: String) {
        val color = when (source) {
            "Cash" -> ContextCompat.getColor(this, R.color.cash_dark_green)
            "GCash" -> ContextCompat.getColor(this, android.R.color.holo_blue_dark)
            "PayMaya" -> ContextCompat.getColor(this, android.R.color.holo_green_light)
            "Bank Transfer" -> ContextCompat.getColor(this, R.color.mobile_loading_purple)
            "Credit Card" -> ContextCompat.getColor(this, android.R.color.holo_orange_dark)
            "Others" -> ContextCompat.getColor(this, android.R.color.holo_red_dark)
            else -> ContextCompat.getColor(this, android.R.color.black)
        }
        binding.hcDownPaymentSourceDropdown.setTextColor(color)
    }

    private fun updateIHDownPaymentSourceColor(source: String) {
        val color = when (source) {
            "Cash" -> ContextCompat.getColor(this, R.color.cash_dark_green)
            "GCash" -> ContextCompat.getColor(this, android.R.color.holo_blue_dark)
            "PayMaya" -> ContextCompat.getColor(this, android.R.color.holo_green_light)
            "Bank Transfer" -> ContextCompat.getColor(this, R.color.mobile_loading_purple)
            "Credit Card" -> ContextCompat.getColor(this, android.R.color.holo_orange_dark)
            "Others" -> ContextCompat.getColor(this, android.R.color.holo_red_dark)
            else -> ContextCompat.getColor(this, android.R.color.black)
        }
        binding.ihDownPaymentSourceDropdown.setTextColor(color)
    }

    private fun showAppropriateCard() {
        // Hide all cards first
        binding.cashPaymentCard.visibility = View.GONE
        binding.homeCreditCard.visibility = View.GONE
        binding.inHouseCard.visibility = View.GONE

        // Show appropriate card based on transaction type
        when (transactionType) {
            "Cash Transaction" -> {
                binding.cashPaymentCard.visibility = View.VISIBLE
                updateCashAmount()
            }
            "Home Credit Transaction" -> {
                binding.homeCreditCard.visibility = View.VISIBLE
                // Set Home Credit colors (red)
                binding.homeCreditCard.strokeColor = ContextCompat.getColor(this, android.R.color.holo_red_dark)
                binding.homeCreditTitle.text = "Home Credit Details"
                binding.homeCreditTitle.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                binding.hcBalanceInput.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                updateHCBalance()
            }
            "Skyro Transaction" -> {
                binding.homeCreditCard.visibility = View.VISIBLE
                // Set Skyro colors (blue)
                binding.homeCreditCard.strokeColor = ContextCompat.getColor(this, android.R.color.holo_blue_dark)
                binding.homeCreditTitle.text = "Skyro Details"
                binding.homeCreditTitle.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
                binding.hcBalanceInput.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
                updateHCBalance()
            }
            "In-House Installment" -> {
                binding.inHouseCard.visibility = View.VISIBLE
                updateInHouseBalance()
                updateMonthlyAmount()
            }
        }
    }

    // ============================================================================
    // END OF PART 3: DROPDOWN SETUP METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 4: ACCESSORY NAME LISTENER
    // ============================================================================

    private fun setupAccessoryNameListener() {
        binding.accessoryNameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                accessoryName = s.toString().trim()
                validateForm()
            }
        })
    }

    // ============================================================================
    // END OF PART 4: ACCESSORY NAME LISTENER
    // ============================================================================


    // ============================================================================
    // START OF PART 5: PRICE AND DISCOUNT LISTENERS
    // ============================================================================

        // Flag to prevent infinite loop between discount watchers
        private var isUpdatingDiscount = false
        // Flag to prevent infinite loop when correcting invalid values
        private var isCorrectingValue = false

        private fun setupPriceListeners() {
            // Price input listener
            binding.priceInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isCorrectingValue) return

                    val input = s.toString()
                    if (input.isNotEmpty()) {
                        try {
                            // Parse with comma stripping
                            val parsedPrice = input.replace(",", "").toDouble()

                            // Validate: Price cannot be negative
                            if (parsedPrice < 0) {
                                isCorrectingValue = true
                                binding.priceInput.setText("0.00")
                                binding.priceInput.setSelection(binding.priceInput.text?.length ?: 0)
                                isCorrectingValue = false
                                price = 0.0
                                showMessage("Price cannot be negative", true)
                            } else {
                                price = parsedPrice
                            }

                            // Re-validate discount against new price
                            validateDiscountAgainstPrice()

                            updateAllCalculations()
                        } catch (e: NumberFormatException) {
                            price = 0.0
                            discount = 0.0
                            updateAllCalculations()
                        }
                    } else {
                        price = 0.0
                        discount = 0.0
                        updateAllCalculations()
                    }

                    validateForm()
                }
            })

            // Discount amount listener with dynamic comma formatting
            binding.discountInput.addTextChangedListener(object : TextWatcher {
                private var currentText = ""

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isUpdatingDiscount || isCorrectingValue) return

                    val input = s.toString()

                    // Avoid re-processing if text hasn't changed
                    if (input == currentText) return

                    // Strip commas and parse
                    val cleanInput = input.replace(",", "")

                    if (cleanInput.isEmpty()) {
                        discount = 0.0
                        isUpdatingDiscount = true
                        binding.discountPercentInput.setText("")
                        isUpdatingDiscount = false
                        updateAllCalculations()
                        currentText = ""
                        return
                    }

                    try {
                        var parsedDiscount = cleanInput.toDouble()

                        // Validate: Discount cannot exceed price
                        if (parsedDiscount > price && price > 0) {
                            parsedDiscount = price
                            showMessage("Discount cannot exceed price", true)
                        }

                        discount = parsedDiscount

                        // Format with commas only (preserve user's decimal input)
                        val parts = cleanInput.split(".")
                        val integerPart = parts[0].toLongOrNull() ?: 0
                        val formattedInteger = String.format("%,d", integerPart)
                        val formatted = if (parts.size > 1) {
                            "$formattedInteger.${parts[1]}"
                        } else {
                            formattedInteger
                        }

                        // Only update if formatting changed
                        if (formatted != input) {
                            isUpdatingDiscount = true
                            binding.discountInput.setText(formatted)
                            // Set cursor to end
                            binding.discountInput.setSelection(formatted.length)
                            isUpdatingDiscount = false
                        }

                        currentText = formatted

                        // Update percent with 1 decimal place
                        if (price > 0) {
                            val percent = (discount / price) * 100
                            isUpdatingDiscount = true
                            binding.discountPercentInput.setText(String.format("%.1f", percent))
                            isUpdatingDiscount = false
                        }
                    } catch (e: NumberFormatException) {
                        discount = 0.0
                    }

                    updateAllCalculations()
                }
            })

            // Discount percent listener
            binding.discountPercentInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isUpdatingDiscount || isCorrectingValue) return

                    val input = s.toString()
                    if (input.isNotEmpty() && price > 0) {
                        try {
                            var percent = input.toDouble()

                            // Validate: Percent cannot exceed 100%
                            if (percent > 100) {
                                percent = 100.0
                                isUpdatingDiscount = true
                                binding.discountPercentInput.setText("100.0")
                                binding.discountPercentInput.setSelection(5)
                                isUpdatingDiscount = false
                                showMessage("Discount cannot exceed 100%", true)
                            }

                            discount = (price * percent) / 100
                            // Update amount with commas and 2 decimal places (from percent calculation)
                            isUpdatingDiscount = true
                            binding.discountInput.setText(String.format("%,.2f", discount))
                            isUpdatingDiscount = false
                        } catch (e: NumberFormatException) {
                            discount = 0.0
                        }
                    } else {
                        discount = 0.0
                    }
                    updateAllCalculations()
                }
            })
        }

        private fun validateDiscountAgainstPrice() {
            // If discount exceeds new price, cap it
            if (discount > price && price > 0) {
                discount = price
                isUpdatingDiscount = true
                binding.discountInput.setText(String.format("%,.2f", discount))
                if (price > 0) {
                    val percent = (discount / price) * 100
                    binding.discountPercentInput.setText(String.format("%.1f", percent))
                }
                isUpdatingDiscount = false
                showMessage("Discount adjusted to match price", true)
            }
        }

        private fun updateAllCalculations() {
            when (transactionType) {
                "Cash Transaction" -> updateCashAmount()
                "Home Credit Transaction", "Skyro Transaction" -> updateHCBalance()
                "In-House Installment" -> {
                    updateInHouseBalance()
                    updateMonthlyAmount()
                }
            }
        }

        private fun updateCashAmount() {
            val amount = price - discount
            binding.cashAmountInput.setText(formatCurrency(amount))
        }

    // ============================================================================
    // END OF PART 5: PRICE AND DISCOUNT LISTENERS
    // ============================================================================


    // ============================================================================
    // START OF PART 6: DOWN PAYMENT LISTENERS
    // ============================================================================

    // Flag to prevent infinite loop in HC down payment formatting
    private var isUpdatingHCDownPayment = false

    private fun setupDownPaymentListeners() {
        // Home Credit / Skyro down payment listener with dynamic comma formatting
        binding.hcDownPaymentInput.addTextChangedListener(object : TextWatcher {
            private var currentText = ""

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingHCDownPayment || isCorrectingValue) return

                val input = s.toString()

                // Avoid re-processing if text hasn't changed
                if (input == currentText) return

                // Strip commas and parse
                val cleanInput = input.replace(",", "")

                if (cleanInput.isEmpty()) {
                    currentText = ""
                    updateHCBalance()
                    updateHCDownPaymentSourceVisibility()
                    return
                }

                try {
                    var downPaymentValue = cleanInput.toDouble()

                    // Calculate max down payment (price - discount)
                    val maxDownPayment = price - discount

                    // Validate: Down payment cannot exceed (price - discount)
                    if (downPaymentValue > maxDownPayment && maxDownPayment > 0) {
                        downPaymentValue = maxDownPayment
                        showMessage("Down payment cannot exceed price minus discount", true)
                    }

                    // Format with commas (no decimals)
                    val formatted = String.format("%,.0f", downPaymentValue)

                    // Only update if formatting changed
                    if (formatted != input) {
                        isUpdatingHCDownPayment = true
                        binding.hcDownPaymentInput.setText(formatted)
                        binding.hcDownPaymentInput.setSelection(formatted.length)
                        isUpdatingHCDownPayment = false
                    }

                    currentText = formatted
                } catch (e: NumberFormatException) {
                    // Invalid input, ignore
                }

                updateHCBalance()
                updateHCDownPaymentSourceVisibility()
            }
        })
    }

    private fun updateHCBalance() {
        val downPayment = binding.hcDownPaymentInput.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0
        val balance = price - discount - downPayment
        binding.hcBalanceInput.setText(formatCurrency(balance))
    }

    private fun updateHCDownPaymentSourceVisibility() {
        val downPayment = binding.hcDownPaymentInput.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0

        if (downPayment > 0) {
            binding.hcDownPaymentSourceLabel.visibility = View.VISIBLE
            binding.hcDownPaymentSourceLayout.visibility = View.VISIBLE
        } else {
            binding.hcDownPaymentSourceLabel.visibility = View.GONE
            binding.hcDownPaymentSourceLayout.visibility = View.GONE
        }
    }

    // ============================================================================
    // END OF PART 6: DOWN PAYMENT LISTENERS
    // ============================================================================


    // ============================================================================
    // START OF PART 7: IN-HOUSE LISTENERS
    // ============================================================================

    // Flag to prevent infinite loop in in-house down payment formatting
    private var isUpdatingIHDownPayment = false
    // Flag to prevent infinite loop in interest formatting
    private var isUpdatingInterest = false

    private fun setupInHouseListeners() {
        // Interest percent listener with single decimal formatting
        binding.interestInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingInterest || isCorrectingValue) return

                val input = s.toString()

                if (input.isEmpty()) {
                    updateInHouseBalance()
                    updateMonthlyAmount()
                    validateForm()
                    return
                }

                try {
                    var percent = input.toDouble()

                    // Validate: Interest percent cannot exceed 100%
                    if (percent > 100) {
                        percent = 100.0
                        isUpdatingInterest = true
                        binding.interestInput.setText(String.format("%.1f", percent))
                        binding.interestInput.setSelection(binding.interestInput.text?.length ?: 0)
                        isUpdatingInterest = false
                        showMessage("Interest cannot exceed 100%", true)
                    }
                } catch (e: NumberFormatException) {
                    // Invalid input, ignore
                }

                updateInHouseBalance()
                updateMonthlyAmount()
                validateForm()
            }
        })

        // Down payment listener with dynamic comma formatting
        binding.ihDownPaymentInput.addTextChangedListener(object : TextWatcher {
            private var currentText = ""

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingIHDownPayment || isCorrectingValue) return

                val input = s.toString()

                // Avoid re-processing if text hasn't changed
                if (input == currentText) return

                // Strip commas and parse
                val cleanInput = input.replace(",", "")

                if (cleanInput.isEmpty()) {
                    currentText = ""
                    updateInHouseBalance()
                    updateMonthlyAmount()
                    updateIHDownPaymentSourceVisibility()
                    validateForm()
                    return
                }

                try {
                    var downPaymentValue = cleanInput.toDouble()

                    // Calculate max down payment (price - discount)
                    val maxDownPayment = price - discount

                    // Validate: Down payment cannot exceed (price - discount)
                    if (downPaymentValue > maxDownPayment && maxDownPayment > 0) {
                        downPaymentValue = maxDownPayment
                        showMessage("Down payment cannot exceed price minus discount", true)
                    }

                    // Format with commas (no decimals)
                    val formatted = String.format("%,.0f", downPaymentValue)

                    // Only update if formatting changed
                    if (formatted != input) {
                        isUpdatingIHDownPayment = true
                        binding.ihDownPaymentInput.setText(formatted)
                        binding.ihDownPaymentInput.setSelection(formatted.length)
                        isUpdatingIHDownPayment = false
                    }

                    currentText = formatted
                } catch (e: NumberFormatException) {
                    // Invalid input, ignore
                }

                updateInHouseBalance()
                updateMonthlyAmount()
                updateIHDownPaymentSourceVisibility()
                validateForm()
            }
        })

        // Months to pay listener
        binding.monthsToPayInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateMonthlyAmount()
                validateForm()
            }
        })
    }

    private fun updateInHouseBalance() {
        // Get values
        val downPayment = binding.ihDownPaymentInput.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0
        val interestPercent = binding.interestInput.text.toString().toDoubleOrNull() ?: 0.0

        // Calculate base amount (Price - Discount - Down Payment)
        val baseAmount = price - discount - downPayment

        // Calculate interest amount from percent
        val interestAmount = baseAmount * (interestPercent / 100)

        // Calculate balance = base amount + interest amount
        val balance = baseAmount + interestAmount

        binding.ihBalanceInput.setText(formatCurrency(balance))
    }

    private fun updateMonthlyAmount() {
        // Parse balance (strip currency symbol and commas)
        val balanceText = binding.ihBalanceInput.text.toString()
            .replace("₱", "")
            .replace(",", "")
        val balance = balanceText.toDoubleOrNull() ?: 0.0

        val months = binding.monthsToPayInput.text.toString().toIntOrNull() ?: 0

        val monthlyAmount = if (months > 0) balance / months else 0.0
        binding.monthlyAmountInput.setText(formatCurrency(monthlyAmount))
    }

    private fun updateIHDownPaymentSourceVisibility() {
        val downPayment = binding.ihDownPaymentInput.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0

        if (downPayment > 0) {
            binding.ihDownPaymentSourceLabel.visibility = View.VISIBLE
            binding.ihDownPaymentSourceLayout.visibility = View.VISIBLE
        } else {
            binding.ihDownPaymentSourceLabel.visibility = View.GONE
            binding.ihDownPaymentSourceLayout.visibility = View.GONE
        }
    }

    // ============================================================================
    // END OF PART 7: IN-HOUSE LISTENERS
    // ============================================================================


    // ============================================================================
    // START OF PART 8: FORM VALIDATION
    // ============================================================================

    private fun validateForm() {
        // Check required fields
        val isAccessoryNameValid = accessoryName.isNotEmpty()
        val isPriceValid = price > 0

        // Base validation
        var isFormValid = isAccessoryNameValid && isPriceValid

        // Additional validation for In-House Installment
        if (transactionType == "In-House Installment" && isFormValid) {
            val interestPercent = binding.interestInput.text.toString().toDoubleOrNull() ?: 0.0
            val monthsToPay = binding.monthsToPayInput.text.toString().toIntOrNull() ?: 0

            // Parse balance
            val balanceText = binding.ihBalanceInput.text.toString()
                .replace("₱", "")
                .replace(",", "")
            val balance = balanceText.toDoubleOrNull() ?: 0.0

            // Validate all conditions
            val isInterestValid = interestPercent <= 100
            val isMonthsValid = monthsToPay > 0
            val isBalanceValid = balance > 0

            isFormValid = isInterestValid && isMonthsValid && isBalanceValid
        }

        // Enable/disable save button based on validation
        if (isFormValid) {
            binding.saveButton.isEnabled = true
            binding.saveButton.alpha = 1.0f
        } else {
            binding.saveButton.isEnabled = false
            binding.saveButton.alpha = 0.5f
        }
    }

    // ============================================================================
    // END OF PART 8: FORM VALIDATION
    // ============================================================================


    // ============================================================================
    // START OF PART 9: BUTTON LISTENERS AND SAVE METHODS
    // ============================================================================

    private fun setupButtonListeners() {
        binding.cancelButton.setOnClickListener {
            finish()
        }

        binding.saveButton.setOnClickListener {
            saveTransaction()
        }
    }

    private fun saveTransaction() {
        // Validate required fields
        if (accessoryName.isEmpty()) {
            showMessage("Please enter an accessory name", true)
            binding.accessoryNameInput.requestFocus()
            return
        }

        if (price <= 0) {
            showMessage("Please enter a valid price (greater than zero)", true)
            binding.priceInput.requestFocus()
            return
        }

        // Build transaction data based on transaction type
        val transactionData = hashMapOf<String, Any>(
            "accessoryName" to accessoryName,
            "price" to price,
            "discount" to discount,
            "transactionType" to transactionType,
            "timestamp" to System.currentTimeMillis()
        )

        when (transactionType) {
            "Cash Transaction" -> {
                transactionData["paymentSource"] = binding.paymentSourceDropdown.text.toString()
                transactionData["totalAmount"] = price - discount
            }
            "Home Credit Transaction", "Skyro Transaction" -> {
                val downPayment = binding.hcDownPaymentInput.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0
                val balance = price - (discount + downPayment)

                transactionData["downPaymentAmount"] = downPayment
                transactionData["balance"] = balance

                if (downPayment > 0) {
                    transactionData["downPaymentSource"] = binding.hcDownPaymentSourceDropdown.text.toString()
                }
            }
            "In-House Installment" -> {
                val interest = binding.interestInput.text.toString().toDoubleOrNull() ?: 0.0
                val downPayment = binding.ihDownPaymentInput.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0
                val balance = (price + interest) - (discount + downPayment)
                val monthsToPay = binding.monthsToPayInput.text.toString().toIntOrNull() ?: 0
                val monthlyAmount = if (monthsToPay > 0) balance / monthsToPay else 0.0

                transactionData["interest"] = interest
                transactionData["downPaymentAmount"] = downPayment
                transactionData["balance"] = balance
                transactionData["monthsToPay"] = monthsToPay
                transactionData["monthlyAmount"] = monthlyAmount

                if (downPayment > 0) {
                    transactionData["downPaymentSource"] = binding.ihDownPaymentSourceDropdown.text.toString()
                }
            }
        }

        // Show progress and save
        binding.progressBar.visibility = View.VISIBLE
        binding.saveButton.isEnabled = false

        // TODO: Save to Firebase - For now just show success message
        // This is a placeholder for actual Firebase save logic
        binding.root.postDelayed({
            binding.progressBar.visibility = View.GONE
            showMessage("Accessory transaction saved successfully!", false)

            // Return to previous screen after short delay
            binding.root.postDelayed({
                finish()
            }, 1000)
        }, 500)
    }

    // ============================================================================
    // END OF PART 9: BUTTON LISTENERS AND SAVE METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 10: HELPER METHODS
    // ============================================================================

    private fun formatCurrency(amount: Double): String {
        return "₱${String.format("%,.2f", amount)}"
    }

    private fun showMessage(message: String, isError: Boolean) {
        Toast.makeText(this, message, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    // ============================================================================
    // END OF PART 10: HELPER METHODS
    // ============================================================================
}