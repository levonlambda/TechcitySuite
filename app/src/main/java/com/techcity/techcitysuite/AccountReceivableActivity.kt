package com.techcity.techcitysuite

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityAccountReceivableBinding
import com.techcity.techcitysuite.databinding.ItemAccountReceivableBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.NumberFormat
import java.util.*

class AccountReceivableActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityAccountReceivableBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Firebase collection names
    private val COLLECTION_DEVICE_TRANSACTIONS = "device_transactions"
    private val COLLECTION_ACCESSORY_TRANSACTIONS = "accessory_transactions"

    // Filter enum
    enum class TransactionTypeFilter {
        ALL,
        HOME_CREDIT,
        SKYRO,
        IN_HOUSE
    }

    // Current filter
    private var currentTransactionTypeFilter: TransactionTypeFilter = TransactionTypeFilter.ALL

    // List of receivables
    private var allReceivables: MutableList<ReceivableItem> = mutableListOf()
    private var filteredReceivables: MutableList<ReceivableItem> = mutableListOf()
    private var adapter: ReceivableAdapter? = null

    // Selected items for marking as paid
    private val selectedItems = mutableSetOf<String>()

    // Activity result launcher for In-House Payment
    private val inHousePaymentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Reload data when returning from In-House Payment activity
        loadReceivables()
    }

    /**
     * Data class to represent a receivable item from either device or accessory transactions
     */
    data class ReceivableItem(
        val id: String,                          // Document ID
        val source: String,                      // "device" or "accessory"
        val itemName: String,                    // Model name (device) or Accessory name
        val itemDetails: String,                 // Variant details for devices
        val transactionType: String,             // Home Credit, Skyro, In-House
        val dateSold: String,                    // Display date
        val finalPrice: Double,                  // Final sale price
        val downpayment: Double,                 // Downpayment amount
        val downpaymentSource: String,           // Cash, GCash, etc.
        val brandZeroSubsidy: Double,            // Brand Zero subsidy amount (for HC/Skyro)
        val totalPayments: Double,               // Total payments made (for In-House)
        val balance: Double,                     // Remaining balance
        val user: String,                        // User who made the sale
        val timestamp: Timestamp?,               // For sorting
        val customerName: String = ""            // Customer name for In-House transactions
    )

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================


    // ============================================================================
    // START OF PART 2: LIFECYCLE METHODS
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAccountReceivableBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = Firebase.firestore

        setupRecyclerView()
        setupFilterButtons()
        setupActionButtons()
        loadReceivables()
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

    private fun setupRecyclerView() {
        binding.receivableRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun setupFilterButtons() {
        // Transaction Type Filter Buttons
        binding.filterAllButton.setOnClickListener {
            setTransactionTypeFilter(TransactionTypeFilter.ALL)
        }

        binding.filterHomeCreditButton.setOnClickListener {
            setTransactionTypeFilter(TransactionTypeFilter.HOME_CREDIT)
        }

        binding.filterSkyroButton.setOnClickListener {
            setTransactionTypeFilter(TransactionTypeFilter.SKYRO)
        }

        binding.filterInHouseButton.setOnClickListener {
            setTransactionTypeFilter(TransactionTypeFilter.IN_HOUSE)
        }

        // Set initial button states
        updateTransactionTypeButtonStates()
    }

    private fun setupActionButtons() {
        // Refresh button
        binding.refreshButton.setOnClickListener {
            loadReceivables()
        }

        // Mark as Paid / Add Payment button
        binding.markAsPaidButton.setOnClickListener {
            handleActionButtonClick()
        }
    }

    private fun setTransactionTypeFilter(filter: TransactionTypeFilter) {
        currentTransactionTypeFilter = filter
        updateTransactionTypeButtonStates()
        applyFilters()
    }

    private fun updateTransactionTypeButtonStates() {
        // Reset all buttons to unselected state (dimmed)
        val outlinedAlpha = 0.5f
        val selectedAlpha = 1.0f

        binding.filterAllButton.alpha = outlinedAlpha
        binding.filterHomeCreditButton.alpha = outlinedAlpha
        binding.filterSkyroButton.alpha = outlinedAlpha
        binding.filterInHouseButton.alpha = outlinedAlpha

        // Highlight the selected button
        when (currentTransactionTypeFilter) {
            TransactionTypeFilter.ALL -> binding.filterAllButton.alpha = selectedAlpha
            TransactionTypeFilter.HOME_CREDIT -> binding.filterHomeCreditButton.alpha = selectedAlpha
            TransactionTypeFilter.SKYRO -> binding.filterSkyroButton.alpha = selectedAlpha
            TransactionTypeFilter.IN_HOUSE -> binding.filterInHouseButton.alpha = selectedAlpha
        }
    }

    // ============================================================================
    // END OF PART 3: UI SETUP METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 4: DATA LOADING METHODS
    // ============================================================================

    private fun loadReceivables() {
        binding.progressBar.visibility = View.VISIBLE
        binding.receivableRecyclerView.visibility = View.GONE
        binding.emptyMessage.visibility = View.GONE

        // Clear selections when reloading
        selectedItems.clear()
        updateSelectionUI()

        scope.launch {
            try {
                val deviceReceivables = withContext(Dispatchers.IO) {
                    loadDeviceReceivables()
                }

                val accessoryReceivables = withContext(Dispatchers.IO) {
                    loadAccessoryReceivables()
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE

                    // Combine and sort by timestamp (newest first)
                    allReceivables = (deviceReceivables + accessoryReceivables)
                        .sortedByDescending { it.timestamp?.seconds ?: 0 }
                        .toMutableList()

                    applyFilters()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.emptyMessage.text = "Error loading receivables: ${e.message}"
                    binding.emptyMessage.visibility = View.VISIBLE
                    showMessage("Error: ${e.message}", true)
                }
            }
        }
    }

    private suspend fun loadDeviceReceivables(): List<ReceivableItem> {
        val receivablesList = mutableListOf<ReceivableItem>()

        // Query Home Credit transactions with unpaid balance
        val homeCreditQuery = db.collection(COLLECTION_DEVICE_TRANSACTIONS)
            .whereEqualTo("transactionType", AppConstants.TRANSACTION_TYPE_HOME_CREDIT)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .await()

        for (doc in homeCreditQuery.documents) {
            val data = doc.data ?: continue
            val homeCreditPayment = data["homeCreditPayment"] as? Map<*, *> ?: continue
            val isBalancePaid = homeCreditPayment["isBalancePaid"] as? Boolean ?: false

            if (!isBalancePaid) {
                val item = parseDeviceReceivable(doc.id, data, "Home Credit", homeCreditPayment)
                if (item != null) receivablesList.add(item)
            }
        }

        // Query Skyro transactions with unpaid balance
        val skyroQuery = db.collection(COLLECTION_DEVICE_TRANSACTIONS)
            .whereEqualTo("transactionType", AppConstants.TRANSACTION_TYPE_SKYRO)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .await()

        for (doc in skyroQuery.documents) {
            val data = doc.data ?: continue
            val skyroPayment = data["skyroPayment"] as? Map<*, *> ?: continue
            val isBalancePaid = skyroPayment["isBalancePaid"] as? Boolean ?: false

            if (!isBalancePaid) {
                val item = parseDeviceReceivable(doc.id, data, "Skyro", skyroPayment)
                if (item != null) receivablesList.add(item)
            }
        }

        // Query In-House Installment transactions with unpaid balance
        val inHouseQuery = db.collection(COLLECTION_DEVICE_TRANSACTIONS)
            .whereEqualTo("transactionType", AppConstants.TRANSACTION_TYPE_IN_HOUSE)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .await()

        for (doc in inHouseQuery.documents) {
            val data = doc.data ?: continue
            val inHousePayment = data["inHouseInstallment"] as? Map<*, *> ?: continue
            val isBalancePaid = inHousePayment["isBalancePaid"] as? Boolean ?: false

            if (!isBalancePaid) {
                val item = parseDeviceReceivable(doc.id, data, "In-House", inHousePayment)
                if (item != null) receivablesList.add(item)
            }
        }

        return receivablesList
    }

    private fun parseDeviceReceivable(
        docId: String,
        data: Map<String, Any>,
        transactionType: String,
        paymentData: Map<*, *>
    ): ReceivableItem? {
        try {
            val manufacturer = data["manufacturer"] as? String ?: ""
            val model = data["model"] as? String ?: ""
            val ram = data["ram"] as? String ?: ""
            val storage = data["storage"] as? String ?: ""
            val color = data["color"] as? String ?: ""

            val itemName = if (manufacturer.isNotEmpty()) "$manufacturer $model" else model
            val itemDetails = if (ram.isNotEmpty() && storage.isNotEmpty()) {
                "$ram + $storage - $color"
            } else {
                ""
            }

            val balance = when (transactionType) {
                "In-House" -> (paymentData["remainingBalance"] as? Number)?.toDouble() ?: 0.0
                else -> (paymentData["balance"] as? Number)?.toDouble() ?: 0.0
            }

            // Brand Zero subsidy is only for Home Credit and Skyro
            val brandZeroSubsidy = when (transactionType) {
                "Home Credit", "Skyro" -> (paymentData["brandZeroSubsidy"] as? Number)?.toDouble() ?: 0.0
                else -> 0.0
            }

            // Total payments for In-House transactions
            val totalPayments = if (transactionType == "In-House") {
                val paymentsArray = paymentData["payments"] as? List<*>
                paymentsArray?.sumOf { payment ->
                    val paymentMap = payment as? Map<*, *>
                    (paymentMap?.get("amount") as? Number)?.toDouble() ?: 0.0
                } ?: 0.0
            } else {
                0.0
            }

            // Customer name for In-House transactions
            val customerName = if (transactionType == "In-House") {
                paymentData["customerName"] as? String ?: ""
            } else {
                ""
            }

            return ReceivableItem(
                id = docId,
                source = "device",
                itemName = itemName,
                itemDetails = itemDetails,
                transactionType = transactionType,
                dateSold = data["dateSold"] as? String ?: "",
                finalPrice = (data["finalPrice"] as? Number)?.toDouble() ?: 0.0,
                downpayment = (paymentData["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0,
                downpaymentSource = paymentData["downpaymentSource"] as? String ?: "",
                brandZeroSubsidy = brandZeroSubsidy,
                totalPayments = totalPayments,
                balance = balance,
                user = data["user"] as? String ?: "",
                timestamp = data["timestamp"] as? Timestamp,
                customerName = customerName
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private suspend fun loadAccessoryReceivables(): List<ReceivableItem> {
        val receivablesList = mutableListOf<ReceivableItem>()

        // Query Home Credit transactions with unpaid balance
        val homeCreditQuery = db.collection(COLLECTION_ACCESSORY_TRANSACTIONS)
            .whereEqualTo("transactionType", AppConstants.TRANSACTION_TYPE_HOME_CREDIT)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .await()

        for (doc in homeCreditQuery.documents) {
            val data = doc.data ?: continue
            val homeCreditPayment = data["homeCreditPayment"] as? Map<*, *> ?: continue
            val isBalancePaid = homeCreditPayment["isBalancePaid"] as? Boolean ?: false

            if (!isBalancePaid) {
                val item = parseAccessoryReceivable(doc.id, data, "Home Credit", homeCreditPayment)
                if (item != null) receivablesList.add(item)
            }
        }

        // Query Skyro transactions with unpaid balance
        val skyroQuery = db.collection(COLLECTION_ACCESSORY_TRANSACTIONS)
            .whereEqualTo("transactionType", AppConstants.TRANSACTION_TYPE_SKYRO)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .await()

        for (doc in skyroQuery.documents) {
            val data = doc.data ?: continue
            val skyroPayment = data["skyroPayment"] as? Map<*, *> ?: continue
            val isBalancePaid = skyroPayment["isBalancePaid"] as? Boolean ?: false

            if (!isBalancePaid) {
                val item = parseAccessoryReceivable(doc.id, data, "Skyro", skyroPayment)
                if (item != null) receivablesList.add(item)
            }
        }

        // Query In-House Installment transactions with unpaid balance
        val inHouseQuery = db.collection(COLLECTION_ACCESSORY_TRANSACTIONS)
            .whereEqualTo("transactionType", AppConstants.TRANSACTION_TYPE_IN_HOUSE)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .await()

        for (doc in inHouseQuery.documents) {
            val data = doc.data ?: continue
            val inHousePayment = data["inHouseInstallment"] as? Map<*, *> ?: continue
            val isBalancePaid = inHousePayment["isBalancePaid"] as? Boolean ?: false

            if (!isBalancePaid) {
                val item = parseAccessoryReceivable(doc.id, data, "In-House", inHousePayment)
                if (item != null) receivablesList.add(item)
            }
        }

        return receivablesList
    }

    private fun parseAccessoryReceivable(
        docId: String,
        data: Map<String, Any>,
        transactionType: String,
        paymentData: Map<*, *>
    ): ReceivableItem? {
        try {
            val balance = when (transactionType) {
                "In-House" -> (paymentData["remainingBalance"] as? Number)?.toDouble() ?: 0.0
                else -> (paymentData["balance"] as? Number)?.toDouble() ?: 0.0
            }

            // Total payments for In-House transactions
            val totalPayments = if (transactionType == "In-House") {
                val paymentsArray = paymentData["payments"] as? List<*>
                paymentsArray?.sumOf { payment ->
                    val paymentMap = payment as? Map<*, *>
                    (paymentMap?.get("amount") as? Number)?.toDouble() ?: 0.0
                } ?: 0.0
            } else {
                0.0
            }

            // Customer name for In-House transactions
            val customerName = if (transactionType == "In-House") {
                paymentData["customerName"] as? String ?: ""
            } else {
                ""
            }

            // Accessories don't have Brand Zero subsidy
            return ReceivableItem(
                id = docId,
                source = "accessory",
                itemName = data["accessoryName"] as? String ?: "",
                itemDetails = "",  // Accessories don't have variant details
                transactionType = transactionType,
                dateSold = data["dateSold"] as? String ?: "",
                finalPrice = (data["finalPrice"] as? Number)?.toDouble() ?: 0.0,
                downpayment = (paymentData["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0,
                downpaymentSource = paymentData["downpaymentSource"] as? String ?: "",
                brandZeroSubsidy = 0.0,  // No BZ for accessories
                totalPayments = totalPayments,
                balance = balance,
                user = data["user"] as? String ?: "",
                timestamp = data["timestamp"] as? Timestamp,
                customerName = customerName
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // ============================================================================
    // END OF PART 4: DATA LOADING METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 5: FILTER AND DISPLAY METHODS
    // ============================================================================

    private fun applyFilters() {
        // Apply transaction type filter
        val filtered = when (currentTransactionTypeFilter) {
            TransactionTypeFilter.ALL -> allReceivables.toList()
            TransactionTypeFilter.HOME_CREDIT -> allReceivables.filter {
                it.transactionType == "Home Credit"
            }
            TransactionTypeFilter.SKYRO -> allReceivables.filter {
                it.transactionType == "Skyro"
            }
            TransactionTypeFilter.IN_HOUSE -> allReceivables.filter {
                it.transactionType == "In-House"
            }
        }

        filteredReceivables = filtered.toMutableList()
        updateSummary()
        displayReceivables()
    }

    private fun updateSummary() {
        val totalReceivable = filteredReceivables.sumOf { it.balance }
        val count = filteredReceivables.size

        binding.totalReceivableAmount.text = formatCurrency(totalReceivable)
        binding.entryCount.text = count.toString()

        // Update the label based on filter
        val filterLabel = when (currentTransactionTypeFilter) {
            TransactionTypeFilter.ALL -> "Total Receivable"
            TransactionTypeFilter.HOME_CREDIT -> "HC Receivable"
            TransactionTypeFilter.SKYRO -> "Skyro Receivable"
            TransactionTypeFilter.IN_HOUSE -> "In-House Receivable"
        }
        binding.totalReceivableLabel.text = filterLabel
    }

    private fun displayReceivables() {
        if (filteredReceivables.isEmpty()) {
            binding.emptyMessage.text = "No unpaid balances"
            binding.emptyMessage.visibility = View.VISIBLE
            binding.receivableRecyclerView.visibility = View.GONE
            adapter = null
        } else {
            binding.emptyMessage.visibility = View.GONE
            binding.receivableRecyclerView.visibility = View.VISIBLE

            adapter = ReceivableAdapter(
                items = filteredReceivables,
                selectedItems = selectedItems,
                onItemSelected = { item, isSelected ->
                    handleItemSelection(item, isSelected)
                }
            )
            binding.receivableRecyclerView.adapter = adapter
        }
    }

    /**
     * Handle item selection with In-House Installment rules:
     * - In-House can only be selected if nothing else is selected
     * - Cannot select multiple In-House entries
     * - Cannot mix In-House with HC/Skyro selections
     */
    private fun handleItemSelection(item: ReceivableItem, isSelected: Boolean) {
        if (isSelected) {
            // Trying to select an item
            val isInHouse = item.transactionType == "In-House"

            // Check if any In-House item is already selected
            val hasInHouseSelected = selectedItems.any { selectedId ->
                allReceivables.find { it.id == selectedId }?.transactionType == "In-House"
            }

            // Check if any non-In-House item is already selected
            val hasOtherSelected = selectedItems.any { selectedId ->
                allReceivables.find { it.id == selectedId }?.transactionType != "In-House"
            }

            if (isInHouse) {
                // Trying to select an In-House item
                if (selectedItems.isNotEmpty()) {
                    // Cannot select In-House if anything is already selected
                    showMessage("In-House entries can only be selected one at a time", false)
                    adapter?.notifyDataSetChanged()
                    return
                }
            } else {
                // Trying to select HC or Skyro
                if (hasInHouseSelected) {
                    // Cannot select HC/Skyro if In-House is already selected
                    showMessage("Cannot mix In-House with other transaction types", false)
                    adapter?.notifyDataSetChanged()
                    return
                }
            }

            // Selection allowed
            selectedItems.add(item.id)
        } else {
            // Deselecting an item - always allowed
            selectedItems.remove(item.id)
        }

        updateSelectionUI()
        adapter?.notifyDataSetChanged()
    }

    private fun updateSelectionUI() {
        val selectedCount = selectedItems.size
        val selectedTotal = filteredReceivables
            .filter { selectedItems.contains(it.id) }
            .sumOf { it.balance }

        binding.selectedCountText.text = "$selectedCount selected"
        binding.selectedAmountText.text = formatCurrency(selectedTotal)
        binding.selectedTotalAmount.text = formatCurrency(selectedTotal)

        // Check if selected item is In-House to change button text
        val hasInHouseSelected = selectedItems.any { selectedId ->
            allReceivables.find { it.id == selectedId }?.transactionType == "In-House"
        }

        // Update button text and color based on selection type
        if (hasInHouseSelected) {
            binding.markAsPaidButton.text = "Add Payment"
            binding.markAsPaidButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.purple)
        } else {
            binding.markAsPaidButton.text = "Mark as Paid"
            binding.markAsPaidButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.cash_dark_green)
        }

        // Enable/disable the button
        if (selectedCount > 0) {
            binding.markAsPaidButton.isEnabled = true
            binding.markAsPaidButton.alpha = 1.0f
        } else {
            binding.markAsPaidButton.isEnabled = false
            binding.markAsPaidButton.alpha = 0.5f
        }
    }

    // ============================================================================
    // END OF PART 5: FILTER AND DISPLAY METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 6: ACTION BUTTON HANDLER
    // ============================================================================

    /**
     * Handle the action button click based on what's selected
     * - If In-House is selected: Open InHousePaymentActivity
     * - If HC/Skyro is selected: Show mark as paid confirmation
     */
    private fun handleActionButtonClick() {
        val hasInHouseSelected = selectedItems.any { selectedId ->
            allReceivables.find { it.id == selectedId }?.transactionType == "In-House"
        }

        if (hasInHouseSelected) {
            // Open In-House Payment Activity
            openInHousePaymentActivity()
        } else {
            // Show mark as paid confirmation for HC/Skyro
            showMarkAsPaidConfirmation()
        }
    }

    private fun openInHousePaymentActivity() {
        // Get the selected In-House item (there should be only one)
        val selectedId = selectedItems.firstOrNull() ?: return
        val selectedItem = allReceivables.find { it.id == selectedId } ?: return

        val intent = Intent(this, InHousePaymentActivity::class.java).apply {
            putExtra("documentId", selectedItem.id)
            putExtra("source", selectedItem.source)
        }
        inHousePaymentLauncher.launch(intent)
    }

    // ============================================================================
    // END OF PART 6: ACTION BUTTON HANDLER
    // ============================================================================


    // ============================================================================
    // START OF PART 7: MARK AS PAID METHODS
    // ============================================================================

    private fun showMarkAsPaidConfirmation() {
        val selectedCount = selectedItems.size
        val selectedTotal = filteredReceivables
            .filter { selectedItems.contains(it.id) }
            .sumOf { it.balance }

        // Build item list for the dialog
        val selectedItemsList = filteredReceivables
            .filter { selectedItems.contains(it.id) }

        // Create message with details
        val message = StringBuilder()
        message.append("You are about to mark the following as paid:\n\n")
        message.append("Entries: $selectedCount\n")
        message.append("Total Amount: ${formatCurrency(selectedTotal)}\n\n")

        // List the items (max 5 shown)
        message.append("Items:\n")
        selectedItemsList.take(5).forEachIndexed { index, item ->
            message.append("${index + 1}. ${item.itemName} (${item.transactionType})\n")
            message.append("   Balance: ${formatCurrency(item.balance)}\n")
        }

        if (selectedItemsList.size > 5) {
            message.append("... and ${selectedItemsList.size - 5} more\n")
        }

        message.append("\nThis action cannot be undone.")

        AlertDialog.Builder(this)
            .setTitle("Confirm Mark as Paid")
            .setMessage(message.toString())
            .setPositiveButton("Confirm") { _, _ ->
                markSelectedAsPaid()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun markSelectedAsPaid() {
        binding.progressBar.visibility = View.VISIBLE

        scope.launch {
            try {
                var successCount = 0
                var failCount = 0

                for (itemId in selectedItems.toList()) {
                    val item = allReceivables.find { it.id == itemId } ?: continue

                    val collection = if (item.source == "device") {
                        COLLECTION_DEVICE_TRANSACTIONS
                    } else {
                        COLLECTION_ACCESSORY_TRANSACTIONS
                    }

                    val fieldPath = when (item.transactionType) {
                        "Home Credit" -> "homeCreditPayment.isBalancePaid"
                        "Skyro" -> "skyroPayment.isBalancePaid"
                        "In-House" -> "inHouseInstallment.isBalancePaid"
                        else -> null
                    }

                    if (fieldPath != null) {
                        try {
                            withContext(Dispatchers.IO) {
                                db.collection(collection)
                                    .document(itemId)
                                    .update(fieldPath, true)
                                    .await()
                            }
                            successCount++
                        } catch (e: Exception) {
                            e.printStackTrace()
                            failCount++
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE

                    if (failCount == 0) {
                        showMessage("$successCount entries marked as paid", false)
                    } else {
                        showMessage("$successCount marked, $failCount failed", true)
                    }

                    // Reload the list
                    loadReceivables()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    showMessage("Error: ${e.message}", true)
                }
            }
        }
    }

    // ============================================================================
    // END OF PART 7: MARK AS PAID METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 8: UTILITY METHODS
    // ============================================================================

    private fun formatCurrency(amount: Double): String {
        val format = NumberFormat.getCurrencyInstance(Locale("en", "PH"))
        return format.format(amount)
    }

    private fun showMessage(message: String, isError: Boolean) {
        Toast.makeText(
            this,
            message,
            if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    // ============================================================================
    // END OF PART 8: UTILITY METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 9: RECYCLERVIEW ADAPTER
    // ============================================================================

    inner class ReceivableAdapter(
        private val items: List<ReceivableItem>,
        private val selectedItems: MutableSet<String>,
        private val onItemSelected: (ReceivableItem, Boolean) -> Unit
    ) : RecyclerView.Adapter<ReceivableAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemAccountReceivableBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(item: ReceivableItem, position: Int) {
                // Order number
                binding.orderNumberText.text = "#${position + 1}"

                // Date sold
                binding.dateSoldText.text = item.dateSold

                // Source badge (Device or Accessory)
                binding.sourceBadge.text = if (item.source == "device") "Device" else "Accessory"
                binding.sourceBadge.backgroundTintList = ContextCompat.getColorStateList(
                    itemView.context,
                    if (item.source == "device") R.color.techcity_blue else R.color.purple
                )

                // Item name
                binding.itemNameText.text = item.itemName

                // Show customer name beside item name for In-House transactions
                if (item.transactionType == "In-House" && item.customerName.isNotEmpty()) {
                    binding.customerNameText.text = " - ${item.customerName}"
                    binding.customerNameText.visibility = View.VISIBLE
                } else {
                    binding.customerNameText.visibility = View.GONE
                }

                // Item details (variant for devices)
                if (item.itemDetails.isNotEmpty()) {
                    binding.itemDetailsText.visibility = View.VISIBLE
                    binding.itemDetailsText.text = item.itemDetails
                } else {
                    binding.itemDetailsText.visibility = View.GONE
                }

                // Transaction type badge - use full words
                val (badgeText, badgeColor) = when (item.transactionType) {
                    "Home Credit" -> "Home Credit" to R.color.red
                    "Skyro" -> "Skyro" to R.color.skyro_light_blue
                    "In-House" -> "In-House" to R.color.purple
                    else -> item.transactionType to R.color.gray
                }
                binding.transactionTypeBadge.text = badgeText
                binding.transactionTypeBadge.backgroundTintList =
                    ContextCompat.getColorStateList(itemView.context, badgeColor)

                // Final price
                binding.finalPriceText.text = formatCurrency(item.finalPrice)

                // Balance
                binding.balanceText.text = formatCurrency(item.balance)

                // Handle Downpayment visibility
                if (item.downpayment > 0) {
                    binding.downpaymentLayout.visibility = View.VISIBLE
                    binding.downpaymentText.text = formatCurrency(item.downpayment)

                    // Downpayment source badge (abbreviated)
                    if (item.downpaymentSource.isNotEmpty()) {
                        binding.downpaymentSourceBadge.visibility = View.VISIBLE
                        binding.downpaymentSourceBadge.text = getAbbreviatedSource(item.downpaymentSource)
                        binding.downpaymentSourceBadge.backgroundTintList =
                            ContextCompat.getColorStateList(
                                itemView.context,
                                getPaymentSourceColor(item.downpaymentSource)
                            )
                    } else {
                        binding.downpaymentSourceBadge.visibility = View.GONE
                    }
                } else {
                    binding.downpaymentLayout.visibility = View.GONE
                }

                // Brand Zero Subsidy visibility
                if (item.brandZeroSubsidy > 0) {
                    binding.brandZeroLayout.visibility = View.VISIBLE
                    binding.brandZeroText.text = formatCurrency(item.brandZeroSubsidy)
                } else {
                    binding.brandZeroLayout.visibility = View.GONE
                }

                // Payments visibility (for In-House transactions)
                if (item.transactionType == "In-House") {
                    binding.paymentsLayout.visibility = View.VISIBLE
                    binding.paymentsText.text = formatCurrency(item.totalPayments)
                    // Show payments in green if > 0, gray if 0
                    binding.paymentsText.setTextColor(
                        ContextCompat.getColor(
                            itemView.context,
                            if (item.totalPayments > 0) R.color.cash_dark_green else R.color.gray
                        )
                    )
                } else {
                    binding.paymentsLayout.visibility = View.GONE
                }

                // User
                binding.userText.text = "by ${item.user}"

                // Checkbox state
                binding.selectCheckbox.setOnCheckedChangeListener(null)
                binding.selectCheckbox.isChecked = selectedItems.contains(item.id)
                binding.selectCheckbox.setOnCheckedChangeListener { _, isChecked ->
                    onItemSelected(item, isChecked)
                }

                // Card click also toggles checkbox
                binding.cardView.setOnClickListener {
                    binding.selectCheckbox.isChecked = !binding.selectCheckbox.isChecked
                }

                // Update card appearance based on selection
                if (selectedItems.contains(item.id)) {
                    binding.cardView.strokeColor = ContextCompat.getColor(itemView.context, R.color.cash_dark_green)
                    binding.cardView.strokeWidth = 3
                } else {
                    binding.cardView.strokeColor = ContextCompat.getColor(itemView.context, R.color.light_gray)
                    binding.cardView.strokeWidth = 2
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAccountReceivableBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], position)
        }

        override fun getItemCount(): Int = items.size

        private fun getAbbreviatedSource(source: String): String {
            return when (source.lowercase()) {
                "cash" -> "Ca"
                "gcash" -> "GC"
                "paymaya" -> "PM"
                else -> source.take(2)
            }
        }

        private fun getPaymentSourceColor(source: String): Int {
            return when (source.lowercase()) {
                "cash" -> R.color.cash_dark_green
                "gcash" -> R.color.techcity_blue
                "paymaya" -> R.color.green
                else -> R.color.gray
            }
        }
    }

    // ============================================================================
    // END OF PART 9: RECYCLERVIEW ADAPTER
    // ============================================================================
}