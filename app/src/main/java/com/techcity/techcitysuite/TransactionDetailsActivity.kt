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
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityTransactionDetailsBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class TransactionDetailsActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityTransactionDetailsBinding
    private lateinit var db: FirebaseFirestore
    private var transactionType: String = ""
    private var transactionFee: Double = 0.0
    private var amount: Double = 0.0
    private var customerPays: Double = 0.0
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
        binding = ActivityTransactionDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firestore
        db = Firebase.firestore

        // Get transaction type from intent
        transactionType = intent.getStringExtra("TRANSACTION_TYPE") ?: "Cash In"

        // Set up UI
        setupUI()

        // Set up payment method dropdown
        setupPaymentMethodDropdown()

        // Set up source of funds dropdown with dynamic label
        setupSourceOfFundsDropdown()

        // Set up listeners
        setupListeners()
    }

    // ============================================================================
    // END OF PART 2: LIFECYCLE METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 3: UI SETUP METHODS
    // ============================================================================

    private fun setupUI() {
        // Set title based on transaction type (remove "Transaction" word for payments and services)
        val titleText = when (transactionType) {
            "Skyro Payment" -> "Skyro Payment"
            "Home Credit Payment" -> "Home Credit Payment"
            "Mobile Loading Service" -> "Mobile Loading Service"
            "Misc Payment" -> "Misc Payment"
            else -> "$transactionType Transaction"
        }
        binding.titleText.text = titleText

        // Set transaction type color
        when (transactionType) {
            "Cash In" -> {
                binding.titleText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            }
            "Cash Out" -> {
                binding.titleText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            }
            "Mobile Loading Service" -> {
                // Purple color for Mobile Loading
                binding.titleText.setTextColor(ContextCompat.getColor(this, R.color.mobile_loading_purple))
            }
            "Skyro Payment" -> {
                // Blue color for Skyro
                binding.titleText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
            }
            "Home Credit Payment" -> {
                // Red color for Home Credit
                binding.titleText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            }
            "Misc Payment" -> {
                // Gray color for Misc Payment
                binding.titleText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            }
        }

        // Show/Hide Paid with field based on transaction type
        when (transactionType) {
            "Cash In", "Mobile Loading Service", "Skyro Payment", "Home Credit Payment" -> {
                // Show Paid with field
                binding.paymentMethodCheckbox.visibility = View.VISIBLE
                binding.paymentMethodLayout.visibility = View.VISIBLE
                // Find the TextView label for "Paid with" and show it
                val parent = binding.paymentMethodCheckbox.parent as? android.view.ViewGroup
                parent?.visibility = View.VISIBLE
            }
            "Cash Out", "Misc Payment" -> {
                // Hide Paid with field
                binding.paymentMethodCheckbox.visibility = View.GONE
                binding.paymentMethodLayout.visibility = View.GONE
                // Find the TextView label for "Paid with" and hide it
                val parent = binding.paymentMethodCheckbox.parent as? android.view.ViewGroup
                parent?.visibility = View.GONE
            }
        }

        // Handle fee options based on transaction type
        when (transactionType) {
            "Skyro Payment", "Home Credit Payment", "Mobile Loading Service" -> {
                // For Skyro, Home Credit, and Mobile Loading: Only Add Fee is enabled
                binding.radioAddToAmount.isChecked = true
                binding.radioDeductFromAmount.isEnabled = false
                binding.radioFree.isEnabled = false

                // Set initial fee (will be calculated for Mobile Loading)
                if (transactionType == "Mobile Loading Service") {
                    binding.feeInput.setText("₱0")
                } else {
                    // Fixed fee of 15 pesos for Skyro and Home Credit
                    transactionFee = 15.0
                    binding.feeInput.setText(formatCurrency(transactionFee))
                }
            }
            "Misc Payment" -> {
                // For Misc Payment: No fee, always free
                binding.radioFree.isChecked = true
                binding.radioAddToAmount.isEnabled = false
                binding.radioDeductFromAmount.isEnabled = false
                binding.radioFree.isEnabled = false

                // Set fee to 0
                transactionFee = 0.0
                binding.feeInput.setText("₱0")

                // Update notes hint to show it's required
                binding.notesInputLayout.hint = "Description (Required)"
            }
            else -> {
                // For Cash In/Out: All options enabled
                binding.radioAddToAmount.isChecked = true
                binding.radioDeductFromAmount.isEnabled = true
                binding.radioFree.isEnabled = true

                // Set initial fee
                binding.feeInput.setText("₱0")
            }
        }

        // Disable fee and customer pays fields (read-only)
        binding.feeInput.isEnabled = false
        binding.customerPaysInput.isEnabled = false

        // Set initial customer pays
        binding.customerPaysInput.setText("₱0")

        // Set initial sequence number display - will be updated when transaction is saved
        binding.sequenceNumber.text = "#---"
    }

    private fun setupPaymentMethodDropdown() {
        // Payment method options
        val paymentMethods = arrayOf("Cash", "GCash", "PayMaya", "Others")

        // Create adapter
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, paymentMethods)

        // Set adapter to the AutoCompleteTextView
        binding.paymentMethodDropdown.setAdapter(adapter)

        // Set default selection (Cash)
        binding.paymentMethodDropdown.setText(paymentMethods[0], false)

        // Initially disabled with gray color
        binding.paymentMethodDropdown.isEnabled = false
        binding.paymentMethodDropdown.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))

        // Add item click listener for color changes
        binding.paymentMethodDropdown.setOnItemClickListener { _, _, position, _ ->
            updatePaymentMethodColor(paymentMethods[position])
        }
    }

    private fun updatePaymentMethodColor(method: String) {
        val color = when (method) {
            "Cash" -> ContextCompat.getColor(this, R.color.cash_dark_green)
            "GCash" -> ContextCompat.getColor(this, android.R.color.holo_blue_dark)
            "PayMaya" -> ContextCompat.getColor(this, android.R.color.holo_green_light)
            "Others" -> ContextCompat.getColor(this, android.R.color.holo_red_dark)
            else -> ContextCompat.getColor(this, android.R.color.black)
        }

        binding.paymentMethodDropdown.setTextColor(color)
    }

    private fun setupSourceOfFundsDropdown() {
        // Different sources based on transaction type
        val sources = when (transactionType) {
            "Mobile Loading Service" -> arrayOf("GCash", "Reloader SIM")
            "Misc Payment" -> arrayOf("Cash", "GCash", "PayMaya", "Others")
            else -> arrayOf("GCash", "PayMaya", "Others")
        }

        // Change label based on transaction type
        when (transactionType) {
            "Cash In" -> {
                binding.sourceOfFundsLabel.text = "Transfer To"
            }
            "Cash Out" -> {
                binding.sourceOfFundsLabel.text = "Source of Funds"
            }
            "Skyro Payment", "Home Credit Payment" -> {
                binding.sourceOfFundsLabel.text = "Payment Method"
            }
            "Mobile Loading Service" -> {
                binding.sourceOfFundsLabel.text = "Load Source"
            }
            "Misc Payment" -> {
                binding.sourceOfFundsLabel.text = "Payment Method"
            }
            else -> {
                binding.sourceOfFundsLabel.text = "Source of Funds"
            }
        }

        // Create adapter
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, sources)

        // Set adapter to the AutoCompleteTextView
        binding.sourceOfFundsDropdown.setAdapter(adapter)

        // Set default selection
        binding.sourceOfFundsDropdown.setText(sources[0], false)

        // Add item click listener for color changes
        binding.sourceOfFundsDropdown.setOnItemClickListener { _, _, position, _ ->
            updateSourceOfFundsColor(sources[position])
        }

        // Set initial color
        updateSourceOfFundsColor(sources[0])
    }

    private fun updateSourceOfFundsColor(source: String) {
        val color = when (source) {
            "Cash" -> ContextCompat.getColor(this, R.color.cash_dark_green)
            "GCash" -> ContextCompat.getColor(this, android.R.color.holo_blue_dark)
            "PayMaya" -> ContextCompat.getColor(this, android.R.color.holo_green_light)
            "Reloader SIM" -> ContextCompat.getColor(this, R.color.mobile_loading_purple)
            "Others" -> ContextCompat.getColor(this, android.R.color.holo_red_dark)
            else -> ContextCompat.getColor(this, android.R.color.black)
        }

        binding.sourceOfFundsDropdown.setTextColor(color)
    }

    private fun setupListeners() {
        // Payment method checkbox listener
        binding.paymentMethodCheckbox.setOnCheckedChangeListener { _, isChecked ->
            binding.paymentMethodDropdown.isEnabled = isChecked
            if (!isChecked) {
                // Reset to Cash when disabled with gray color
                binding.paymentMethodDropdown.setText("Cash", false)
                binding.paymentMethodDropdown.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            } else {
                // Apply color when enabled
                updatePaymentMethodColor(binding.paymentMethodDropdown.text.toString())
            }
        }

        // Amount field text watcher
        binding.amountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val input = s.toString()
                if (input.isNotEmpty()) {
                    try {
                        amount = input.toDouble()
                        calculateFeeAndTotal()
                    } catch (e: NumberFormatException) {
                        amount = 0.0
                        transactionFee = 0.0
                        customerPays = 0.0
                        updateDisplay()
                    }
                } else {
                    amount = 0.0
                    if (transactionType != "Skyro Payment" && transactionType != "Home Credit Payment") {
                        transactionFee = 0.0
                    }
                    customerPays = 0.0
                    updateDisplay()
                }
            }
        })

        // Radio button listeners for fee options
        binding.radioAddToAmount.setOnClickListener {
            // Only allow if not Misc Payment
            if (transactionType != "Misc Payment") {
                if (amount > 0) {
                    calculateFeeAndTotal()
                }
                binding.notesInputLayout.hint = "Add transaction details..."
            }
        }

        binding.radioDeductFromAmount.setOnClickListener {
            // Only allow if Cash In/Out
            if (transactionType == "Cash In" || transactionType == "Cash Out") {
                if (amount > 0) {
                    calculateFeeAndTotal()
                }
                binding.notesInputLayout.hint = "Add transaction details..."
            }
        }

        binding.radioFree.setOnClickListener {
            // Only allow if Cash In/Out
            if (transactionType == "Cash In" || transactionType == "Cash Out") {
                if (amount > 0) {
                    calculateFeeAndTotal()
                }
                binding.notesInputLayout.hint = "Reason for free transaction (Required)"
            } else if (transactionType == "Misc Payment") {
                // Always free for Misc Payment
                binding.notesInputLayout.hint = "Description (Required)"
            }
        }

        // Save button listener
        binding.saveButton.setOnClickListener {
            saveTransaction()
        }

        // Cancel button listener
        binding.cancelButton.setOnClickListener {
            finish()
        }
    }

    // ============================================================================
    // END OF PART 3: UI SETUP METHODS
    // ============================================================================



    // ============================================================================
    // START OF PART 4: FEE CALCULATION METHODS
    // ============================================================================

    private fun calculateFee(baseAmount: Double): Double {
        // Misc Payment has no fee
        if (transactionType == "Misc Payment") {
            return 0.0
        }

        // Mobile Loading Service fee structure
        if (transactionType == "Mobile Loading Service") {
            return when {
                baseAmount <= 0 -> 0.0
                baseAmount <= 99 -> 5.0
                baseAmount <= 499 -> 10.0
                baseAmount <= 999 -> 20.0
                baseAmount >= 1000 -> 30.0
                else -> 0.0
            }
        }

        // Fixed fee for Skyro and Home Credit
        if (transactionType == "Skyro Payment" || transactionType == "Home Credit Payment") {
            return 15.0
        }

        // If Free option is selected, no fee
        if (binding.radioFree.isChecked) {
            return 0.0
        }

        // Fee calculation for Cash In/Out based on the provided table
        return when {
            baseAmount <= 0 -> 0.0
            baseAmount <= 100 -> 5.0
            baseAmount <= 500 -> 10.0
            baseAmount <= 1000 -> 15.0
            baseAmount <= 1500 -> 20.0
            baseAmount <= 2000 -> 30.0
            baseAmount <= 2500 -> 40.0
            baseAmount <= 3000 -> 50.0
            baseAmount <= 3500 -> 60.0
            baseAmount <= 4000 -> 70.0
            baseAmount <= 4500 -> 80.0
            baseAmount <= 5000 -> 90.0
            baseAmount <= 5500 -> 100.0
            baseAmount <= 6000 -> 110.0
            baseAmount <= 6500 -> 120.0
            baseAmount <= 7000 -> 130.0
            baseAmount <= 7500 -> 140.0
            baseAmount <= 8000 -> 150.0
            baseAmount <= 8500 -> 160.0
            baseAmount <= 9000 -> 170.0
            baseAmount <= 9500 -> 180.0
            baseAmount <= 10000 -> 190.0
            baseAmount <= 10500 -> 200.0
            baseAmount <= 11000 -> 210.0
            baseAmount <= 11500 -> 220.0
            baseAmount <= 12000 -> 230.0
            baseAmount <= 12500 -> 240.0
            baseAmount <= 13000 -> 250.0
            baseAmount <= 13500 -> 260.0
            baseAmount <= 14000 -> 270.0
            baseAmount <= 14500 -> 280.0
            baseAmount <= 15000 -> 290.0
            baseAmount <= 15500 -> 300.0
            baseAmount <= 16000 -> 310.0
            baseAmount <= 16500 -> 320.0
            baseAmount <= 17000 -> 330.0
            baseAmount <= 17500 -> 340.0
            baseAmount <= 18000 -> 350.0
            baseAmount <= 18500 -> 360.0
            baseAmount <= 19000 -> 370.0
            baseAmount <= 19500 -> 380.0
            baseAmount <= 20000 -> 390.0
            else -> {
                // For amounts above 20000, add 10 pesos per 500 increment
                val baseCharge = 390.0
                val excess = baseAmount - 20000
                val additionalCharge = (kotlin.math.ceil(excess / 500) * 10)
                baseCharge + additionalCharge
            }
        }
    }

    private fun calculateFeeAndTotal() {
        if (amount <= 0) {
            // For fixed fee services, still show fee even with 0 amount
            if (transactionType == "Skyro Payment" || transactionType == "Home Credit Payment") {
                transactionFee = 15.0
            } else {
                transactionFee = 0.0
            }
            customerPays = 0.0
            updateDisplay()
            return
        }

        // Calculate fee based on transaction type
        transactionFee = calculateFee(amount)

        // Calculate what customer pays based on selected option
        when {
            binding.radioAddToAmount.isChecked -> {
                // Customer pays amount + fee
                customerPays = amount + transactionFee
            }
            binding.radioDeductFromAmount.isChecked -> {
                // Customer pays only the amount (fee is deducted from what they receive)
                customerPays = amount
            }
            binding.radioFree.isChecked -> {
                // No fee, customer pays exact amount
                transactionFee = 0.0
                customerPays = amount
            }
        }

        updateDisplay()
    }

    private fun updateDisplay() {

        // ============================================================================
        // END OF PART 4: FEE CALCULATION METHODS
        // ============================================================================



        // ============================================================================
        // START OF PART 5: UI UPDATE METHODS
        // ============================================================================

        // Update fee display
        binding.feeInput.setText(formatCurrency(transactionFee))

        // Update customer pays field
        binding.customerPaysInput.setText(formatCurrency(customerPays))
    }


    // ============================================================================
    // END OF PART 5: UI UPDATE METHODS
    // ============================================================================



    // ============================================================================
    // START OF PART 6: HELPER METHODS
    // ============================================================================

    private fun formatCurrency(amount: Double): String {
        return "₱${String.format("%,.2f", amount)}"
    }

    private fun showMessage(message: String, isError: Boolean) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ============================================================================
    // END OF PART 6: HELPER METHODS
    // ============================================================================

    // ============================================================================
