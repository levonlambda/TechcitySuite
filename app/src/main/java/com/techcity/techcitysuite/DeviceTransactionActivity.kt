package com.techcity.techcitysuite

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import com.techcity.techcitysuite.databinding.ActivityDeviceTransactionBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class DeviceTransactionActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityDeviceTransactionBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Device and price information
    private var foundInventoryItem: InventoryItem? = null
    private var price: Double = 0.0
    private var discount: Double = 0.0

    // Device type from phones collection (for subsidy calculation)
    private var deviceType: String = "phone" // Default to phone

    // Transaction type
    private var transactionType: String = "Cash Transaction"
    private val transactionTypes = arrayOf(
        "Cash Transaction",
        "Home Credit Transaction",
        "Skyro Transaction",
        "In-House Installment"
    )

    // Brand Zero (for Home Credit and Skyro)
    private var brandZero: Boolean = false
    private val brandZeroOptions = arrayOf("Yes", "No")

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
        binding = ActivityDeviceTransactionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firestore
        db = Firebase.firestore

        // Set up UI
        setupDropdowns()
        setupPriceListeners()
        setupDownPaymentListeners()
        setupInHouseListeners()
        setupButtonListeners()
        setupSearchButton()

        // Disable save button and transaction type until valid IMEI is selected
        binding.saveButton.isEnabled = false
        binding.saveButton.alpha = 0.5f
        binding.purchaseTypeDropdown.isEnabled = false
        binding.purchaseTypeLayout.isEnabled = false

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
            validateFormForTransactionType()
        }

        // Payment Source Dropdown (for Cash Transaction)
        val paymentAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, paymentSources)
        binding.paymentSourceDropdown.setAdapter(paymentAdapter)
        binding.paymentSourceDropdown.setText(paymentSources[0], false)
        updatePaymentSourceColor(paymentSources[0])

        binding.paymentSourceDropdown.setOnItemClickListener { _, _, position, _ ->
            updatePaymentSourceColor(paymentSources[position])
        }

        // Brand Zero Dropdown (for Home Credit and Skyro)
        val brandZeroAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, brandZeroOptions)
        binding.brandZeroDropdown.setAdapter(brandZeroAdapter)
        binding.brandZeroDropdown.setText(brandZeroOptions[1], false) // Default to "No"
        brandZero = false
        updateBrandZeroColor("No")

        binding.brandZeroDropdown.setOnItemClickListener { _, _, position, _ ->
            val selected = brandZeroOptions[position]
            brandZero = (selected == "Yes")
            updateBrandZeroColor(selected)
            // Show/hide subsidy field based on Brand Zero selection
            updateSubsidyVisibility()
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

    private fun updateBrandZeroColor(value: String) {
        val color = when (value) {
            "Yes" -> ContextCompat.getColor(this, R.color.green)
            "No" -> ContextCompat.getColor(this, R.color.red)
            else -> ContextCompat.getColor(this, android.R.color.black)
        }
        binding.brandZeroDropdown.setTextColor(color)
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
                updateSubsidyVisibility()
            }
            "Skyro Transaction" -> {
                binding.homeCreditCard.visibility = View.VISIBLE
                // Set Skyro colors (blue)
                binding.homeCreditCard.strokeColor = ContextCompat.getColor(this, android.R.color.holo_blue_dark)
                binding.homeCreditTitle.text = "Skyro Details"
                binding.homeCreditTitle.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
                binding.hcBalanceInput.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
                updateHCBalance()
                updateSubsidyVisibility()
            }
            "In-House Installment" -> {
                binding.inHouseCard.visibility = View.VISIBLE
                updateInHouseBalance()
                updateMonthlyAmount()
            }
        }
    }

    private fun validateFormForTransactionType() {
        // Check if valid IMEI is selected first
        if (foundInventoryItem == null) {
            disableFormControls()
            return
        }

        when (transactionType) {
            "In-House Installment" -> {
                validateInHouseForm()
            }
            else -> {
                // For other transaction types, enable save button if IMEI is valid
                binding.saveButton.isEnabled = true
                binding.saveButton.alpha = 1.0f
            }
        }
    }

    // ============================================================================
    // END OF PART 3: DROPDOWN SETUP METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 4: PRICE AND DISCOUNT LISTENERS
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
                "Home Credit Transaction", "Skyro Transaction" -> {
                    updateHCBalance()
                    // Update subsidy when price changes
                    if (brandZero) {
                        updateSubsidyAmount()
                    }
                }
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
    // END OF PART 4: PRICE AND DISCOUNT LISTENERS
    // ============================================================================


    // ============================================================================
    // START OF PART 5: DOWN PAYMENT LISTENERS
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

            // Calculate subsidy if Brand Zero is enabled
            val subsidy = if (brandZero) {
                val subsidyPercent = when (deviceType.lowercase()) {
                    "laptop" -> 0.08  // 8% for laptops
                    else -> 0.03      // 3% for phones and tablets
                }
                price * subsidyPercent
            } else {
                0.0
            }

            // Balance = Price - Discount - Down Payment - Subsidy (if Brand Zero)
            val balance = price - discount - downPayment - subsidy
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

        private fun updateSubsidyVisibility() {
            // Only show subsidy when Brand Zero is Yes
            if (brandZero) {
                binding.hcSubsidyContainer.visibility = View.VISIBLE
                updateSubsidyAmount()
            } else {
                binding.hcSubsidyContainer.visibility = View.GONE
            }
            // Update balance whenever Brand Zero changes (to add/remove subsidy from calculation)
            updateHCBalance()
        }

        private fun updateSubsidyAmount() {
            // Calculate subsidy based on device type
            // Phone/Tablet = 3% of price
            // Laptop = 8% of price
            val subsidyPercent = when (deviceType.lowercase()) {
                "laptop" -> 0.08  // 8% for laptops
                else -> 0.03      // 3% for phones and tablets
            }

            val subsidyAmount = price * subsidyPercent
            binding.hcSubsidyInput.setText(formatCurrency(subsidyAmount))

            // Update label to show the percentage
            val percentText = when (deviceType.lowercase()) {
                "laptop" -> "Subsidy (8%)"
                else -> "Subsidy (3%)"
            }
            binding.hcSubsidyLabel.text = percentText
        }

    // ============================================================================
    // END OF PART 5: DOWN PAYMENT LISTENERS
    // ============================================================================


    // ============================================================================
    // START OF PART 6: IN-HOUSE LISTENERS
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
                    validateInHouseForm()
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
                validateInHouseForm()
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
                    validateInHouseForm()
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
                validateInHouseForm()
            }
        })

        // Months to pay listener
        binding.monthsToPayInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateMonthlyAmount()
                validateInHouseForm()
            }
        })
    }

    private fun updateInHouseBalance() {
        // Get values
        val downPayment = binding.ihDownPaymentInput.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0
        val interestPercent = binding.interestInput.text.toString().toDoubleOrNull() ?: 0.0

        // Calculate final price (Price - Discount)
        val finalPrice = price - discount

        // Calculate interest amount on FINAL PRICE (not on amount after downpayment)
        // This matches the InHousePaymentActivity calculation
        val interestAmount = finalPrice * (interestPercent / 100)

        // Calculate balance = (Final Price - Down Payment) + Interest Amount
        val balance = (finalPrice - downPayment) + interestAmount

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

    private fun validateInHouseForm() {
        // Only validate if In-House Installment is selected
        if (transactionType != "In-House Installment") return

        // Check if valid IMEI is selected
        if (foundInventoryItem == null) {
            disableFormControls()
            return
        }

        // Get values
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

        // Enable/disable save button based on validation
        if (isInterestValid && isMonthsValid && isBalanceValid) {
            binding.saveButton.isEnabled = true
            binding.saveButton.alpha = 1.0f
        } else {
            binding.saveButton.isEnabled = false
            binding.saveButton.alpha = 0.5f
        }
    }

    // ============================================================================
    // END OF PART 6: IN-HOUSE LISTENERS
    // ============================================================================


    // ============================================================================
    // START OF PART 7: SEARCH BUTTON SETUP
    // ============================================================================

    private fun setupSearchButton() {
        binding.searchButton.setOnClickListener {
            searchByIMEI()
        }
    }

    // ============================================================================
    // END OF PART 7: SEARCH BUTTON SETUP
    // ============================================================================


    // ============================================================================
    // START OF PART 8: IMEI SEARCH METHODS
    // ============================================================================

    private fun searchByIMEI() {
        val searchTerm = binding.imeiInput.text.toString().trim()

        if (searchTerm.isEmpty()) {
            showMessage("Please enter IMEI or Serial Number", true)
            return
        }

        // Show progress
        binding.progressBar.visibility = View.VISIBLE
        binding.searchButton.isEnabled = false

        // Search in Firestore with partial matching
        scope.launch {
            try {
                searchInFirestorePartial(searchTerm)
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.searchButton.isEnabled = true
                showMessage("Error: ${e.message}", true)
            }
        }
    }

    private suspend fun searchInFirestorePartial(searchTerm: String) {
        withContext(Dispatchers.IO) {
            try {
                // Fetch all inventory items
                val querySnapshot = db.collection("inventory")
                    .get()
                    .await()

                // Filter for partial matches in imei1, imei2, or serialNumber
                val matchingItems = mutableListOf<Pair<InventoryItem, String>>()

                for (document in querySnapshot.documents) {
                    val data = document.data ?: continue

                    val imei1 = (data["imei1"] ?: "").toString()
                    val imei2 = (data["imei2"] ?: "").toString()
                    val serialNumber = (data["serialNumber"] ?: "").toString()

                    // Determine matched identifier
                    var matchedIdentifier: String? = null

                    if (imei1.isNotEmpty() && imei1.contains(searchTerm, ignoreCase = true)) {
                        matchedIdentifier = imei1
                    } else if (imei2.isNotEmpty() && imei2.contains(searchTerm, ignoreCase = true)) {
                        matchedIdentifier = imei2
                    } else if (serialNumber.isNotEmpty() && serialNumber.contains(searchTerm, ignoreCase = true)) {
                        matchedIdentifier = serialNumber
                    }

                    if (matchedIdentifier != null) {
                        val isArchived = data["isArchived"] as? Boolean ?: false
                        if (!isArchived) {
                            val item = document.toObject(InventoryItem::class.java)
                            if (item != null) {
                                matchingItems.add(Pair(item, matchedIdentifier))
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.searchButton.isEnabled = true

                    when {
                        matchingItems.isEmpty() -> {
                            showMessage("No device found matching: $searchTerm", false)
                            clearDeviceInfo()
                        }
                        matchingItems.size == 1 -> {
                            val (item, matchedIdentifier) = matchingItems[0]
                            foundInventoryItem = item
                            displayDeviceInfo(item, matchedIdentifier)
                            showMessage("Device found!", false)

                            // Fetch device type from phones collection
                            fetchDeviceType(item.manufacturer, item.model)
                        }
                        else -> {
                            showMessage("Multiple matches found (${matchingItems.size}). Please enter more digits.", true)
                            clearDeviceInfo()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.searchButton.isEnabled = true
                    showMessage("Search failed: ${e.message}", true)
                }
            }
        }
    }

    private fun fetchDeviceType(manufacturer: String, model: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Query phones collection by manufacturer and model
                    val querySnapshot = db.collection("phones")
                        .whereEqualTo("manufacturer", manufacturer)
                        .whereEqualTo("model", model)
                        .get()
                        .await()

                    val fetchedDeviceType = if (querySnapshot.documents.isNotEmpty()) {
                        val phoneDoc = querySnapshot.documents[0]
                        phoneDoc.getString("deviceType") ?: "phone"
                    } else {
                        "phone" // Default to phone if not found
                    }

                    withContext(Dispatchers.Main) {
                        deviceType = fetchedDeviceType
                        // Update subsidy if it's visible
                        if (brandZero) {
                            updateSubsidyAmount()
                        }
                    }
                }
            } catch (e: Exception) {
                // If fetch fails, default to phone
                deviceType = "phone"
            }
        }
    }

    private fun displayDeviceInfo(item: InventoryItem, matchedIdentifier: String) {
        // Update IMEI field with the complete matched identifier
        binding.imeiInput.setText(matchedIdentifier)

        // Update Device Details hint to show brand name (bold style)
        binding.deviceDetailsLayout.hint = item.manufacturer
        binding.deviceDetailsLayout.setHintTextAppearance(R.style.BoldHintStyle)

        // Format RAM with GB if not already present
        val ramDisplay = if (item.ram.contains("GB", ignoreCase = true)) {
            item.ram
        } else {
            "${item.ram}GB"
        }

        // Format Storage with GB if not already present
        val storageDisplay = if (item.storage.contains("GB", ignoreCase = true)) {
            item.storage
        } else {
            "${item.storage}GB"
        }

        // Set field value: Model - RAM + Storage - (Color)
        val deviceDetails = "${item.model} - $ramDisplay + $storageDisplay - (${item.color})"
        binding.deviceDetailsInput.setText(deviceDetails)

        // Set text color based on status
        val textColor = when (item.status.lowercase()) {
            "on-hand" -> ContextCompat.getColor(this, R.color.techcity_blue)
            "on-display" -> ContextCompat.getColor(this, android.R.color.black)
            else -> ContextCompat.getColor(this, android.R.color.black)
        }
        binding.deviceDetailsInput.setTextColor(textColor)

        // Auto-fill price with formatting (commas and 2 decimal places)
        price = item.retailPrice
        binding.priceInput.setText(String.format("%,.2f", price))

        // Enable save button and transaction type dropdown
        enableFormControls()

        // Update all calculations
        updateAllCalculations()
    }

    private fun clearDeviceInfo() {
        // Reset Device Details hint to default (normal style)
        binding.deviceDetailsLayout.hint = "Will be populated from IMEI"
        binding.deviceDetailsLayout.setHintTextAppearance(R.style.NormalHintStyle)

        // Clear device details field and reset color
        binding.deviceDetailsInput.setText("")
        binding.deviceDetailsInput.setTextColor(ContextCompat.getColor(this, R.color.gray))

        // Clear price field
        binding.priceInput.setText("")
        price = 0.0

        // Clear found item and reset device type
        foundInventoryItem = null
        deviceType = "phone"

        // Disable save button and transaction type dropdown
        disableFormControls()
    }

    private fun enableFormControls() {
        // Enable save button
        binding.saveButton.isEnabled = true
        binding.saveButton.alpha = 1.0f

        // Enable transaction type dropdown
        binding.purchaseTypeDropdown.isEnabled = true
        binding.purchaseTypeLayout.isEnabled = true
    }

    private fun disableFormControls() {
        // Disable save button
        binding.saveButton.isEnabled = false
        binding.saveButton.alpha = 0.5f

        // Disable transaction type dropdown
        binding.purchaseTypeDropdown.isEnabled = false
        binding.purchaseTypeLayout.isEnabled = false
    }

    // ============================================================================
    // END OF PART 8: IMEI SEARCH METHODS
    // ============================================================================

    // ============================================================================
    // START OF PART 9: BUTTON LISTENERS AND SAVE METHODS
    // ============================================================================

    // NOTE: This is the TESTING version that only creates device_transactions
    // and does NOT update inventory status. For production, use the version
    // that includes the inventory update.

    private fun setupButtonListeners() {
        binding.cancelButton.setOnClickListener {
            finish()
        }

        binding.saveButton.setOnClickListener {
            saveTransaction()
        }
    }

    private fun saveTransaction() {
        // -------------------------------------------------------------------------
        // STEP 1: Validate Prerequisites
        // -------------------------------------------------------------------------

        // Get SharedPreferences
        val prefs = getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val user = prefs.getString(AppConstants.KEY_USER, "") ?: ""
        val userLocation = prefs.getString(AppConstants.KEY_STORE_LOCATION, "") ?: ""

        // Validate user settings
        if (user.isEmpty()) {
            showMessage("Please configure User in Settings first", true)
            return
        }

        if (userLocation.isEmpty()) {
            showMessage("Please configure Store Location in Settings first", true)
            return
        }

        // Validate device is found
        if (foundInventoryItem == null) {
            showMessage("Please search for a device first", true)
            return
        }

        // TESTING MODE: Skip the "already sold" check so we can test with same device
        // For production, uncomment this validation:
        /*
        if (foundInventoryItem!!.status.equals("Sold", ignoreCase = true)) {
            showMessage("This device has already been sold", true)
            return
        }
        */

        // Validate price
        if (price <= 0) {
            showMessage("Please enter a valid price", true)
            return
        }

        // Transaction type specific validations
        when (transactionType) {
            AppConstants.TRANSACTION_TYPE_CASH -> {
                val paymentSource = binding.paymentSourceDropdown.text.toString()
                if (paymentSource.isEmpty()) {
                    showMessage("Please select a payment source", true)
                    return
                }
            }
            AppConstants.TRANSACTION_TYPE_HOME_CREDIT, AppConstants.TRANSACTION_TYPE_SKYRO -> {
                val downPayment = binding.hcDownPaymentInput.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0
                if (downPayment > 0) {
                    val downPaymentSource = binding.hcDownPaymentSourceDropdown.text.toString()
                    if (downPaymentSource.isEmpty()) {
                        showMessage("Please select a downpayment source", true)
                        return
                    }
                }
                // Calculate balance and validate it's not negative
                val subsidyAmount = if (brandZero) {
                    val subsidyPercent = when (deviceType.lowercase()) {
                        "laptop" -> AppConstants.SUBSIDY_PERCENT_LAPTOP / 100.0
                        else -> AppConstants.SUBSIDY_PERCENT_PHONE / 100.0
                    }
                    price * subsidyPercent
                } else 0.0
                val balance = price - discount - downPayment - subsidyAmount
                if (balance < 0) {
                    showMessage("Balance cannot be negative", true)
                    return
                }
            }
            AppConstants.TRANSACTION_TYPE_IN_HOUSE -> {
                val downPayment = binding.ihDownPaymentInput.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0
                val interestPercent = binding.interestInput.text.toString().toDoubleOrNull() ?: 0.0
                val monthsToPay = binding.monthsToPayInput.text.toString().toIntOrNull() ?: 0

                if (downPayment <= 0) {
                    showMessage("Downpayment is required for In-House Installment", true)
                    return
                }

                val downPaymentSource = binding.ihDownPaymentSourceDropdown.text.toString()
                if (downPaymentSource.isEmpty()) {
                    showMessage("Please select a downpayment source", true)
                    return
                }

                if (interestPercent < 0 || interestPercent > 100) {
                    showMessage("Interest must be between 0 and 100%", true)
                    return
                }

                if (monthsToPay <= 0) {
                    showMessage("Months to pay must be greater than 0", true)
                    return
                }

                val balance = price - discount - downPayment
                if (balance <= 0) {
                    showMessage("Balance must be greater than 0", true)
                    return
                }
            }
        }

        // -------------------------------------------------------------------------
        // STEP 2: Show Progress
        // -------------------------------------------------------------------------
        binding.progressBar.visibility = View.VISIBLE
        binding.saveButton.isEnabled = false

        // -------------------------------------------------------------------------
        // STEP 3: Build and Save Transaction
        // -------------------------------------------------------------------------
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    saveTransactionToFirebase(prefs, user, userLocation)
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE

                    if (result.isSuccess) {
                        showMessage("Transaction saved successfully!", false)
                        // Return to previous screen after short delay
                        binding.root.postDelayed({
                            finish()
                        }, 1000)
                    } else {
                        binding.saveButton.isEnabled = true
                        showMessage("Failed to save transaction: ${result.exceptionOrNull()?.message}", true)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.saveButton.isEnabled = true
                    showMessage("Error saving transaction: ${e.message}", true)
                }
            }
        }
    }

    private suspend fun saveTransactionToFirebase(
        prefs: android.content.SharedPreferences,
        user: String,
        userLocation: String
    ): Result<String> {
        return try {
            // Get current date/time
            val now = Date()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val monthFormat = SimpleDateFormat("yyyy-MM", Locale.US)
            val yearFormat = SimpleDateFormat("yyyy", Locale.US)
            val displayDateFormat = SimpleDateFormat("M/d/yyyy", Locale.US)
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

            val dateString = dateFormat.format(now)
            val monthString = monthFormat.format(now)
            val yearString = yearFormat.format(now)
            val dateSoldString = displayDateFormat.format(now)
            val timeString = timeFormat.format(now)

            // Get device ID
            val appDeviceId = AppSettingsManager.getDeviceId(this@DeviceTransactionActivity)

            // Get inventory item (already validated as not null)
            val inventoryItem = foundInventoryItem!!

            // Build account settings snapshot
            val accountSettingsSnapshot = buildAccountSettingsSnapshot(prefs)

            // Calculate pricing
            val finalPrice = price - discount
            val discountPercent = if (price > 0) (discount / price) * 100 else 0.0

            // Get identifier used (from IMEI input field)
            val identifierUsed = binding.imeiInput.text.toString().trim()

            // Build transaction data as HashMap for Firestore
            val transactionData = hashMapOf<String, Any?>(
                // Date and Time Fields
                "date" to dateString,
                "month" to monthString,
                "year" to yearString,
                "timestamp" to FieldValue.serverTimestamp(),
                "dateSold" to dateSoldString,
                "time" to timeString,

                // Sort Order (for custom ordering in list view, 0 = use timestamp order)
                "sortOrder" to 0,

                // User and Location
                "user" to user,
                "userLocation" to userLocation,
                "deviceId" to appDeviceId,

                // Inventory Reference and Status Tracking
                "inventoryDocumentId" to inventoryItem.id,
                "originalStatus" to inventoryItem.status,
                "originalLastUpdated" to inventoryItem.lastUpdated,
                "newStatus" to AppConstants.INVENTORY_STATUS_SOLD,
                "newLastUpdated" to dateSoldString,

                // Device Identification
                "imei1" to inventoryItem.imei1,
                "imei2" to inventoryItem.imei2,
                "serialNumber" to inventoryItem.serialNumber,
                "identifierUsed" to identifierUsed,

                // Device Details
                "deviceType" to deviceType,
                "manufacturer" to inventoryItem.manufacturer,
                "model" to inventoryItem.model,
                "ram" to inventoryItem.ram,
                "storage" to inventoryItem.storage,
                "color" to inventoryItem.color,

                // Pricing Information - Original
                "originalRetailPrice" to inventoryItem.retailPrice,
                "originalDealersPrice" to inventoryItem.dealersPrice,

                // Pricing Information - Transaction
                "price" to price,
                "discountAmount" to discount,
                "discountPercent" to discountPercent,
                "finalPrice" to finalPrice,

                // Transaction Type
                "transactionType" to transactionType,

                // Account Settings Snapshot
                "accountSettingsSnapshot" to hashMapOf(
                    "cashAccount" to accountSettingsSnapshot.cashAccount,
                    "gcashAccount" to accountSettingsSnapshot.gcashAccount,
                    "paymayaAccount" to accountSettingsSnapshot.paymayaAccount,
                    "qrphAccount" to accountSettingsSnapshot.qrphAccount,
                    "creditCardAccount" to accountSettingsSnapshot.creditCardAccount,
                    "otherAccount" to accountSettingsSnapshot.otherAccount
                ),

                // Transaction Status
                "status" to AppConstants.STATUS_COMPLETED,

                // Void Information (null for new transactions)
                "voidReason" to null,
                "voidedAt" to null,
                "voidedBy" to null,

                // Audit Trail
                "createdBy" to user,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedBy" to null,
                "updatedAt" to null,

                // Notes
                "notes" to ""
            )

            // Add payment details based on transaction type
            when (transactionType) {
                AppConstants.TRANSACTION_TYPE_CASH -> {
                    val paymentSource = binding.paymentSourceDropdown.text.toString()
                    val accountDetails = getAccountDetailsForPaymentSource(paymentSource, prefs)

                    transactionData["cashPayment"] = hashMapOf(
                        "amountPaid" to finalPrice,
                        "paymentSource" to paymentSource,
                        "accountDetails" to hashMapOf(
                            "accountName" to accountDetails.accountName,
                            "accountType" to accountDetails.accountType
                        )
                    )
                    transactionData["homeCreditPayment"] = null
                    transactionData["skyroPayment"] = null
                    transactionData["inHouseInstallment"] = null
                }

                AppConstants.TRANSACTION_TYPE_HOME_CREDIT -> {
                    val downPayment = binding.hcDownPaymentInput.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0
                    val downPaymentSource = binding.hcDownPaymentSourceDropdown.text.toString()

                    // Calculate subsidy
                    val subsidyPercent = if (brandZero) {
                        when (deviceType.lowercase()) {
                            "laptop" -> AppConstants.SUBSIDY_PERCENT_LAPTOP
                            else -> AppConstants.SUBSIDY_PERCENT_PHONE
                        }
                    } else 0.0
                    val subsidyAmount = if (brandZero) price * (subsidyPercent / 100.0) else 0.0

                    // Calculate balance
                    val balance = finalPrice - downPayment - subsidyAmount

                    // Get account details for downpayment source (if downpayment > 0)
                    val accountDetails = if (downPayment > 0) {
                        getAccountDetailsForPaymentSource(downPaymentSource, prefs)
                    } else {
                        AccountDetails()
                    }

                    transactionData["homeCreditPayment"] = hashMapOf(
                        "downpaymentAmount" to downPayment,
                        "downpaymentSource" to if (downPayment > 0) downPaymentSource else "",
                        "accountDetails" to hashMapOf(
                            "accountName" to accountDetails.accountName,
                            "accountType" to accountDetails.accountType
                        ),
                        "brandZero" to brandZero,
                        "brandZeroSubsidy" to subsidyAmount,
                        "subsidyPercent" to subsidyPercent,
                        "balance" to balance,
                        "isBalancePaid" to false,
                        "balancePaidDate" to null,
                        "balancePaidBy" to null,
                        "balancePaidTimestamp" to null
                    )
                    transactionData["cashPayment"] = null
                    transactionData["skyroPayment"] = null
                    transactionData["inHouseInstallment"] = null
                }

                AppConstants.TRANSACTION_TYPE_SKYRO -> {
                    val downPayment = binding.hcDownPaymentInput.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0
                    val downPaymentSource = binding.hcDownPaymentSourceDropdown.text.toString()

                    // Calculate subsidy
                    val subsidyPercent = if (brandZero) {
                        when (deviceType.lowercase()) {
                            "laptop" -> AppConstants.SUBSIDY_PERCENT_LAPTOP
                            else -> AppConstants.SUBSIDY_PERCENT_PHONE
                        }
                    } else 0.0
                    val subsidyAmount = if (brandZero) price * (subsidyPercent / 100.0) else 0.0

                    // Calculate balance
                    val balance = finalPrice - downPayment - subsidyAmount

                    // Get account details for downpayment source (if downpayment > 0)
                    val accountDetails = if (downPayment > 0) {
                        getAccountDetailsForPaymentSource(downPaymentSource, prefs)
                    } else {
                        AccountDetails()
                    }

                    transactionData["skyroPayment"] = hashMapOf(
                        "downpaymentAmount" to downPayment,
                        "downpaymentSource" to if (downPayment > 0) downPaymentSource else "",
                        "accountDetails" to hashMapOf(
                            "accountName" to accountDetails.accountName,
                            "accountType" to accountDetails.accountType
                        ),
                        "brandZero" to brandZero,
                        "brandZeroSubsidy" to subsidyAmount,
                        "subsidyPercent" to subsidyPercent,
                        "balance" to balance,
                        "isBalancePaid" to false,
                        "balancePaidDate" to null,
                        "balancePaidBy" to null,
                        "balancePaidTimestamp" to null
                    )
                    transactionData["cashPayment"] = null
                    transactionData["homeCreditPayment"] = null
                    transactionData["inHouseInstallment"] = null
                }

                AppConstants.TRANSACTION_TYPE_IN_HOUSE -> {
                    val downPayment = binding.ihDownPaymentInput.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0
                    val downPaymentSource = binding.ihDownPaymentSourceDropdown.text.toString()
                    val interestPercent = binding.interestInput.text.toString().toDoubleOrNull() ?: 0.0
                    val monthsToPay = binding.monthsToPayInput.text.toString().toIntOrNull() ?: 0

                    // Calculate amounts
                    val balance = finalPrice - downPayment
                    val interestAmount = balance * (interestPercent / 100.0)
                    val totalAmountDue = balance + interestAmount
                    val monthlyAmount = if (monthsToPay > 0) totalAmountDue / monthsToPay else 0.0

                    // Get account details for downpayment source
                    val accountDetails = getAccountDetailsForPaymentSource(downPaymentSource, prefs)

                    transactionData["inHouseInstallment"] = hashMapOf(
                        "customerName" to binding.ihCustomerNameInput.text.toString().trim(),
                        "downpaymentAmount" to downPayment,
                        "downpaymentSource" to downPaymentSource,
                        "accountDetails" to hashMapOf(
                            "accountName" to accountDetails.accountName,
                            "accountType" to accountDetails.accountType
                        ),
                        "interestPercent" to interestPercent,
                        "interestAmount" to interestAmount,
                        "monthsToPay" to monthsToPay,
                        "monthlyAmount" to monthlyAmount,
                        "balance" to balance,
                        "totalAmountDue" to totalAmountDue,
                        "isBalancePaid" to false,
                        "remainingBalance" to totalAmountDue,
                        "lastPaymentDate" to null,
                        "lastPaymentAmount" to null,
                        "balancePaidDate" to null,
                        "balancePaidBy" to null,
                        "balancePaidTimestamp" to null
                    )
                    transactionData["cashPayment"] = null
                    transactionData["homeCreditPayment"] = null
                    transactionData["skyroPayment"] = null
                }
            }

            // -------------------------------------------------------------------------
            // STEP 4: Save to Firestore (TESTING MODE - NO INVENTORY UPDATE)
            // -------------------------------------------------------------------------
            // This version ONLY creates a new document in device_transactions
            // It does NOT update the inventory status
            // -------------------------------------------------------------------------

            val newDocRef = db.collection(AppConstants.COLLECTION_DEVICE_TRANSACTIONS)
                .add(transactionData)
                .await()

            Result.success(newDocRef.id)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get account details for a given payment source
     */
    private fun getAccountDetailsForPaymentSource(
        paymentSource: String,
        prefs: android.content.SharedPreferences
    ): AccountDetails {
        val accountName = when (paymentSource) {
            AppConstants.PAYMENT_SOURCE_CASH ->
                prefs.getString(AppConstants.KEY_CASH_ACCOUNT, "") ?: ""
            AppConstants.PAYMENT_SOURCE_GCASH ->
                prefs.getString(AppConstants.KEY_GCASH_ACCOUNT, "") ?: ""
            AppConstants.PAYMENT_SOURCE_PAYMAYA ->
                prefs.getString(AppConstants.KEY_PAYMAYA_ACCOUNT, "") ?: ""
            AppConstants.PAYMENT_SOURCE_BANK_TRANSFER ->
                prefs.getString(AppConstants.KEY_QRPH_ACCOUNT, "") ?: ""
            AppConstants.PAYMENT_SOURCE_CREDIT_CARD ->
                prefs.getString(AppConstants.KEY_CREDIT_CARD_ACCOUNT, "") ?: ""
            AppConstants.PAYMENT_SOURCE_OTHERS ->
                prefs.getString(AppConstants.KEY_OTHER_ACCOUNT, "") ?: ""
            else -> ""
        }

        return AccountDetails(
            accountName = accountName,
            accountType = paymentSource
        )
    }

    /**
     * Build account settings snapshot from SharedPreferences
     */
    private fun buildAccountSettingsSnapshot(prefs: android.content.SharedPreferences): AccountSettingsSnapshot {
        return AccountSettingsSnapshot(
            cashAccount = prefs.getString(AppConstants.KEY_CASH_ACCOUNT, "") ?: "",
            gcashAccount = prefs.getString(AppConstants.KEY_GCASH_ACCOUNT, "") ?: "",
            paymayaAccount = prefs.getString(AppConstants.KEY_PAYMAYA_ACCOUNT, "") ?: "",
            qrphAccount = prefs.getString(AppConstants.KEY_QRPH_ACCOUNT, "") ?: "",
            creditCardAccount = prefs.getString(AppConstants.KEY_CREDIT_CARD_ACCOUNT, "") ?: "",
            otherAccount = prefs.getString(AppConstants.KEY_OTHER_ACCOUNT, "") ?: ""
        )
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