package com.techcity.techcitysuite

import android.app.DatePickerDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityLedgerViewBinding
import com.techcity.techcitysuite.databinding.ItemLedgerEntryBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.*


class LedgerViewActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityLedgerViewBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Firebase collection names
    private val COLLECTION_SERVICE_TRANSACTIONS = "service_transactions"
    private val COLLECTION_DEVICE_TRANSACTIONS = "device_transactions"
    private val COLLECTION_ACCESSORY_TRANSACTIONS = "accessory_transactions"

    // Current ledger type as String for Firebase compatibility
    private var currentLedgerType: String = "Cash"  // "Cash", "GCash", "PayMaya", "Others"
    private var showingAllCredits = false
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var currentAdapter: LedgerEntriesAdapter? = null
    private var selectedDate: String = ""

    // Track toggled entries by their ID
    private val toggledEntries = mutableSetOf<String>()

    // Transaction type filter
    private var currentTransactionFilter: TransactionTypeFilter = TransactionTypeFilter.ALL

    // Enum for transaction type filters
    enum class TransactionTypeFilter {
        ALL,
        GADGETS,
        ACCESSORIES,
        SERVICE
    }

    // Cached transactions from Firebase for the selected date
    private var cachedTransactions: List<ServiceTransaction> = emptyList()
    private var cachedDeviceTransactions: List<Map<String, Any?>> = emptyList()
    private var cachedAccessoryTransactions: List<Map<String, Any?>> = emptyList()

    /**
     * Display model for ledger entries - created from ServiceTransaction embedded ledger data
     */
    data class LedgerEntryDisplay(
        val id: String,                      // Unique entry ID (transactionId_credit or transactionId_debit)
        val transactionId: String,           // Original Firebase document ID
        val transactionNumber: Int,          // Ledger transaction number
        val transactionType: String,         // "Cash In", "Cash Out", etc.
        val entryType: String,               // "CREDIT" or "DEBIT"
        val amount: Double,                  // Entry amount
        val ledgerType: String,              // Which ledger this entry belongs to
        val description: String,             // Entry description
        val notes: String,                   // Transaction notes
        val date: String,                    // Display date (M/d/yyyy)
        val time: String,                    // Display time (h:mm a)
        val timestamp: Timestamp?,           // Server timestamp for sorting
        var sortOrder: Int = 0,              // Custom sort order
        val sourceCollection: String = "service",  // "service", "device", or "accessory"
        var displaySequence: Int = 0         // Display-only sequence number (assigned after sorting)
    )