// START OF PART 7: TRANSACTION SAVE METHODS
// ============================================================================

    private fun saveTransaction() {
        // Validate input
        if (amount <= 0) {
            showMessage("Please enter a valid amount", true)
            return
        }

        // Get notes
        val notes = binding.notesInput.text.toString().trim()

        // Get payment method
        val paymentMethod = if (binding.paymentMethodCheckbox.isChecked) {
            binding.paymentMethodDropdown.text.toString()
        } else {
            null // Not specified if checkbox is unchecked
        }

        // Check if notes are required based on transaction type
        when (transactionType) {
            "Misc Payment" -> {
                // Notes are ALWAYS required for Misc Payment
                if (notes.isEmpty()) {
                    showMessage("Please provide a description for this payment", true)
                    binding.notesInput.requestFocus()
                    return
                }
            }
            "Cash In", "Cash Out" -> {
                // Notes required only if Free option is selected
                if (binding.radioFree.isChecked && notes.isEmpty()) {
                    showMessage("Please provide a reason for free transaction", true)
                    binding.notesInput.requestFocus()
                    return
                }
            }
        }

        // Get source of funds / payment method
        val sourceOfFunds = binding.sourceOfFundsDropdown.text.toString()

        // Calculate actual amount customer receives
        val customerReceives = when {
            binding.radioAddToAmount.isChecked -> amount  // Customer receives full amount
            binding.radioDeductFromAmount.isChecked -> amount - transactionFee  // Minus fee
            binding.radioFree.isChecked -> amount  // Full amount (no fee)
            else -> amount
        }

        // Determine fee option text
        val feeOption = when {
            binding.radioAddToAmount.isChecked -> "Add Fee"
            binding.radioDeductFromAmount.isChecked -> "Deduct Fee"
            binding.radioFree.isChecked -> "Free"
            else -> "Add Fee"
        }

        // Show confirmation dialog
        showConfirmationDialog(
            transactionType = transactionType,
            amount = amount,
            fee = transactionFee,
            feeOption = feeOption,
            customerReceives = customerReceives,
            sourceOfFunds = sourceOfFunds,
            paymentMethod = paymentMethod,
            notes = notes
        )
    }

    private fun showConfirmationDialog(
        transactionType: String,
        amount: Double,
        fee: Double,
        feeOption: String,
        customerReceives: Double,
        sourceOfFunds: String,
        paymentMethod: String?,
        notes: String
    ) {
        // Inflate the dialog layout
        val dialogView = layoutInflater.inflate(R.layout.dialog_transaction_confirmation, null)

        // Create the dialog
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // Get dialog views using the inflated dialogView
        val dialogTransactionType: android.widget.TextView = dialogView.findViewById(R.id.dialogTransactionType)
        val dialogAmount: android.widget.TextView = dialogView.findViewById(R.id.dialogAmount)
        val dialogFee: android.widget.TextView = dialogView.findViewById(R.id.dialogFee)
        val dialogFeeOption: android.widget.TextView = dialogView.findViewById(R.id.dialogFeeOption)
        val dialogTotal: android.widget.TextView = dialogView.findViewById(R.id.dialogTotal)
        val paidWithRow: android.widget.LinearLayout = dialogView.findViewById(R.id.paidWithRow)
        val dialogPaidWith: android.widget.TextView = dialogView.findViewById(R.id.dialogPaidWith)
        val dialogMessage: android.widget.TextView = dialogView.findViewById(R.id.dialogMessage)
        val dialogCancelButton: android.widget.Button = dialogView.findViewById(R.id.dialogCancelButton)
        val dialogConfirmButton: android.widget.Button = dialogView.findViewById(R.id.dialogConfirmButton)

        // Set transaction type with color
        dialogTransactionType.text = transactionType
        val transactionTypeColor = when (transactionType) {
            "Cash In" -> ContextCompat.getColor(this, android.R.color.holo_green_dark)
            "Cash Out" -> ContextCompat.getColor(this, android.R.color.holo_red_dark)
            "Mobile Loading Service" -> ContextCompat.getColor(this, R.color.mobile_loading_purple)
            "Skyro Payment" -> ContextCompat.getColor(this, android.R.color.holo_blue_dark)
            "Home Credit Payment" -> ContextCompat.getColor(this, android.R.color.holo_red_dark)
            "Misc Payment" -> ContextCompat.getColor(this, android.R.color.darker_gray)
            else -> ContextCompat.getColor(this, android.R.color.black)
        }
        dialogTransactionType.setTextColor(transactionTypeColor)

        // Calculate dynamic margin based on specific transaction types
        val marginInDp = when (transactionType) {
            "Cash In" -> 135
            "Cash Out" -> 130
            "Misc Payment" -> 110
            "Skyro Payment" -> 90
            "Mobile Loading Service" -> 60
            "Home Credit Payment" -> 60
            else -> 95
        }

        // Convert dp to pixels
        val marginInPx = (marginInDp * resources.displayMetrics.density).toInt()

        // Apply dynamic margin to transaction type
        val layoutParams = dialogTransactionType.layoutParams as android.view.ViewGroup.MarginLayoutParams
        layoutParams.marginEnd = marginInPx
        dialogTransactionType.layoutParams = layoutParams

        // Set amount
        dialogAmount.text = formatCurrency(amount)

        // Set fee
        dialogFee.text = formatCurrency(fee)

        // Set fee option
        dialogFeeOption.text = feeOption

        // Calculate customer pays (Total)
        val customerPays = when (feeOption) {
            "Add Fee" -> amount + fee
            "Deduct Fee" -> amount
            "Free" -> amount
            else -> amount + fee
        }

        // Set total (customer pays) - BLACK and BOLD
        dialogTotal.text = formatCurrency(customerPays)
        dialogTotal.setTextColor(ContextCompat.getColor(this, android.R.color.black))

        // Handle "Paid With" field based on transaction type
        when (transactionType) {
            "Cash In", "Mobile Loading Service", "Skyro Payment", "Home Credit Payment" -> {
                // For these transactions, show what customer paid with (Cash by default or selected payment method)
                paidWithRow.visibility = View.VISIBLE
                val actualPaidWith = if (binding.paymentMethodCheckbox.isChecked && paymentMethod != null) {
                    paymentMethod
                } else {
                    "Cash"  // Default to Cash if checkbox not checked
                }
                dialogPaidWith.text = actualPaidWith

                // Set color for paid with based on the selected payment method
                val paidWithColor = when (actualPaidWith) {
                    "Cash" -> ContextCompat.getColor(this, R.color.cash_dark_green)
                    "GCash" -> ContextCompat.getColor(this, android.R.color.holo_blue_dark)
                    "PayMaya" -> ContextCompat.getColor(this, android.R.color.holo_green_light)
                    "Others" -> ContextCompat.getColor(this, android.R.color.holo_red_dark)
                    else -> ContextCompat.getColor(this, android.R.color.black)
                }
                dialogPaidWith.setTextColor(paidWithColor)
            }
            "Cash Out" -> {
                // For Cash Out, Source of Funds is how customer pays (they receive cash)
                paidWithRow.visibility = View.VISIBLE
                dialogPaidWith.text = sourceOfFunds  // This is how they pay (GCash, PayMaya, Others)

                // Set color based on source of funds
                val paidWithColor = when (sourceOfFunds) {
                    "GCash" -> ContextCompat.getColor(this, android.R.color.holo_blue_dark)
                    "PayMaya" -> ContextCompat.getColor(this, android.R.color.holo_green_light)
                    "Others" -> ContextCompat.getColor(this, android.R.color.holo_red_dark)
                    else -> ContextCompat.getColor(this, android.R.color.black)
                }
                dialogPaidWith.setTextColor(paidWithColor)
            }
            "Misc Payment" -> {
                // For Misc Payment, Payment Method is how customer pays
                paidWithRow.visibility = View.VISIBLE
                dialogPaidWith.text = sourceOfFunds  // This is the payment method (Cash, GCash, PayMaya, Others)

                // Set color based on payment method
                val paidWithColor = when (sourceOfFunds) {
                    "Cash" -> ContextCompat.getColor(this, R.color.cash_dark_green)
                    "GCash" -> ContextCompat.getColor(this, android.R.color.holo_blue_dark)
                    "PayMaya" -> ContextCompat.getColor(this, android.R.color.holo_green_light)
                    "Others" -> ContextCompat.getColor(this, android.R.color.holo_red_dark)
                    else -> ContextCompat.getColor(this, android.R.color.black)
                }
                dialogPaidWith.setTextColor(paidWithColor)
            }
        }

        // Build the customer receives message based on transaction type
        when (transactionType) {
            "Cash Out" -> {
                // For Cash Out: Customer receives cash amount
                val messageText = android.text.SpannableStringBuilder()

                // "Customer receives " in black
                val part1 = "Customer receives "
                messageText.append(part1)
                messageText.setSpan(
                    android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, android.R.color.black)),
                    0,
                    part1.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // Amount in dark green
                val startAmount = messageText.length
                messageText.append(formatCurrency(customerReceives))
                messageText.setSpan(
                    android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, R.color.cash_dark_green)),
                    startAmount,
                    messageText.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // " in " in black
                val part2 = " in "
                val startIn = messageText.length
                messageText.append(part2)
                messageText.setSpan(
                    android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, android.R.color.black)),
                    startIn,
                    messageText.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // "Cash" in green
                val startCash = messageText.length
                messageText.append("Cash")
                messageText.setSpan(
                    android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, R.color.cash_dark_green)),
                    startCash,
                    messageText.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                dialogMessage.text = messageText
            }
            "Misc Payment" -> {
                // For Misc Payment: Just show payment received message
                val messageText = android.text.SpannableStringBuilder()

                // "Payment received: " in black
                val part1 = "Payment received: "
                messageText.append(part1)
                messageText.setSpan(
                    android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, android.R.color.black)),
                    0,
                    part1.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // Amount in dark green
                val startAmount = messageText.length
                messageText.append(formatCurrency(amount))
                messageText.setSpan(
                    android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, R.color.cash_dark_green)),
                    startAmount,
                    messageText.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                dialogMessage.text = messageText
            }
            "Skyro Payment" -> {
                // For Skyro: "Skyro payment ₱X.XX paid via [source]"
                val messageText = android.text.SpannableStringBuilder()

                // "Skyro payment " in black
                val part1 = "Skyro payment "
                messageText.append(part1)
                messageText.setSpan(
                    android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, android.R.color.black)),
                    0,
                    part1.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // Amount in dark green
                val startAmount = messageText.length
                messageText.append(formatCurrency(amount))
                messageText.setSpan(
                    android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, R.color.cash_dark_green)),
                    startAmount,
                    messageText.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // " paid via " in black
                val part2 = " paid via "
                val startPaidVia = messageText.length
                messageText.append(part2)
                messageText.setSpan(
                    android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, android.R.color.black)),
                    startPaidVia,
                    messageText.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // Source in its specific color
                val sourceColor = when (sourceOfFunds) {
                    "GCash" -> ContextCompat.getColor(this, android.R.color.holo_blue_dark)
                    "PayMaya" -> ContextCompat.getColor(this, android.R.color.holo_green_light)
                    "Others" -> ContextCompat.getColor(this, android.R.color.holo_red_dark)
                    else -> ContextCompat.getColor(this, android.R.color.black)
                }

                val startSource = messageText.length
                messageText.append(sourceOfFunds)
                messageText.setSpan(
                    android.text.style.ForegroundColorSpan(sourceColor),
                    startSource,
                    messageText.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                dialogMessage.text = messageText
            }
            "Home Credit Payment" -> {
                // For Home Credit: "Home Credit payment ₱X.XX paid via [source]"
                val messageText = android.text.SpannableStringBuilder()

                // "Home Credit payment " in black
                val part1 = "Home Credit payment "
                messageText.append(part1)
                messageText.setSpan(
                    android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, android.R.color.black)),
                    0,
                    part1.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // Amount in dark green
                val startAmount = messageText.length
                messageText.append(formatCurrency(amount))
                messageText.setSpan(
                    android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, R.color.cash_dark_green)),
                    startAmount,
                    messageText.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // " paid via " in black
                val part2 = " paid via "
                val startPaidVia = messageText.length
                messageText.append(part2)
                messageText.setSpan(
                    android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, android.R.color.black)),
                    startPaidVia,
                    messageText.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // Source in its specific color
                val sourceColor = when (sourceOfFunds) {
                    "GCash" -> ContextCompat.getColor(this, android.R.color.holo_blue_dark)
                    "PayMaya" -> ContextCompat.getColor(this, android.R.color.holo_green_light)
                    "Others" -> ContextCompat.getColor(this, android.R.color.holo_red_dark)
                    else -> ContextCompat.getColor(this, android.R.color.black)
                }

                val startSource = messageText.length
                messageText.append(sourceOfFunds)
                messageText.setSpan(
                    android.text.style.ForegroundColorSpan(sourceColor),
                    startSource,
                    messageText.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                dialogMessage.text = messageText
            }
            else -> {
                // For Cash In and Mobile Loading Service
                val amountText = formatCurrency(customerReceives)
                val sourceColor = when (sourceOfFunds) {
                    "Cash" -> ContextCompat.getColor(this, R.color.cash_dark_green)
                    "GCash" -> ContextCompat.getColor(this, android.R.color.holo_blue_dark)
                    "PayMaya" -> ContextCompat.getColor(this, android.R.color.holo_green_light)
                    "Reloader SIM" -> ContextCompat.getColor(this, R.color.mobile_loading_purple)
                    "Others" -> ContextCompat.getColor(this, android.R.color.holo_red_dark)
                    else -> ContextCompat.getColor(this, android.R.color.black)
                }

                // Build the styled message
                val messageText = android.text.SpannableStringBuilder()

                // "Customer receives " in black
                val part1 = "Customer receives "
                messageText.append(part1)
                messageText.setSpan(
                    android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, android.R.color.black)),
                    0,
                    part1.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // Amount in dark green
                val startAmount = messageText.length
                messageText.append(amountText)
                messageText.setSpan(
                    android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, R.color.cash_dark_green)),
                    startAmount,
                    messageText.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // " via " in black
                val part2 = " via "
                val startVia = messageText.length
                messageText.append(part2)
                messageText.setSpan(
                    android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, android.R.color.black)),
                    startVia,
                    messageText.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // Source in its specific color
                val startSource = messageText.length
                messageText.append(sourceOfFunds)
                messageText.setSpan(
                    android.text.style.ForegroundColorSpan(sourceColor),
                    startSource,
                    messageText.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                dialogMessage.text = messageText
            }
        }

        // Cancel button
        dialogCancelButton.setOnClickListener {
            dialog.dismiss()
        }

        // Confirm button
        dialogConfirmButton.setOnClickListener {
            dialog.dismiss()
            proceedWithSave(
                transactionType = transactionType,
                amount = amount,
                fee = fee,
                feeOption = feeOption,
                customerReceives = customerReceives,
                sourceOfFunds = sourceOfFunds,
                paymentMethod = paymentMethod,
                notes = notes
            )
        }

        // Show the dialog
        dialog.show()
    }

    private fun proceedWithSave(
        transactionType: String,
        amount: Double,
        fee: Double,
        feeOption: String,
        customerReceives: Double,
        sourceOfFunds: String,
        paymentMethod: String?,
        notes: String
    ) {
        // Show progress
        binding.progressBar.visibility = View.VISIBLE
        binding.saveButton.isEnabled = false

        // Calculate customer pays
        val customerPays = when (feeOption) {
            "Add Fee" -> amount + fee
            "Deduct Fee" -> amount  // Customer pays the amount, receives less
            "Free" -> amount
            else -> amount + fee
        }

        // Calculate what customer actually receives
        val actualCustomerReceives = when (feeOption) {
            "Add Fee" -> amount  // Customer receives full amount, pays extra for fee
            "Deduct Fee" -> amount - fee  // Customer receives amount minus fee
            "Free" -> amount  // Customer receives full amount
            else -> amount
        }

        // Process transaction in ledger system
        // Pass the actual amount customer receives for proper ledger entries
        val transactionNumber = TransactionProcessor.processTransaction(
            transactionType = transactionType,
            amount = actualCustomerReceives,  // This should be what customer receives
            customerPays = customerPays,
            sourceOfFunds = sourceOfFunds,
            paidWith = paymentMethod,
            isPaidWithChecked = binding.paymentMethodCheckbox.isChecked,
            notes = notes
        )

        // COMMENTED OUT FIRESTORE SAVING FOR NOW
        /*
        // Create transaction object for Firebase (keeping existing functionality)
        val transaction = hashMapOf(
            "transactionType" to transactionType,
            "sequenceNumber" to transactionNumber,  // Use the ledger transaction number
            "amount" to amount,
            "fee" to fee,
            "customerPays" to customerPays,
            "customerReceives" to customerReceives,
            "feeOption" to feeOption,
            "sourceOfFunds" to sourceOfFunds,
            "notes" to notes,
            "timestamp" to System.currentTimeMillis(),
            "date" to SimpleDateFormat("M/d/yyyy", Locale.US).format(Date()),
            "time" to SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
            "status" to "completed"
        )

        // Add payment method if specified
        if (paymentMethod != null) {
            transaction["paymentMethod"] = paymentMethod
        }

        // Save to Firestore
        db.collection("transactions")
            .add(transaction)
            .addOnSuccessListener { documentReference ->
                binding.progressBar.visibility = View.GONE
                showMessage("Transaction saved successfully!", false)

                // Clear fields and finish after a delay
                binding.root.postDelayed({
                    finish()
                }, 1500)
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                binding.saveButton.isEnabled = true
                showMessage("Error saving transaction: ${e.message}", true)
            }
        */

        // FOR TESTING: Just save to ledger without Firestore
        // Simulate success after processing ledger
        binding.progressBar.visibility = View.GONE
        showMessage("Transaction #$transactionNumber saved to ledger!", false)

        // Clear fields and finish after a delay
        binding.root.postDelayed({
            finish()
        }, 1000)
    }

// ============================================================================
// END OF PART 7: TRANSACTION SAVE METHODS
// ============================================================================


    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ============================================================================
    // END OF PART 2: LIFECYCLE METHODS
    // ============================================================================


}