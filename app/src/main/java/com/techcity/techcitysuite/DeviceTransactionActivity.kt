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
    // START OF PART 4: PRICE AND DISCOUNT LISTENERS
    // ============================================================================

    private fun setupPriceListeners() {
        // Price input listener
        binding.priceInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val input = s.toString()
                price = if (input.isNotEmpty()) {
                    try {
                        input.toDouble()
                    } catch (e: NumberFormatException) {
                        0.0
                    }
                } else {
                    0.0
                }
                updateAllCalculations()
            }
        })

        // Discount amount listener
        binding.discountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val input = s.toString()
                if (input.isNotEmpty()) {
                    try {
                        discount = input.toDouble()
                        // Update percent
                        if (price > 0) {
                            val percent = (discount / price) * 100
                            binding.discountPercentInput.removeTextChangedListener(percentWatcher)
                            binding.discountPercentInput.setText(String.format("%.2f", percent))
                            binding.discountPercentInput.addTextChangedListener(percentWatcher)
                        }
                    } catch (e: NumberFormatException) {
                        discount = 0.0
                    }
                } else {
                    discount = 0.0
                    binding.discountPercentInput.removeTextChangedListener(percentWatcher)
                    binding.discountPercentInput.setText("")
                    binding.discountPercentInput.addTextChangedListener(percentWatcher)
                }
                updateAllCalculations()
            }
        })

        // Discount percent listener
        binding.discountPercentInput.addTextChangedListener(percentWatcher)
    }

    private val percentWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val input = s.toString()
            if (input.isNotEmpty() && price > 0) {
                try {
                    val percent = input.toDouble()
                    discount = (price * percent) / 100
                    // Update amount
                    binding.discountInput.removeTextChangedListener(binding.discountInput.getTag() as? TextWatcher)
                    binding.discountInput.setText(String.format("%.2f", discount))
                } catch (e: NumberFormatException) {
                    discount = 0.0
                }
            } else {
                discount = 0.0
            }
            updateAllCalculations()
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
    // END OF PART 4: PRICE AND DISCOUNT LISTENERS
    // ============================================================================


    // ============================================================================
    // START OF PART 5: DOWN PAYMENT LISTENERS
    // ============================================================================

    private fun setupDownPaymentListeners() {
        // Home Credit / Skyro down payment listener
        binding.hcDownPaymentInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateHCBalance()
                updateHCDownPaymentSourceVisibility()
            }
        })
    }

    private fun updateHCBalance() {
        val downPayment = binding.hcDownPaymentInput.text.toString().toDoubleOrNull() ?: 0.0
        val balance = price - discount - downPayment
        binding.hcBalanceInput.setText(formatCurrency(balance))
    }

    private fun updateHCDownPaymentSourceVisibility() {
        val downPayment = binding.hcDownPaymentInput.text.toString().toDoubleOrNull() ?: 0.0

        if (downPayment > 0) {
            binding.hcDownPaymentSourceLabel.visibility = View.VISIBLE
            binding.hcDownPaymentSourceLayout.visibility = View.VISIBLE
        } else {
            binding.hcDownPaymentSourceLabel.visibility = View.GONE
            binding.hcDownPaymentSourceLayout.visibility = View.GONE
        }
    }

    // ============================================================================
    // END OF PART 5: DOWN PAYMENT LISTENERS
    // ============================================================================


    // ============================================================================
    // START OF PART 6: IN-HOUSE LISTENERS
    // ============================================================================

    private fun setupInHouseListeners() {
        // Interest listener
        binding.interestInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateInHouseBalance()
                updateMonthlyAmount()
            }
        })

        // Down payment listener
        binding.ihDownPaymentInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateInHouseBalance()
                updateMonthlyAmount()
            }
        })

        // Months to pay listener
        binding.monthsToPayInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateMonthlyAmount()
            }
        })
    }

    private fun updateInHouseBalance() {
        val interest = binding.interestInput.text.toString().toDoubleOrNull() ?: 0.0
        val downPayment = binding.ihDownPaymentInput.text.toString().toDoubleOrNull() ?: 0.0
        val balance = (price + interest) - discount - downPayment
        binding.ihBalanceInput.setText(formatCurrency(balance))
    }

    private fun updateMonthlyAmount() {
        val balance = binding.ihBalanceInput.text.toString().replace("₱", "").replace(",", "").toDoubleOrNull() ?: 0.0
        val months = binding.monthsToPayInput.text.toString().toIntOrNull() ?: 0

        val monthlyAmount = if (months > 0) balance / months else 0.0
        binding.monthlyAmountInput.setText(formatCurrency(monthlyAmount))
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
        val imei = binding.imeiInput.text.toString().trim()

        if (imei.isEmpty()) {
            showMessage("Please enter IMEI or Serial Number", true)
            return
        }

        // Show progress
        binding.progressBar.visibility = View.VISIBLE
        binding.searchButton.isEnabled = false

        // Search in Firestore
        scope.launch {
            try {
                searchInFirestore(imei)
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.searchButton.isEnabled = true
                showMessage("Error: ${e.message}", true)
            }
        }
    }

    private suspend fun searchInFirestore(imei: String) {
        withContext(Dispatchers.IO) {
            try {
                // Search in imei1 field
                val querySnapshot1 = db.collection("inventory")
                    .whereEqualTo("imei1", imei)
                    .get()
                    .await()

                // Search in imei2 field if not found in imei1
                val querySnapshot2 = if (querySnapshot1.isEmpty) {
                    db.collection("inventory")
                        .whereEqualTo("imei2", imei)
                        .get()
                        .await()
                } else {
                    querySnapshot1
                }

                // Search in serialNumber field if not found in imei fields
                val querySnapshot3 = if (querySnapshot2.isEmpty) {
                    db.collection("inventory")
                        .whereEqualTo("serialNumber", imei)
                        .get()
                        .await()
                } else {
                    querySnapshot2
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.searchButton.isEnabled = true

                    if (!querySnapshot1.isEmpty || !querySnapshot2.isEmpty || !querySnapshot3.isEmpty) {
                        // Get the first matching document
                        val document = when {
                            !querySnapshot1.isEmpty -> querySnapshot1.documents[0]
                            !querySnapshot2.isEmpty -> querySnapshot2.documents[0]
                            else -> querySnapshot3.documents[0]
                        }

                        // Convert to InventoryItem object
                        val item = document.toObject(InventoryItem::class.java)

                        if (item != null) {
                            foundInventoryItem = item
                            displayDeviceInfo(item)
                        } else {
                            showMessage("Error parsing device data", true)
                        }
                    } else {
                        showMessage("No device found with: $imei", false)
                        clearDeviceInfo()
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

    private fun displayDeviceInfo(item: InventoryItem) {
        // Display device details in combined field
        val deviceDetails = "${item.manufacturer} ${item.model} | ${item.ram} / ${item.storage} / ${item.color}"
        binding.deviceDetailsInput.setText(deviceDetails)

        // Auto-fill price
        price = item.retailPrice
        binding.priceInput.setText(item.retailPrice.toString())

        // Update all calculations
        updateAllCalculations()

        showMessage("Device found!", false)
    }

    private fun clearDeviceInfo() {
        binding.deviceDetailsInput.setText("")
        foundInventoryItem = null
    }

    // ============================================================================
    // END OF PART 8: IMEI SEARCH METHODS
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
        if (price <= 0) {
            showMessage("Please enter a valid price", true)
            return
        }

        // Build transaction data based on transaction type
        val transactionData = hashMapOf<String, Any>(
            "imei" to binding.imeiInput.text.toString().trim(),
            "deviceDetails" to binding.deviceDetailsInput.text.toString(),
            "price" to price,
            "discount" to discount,
            "transactionType" to transactionType,
            "timestamp" to System.currentTimeMillis()
        )

        // Add inventory item ID if found
        foundInventoryItem?.let {
            transactionData["inventoryId"] = it.id
        }

        when (transactionType) {
            "Cash Transaction" -> {
                transactionData["paymentSource"] = binding.paymentSourceDropdown.text.toString()
                transactionData["totalAmount"] = price - discount
            }
            "Home Credit Transaction", "Skyro Transaction" -> {
                val brandZeroValue = brandZero
                val downPayment = binding.hcDownPaymentInput.text.toString().toDoubleOrNull() ?: 0.0
                val balance = price - (discount + downPayment)

                transactionData["brandZero"] = brandZeroValue
                transactionData["downPaymentAmount"] = downPayment
                transactionData["balance"] = balance

                if (downPayment > 0) {
                    transactionData["downPaymentSource"] = binding.hcDownPaymentSourceDropdown.text.toString()
                }
            }
            "In-House Installment" -> {
                val interest = binding.interestInput.text.toString().toDoubleOrNull() ?: 0.0
                val downPayment = binding.ihDownPaymentInput.text.toString().toDoubleOrNull() ?: 0.0
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
            showMessage("Transaction saved successfully!", false)

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