// ============================================================================
// END OF PART 1: PROPERTIES AND INITIALIZATION
// ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize View Binding
        binding = ActivityLedgerViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firebase
        db = Firebase.firestore

        // Set up UI
        setupUI()

        // Set up drag and drop
        setupDragAndDrop()

        // Set up transaction type filters
        setupTransactionTypeFilters()

        // Load initial ledger (Cash) from Firebase
        loadTransactionsFromFirebase()
    }

    override fun onResume() {
        super.onResume()
        // Refresh data from Firebase when returning to this activity
        loadTransactionsFromFirebase()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun setupUI() {
        // Initialize selected date to today
        selectedDate = getCurrentDate()
        updateDateLabel()

        // Set up ledger selection buttons
        binding.cashButton.setOnClickListener {
            showingAllCredits = false
            currentLedgerType = "Cash"
            displayLedgerFromCache()
        }

        binding.gcashButton.setOnClickListener {
            showingAllCredits = false
            currentLedgerType = "GCash"
            displayLedgerFromCache()
        }

        binding.paymayaButton.setOnClickListener {
            showingAllCredits = false
            currentLedgerType = "PayMaya"
            displayLedgerFromCache()
        }

        binding.othersButton.setOnClickListener {
            showingAllCredits = false
            currentLedgerType = "Others"
            displayLedgerFromCache()
        }

        // Set up All Credits button
        binding.allCreditsButton.setOnClickListener {
            showAllCredits()
        }

        // Set up menu button for date picker
        binding.menuButton.setOnClickListener {
            showDatePicker()
        }

        // Set up RecyclerView
        binding.ledgerRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun setupDragAndDrop() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT  // Add swipe left for delete
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition

                if (currentAdapter != null) {
                    currentAdapter?.swapItems(fromPosition, toPosition)

                    // Update order numbers after swap
                    val start = minOf(fromPosition, toPosition)
                    val end = maxOf(fromPosition, toPosition)
                    currentAdapter?.notifyItemRangeChanged(start, end - start + 1)

                    // Note: For Firebase, we would save the new order here
                    // This can be implemented later if needed

                    return true
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                if (direction == ItemTouchHelper.LEFT) {
                    val position = viewHolder.adapterPosition
                    val entry = currentAdapter?.getEntries()?.get(position)

                    entry?.let {
                        showDeleteConfirmationDialog(it, position)
                    }
                }
            }

            override fun isLongPressDragEnabled(): Boolean {
                // Enable drag for both individual ledgers and All Credits view
                return true
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)

                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    // Item is being dragged - change border color to blue
                    viewHolder?.let {
                        if (it is LedgerEntriesAdapter.ViewHolder) {
                            it.setDragging(true)
                        }
                    }
                } else if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    // Item is being swiped - change background to red
                    viewHolder?.let {
                        if (it is LedgerEntriesAdapter.ViewHolder) {
                            it.setSwiping(true)
                        }
                    }
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)

                // Dragging/swiping ended - reset appearance
                if (viewHolder is LedgerEntriesAdapter.ViewHolder) {
                    viewHolder.setDragging(false)
                    viewHolder.setSwiping(false)
                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    // Draw blue background with delete icon when swiping
                    val itemView = viewHolder.itemView
                    val paint = Paint()

                    if (dX < 0) {  // Swiping left
                        // Draw blue background
                        paint.color = ContextCompat.getColor(
                            this@LedgerViewActivity,
                            R.color.techcity_blue
                        )
                        c.drawRect(
                            itemView.right.toFloat() + dX,
                            itemView.top.toFloat(),
                            itemView.right.toFloat(),
                            itemView.bottom.toFloat(),
                            paint
                        )

                        // Draw delete icon
                        val deleteIcon = ContextCompat.getDrawable(
                            this@LedgerViewActivity,
                            android.R.drawable.ic_menu_delete
                        )

                        deleteIcon?.let { icon ->
                            val iconMargin = (itemView.height - icon.intrinsicHeight) / 2
                            val iconTop = itemView.top + iconMargin
                            val iconBottom = iconTop + icon.intrinsicHeight
                            val iconLeft = itemView.right - iconMargin - icon.intrinsicWidth
                            val iconRight = itemView.right - iconMargin

                            icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                            icon.setTint(Color.WHITE)
                            icon.draw(c)
                        }
                    }
                }

                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }

        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(binding.ledgerRecyclerView)
    }

    private fun setupTransactionTypeFilters() {
        // Set up filter buttons
        binding.filterAllButton.setOnClickListener {
            currentTransactionFilter = TransactionTypeFilter.ALL
            updateFilterButtonStates()
            refreshCurrentView()
        }

        binding.filterGadgetsButton.setOnClickListener {
            currentTransactionFilter = TransactionTypeFilter.GADGETS
            updateFilterButtonStates()
            refreshCurrentView()
        }

        binding.filterAccessoriesButton.setOnClickListener {
            currentTransactionFilter = TransactionTypeFilter.ACCESSORIES
            updateFilterButtonStates()
            refreshCurrentView()
        }

        binding.filterServiceButton.setOnClickListener {
            currentTransactionFilter = TransactionTypeFilter.SERVICE
            updateFilterButtonStates()
            refreshCurrentView()
        }

        // Set initial button states
        updateFilterButtonStates()
    }

    private fun updateFilterButtonStates() {
        // Reset all buttons to non-selected state (faded, no border, regular blue)
        resetButtonToNonSelected(binding.filterAllButton as com.google.android.material.button.MaterialButton)
        resetButtonToNonSelected(binding.filterGadgetsButton as com.google.android.material.button.MaterialButton)
        resetButtonToNonSelected(binding.filterAccessoriesButton as com.google.android.material.button.MaterialButton)
        resetButtonToNonSelected(binding.filterServiceButton as com.google.android.material.button.MaterialButton)

        // Highlight selected button (full opacity, white border, darker blue)
        when (currentTransactionFilter) {
            TransactionTypeFilter.ALL -> setButtonToSelected(binding.filterAllButton as com.google.android.material.button.MaterialButton)
            TransactionTypeFilter.GADGETS -> setButtonToSelected(binding.filterGadgetsButton as com.google.android.material.button.MaterialButton)
            TransactionTypeFilter.ACCESSORIES -> setButtonToSelected(binding.filterAccessoriesButton as com.google.android.material.button.MaterialButton)
            TransactionTypeFilter.SERVICE -> setButtonToSelected(binding.filterServiceButton as com.google.android.material.button.MaterialButton)
        }
    }

    private fun resetButtonToNonSelected(button: com.google.android.material.button.MaterialButton) {
        button.alpha = 0.5f
        button.strokeWidth = 0
        button.backgroundTintList = ContextCompat.getColorStateList(this, R.color.techcity_blue)
    }

    private fun setButtonToSelected(button: com.google.android.material.button.MaterialButton) {
        button.alpha = 1.0f
        button.strokeWidth = 4
        button.setStrokeColorResource(android.R.color.white)
        button.backgroundTintList = ContextCompat.getColorStateList(this, R.color.techcity_blue_dark)
    }

    private fun refreshCurrentView() {
        if (showingAllCredits) {
            showAllCredits()
        } else {
            displayLedgerFromCache()
        }
    }

    private fun filterEntriesByTransactionType(entries: List<LedgerEntryDisplay>): List<LedgerEntryDisplay> {
        return when (currentTransactionFilter) {
            TransactionTypeFilter.ALL -> entries
            TransactionTypeFilter.GADGETS -> entries.filter { entry ->
                entry.sourceCollection == "device"
            }
            TransactionTypeFilter.ACCESSORIES -> entries.filter { entry ->
                entry.sourceCollection == "accessory"
            }
            TransactionTypeFilter.SERVICE -> entries.filter { entry ->
                entry.sourceCollection == "service"
            }
        }
    }

    private fun showDeleteConfirmationDialog(entry: LedgerEntryDisplay, position: Int) {
        // Build message showing what will be deleted
        val message = StringBuilder()
        message.append("Delete Transaction #${entry.transactionNumber.toString().padStart(3, '0')}?\n\n")
        message.append("Type: ${entry.transactionType}\n")
        message.append("Entry: ${entry.entryType}\n")
        message.append("Amount: ${formatCurrency(entry.amount)}\n")
        message.append("Ledger: ${entry.ledgerType}\n\n")
        message.append("This will delete the entire transaction from the database.")

        AlertDialog.Builder(this)
            .setTitle("Confirm Delete")
            .setMessage(message.toString())
            .setPositiveButton("Delete") { _, _ ->
                // Delete the transaction from Firebase
                deleteTransactionFromFirebase(entry, position)
            }
            .setNegativeButton("Cancel") { _, _ ->
                // Refresh to restore the swiped item
                currentAdapter?.notifyItemChanged(position)
            }
            .setOnCancelListener {
                // Refresh to restore the swiped item if dialog is cancelled
                currentAdapter?.notifyItemChanged(position)
            }
            .show()
    }

    private fun deleteTransactionFromFirebase(entry: LedgerEntryDisplay, position: Int) {
        // Determine which collection to delete from
        val collectionName = when (entry.sourceCollection) {
            "device" -> COLLECTION_DEVICE_TRANSACTIONS
            "accessory" -> COLLECTION_ACCESSORY_TRANSACTIONS
            else -> COLLECTION_SERVICE_TRANSACTIONS
        }

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.collection(collectionName)
                        .document(entry.transactionId)
                        .delete()
                        .await()
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@LedgerViewActivity,
                        "Transaction deleted",
                        Toast.LENGTH_SHORT
                    ).show()

                    // Reload transactions from Firebase
                    loadTransactionsFromFirebase()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    currentAdapter?.notifyItemChanged(position)
                    Toast.makeText(
                        this@LedgerViewActivity,
                        "Error deleting: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Load transactions from Firebase for the selected date
     */
    private fun loadTransactionsFromFirebase() {
        // Convert display date to query format
        val queryDate = convertDisplayDateToQueryFormat(selectedDate)

        scope.launch {
            try {
                // Load service transactions
                val serviceResult = withContext(Dispatchers.IO) {
                    db.collection(COLLECTION_SERVICE_TRANSACTIONS)
                        .whereEqualTo("date", queryDate)
                        .whereEqualTo("status", "completed")
                        .orderBy("timestamp", Query.Direction.ASCENDING)
                        .get()
                        .await()
                }

                // Load device transactions (may not have status field)
                val deviceResult = withContext(Dispatchers.IO) {
                    db.collection(COLLECTION_DEVICE_TRANSACTIONS)
                        .whereEqualTo("date", queryDate)
                        .orderBy("timestamp", Query.Direction.ASCENDING)
                        .get()
                        .await()
                }

                // Load accessory transactions (may not have status field)
                val accessoryResult = withContext(Dispatchers.IO) {
                    db.collection(COLLECTION_ACCESSORY_TRANSACTIONS)
                        .whereEqualTo("date", queryDate)
                        .orderBy("timestamp", Query.Direction.ASCENDING)
                        .get()
                        .await()
                }

                // Parse service transactions
                val loadedTransactions = mutableListOf<ServiceTransaction>()
                for (document in serviceResult.documents) {
                    try {
                        val data = document.data ?: continue

                        val transaction = ServiceTransaction(
                            id = document.id,
                            date = data["date"] as? String ?: "",
                            month = data["month"] as? String ?: "",
                            year = data["year"] as? String ?: "",
                            timestamp = data["timestamp"] as? Timestamp,
                            dateSaved = data["dateSaved"] as? String ?: "",
                            time = data["time"] as? String ?: "",
                            user = data["user"] as? String ?: "",
                            userLocation = data["userLocation"] as? String ?: "",
                            deviceId = data["deviceId"] as? String ?: "",
                            transactionType = data["transactionType"] as? String ?: "",
                            amount = (data["amount"] as? Number)?.toDouble() ?: 0.0,
                            fee = (data["fee"] as? Number)?.toDouble() ?: 0.0,
                            feeOption = data["feeOption"] as? String ?: "",
                            customerPays = (data["customerPays"] as? Number)?.toDouble() ?: 0.0,
                            customerReceives = (data["customerReceives"] as? Number)?.toDouble() ?: 0.0,
                            sourceOfFunds = data["sourceOfFunds"] as? String ?: "",
                            paidWith = data["paidWith"] as? String,
                            isPaidWithChecked = data["isPaidWithChecked"] as? Boolean ?: false,
                            creditLedgerType = data["creditLedgerType"] as? String ?: "",
                            creditAmount = (data["creditAmount"] as? Number)?.toDouble() ?: 0.0,
                            creditDescription = data["creditDescription"] as? String ?: "",
                            debitLedgerType = data["debitLedgerType"] as? String ?: "",
                            debitAmount = (data["debitAmount"] as? Number)?.toDouble() ?: 0.0,
                            debitDescription = data["debitDescription"] as? String ?: "",
                            ledgerTransactionNumber = (data["ledgerTransactionNumber"] as? Number)?.toInt() ?: 0,
                            sortOrder = (data["sortOrder"] as? Number)?.toInt() ?: 0,
                            status = data["status"] as? String ?: "completed",
                            notes = data["notes"] as? String ?: "",
                            createdBy = data["createdBy"] as? String ?: "",
                            createdAt = data["createdAt"] as? Timestamp
                        )

                        loadedTransactions.add(transaction)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // Store device transactions as raw maps (with document ID)
                val loadedDeviceTransactions = deviceResult.documents.mapNotNull { doc ->
                    doc.data?.toMutableMap()?.also { it["_id"] = doc.id }
                }

                // Debug: Log device transaction count
                android.util.Log.d("LedgerView", "Loaded ${loadedDeviceTransactions.size} device transactions for date: $queryDate")
                loadedDeviceTransactions.forEach { data ->
                    android.util.Log.d("LedgerView", "Device: ${data["transactionType"]} - ${data["paymentSource"]} - ${data["totalAmount"]}")
                }

                // Store accessory transactions as raw maps (with document ID)
                val loadedAccessoryTransactions = accessoryResult.documents.mapNotNull { doc ->
                    doc.data?.toMutableMap()?.also { it["_id"] = doc.id }
                }

                // Debug: Log accessory transaction count
                android.util.Log.d("LedgerView", "Loaded ${loadedAccessoryTransactions.size} accessory transactions for date: $queryDate")

                withContext(Dispatchers.Main) {
                    cachedTransactions = loadedTransactions
                    cachedDeviceTransactions = loadedDeviceTransactions
                    cachedAccessoryTransactions = loadedAccessoryTransactions
                    displayLedgerFromCache()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.emptyMessage.text = "Error loading transactions"
                    binding.emptyMessage.visibility = View.VISIBLE
                    binding.ledgerRecyclerView.visibility = View.GONE
                    Toast.makeText(
                        this@LedgerViewActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Build ledger entries from cached transactions for the current ledger type
     */
    private fun buildLedgerEntriesFromCache(): List<LedgerEntryDisplay> {
        val entries = mutableListOf<LedgerEntryDisplay>()

        // Build entries from SERVICE transactions
        for (transaction in cachedTransactions) {
            // Credit entry - if this ledger receives credit
            if (transaction.creditLedgerType == currentLedgerType) {
                entries.add(
                    LedgerEntryDisplay(
                        id = "${transaction.id}_credit",
                        transactionId = transaction.id,
                        transactionNumber = transaction.ledgerTransactionNumber,
                        transactionType = transaction.transactionType,
                        entryType = "CREDIT",
                        amount = transaction.creditAmount,
                        ledgerType = transaction.creditLedgerType,
                        description = transaction.creditDescription,
                        notes = transaction.notes,
                        date = transaction.dateSaved,
                        time = convertTimestampToLocalTime(transaction.timestamp),
                        timestamp = transaction.timestamp,
                        sortOrder = transaction.sortOrder,
                        sourceCollection = "service"
                    )
                )
            }

            // Debit entry - if this ledger has debit
            if (transaction.debitLedgerType == currentLedgerType) {
                entries.add(
                    LedgerEntryDisplay(
                        id = "${transaction.id}_debit",
                        transactionId = transaction.id,
                        transactionNumber = transaction.ledgerTransactionNumber,
                        transactionType = transaction.transactionType,
                        entryType = "DEBIT",
                        amount = transaction.debitAmount,
                        ledgerType = transaction.debitLedgerType,
                        description = transaction.debitDescription,
                        notes = transaction.notes,
                        date = transaction.dateSaved,
                        time = convertTimestampToLocalTime(transaction.timestamp),
                        timestamp = transaction.timestamp,
                        sortOrder = transaction.sortOrder,
                        sourceCollection = "service"
                    )
                )
            }
        }

        // Build entries from DEVICE transactions
        entries.addAll(buildDeviceLedgerEntries(currentLedgerType))

        // Build entries from ACCESSORY transactions
        entries.addAll(buildAccessoryLedgerEntries(currentLedgerType))

        // Sort by sortOrder, then by timestamp
        return entries.sortedWith(compareBy(
            { it.sortOrder },
            { it.timestamp?.seconds ?: 0L }
        ))
    }

    /**
     * Map payment source to ledger type
     */
    private fun mapPaymentSourceToLedger(paymentSource: String): String {
        return when (paymentSource) {
            "Cash" -> "Cash"
            "GCash" -> "GCash"
            "PayMaya" -> "PayMaya"
            "Bank Transfer" -> "Others"
            "Credit Card" -> "Others"
            "Others" -> "Others"
            else -> "Others"
        }
    }

    /**
     * Build ledger entries from DEVICE transactions (computed from existing fields)
     */
    private fun buildDeviceLedgerEntries(targetLedger: String): List<LedgerEntryDisplay> {
        val entries = mutableListOf<LedgerEntryDisplay>()

        for (data in cachedDeviceTransactions) {
            val docId = data["_id"] as? String ?: continue
            val transactionType = data["transactionType"] as? String ?: ""

            // Build device details string from individual fields
            val manufacturer = data["manufacturer"] as? String ?: ""
            val model = data["model"] as? String ?: ""
            val storage = data["storage"] as? String ?: ""
            val color = data["color"] as? String ?: ""

            val timestamp = data["timestamp"] as? Timestamp
            val dateSold = data["dateSold"] as? String ?: ""
            val notes = data["notes"] as? String ?: ""
            val sortOrder = (data["sortOrder"] as? Number)?.toInt() ?: 0
            val transactionNumber = (data["transactionNumber"] as? Number)?.toInt() ?: 0

            // Get IMEI1 or Serial Number for display (priority: IMEI1 > Serial Number)
            val imei1 = data["imei1"] as? String ?: ""
            val serialNumber = data["serialNumber"] as? String ?: ""
            val deviceIdentifier = when {
                imei1.isNotEmpty() -> imei1
                serialNumber.isNotEmpty() -> serialNumber
                else -> ""
            }

            // Build device details with identifier
            val deviceDetails = if (deviceIdentifier.isNotEmpty()) {
                "$manufacturer $model $storage $color ($deviceIdentifier)".trim()
            } else {
                "$manufacturer $model $storage $color".trim()
            }

            when (transactionType) {
                "Cash Transaction" -> {
                    // Get nested cashPayment object
                    val cashPayment = data["cashPayment"] as? Map<String, Any?> ?: emptyMap()
                    val paymentSource = cashPayment["paymentSource"] as? String ?: "Cash"
                    val creditLedger = mapPaymentSourceToLedger(paymentSource)
                    val finalPrice = (data["finalPrice"] as? Number)?.toDouble() ?: 0.0

                    if (creditLedger == targetLedger && finalPrice > 0) {
                        entries.add(LedgerEntryDisplay(
                            id = "${docId}_credit",
                            transactionId = docId,
                            transactionNumber = transactionNumber,
                            transactionType = transactionType,
                            entryType = "CREDIT",
                            amount = finalPrice,
                            ledgerType = creditLedger,
                            description = "Device Sale - $deviceDetails",
                            notes = notes,
                            date = dateSold,
                            time = convertTimestampToLocalTime(timestamp),
                            timestamp = timestamp,
                            sortOrder = sortOrder,
                            sourceCollection = "device"
                        ))
                    }
                }

                "Home Credit Transaction" -> {
                    // Get nested homeCreditPayment object
                    val hcPayment = data["homeCreditPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpaymentAmount = (hcPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val downpaymentSource = hcPayment["downpaymentSource"] as? String ?: ""
                    val balance = (hcPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val isBalancePaid = hcPayment["isBalancePaid"] as? Boolean ?: false
                    val brandZero = hcPayment["brandZero"] as? Boolean ?: false
                    val brandZeroSubsidy = (hcPayment["brandZeroSubsidy"] as? Number)?.toDouble() ?: 0.0

                    // CREDIT entry for downpayment (if any)
                    if (downpaymentAmount > 0) {
                        val dpLedger = mapPaymentSourceToLedger(downpaymentSource)
                        if (dpLedger == targetLedger) {
                            entries.add(LedgerEntryDisplay(
                                id = "${docId}_dp",
                                transactionId = docId,
                                transactionNumber = transactionNumber,
                                transactionType = transactionType,
                                entryType = "CREDIT",
                                amount = downpaymentAmount,
                                ledgerType = dpLedger,
                                description = "Home Credit DP - $deviceDetails",
                                notes = notes,
                                date = dateSold,
                                time = convertTimestampToLocalTime(timestamp),
                                timestamp = timestamp,
                                sortOrder = sortOrder,
                                sourceCollection = "device"
                            ))
                        }
                    }

                    // Balance entry (DEBIT if unpaid, CREDIT if paid) - always goes to Others
                    if (balance > 0 && targetLedger == "Others") {
                        val balanceEntryType = if (isBalancePaid) "CREDIT" else "DEBIT"
                        val balanceStatus = if (isBalancePaid) "paid" else "unpaid"
                        val balanceDesc = if (brandZero && brandZeroSubsidy > 0) {
                            "Balance ($balanceStatus) - Brand Zero: ${formatCurrency(brandZeroSubsidy)}"
                        } else {
                            "Balance ($balanceStatus)"
                        }

                        entries.add(LedgerEntryDisplay(
                            id = "${docId}_balance",
                            transactionId = docId,
                            transactionNumber = transactionNumber,
                            transactionType = transactionType,
                            entryType = balanceEntryType,
                            amount = balance,
                            ledgerType = "Others",
                            description = balanceDesc,
                            notes = notes,
                            date = dateSold,
                            time = convertTimestampToLocalTime(timestamp),
                            timestamp = timestamp,
                            sortOrder = sortOrder,
                            sourceCollection = "device"
                        ))
                    }
                }

                "Skyro Transaction" -> {
                    // Get nested skyroPayment object
                    val skyroPayment = data["skyroPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpaymentAmount = (skyroPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val downpaymentSource = skyroPayment["downpaymentSource"] as? String ?: ""
                    val balance = (skyroPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val isBalancePaid = skyroPayment["isBalancePaid"] as? Boolean ?: false
                    val brandZero = skyroPayment["brandZero"] as? Boolean ?: false
                    val brandZeroSubsidy = (skyroPayment["brandZeroSubsidy"] as? Number)?.toDouble() ?: 0.0

                    // CREDIT entry for downpayment (if any)
                    if (downpaymentAmount > 0) {
                        val dpLedger = mapPaymentSourceToLedger(downpaymentSource)
                        if (dpLedger == targetLedger) {
                            entries.add(LedgerEntryDisplay(
                                id = "${docId}_dp",
                                transactionId = docId,
                                transactionNumber = transactionNumber,
                                transactionType = transactionType,
                                entryType = "CREDIT",
                                amount = downpaymentAmount,
                                ledgerType = dpLedger,
                                description = "Skyro DP - $deviceDetails",
                                notes = notes,
                                date = dateSold,
                                time = convertTimestampToLocalTime(timestamp),
                                timestamp = timestamp,
                                sortOrder = sortOrder,
                                sourceCollection = "device"
                            ))
                        }
                    }

                    // Balance entry - always goes to Others
                    if (balance > 0 && targetLedger == "Others") {
                        val balanceEntryType = if (isBalancePaid) "CREDIT" else "DEBIT"
                        val balanceStatus = if (isBalancePaid) "paid" else "unpaid"
                        val balanceDesc = if (brandZero && brandZeroSubsidy > 0) {
                            "Balance ($balanceStatus) - Brand Zero: ${formatCurrency(brandZeroSubsidy)}"
                        } else {
                            "Balance ($balanceStatus)"
                        }

                        entries.add(LedgerEntryDisplay(
                            id = "${docId}_balance",
                            transactionId = docId,
                            transactionNumber = transactionNumber,
                            transactionType = transactionType,
                            entryType = balanceEntryType,
                            amount = balance,
                            ledgerType = "Others",
                            description = balanceDesc,
                            notes = notes,
                            date = dateSold,
                            time = convertTimestampToLocalTime(timestamp),
                            timestamp = timestamp,
                            sortOrder = sortOrder,
                            sourceCollection = "device"
                        ))
                    }
                }

                "In-House Installment" -> {
                    // Get nested inHouseInstallment object
                    val ihPayment = data["inHouseInstallment"] as? Map<String, Any?> ?: emptyMap()
                    val downpaymentAmount = (ihPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val downpaymentSource = ihPayment["downpaymentSource"] as? String ?: ""
                    val balance = (ihPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val isBalancePaid = ihPayment["isBalancePaid"] as? Boolean ?: false

                    // CREDIT entry for downpayment (if any)
                    if (downpaymentAmount > 0) {
                        val dpLedger = mapPaymentSourceToLedger(downpaymentSource)
                        if (dpLedger == targetLedger) {
                            entries.add(LedgerEntryDisplay(
                                id = "${docId}_dp",
                                transactionId = docId,
                                transactionNumber = transactionNumber,
                                transactionType = transactionType,
                                entryType = "CREDIT",
                                amount = downpaymentAmount,
                                ledgerType = dpLedger,
                                description = "In-House DP - $deviceDetails",
                                notes = notes,
                                date = dateSold,
                                time = convertTimestampToLocalTime(timestamp),
                                timestamp = timestamp,
                                sortOrder = sortOrder,
                                sourceCollection = "device"
                            ))
                        }
                    }

                    // Balance entry - always goes to Others (no Brand Zero for In-House)
                    if (balance > 0 && targetLedger == "Others") {
                        val balanceEntryType = if (isBalancePaid) "CREDIT" else "DEBIT"
                        val balanceStatus = if (isBalancePaid) "paid" else "unpaid"

                        entries.add(LedgerEntryDisplay(
                            id = "${docId}_balance",
                            transactionId = docId,
                            transactionNumber = transactionNumber,
                            transactionType = transactionType,
                            entryType = balanceEntryType,
                            amount = balance,
                            ledgerType = "Others",
                            description = "Balance ($balanceStatus)",
                            notes = notes,
                            date = dateSold,
                            time = convertTimestampToLocalTime(timestamp),
                            timestamp = timestamp,
                            sortOrder = sortOrder,
                            sourceCollection = "device"
                        ))
                    }
                }
            }
        }

        return entries
    }

    /**
     * Build ledger entries from ACCESSORY transactions (computed from existing fields)
     */
    private fun buildAccessoryLedgerEntries(targetLedger: String): List<LedgerEntryDisplay> {
        val entries = mutableListOf<LedgerEntryDisplay>()

        for (data in cachedAccessoryTransactions) {
            val docId = data["_id"] as? String ?: continue
            val transactionType = data["transactionType"] as? String ?: ""
            val accessoryName = data["accessoryName"] as? String ?: ""
            val timestamp = data["timestamp"] as? Timestamp
            val dateSold = data["dateSold"] as? String ?: ""
            val notes = data["notes"] as? String ?: ""
            val sortOrder = (data["sortOrder"] as? Number)?.toInt() ?: 0
            val transactionNumber = (data["transactionNumber"] as? Number)?.toInt() ?: 0

            when (transactionType) {
                "Cash Transaction" -> {
                    // Get nested cashPayment object
                    val cashPayment = data["cashPayment"] as? Map<String, Any?> ?: emptyMap()
                    val paymentSource = cashPayment["paymentSource"] as? String ?: "Cash"
                    val creditLedger = mapPaymentSourceToLedger(paymentSource)
                    val finalPrice = (data["finalPrice"] as? Number)?.toDouble() ?: 0.0

                    if (creditLedger == targetLedger && finalPrice > 0) {
                        entries.add(LedgerEntryDisplay(
                            id = "${docId}_credit",
                            transactionId = docId,
                            transactionNumber = transactionNumber,
                            transactionType = transactionType,
                            entryType = "CREDIT",
                            amount = finalPrice,
                            ledgerType = creditLedger,
                            description = "Accessory Sale - $accessoryName",
                            notes = notes,
                            date = dateSold,
                            time = convertTimestampToLocalTime(timestamp),
                            timestamp = timestamp,
                            sortOrder = sortOrder,
                            sourceCollection = "accessory"
                        ))
                    }
                }

                "Home Credit Transaction" -> {
                    // Get nested homeCreditPayment object
                    val hcPayment = data["homeCreditPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpaymentAmount = (hcPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val downpaymentSource = hcPayment["downpaymentSource"] as? String ?: ""
                    val balance = (hcPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val isBalancePaid = hcPayment["isBalancePaid"] as? Boolean ?: false

                    // CREDIT entry for downpayment (if any)
                    if (downpaymentAmount > 0) {
                        val dpLedger = mapPaymentSourceToLedger(downpaymentSource)
                        if (dpLedger == targetLedger) {
                            entries.add(LedgerEntryDisplay(
                                id = "${docId}_dp",
                                transactionId = docId,
                                transactionNumber = transactionNumber,
                                transactionType = transactionType,
                                entryType = "CREDIT",
                                amount = downpaymentAmount,
                                ledgerType = dpLedger,
                                description = "Home Credit DP - $accessoryName",
                                notes = notes,
                                date = dateSold,
                                time = convertTimestampToLocalTime(timestamp),
                                timestamp = timestamp,
                                sortOrder = sortOrder,
                                sourceCollection = "accessory"
                            ))
                        }
                    }

                    // Balance entry - always goes to Others (no Brand Zero for accessories)
                    if (balance > 0 && targetLedger == "Others") {
                        val balanceEntryType = if (isBalancePaid) "CREDIT" else "DEBIT"
                        val balanceStatus = if (isBalancePaid) "paid" else "unpaid"

                        entries.add(LedgerEntryDisplay(
                            id = "${docId}_balance",
                            transactionId = docId,
                            transactionNumber = transactionNumber,
                            transactionType = transactionType,
                            entryType = balanceEntryType,
                            amount = balance,
                            ledgerType = "Others",
                            description = "Balance ($balanceStatus)",
                            notes = notes,
                            date = dateSold,
                            time = convertTimestampToLocalTime(timestamp),
                            timestamp = timestamp,
                            sortOrder = sortOrder,
                            sourceCollection = "accessory"
                        ))
                    }
                }

                "Skyro Transaction" -> {
                    // Get nested skyroPayment object
                    val skyroPayment = data["skyroPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpaymentAmount = (skyroPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val downpaymentSource = skyroPayment["downpaymentSource"] as? String ?: ""
                    val balance = (skyroPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val isBalancePaid = skyroPayment["isBalancePaid"] as? Boolean ?: false

                    // CREDIT entry for downpayment (if any)
                    if (downpaymentAmount > 0) {
                        val dpLedger = mapPaymentSourceToLedger(downpaymentSource)
                        if (dpLedger == targetLedger) {
                            entries.add(LedgerEntryDisplay(
                                id = "${docId}_dp",
                                transactionId = docId,
                                transactionNumber = transactionNumber,
                                transactionType = transactionType,
                                entryType = "CREDIT",
                                amount = downpaymentAmount,
                                ledgerType = dpLedger,
                                description = "Skyro DP - $accessoryName",
                                notes = notes,
                                date = dateSold,
                                time = convertTimestampToLocalTime(timestamp),
                                timestamp = timestamp,
                                sortOrder = sortOrder,
                                sourceCollection = "accessory"
                            ))
                        }
                    }

                    // Balance entry - always goes to Others
                    if (balance > 0 && targetLedger == "Others") {
                        val balanceEntryType = if (isBalancePaid) "CREDIT" else "DEBIT"
                        val balanceStatus = if (isBalancePaid) "paid" else "unpaid"

                        entries.add(LedgerEntryDisplay(
                            id = "${docId}_balance",
                            transactionId = docId,
                            transactionNumber = transactionNumber,
                            transactionType = transactionType,
                            entryType = balanceEntryType,
                            amount = balance,
                            ledgerType = "Others",
                            description = "Balance ($balanceStatus)",
                            notes = notes,
                            date = dateSold,
                            time = convertTimestampToLocalTime(timestamp),
                            timestamp = timestamp,
                            sortOrder = sortOrder,
                            sourceCollection = "accessory"
                        ))
                    }
                }

                "In-House Installment" -> {
                    // Get nested inHouseInstallment object
                    val ihPayment = data["inHouseInstallment"] as? Map<String, Any?> ?: emptyMap()
                    val downpaymentAmount = (ihPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val downpaymentSource = ihPayment["downpaymentSource"] as? String ?: ""
                    val balance = (ihPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val isBalancePaid = ihPayment["isBalancePaid"] as? Boolean ?: false

                    // CREDIT entry for downpayment (if any)
                    if (downpaymentAmount > 0) {
                        val dpLedger = mapPaymentSourceToLedger(downpaymentSource)
                        if (dpLedger == targetLedger) {
                            entries.add(LedgerEntryDisplay(
                                id = "${docId}_dp",
                                transactionId = docId,
                                transactionNumber = transactionNumber,
                                transactionType = transactionType,
                                entryType = "CREDIT",
                                amount = downpaymentAmount,
                                ledgerType = dpLedger,
                                description = "In-House DP - $accessoryName",
                                notes = notes,
                                date = dateSold,
                                time = convertTimestampToLocalTime(timestamp),
                                timestamp = timestamp,
                                sortOrder = sortOrder,
                                sourceCollection = "accessory"
                            ))
                        }
                    }

                    // Balance entry - always goes to Others
                    if (balance > 0 && targetLedger == "Others") {
                        val balanceEntryType = if (isBalancePaid) "CREDIT" else "DEBIT"
                        val balanceStatus = if (isBalancePaid) "paid" else "unpaid"

                        entries.add(LedgerEntryDisplay(
                            id = "${docId}_balance",
                            transactionId = docId,
                            transactionNumber = transactionNumber,
                            transactionType = transactionType,
                            entryType = balanceEntryType,
                            amount = balance,
                            ledgerType = "Others",
                            description = "Balance ($balanceStatus)",
                            notes = notes,
                            date = dateSold,
                            time = convertTimestampToLocalTime(timestamp),
                            timestamp = timestamp,
                            sortOrder = sortOrder,
                            sourceCollection = "accessory"
                        ))
                    }
                }
            }
        }

        return entries
    }

    /**
     * Build all credit entries from cached transactions (for All Credits view)
     */
    private fun buildAllCreditsFromCache(): List<LedgerEntryDisplay> {
        val entries = mutableListOf<LedgerEntryDisplay>()

        // Service transaction credits
        for (transaction in cachedTransactions) {
            // Add credit entry from each transaction
            if (transaction.creditLedgerType.isNotEmpty() && transaction.creditAmount > 0) {
                entries.add(
                    LedgerEntryDisplay(
                        id = "${transaction.id}_credit",
                        transactionId = transaction.id,
                        transactionNumber = transaction.ledgerTransactionNumber,
                        transactionType = transaction.transactionType,
                        entryType = "CREDIT",
                        amount = transaction.creditAmount,
                        ledgerType = transaction.creditLedgerType,
                        description = transaction.creditDescription,
                        notes = transaction.notes,
                        date = transaction.dateSaved,
                        time = convertTimestampToLocalTime(transaction.timestamp),
                        timestamp = transaction.timestamp,
                        sortOrder = transaction.sortOrder,
                        sourceCollection = "service"
                    )
                )
            }
        }

        // Device transaction credits (from all ledgers)
        for (ledger in listOf("Cash", "GCash", "PayMaya", "Others")) {
            entries.addAll(buildDeviceLedgerEntries(ledger).filter { it.entryType == "CREDIT" })
        }

        // Accessory transaction credits (from all ledgers)
        for (ledger in listOf("Cash", "GCash", "PayMaya", "Others")) {
            entries.addAll(buildAccessoryLedgerEntries(ledger).filter { it.entryType == "CREDIT" })
        }

        // Remove duplicates and sort
        return entries.distinctBy { it.id }.sortedWith(compareBy(
            { it.sortOrder },
            { it.timestamp?.seconds ?: 0L }
        ))
    }

    private fun displayLedgerFromCache() {
        showingAllCredits = false

        // Clear toggled entries when switching ledgers
        toggledEntries.clear()

        // Update button states
        updateButtonStates(currentLedgerType)

        // Update title
        binding.ledgerTitle.text = "$currentLedgerType Ledger"

        // Build entries from cache and apply filter
        val allEntries = buildLedgerEntriesFromCache()
        val entries = filterEntriesByTransactionType(allEntries)

        // Assign display sequence numbers (1-based, in order of appearance)
        entries.forEachIndexed { index, entry ->
            entry.displaySequence = index + 1
        }

        // Calculate totals from filtered entries
        val creditTotal = entries.filter { it.entryType == "CREDIT" }
            .sumOf { it.amount }
        val debitTotal = entries.filter { it.entryType == "DEBIT" }
            .sumOf { it.amount }
        val filteredBalance = creditTotal - debitTotal

        // Update amounts
        binding.totalAmount.text = formatCurrency(filteredBalance)
        binding.creditAmount.text = formatCurrency(creditTotal)
        binding.debitAmount.text = formatCurrency(debitTotal)

        // Set total color based on value
        if (filteredBalance >= 0) {
            binding.totalAmount.setTextColor(
                ContextCompat.getColor(this, android.R.color.black)
            )
        } else {
            binding.totalAmount.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_red_dark)
            )
        }

        // Load entries
        if (entries.isEmpty()) {
            binding.emptyMessage.text = "No transactions for this date"
            binding.emptyMessage.visibility = View.VISIBLE
            binding.ledgerRecyclerView.visibility = View.GONE
            currentAdapter = null
        } else {
            binding.emptyMessage.visibility = View.GONE
            binding.ledgerRecyclerView.visibility = View.VISIBLE

            // Create mutable list for drag and drop
            val mutableEntries = entries.toMutableList()
            currentAdapter = LedgerEntriesAdapter(
                entries = mutableEntries,
                showPaymentSource = true,
                canReorder = true,
                toggledEntries = toggledEntries,
                onEntryClick = { entryId ->
                    toggleEntryBackground(entryId)
                }
            )
            binding.ledgerRecyclerView.adapter = currentAdapter
        }
    }

    private fun showAllCredits() {
        showingAllCredits = true

        // Clear toggled entries when switching to All Credits
        toggledEntries.clear()

        // Reset button states (none selected)
        binding.cashButton.alpha = 0.6f
        binding.gcashButton.alpha = 0.6f
        binding.paymayaButton.alpha = 0.6f
        binding.othersButton.alpha = 0.6f

        // Update title
        binding.ledgerTitle.text = "All Credit Transactions"

        // Build all credit entries from cache and apply filter
        val allCreditEntries = buildAllCreditsFromCache()
        val entries = filterEntriesByTransactionType(allCreditEntries)

        // Assign display sequence numbers (1-based, in order of appearance)
        entries.forEachIndexed { index, entry ->
            entry.displaySequence = index + 1
        }

        // Calculate totals from filtered entries
        val creditTotal = entries.sumOf { it.amount }

        // Update amounts
        binding.totalAmount.text = formatCurrency(creditTotal)
        binding.creditAmount.text = formatCurrency(creditTotal)
        binding.debitAmount.text = formatCurrency(0.0)

        // Set total color
        binding.totalAmount.setTextColor(
            ContextCompat.getColor(this, android.R.color.black)
        )

        // Load entries
        if (entries.isEmpty()) {
            binding.emptyMessage.text = "No credit transactions for this date"
            binding.emptyMessage.visibility = View.VISIBLE
            binding.ledgerRecyclerView.visibility = View.GONE
            currentAdapter = null
        } else {
            binding.emptyMessage.visibility = View.GONE
            binding.ledgerRecyclerView.visibility = View.VISIBLE

            // Set adapter with canReorder = true for All Credits view now
            val mutableEntries = entries.toMutableList()
            currentAdapter = LedgerEntriesAdapter(
                entries = mutableEntries,
                showPaymentSource = true,
                canReorder = true,
                toggledEntries = toggledEntries,
                onEntryClick = { entryId ->
                    toggleEntryBackground(entryId)
                }
            )
            binding.ledgerRecyclerView.adapter = currentAdapter
        }
    }

    private fun toggleEntryBackground(entryId: String) {
        if (toggledEntries.contains(entryId)) {
            toggledEntries.remove(entryId)
        } else {
            toggledEntries.add(entryId)
        }
        // Notify adapter to update the specific item
        currentAdapter?.notifyDataSetChanged()
    }

    private fun updateButtonStates(selectedType: String) {
        // Reset all button alphas
        binding.cashButton.alpha = 0.6f
        binding.gcashButton.alpha = 0.6f
        binding.paymayaButton.alpha = 0.6f
        binding.othersButton.alpha = 0.6f

        // Highlight selected button
        when (selectedType) {
            "Cash" -> binding.cashButton.alpha = 1.0f
            "GCash" -> binding.gcashButton.alpha = 1.0f
            "PayMaya" -> binding.paymayaButton.alpha = 1.0f
            "Others" -> binding.othersButton.alpha = 1.0f
        }
    }

    private fun formatCurrency(amount: Double): String {
        return "₱${String.format("%,.2f", amount)}"
    }

    private fun getCurrentDate(): String {
        val timeZone = TimeZone.getTimeZone("GMT+08:00")
        val format = SimpleDateFormat("M/d/yyyy", Locale.US)
        format.timeZone = timeZone
        return format.format(Date())
    }

    private fun updateDateLabel() {
        val today = getCurrentDate()
        if (selectedDate == today) {
            binding.dateLabel.text = "Today"
        } else {
            binding.dateLabel.text = selectedDate
        }
    }

    private fun convertDisplayDateToQueryFormat(displayDate: String): String {
        return try {
            val inputFormat = SimpleDateFormat("M/d/yyyy", Locale.US)
            val outputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val date = inputFormat.parse(displayDate)
            outputFormat.format(date ?: Date())
        } catch (e: Exception) {
            val outputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            outputFormat.format(Date())
        }
    }

    private fun convertTimestampToLocalTime(timestamp: Timestamp?): String {
        if (timestamp == null) return ""
        return try {
            val date = timestamp.toDate()
            val format = SimpleDateFormat("h:mm a", Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT+08:00")
            format.format(date)
        } catch (e: Exception) {
            ""
        }
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00"))

        // Parse current selected date
        try {
            val format = SimpleDateFormat("M/d/yyyy", Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT+08:00")
            val date = format.parse(selectedDate)
            if (date != null) {
                calendar.time = date
            }
        } catch (e: Exception) {
            // Use current date if parsing fails
        }

        // Create custom dialog
        val dialogView = layoutInflater.inflate(R.layout.dialog_date_picker, null)
        val datePicker = dialogView.findViewById<android.widget.DatePicker>(R.id.datePicker)
        val cancelButton = dialogView.findViewById<android.widget.Button>(R.id.cancelButton)
        val okButton = dialogView.findViewById<android.widget.Button>(R.id.okButton)

        // Set initial date
        datePicker.init(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
            null
        )

        // Create dialog
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // Set button click listeners
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        okButton.setOnClickListener {
            // Update selected date
            selectedDate = "${datePicker.month + 1}/${datePicker.dayOfMonth}/${datePicker.year}"
            updateDateLabel()

            // Reload transactions from Firebase with new date
            loadTransactionsFromFirebase()

            dialog.dismiss()
        }

        dialog.show()

        // Adjust dialog size after showing
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt(), // 85% of screen width
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun addDividerToButtonBar(parent: android.view.ViewGroup) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)

            // Look for the button bar (usually has id "buttonPanel")
            if (child.id == android.R.id.button1 || child.id == android.R.id.button2) {
                // Found a button, add border to its parent
                (child.parent as? android.view.View)?.let { buttonBar ->
                    buttonBar.setPadding(
                        buttonBar.paddingLeft,
                        32, // Add top padding
                        buttonBar.paddingRight,
                        buttonBar.paddingBottom
                    )
                    // Add top border
                    buttonBar.background = android.graphics.drawable.LayerDrawable(
                        arrayOf(
                            android.graphics.drawable.ColorDrawable(
                                ContextCompat.getColor(this, R.color.light_gray)
                            ).apply {
                                setBounds(0, 0, 10000, 2)
                            },
                            android.graphics.drawable.ColorDrawable(
                                android.graphics.Color.WHITE
                            )
                        )
                    )
                }
                return
            }

            if (child is android.view.ViewGroup) {
                addDividerToButtonBar(child)
            }
        }
    }

    /**
     * RecyclerView Adapter for Ledger Entries
     */
    inner class LedgerEntriesAdapter(
        private val entries: MutableList<LedgerEntryDisplay>,
        private val showPaymentSource: Boolean = false,
        private val canReorder: Boolean = false,
        private val toggledEntries: MutableSet<String>,
        private val onEntryClick: (String) -> Unit
    ) : RecyclerView.Adapter<LedgerEntriesAdapter.ViewHolder>() {

        fun swapItems(fromPosition: Int, toPosition: Int) {
            if (fromPosition < toPosition) {
                for (i in fromPosition until toPosition) {
                    Collections.swap(entries, i, i + 1)
                }
            } else {
                for (i in fromPosition downTo toPosition + 1) {
                    Collections.swap(entries, i, i - 1)
                }
            }
            notifyItemMoved(fromPosition, toPosition)
        }

        fun getEntries(): List<LedgerEntryDisplay> = entries

        inner class ViewHolder(private val binding: ItemLedgerEntryBinding) :
            RecyclerView.ViewHolder(binding.root) {

            init {
                // Set up click listener on the card view
                binding.cardView.setOnClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        val entry = entries[position]
                        onEntryClick(entry.id)
                    }
                }
            }

            fun setDragging(isDragging: Boolean) {
                if (isDragging) {
                    // Change stroke color to blue when dragging
                    binding.cardView.strokeColor = ContextCompat.getColor(itemView.context, android.R.color.holo_blue_dark)
                    binding.cardView.strokeWidth = 4  // Make border thicker
                    binding.cardView.cardElevation = 8f  // Increase elevation for lifted effect
                } else {
                    // Reset to normal state
                    binding.cardView.strokeColor = ContextCompat.getColor(itemView.context, R.color.light_gray)
                    binding.cardView.strokeWidth = 2  // Normal border width
                    binding.cardView.cardElevation = 2f  // Normal elevation
                }
            }

            fun setSwiping(isSwiping: Boolean) {
                if (isSwiping) {
                    // Optional: Add visual feedback when swiping
                    binding.cardView.alpha = 0.8f
                } else {
                    binding.cardView.alpha = 1.0f
                }
            }

            fun bind(entry: LedgerEntryDisplay) {
                // Add visual indicator that item can be dragged (only in individual ledger views)
                if (canReorder) {
                    binding.root.alpha = 1.0f
                } else {
                    binding.root.alpha = 1.0f
                }

                // Set background color based on toggle state
                if (toggledEntries.contains(entry.id)) {
                    // Toggled state - subtle light gray background (same as system light_gray)
                    binding.cardView.setCardBackgroundColor(
                        ContextCompat.getColor(itemView.context, R.color.toggle_gray)
                    )
                } else {
                    // Normal state - white background
                    binding.cardView.setCardBackgroundColor(
                        ContextCompat.getColor(itemView.context, android.R.color.white)
                    )
                }

                // Set transaction number (use displaySequence for unified numbering across all sources)
                binding.transactionNumber.text = "#${entry.displaySequence.toString().padStart(3, '0')}"

                // Set transaction type with color coding
                binding.transactionType.text = entry.transactionType

                // Apply color to transaction type based on the type
                val transactionTypeColor = when (entry.transactionType) {
                    // Service transaction types
                    "Cash In" -> ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark)
                    "Cash Out" -> ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark)
                    "Mobile Loading Service" -> ContextCompat.getColor(itemView.context, R.color.mobile_loading_purple)
                    "Skyro Payment" -> ContextCompat.getColor(itemView.context, android.R.color.holo_blue_dark)
                    "Home Credit Payment" -> ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark)
                    "Misc Payment" -> ContextCompat.getColor(itemView.context, android.R.color.darker_gray)
                    // Device and Accessory transaction types
                    "Cash Transaction" -> ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark)
                    "Home Credit Transaction" -> ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark)
                    "Skyro Transaction" -> ContextCompat.getColor(itemView.context, android.R.color.holo_blue_dark)
                    "In-House Installment" -> ContextCompat.getColor(itemView.context, android.R.color.holo_orange_dark)
                    else -> ContextCompat.getColor(itemView.context, android.R.color.black)
                }
                binding.transactionType.setTextColor(transactionTypeColor)

                // Show payment source badge
                if (showPaymentSource) {
                    binding.paymentSource.visibility = View.VISIBLE
                    binding.paymentSource.text = entry.ledgerType

                    // Create rounded background with appropriate color
                    val shape = GradientDrawable()
                    shape.cornerRadius = 20f
                    when (entry.ledgerType) {
                        "Cash" -> shape.setColor(ContextCompat.getColor(itemView.context, R.color.cash_dark_green))
                        "GCash" -> shape.setColor(ContextCompat.getColor(itemView.context, android.R.color.holo_blue_dark))
                        "PayMaya" -> shape.setColor(ContextCompat.getColor(itemView.context, android.R.color.holo_green_light))
                        "Others" -> shape.setColor(ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark))
                    }
                    binding.paymentSource.background = shape
                } else {
                    binding.paymentSource.visibility = View.GONE
                }

                // Set entry type (CREDIT or DEBIT)
                binding.entryType.text = entry.entryType

                // Set entry type color and background
                if (entry.entryType == "CREDIT") {
                    binding.entryType.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.green)
                    )
                    binding.entryType.setBackgroundResource(R.drawable.rounded_background_green)
                } else {
                    binding.entryType.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.red)
                    )
                    binding.entryType.setBackgroundResource(R.drawable.rounded_background_red)
                }

                // Set amount with proper formatting
                binding.amount.text = formatCurrency(entry.amount)

                // Set amount color based on entry type
                if (entry.entryType == "CREDIT") {
                    binding.amount.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.green)
                    )
                } else {
                    binding.amount.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.red)
                    )
                }

                // Set description and notes
                val descriptionText = StringBuilder()
                if (entry.description.isNotEmpty()) {
                    descriptionText.append(entry.description)
                }
                if (entry.notes.isNotEmpty()) {
                    if (descriptionText.isNotEmpty()) {
                        descriptionText.append("\n")
                    }
                    descriptionText.append("Notes: ${entry.notes}")
                }

                if (descriptionText.isNotEmpty()) {
                    binding.notes.visibility = View.VISIBLE
                    binding.notes.text = descriptionText.toString()
                    binding.notes.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.techcity_blue)
                    )
                } else {
                    binding.notes.visibility = View.GONE
                }

                // Set date and time
                binding.date.text = entry.date
                binding.time.text = entry.time
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemLedgerEntryBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(entries[position])
        }

        override fun getItemCount(): Int = entries.size
    }
}