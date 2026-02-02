package com.techcity.techcitysuite

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityInHousePaymentBinding
import com.techcity.techcitysuite.databinding.ItemPaymentHistoryBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class InHousePaymentActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityInHousePaymentBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Intent extras
    private var documentId: String = ""
    private var source: String = ""  // "device" or "accessory"

    // Collection name based on source
    private val collectionName: String
        get() = if (source == "device") "device_transactions" else "accessory_transactions"

    // Item data
    private var itemName: String = ""
    private var itemDetails: String = ""
    private var dateSold: String = ""
    private var customerName: String = ""  // Customer name for In-House

    // Transaction details
    private var originalPrice: Double = 0.0
    private var discount: Double = 0.0
    private var finalPrice: Double = 0.0
    private var downpaymentAmount: Double = 0.0
    private var downpaymentSource: String = ""
    private var interestPercent: Double = 0.0
    private var interestAmount: Double = 0.0  // Stored in Firebase

    // Balance data
    private var originalBalance: Double = 0.0
    private var remainingBalance: Double = 0.0
    private var monthlyAmount: Double = 0.0
    private var monthsToPay: Int = 0
    private var payments: MutableList<PaymentRecord> = mutableListOf()

    // Payment sources
    private val paymentSources = listOf("Cash", "GCash", "PayMaya", "Others")

    /**
     * Data class for payment records
     * This structure is used for both reading from and writing to Firebase
     * The timestamp field ensures uniqueness for arrayUnion operations
     */
    data class PaymentRecord(
        val date: String,
        val amount: Double,
        val remainingAfter: Double,
        val source: String = "Cash",
        val timestamp: Long = 0L  // Unique identifier to prevent arrayUnion deduplication
    )

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================


    // ============================================================================
    // START OF PART 2: LIFECYCLE METHODS
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityInHousePaymentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = Firebase.firestore

        // Get intent extras
        documentId = intent.getStringExtra("documentId") ?: ""
        source = intent.getStringExtra("source") ?: "device"

        if (documentId.isEmpty()) {
            showMessage("Error: No document ID provided", true)
            finish()
            return
        }

        setupUI()
        loadTransactionData()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ============================================================================
    // END OF PART 2: LIFECYCLE METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 3: UI SETUP METHODS
    // ============================================================================

    private fun setupUI() {
        // Back button (now a TextView)
        binding.backButton.setOnClickListener {
            finish()
        }

        // Cancel button
        binding.cancelButton.setOnClickListener {
            finish()
        }

        // Save payment button
        binding.savePaymentButton.setOnClickListener {
            validateAndSavePayment()
        }

        // Quick amount buttons
        binding.monthlyAmountButton.setOnClickListener {
            binding.paymentAmountInput.setText(formatAmountForInput(monthlyAmount))
        }

        binding.remainingAmountButton.setOnClickListener {
            binding.paymentAmountInput.setText(formatAmountForInput(remainingBalance))
        }

        // Payment source dropdown
        val sourceAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, paymentSources)
        binding.paymentSourceDropdown.setAdapter(sourceAdapter)
        binding.paymentSourceDropdown.setText("Cash", false)

        // Set initial color for Cash
        updatePaymentSourceColor("Cash")

        // Add listener for dropdown selection changes
        binding.paymentSourceDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedSource = paymentSources[position]
            updatePaymentSourceColor(selectedSource)
        }

        // Source badge
        binding.sourceBadge.text = if (source == "device") "Device" else "Accessory"
        binding.sourceBadge.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (source == "device") R.color.techcity_blue else R.color.purple
        )
    }

    /**
     * Updates the payment source dropdown color based on selection
     * Uses the same colors as DeviceTransactionDetailsActivity
     */
    private fun updatePaymentSourceColor(paymentSource: String) {
        val colorRes = when (paymentSource.lowercase()) {
            "cash" -> R.color.cash_dark_green
            "gcash" -> R.color.gcash_blue
            "paymaya" -> R.color.paymaya_green
            "others" -> R.color.others_gray
            else -> R.color.gray
        }

        val color = ContextCompat.getColor(this, colorRes)

        // Update dropdown text color
        binding.paymentSourceDropdown.setTextColor(color)

        // Update the TextInputLayout box stroke color
        binding.paymentSourceLayout.boxStrokeColor = color
        binding.paymentSourceLayout.hintTextColor = ColorStateList.valueOf(color)
    }

    // ============================================================================
    // END OF PART 3: UI SETUP METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 4: DATA LOADING METHODS
    // ============================================================================

    private fun loadTransactionData() {
        binding.progressOverlay.visibility = View.VISIBLE

        scope.launch {
            try {
                val document = withContext(Dispatchers.IO) {
                    db.collection(collectionName)
                        .document(documentId)
                        .get()
                        .await()
                }

                val data = document.data
                if (data == null) {
                    withContext(Dispatchers.Main) {
                        binding.progressOverlay.visibility = View.GONE
                        showMessage("Error: Transaction not found", true)
                        finish()
                    }
                    return@launch
                }

                // Parse item details based on source
                if (source == "device") {
                    val manufacturer = data["manufacturer"] as? String ?: ""
                    val model = data["model"] as? String ?: ""
                    val ram = data["ram"] as? String ?: ""
                    val storage = data["storage"] as? String ?: ""
                    val color = data["color"] as? String ?: ""

                    itemName = if (manufacturer.isNotEmpty()) "$manufacturer $model" else model
                    itemDetails = if (ram.isNotEmpty() && storage.isNotEmpty()) {
                        "$ram + $storage - $color"
                    } else {
                        ""
                    }
                } else {
                    itemName = data["accessoryName"] as? String ?: ""
                    itemDetails = ""
                }

                dateSold = data["dateSold"] as? String ?: ""

                // Parse transaction details
                // Get final price first (this is usually reliable)
                finalPrice = (data["finalPrice"] as? Number)?.toDouble() ?: 0.0

                // Get discount - field name is "discountAmount"
                discount = (data["discountAmount"] as? Number)?.toDouble()
                    ?: (data["discount"] as? Number)?.toDouble()
                            ?: 0.0

                // Original price is not stored directly - calculate from finalPrice + discount
                originalPrice = (data["retailPrice"] as? Number)?.toDouble()
                    ?: (data["originalPrice"] as? Number)?.toDouble()
                            ?: 0.0

                // If no original price stored, calculate it
                if (originalPrice == 0.0 && discount > 0) {
                    originalPrice = finalPrice + discount
                }

                // If still no original price and no discount, use final price
                if (originalPrice == 0.0) {
                    originalPrice = finalPrice
                }

                // Parse In-House Installment data with backward compatibility
                val inHouseData = data["inHouseInstallment"] as? Map<*, *>
                if (inHouseData != null) {
                    // Customer name
                    customerName = inHouseData["customerName"] as? String ?: ""

                    // Downpayment info
                    downpaymentAmount = (inHouseData["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    downpaymentSource = inHouseData["downpaymentSource"] as? String ?: ""
                    interestPercent = (inHouseData["interestPercent"] as? Number)?.toDouble() ?: 0.0

                    // Interest amount - read from Firebase if stored, otherwise calculate
                    interestAmount = (inHouseData["interestAmount"] as? Number)?.toDouble() ?: 0.0

                    // If interest amount not stored, calculate it
                    if (interestAmount == 0.0 && interestPercent > 0) {
                        val amountAfterDownpayment = finalPrice - downpaymentAmount
                        interestAmount = amountAfterDownpayment * interestPercent / 100
                    }

                    monthlyAmount = (inHouseData["monthlyAmount"] as? Number)?.toDouble() ?: 0.0
                    monthsToPay = (inHouseData["monthsToPay"] as? Number)?.toInt() ?: 0

                    // Parse payments array
                    val paymentsData = inHouseData["payments"] as? List<*>
                    payments.clear()

                    if (paymentsData != null) {
                        for (paymentMap in paymentsData) {
                            val payment = paymentMap as? Map<*, *> ?: continue
                            payments.add(
                                PaymentRecord(
                                    date = payment["date"] as? String ?: "",
                                    amount = (payment["amount"] as? Number)?.toDouble() ?: 0.0,
                                    remainingAfter = (payment["remainingAfter"] as? Number)?.toDouble() ?: 0.0,
                                    source = payment["source"] as? String ?: "Cash",
                                    timestamp = (payment["timestamp"] as? Number)?.toLong() ?: 0L
                                )
                            )
                        }

                        // Sort payments by timestamp (oldest first) for proper display order
                        payments.sortBy { it.timestamp }
                    }

                    // Calculate the CORRECT original balance including interest
                    // Formula: (Final Price - Downpayment) + Interest Amount
                    originalBalance = (finalPrice - downpaymentAmount) + interestAmount

                    // Calculate total paid from payments
                    val totalPaid = payments.sumOf { it.amount }

                    // Calculate the CORRECT remaining balance
                    // Formula: Original Balance - Total Paid
                    remainingBalance = originalBalance - totalPaid

                    // Ensure remaining balance doesn't go negative
                    if (remainingBalance < 0) {
                        remainingBalance = 0.0
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.progressOverlay.visibility = View.GONE
                    displayTransactionData()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressOverlay.visibility = View.GONE
                    showMessage("Error loading data: ${e.message}", true)
                }
            }
        }
    }

    private fun displayTransactionData() {
        // Item details
        binding.itemNameText.text = itemName

        // Show customer name beside item name if available
        if (customerName.isNotEmpty()) {
            binding.customerNameText.text = " - $customerName"
            binding.customerNameText.visibility = View.VISIBLE
        } else {
            binding.customerNameText.visibility = View.GONE
        }

        binding.itemDetailsText.text = itemDetails
        binding.itemDetailsText.visibility = if (itemDetails.isNotEmpty()) View.VISIBLE else View.GONE
        binding.dateSoldText.text = dateSold

        // Transaction details
        binding.originalPriceText.text = formatCurrency(originalPrice)
        binding.finalPriceText.text = formatCurrency(finalPrice)

        // Discount visibility
        if (discount > 0) {
            binding.discountLayout.visibility = View.VISIBLE
            binding.discountText.text = "-${formatCurrency(discount)}"
        } else {
            binding.discountLayout.visibility = View.GONE
        }

        // Downpayment visibility
        if (downpaymentAmount > 0) {
            binding.downpaymentLayout.visibility = View.VISIBLE
            binding.downpaymentText.text = formatCurrency(downpaymentAmount)
            binding.downpaymentSourceBadge.text = downpaymentSource
            binding.downpaymentSourceBadge.backgroundTintList = ContextCompat.getColorStateList(
                this,
                getPaymentSourceColor(downpaymentSource)
            )
        } else {
            binding.downpaymentLayout.visibility = View.GONE
        }

        // Interest rate and amount
        binding.interestRateText.text = "${interestPercent}%"
        binding.interestAmountText.text = formatCurrency(interestAmount)

        // Balance (amount after downpayment + interest)
        // This is the total amount to be paid in installments (same as originalBalance)
        binding.balanceAfterInterestText.text = formatCurrency(originalBalance)

        // Balance summary
        binding.originalBalanceText.text = formatCurrency(originalBalance)
        binding.remainingBalanceText.text = formatCurrency(remainingBalance)
        binding.monthlyAmountText.text = formatCurrency(monthlyAmount)
        binding.monthsToPayText.text = "$monthsToPay months"

        // Calculate total paid
        val totalPaid = payments.sumOf { it.amount }
        binding.totalPaidText.text = formatCurrency(totalPaid)

        // Update remaining balance color based on status
        if (remainingBalance <= 0) {
            binding.remainingBalanceText.setTextColor(ContextCompat.getColor(this, R.color.cash_dark_green))
            binding.remainingBalanceText.text = "PAID"
            binding.addPaymentCard.visibility = View.GONE
            binding.savePaymentButton.visibility = View.GONE
        } else {
            binding.remainingBalanceText.setTextColor(ContextCompat.getColor(this, R.color.red))
            binding.addPaymentCard.visibility = View.VISIBLE
            binding.savePaymentButton.visibility = View.VISIBLE
        }

        // Payment history
        displayPaymentHistory()
    }

    private fun displayPaymentHistory() {
        val paymentCount = payments.size
        binding.paymentCountText.text = "$paymentCount payment${if (paymentCount != 1) "s" else ""}"

        // Clear previous payment views
        binding.paymentHistoryContainer.removeAllViews()

        if (payments.isEmpty()) {
            binding.emptyPaymentText.visibility = View.VISIBLE
            binding.paymentHistoryContainer.visibility = View.GONE
        } else {
            binding.emptyPaymentText.visibility = View.GONE
            binding.paymentHistoryContainer.visibility = View.VISIBLE

            // Add each payment as a view using item binding
            payments.forEachIndexed { index, payment ->
                val itemBinding = ItemPaymentHistoryBinding.inflate(
                    LayoutInflater.from(this),
                    binding.paymentHistoryContainer,
                    false
                )

                // Payment number
                itemBinding.paymentNumberText.text = "${index + 1}"

                // Date
                itemBinding.paymentDateText.text = payment.date

                // Source badge
                itemBinding.paymentSourceBadge.text = payment.source
                itemBinding.paymentSourceBadge.backgroundTintList = ContextCompat.getColorStateList(
                    this,
                    getPaymentSourceColor(payment.source)
                )

                // Amount
                itemBinding.paymentAmountText.text = formatCurrency(payment.amount)

                // Remaining after
                itemBinding.remainingAfterText.text = "Bal: ${formatCurrency(payment.remainingAfter)}"

                // Add to container
                binding.paymentHistoryContainer.addView(itemBinding.root)
            }
        }
    }

    // ============================================================================
    // END OF PART 4: DATA LOADING METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 5: PAYMENT SAVING METHODS
    // ============================================================================

    private fun validateAndSavePayment() {
        val amountText = binding.paymentAmountInput.text.toString().trim()
        val paymentSource = binding.paymentSourceDropdown.text.toString()

        // Validate amount
        if (amountText.isEmpty()) {
            binding.paymentAmountLayout.error = "Please enter an amount"
            return
        }

        val amount = amountText.replace(",", "").toDoubleOrNull()
        if (amount == null || amount <= 0) {
            binding.paymentAmountLayout.error = "Please enter a valid amount"
            return
        }

        // Add small tolerance (0.01) for floating-point precision issues
        if (amount > remainingBalance + 0.01) {
            binding.paymentAmountLayout.error = "Amount exceeds remaining balance"
            return
        }

        binding.paymentAmountLayout.error = null

        // Show confirmation dialog
        showPaymentConfirmation(amount, paymentSource)
    }

    private fun showPaymentConfirmation(amount: Double, paymentSource: String) {
        val newRemaining = remainingBalance - amount
        val willBeFullyPaid = newRemaining <= 0

        val message = StringBuilder()
        message.append("Payment Amount: ${formatCurrency(amount)}\n")
        message.append("Payment Source: $paymentSource\n\n")
        message.append("Current Balance: ${formatCurrency(remainingBalance)}\n")
        message.append("New Balance: ${formatCurrency(newRemaining)}\n")

        if (willBeFullyPaid) {
            message.append("\nThis payment will FULLY SETTLE the installment.")
        }

        AlertDialog.Builder(this)
            .setTitle("Confirm Payment")
            .setMessage(message.toString())
            .setPositiveButton("Confirm") { _, _ ->
                savePayment(amount, paymentSource)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun savePayment(amount: Double, paymentSource: String) {
        binding.progressOverlay.visibility = View.VISIBLE

        val newRemaining = remainingBalance - amount
        val isFullyPaid = newRemaining <= 0

        // Create payment record
        val today = SimpleDateFormat("M/d/yyyy", Locale.US).format(Date())
        val paymentRecord = hashMapOf(
            "date" to today,
            "amount" to amount,
            "remainingAfter" to newRemaining,
            "source" to paymentSource
        )

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val docRef = db.collection(collectionName).document(documentId)

                    // Build update map
                    val updates = hashMapOf<String, Any>(
                        "inHouseInstallment.balance" to newRemaining,  // Use "balance" field name
                        "inHouseInstallment.payments" to FieldValue.arrayUnion(paymentRecord)
                    )

                    // BACKWARD COMPATIBILITY: Set originalBalance if this is the first payment
                    // and originalBalance wasn't set before
                    if (payments.isEmpty() && originalBalance > 0) {
                        updates["inHouseInstallment.originalBalance"] = originalBalance
                    }

                    // Mark as fully paid if balance is settled
                    if (isFullyPaid) {
                        updates["inHouseInstallment.isBalancePaid"] = true
                    }

                    docRef.update(updates).await()
                }

                withContext(Dispatchers.Main) {
                    binding.progressOverlay.visibility = View.GONE
                    showMessage("Payment recorded successfully", false)

                    if (isFullyPaid) {
                        // Return to previous screen
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        // Reload data to show updated values
                        loadTransactionData()
                        binding.paymentAmountInput.text?.clear()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressOverlay.visibility = View.GONE
                    showMessage("Error saving payment: ${e.message}", true)
                }
            }
        }
    }

    // ============================================================================
    // END OF PART 5: PAYMENT SAVING METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 6: UTILITY METHODS
    // ============================================================================

    private fun formatCurrency(amount: Double): String {
        val format = NumberFormat.getCurrencyInstance(Locale("en", "PH"))
        return format.format(amount)
    }

    private fun formatAmountForInput(amount: Double): String {
        return String.format(Locale.US, "%.2f", amount)
    }

    private fun showMessage(message: String, isError: Boolean) {
        Toast.makeText(
            this,
            message,
            if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    private fun getPaymentSourceColor(source: String): Int {
        return when (source.lowercase()) {
            "cash" -> R.color.cash_dark_green
            "gcash" -> R.color.gcash_blue
            "paymaya" -> R.color.paymaya_green
            "others" -> R.color.others_gray
            else -> R.color.gray
        }
    }

    // ============================================================================
    // END OF PART 6: UTILITY METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 7: RECYCLERVIEW ADAPTER
    // ============================================================================

    inner class PaymentHistoryAdapter(
        private val payments: List<PaymentRecord>
    ) : RecyclerView.Adapter<PaymentHistoryAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemPaymentHistoryBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(payment: PaymentRecord, position: Int) {
                // Payment number
                binding.paymentNumberText.text = "${position + 1}"

                // Date
                binding.paymentDateText.text = payment.date

                // Source badge
                binding.paymentSourceBadge.text = payment.source
                binding.paymentSourceBadge.backgroundTintList = ContextCompat.getColorStateList(
                    itemView.context,
                    getPaymentSourceColor(payment.source)
                )

                // Amount
                binding.paymentAmountText.text = formatCurrency(payment.amount)

                // Remaining after
                binding.remainingAfterText.text = "Bal: ${formatCurrency(payment.remainingAfter)}"
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemPaymentHistoryBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(payments[position], position)
        }

        override fun getItemCount(): Int = payments.size

        private fun getPaymentSourceColor(source: String): Int {
            return when (source.lowercase()) {
                "cash" -> R.color.cash_dark_green
                "gcash" -> R.color.gcash_blue
                "paymaya" -> R.color.paymaya_green
                "others" -> R.color.others_gray
                else -> R.color.gray
            }
        }
    }

    // ============================================================================
    // END OF PART 7: RECYCLERVIEW ADAPTER
    // ============================================================================
}