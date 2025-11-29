package com.techcity.techcitysuite

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import com.techcity.techcitysuite.databinding.ActivityDeviceTransactionListBinding
import com.techcity.techcitysuite.databinding.ItemDeviceTransactionBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class DeviceTransactionListActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityDeviceTransactionListBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Current filter
    private var currentFilter: TransactionTypeFilter = TransactionTypeFilter.ALL

    // Selected date for filtering
    private var selectedDate: String = ""

    // List of transactions (using a data class that includes the server timestamp)
    private var transactions: MutableList<DeviceTransactionDisplay> = mutableListOf()
    private var adapter: DeviceTransactionAdapter? = null

    // ItemTouchHelper for drag and drop
    private lateinit var itemTouchHelper: ItemTouchHelper

    // Track toggled entries by their document ID (for visual comparison)
    private val toggledEntries = mutableSetOf<String>()

    // Filter enum
    enum class TransactionTypeFilter {
        ALL,
        CASH,
        HOME_CREDIT,
        SKYRO,
        IN_HOUSE
    }

    // Color constants for transaction types
    private val COLOR_ALL = R.color.techcity_blue
    private val COLOR_CASH = R.color.cash_dark_green
    private val COLOR_HOME_CREDIT = R.color.red
    private val COLOR_SKYRO = R.color.skyro_light_blue
    private val COLOR_IN_HOUSE = R.color.purple

    // Data class to hold transaction with server timestamp for local time conversion
    data class DeviceTransactionDisplay(
        val transaction: DeviceTransaction,
        val serverTimestamp: Timestamp?,
        var sortOrder: Int = 0  // For custom ordering
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
        binding = ActivityDeviceTransactionListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firestore
        db = Firebase.firestore

        // Initialize selected date to today (Philippine time)
        selectedDate = getCurrentDatePhilippines()
        updateDateLabel()

        // Setup UI
        setupRecyclerView()
        setupDragAndDrop()
        setupFilterButtons()
        setupAddButton()
        setupMenuButton()

        // Load transactions
        loadTransactions()
    }

    override fun onResume() {
        super.onResume()
        // Refresh list when returning from creating a new transaction
        loadTransactions()
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
        binding.transactionRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun setupDragAndDrop() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0  // No swipe
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition

                if (adapter != null) {
                    adapter?.swapItems(fromPosition, toPosition)

                    // Save the new order to Firebase
                    saveOrderToFirebase()

                    return true
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // No swipe action
            }

            override fun isLongPressDragEnabled(): Boolean {
                return true
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)

                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    // Item is being dragged - change border color to blue
                    viewHolder?.let {
                        if (it is DeviceTransactionAdapter.ViewHolder) {
                            it.setDragging(true)
                        }
                    }
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)

                // Dragging ended - reset appearance
                if (viewHolder is DeviceTransactionAdapter.ViewHolder) {
                    viewHolder.setDragging(false)
                }
            }
        }

        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(binding.transactionRecyclerView)
    }

    private fun setupFilterButtons() {
        // Set initial state - All selected
        updateFilterButtonStates(TransactionTypeFilter.ALL)

        binding.filterAllButton.setOnClickListener {
            currentFilter = TransactionTypeFilter.ALL
            updateFilterButtonStates(currentFilter)
            applyFilter()
        }

        binding.filterCashButton.setOnClickListener {
            currentFilter = TransactionTypeFilter.CASH
            updateFilterButtonStates(currentFilter)
            applyFilter()
        }

        binding.filterHomeCreditButton.setOnClickListener {
            currentFilter = TransactionTypeFilter.HOME_CREDIT
            updateFilterButtonStates(currentFilter)
            applyFilter()
        }

        binding.filterSkyroButton.setOnClickListener {
            currentFilter = TransactionTypeFilter.SKYRO
            updateFilterButtonStates(currentFilter)
            applyFilter()
        }

        binding.filterInHouseButton.setOnClickListener {
            currentFilter = TransactionTypeFilter.IN_HOUSE
            updateFilterButtonStates(currentFilter)
            applyFilter()
        }
    }

    private fun updateFilterButtonStates(selected: TransactionTypeFilter) {
        // Reset all buttons to unselected state (dimmed)
        binding.filterAllButton.alpha = 0.5f
        binding.filterCashButton.alpha = 0.5f
        binding.filterHomeCreditButton.alpha = 0.5f
        binding.filterSkyroButton.alpha = 0.5f
        binding.filterInHouseButton.alpha = 0.5f

        // Highlight selected button
        when (selected) {
            TransactionTypeFilter.ALL -> binding.filterAllButton.alpha = 1.0f
            TransactionTypeFilter.CASH -> binding.filterCashButton.alpha = 1.0f
            TransactionTypeFilter.HOME_CREDIT -> binding.filterHomeCreditButton.alpha = 1.0f
            TransactionTypeFilter.SKYRO -> binding.filterSkyroButton.alpha = 1.0f
            TransactionTypeFilter.IN_HOUSE -> binding.filterInHouseButton.alpha = 1.0f
        }
    }

    private fun setupAddButton() {
        binding.addButton.setOnClickListener {
            // Open DeviceTransactionActivity to create new transaction
            val intent = Intent(this, DeviceTransactionActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupMenuButton() {
        binding.menuButton.setOnClickListener {
            showDatePicker()
        }
    }

    // ============================================================================
    // END OF PART 3: UI SETUP METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 4: DATA LOADING METHODS
    // ============================================================================

    private fun loadTransactions() {
        binding.progressBar.visibility = View.VISIBLE
        binding.transactionRecyclerView.visibility = View.GONE
        binding.emptyMessage.visibility = View.GONE

        // Clear toggled entries when loading new data
        toggledEntries.clear()

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    fetchTransactionsFromFirebase()
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    transactions = result.toMutableList()
                    applyFilter()
                }
            } catch (e: Exception) {
                e.printStackTrace()  // Log to Logcat
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.emptyMessage.text = "Error loading transactions: ${e.message}"
                    binding.emptyMessage.visibility = View.VISIBLE
                    showMessage("Error: ${e.message}", true)
                }
            }
        }
    }

    private suspend fun fetchTransactionsFromFirebase(): List<DeviceTransactionDisplay> {
        // Convert selected date from M/d/yyyy to yyyy-MM-dd for query
        val queryDate = convertDisplayDateToQueryDate(selectedDate)

        // Query by date and status, order by timestamp ASCENDING (first entry at top)
        // Note: sortOrder is handled in code after fetching to support documents without this field
        val querySnapshot = db.collection(AppConstants.COLLECTION_DEVICE_TRANSACTIONS)
            .whereEqualTo("date", queryDate)
            .whereEqualTo("status", AppConstants.STATUS_COMPLETED)
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .get()
            .await()

        val results = querySnapshot.documents.mapNotNull { document ->
            try {
                // Manual mapping since we have nested objects
                val data = document.data ?: return@mapNotNull null

                // Get the server timestamp for local time conversion
                val serverTimestamp = data["timestamp"] as? Timestamp

                // Get sortOrder (default to 0 if not set)
                val sortOrder = (data["sortOrder"] as? Number)?.toInt() ?: 0

                val transaction = DeviceTransaction(
                    id = document.id,
                    date = data["date"] as? String ?: "",
                    month = data["month"] as? String ?: "",
                    year = data["year"] as? String ?: "",
                    dateSold = data["dateSold"] as? String ?: "",
                    time = data["time"] as? String ?: "",
                    user = data["user"] as? String ?: "",
                    userLocation = data["userLocation"] as? String ?: "",
                    deviceId = data["deviceId"] as? String ?: "",
                    inventoryDocumentId = data["inventoryDocumentId"] as? String ?: "",
                    originalStatus = data["originalStatus"] as? String ?: "",
                    originalLastUpdated = data["originalLastUpdated"] as? String ?: "",
                    newStatus = data["newStatus"] as? String ?: "",
                    newLastUpdated = data["newLastUpdated"] as? String ?: "",
                    imei1 = data["imei1"] as? String ?: "",
                    imei2 = data["imei2"] as? String ?: "",
                    serialNumber = data["serialNumber"] as? String ?: "",
                    identifierUsed = data["identifierUsed"] as? String ?: "",
                    deviceType = data["deviceType"] as? String ?: "",
                    manufacturer = data["manufacturer"] as? String ?: "",
                    model = data["model"] as? String ?: "",
                    ram = data["ram"] as? String ?: "",
                    storage = data["storage"] as? String ?: "",
                    color = data["color"] as? String ?: "",
                    originalRetailPrice = (data["originalRetailPrice"] as? Number)?.toDouble() ?: 0.0,
                    originalDealersPrice = (data["originalDealersPrice"] as? Number)?.toDouble() ?: 0.0,
                    price = (data["price"] as? Number)?.toDouble() ?: 0.0,
                    discountAmount = (data["discountAmount"] as? Number)?.toDouble() ?: 0.0,
                    discountPercent = (data["discountPercent"] as? Number)?.toDouble() ?: 0.0,
                    finalPrice = (data["finalPrice"] as? Number)?.toDouble() ?: 0.0,
                    transactionType = data["transactionType"] as? String ?: "",
                    cashPayment = parseCashPayment(data["cashPayment"] as? Map<*, *>),
                    homeCreditPayment = parseHomeCreditPayment(data["homeCreditPayment"] as? Map<*, *>),
                    skyroPayment = parseSkyroPayment(data["skyroPayment"] as? Map<*, *>),
                    inHouseInstallment = parseInHouseInstallment(data["inHouseInstallment"] as? Map<*, *>),
                    status = data["status"] as? String ?: "",
                    createdBy = data["createdBy"] as? String ?: "",
                    notes = data["notes"] as? String ?: ""
                )

                DeviceTransactionDisplay(transaction, serverTimestamp, sortOrder)
            } catch (e: Exception) {
                null
            }
        }

        // Sort results: first by sortOrder (if set), then by timestamp
        // Documents with sortOrder > 0 have been manually reordered
        // Documents with sortOrder = 0 use their natural timestamp order
        return results.sortedWith(compareBy({ it.sortOrder }, { it.serverTimestamp?.seconds ?: 0 }))
    }

    // Helper functions to parse nested payment objects
    private fun parseCashPayment(data: Map<*, *>?): CashPaymentDetails? {
        if (data == null) return null
        val accountData = data["accountDetails"] as? Map<*, *>
        return CashPaymentDetails(
            amountPaid = (data["amountPaid"] as? Number)?.toDouble() ?: 0.0,
            paymentSource = data["paymentSource"] as? String ?: "",
            accountDetails = AccountDetails(
                accountName = accountData?.get("accountName") as? String ?: "",
                accountType = accountData?.get("accountType") as? String ?: ""
            )
        )
    }

    private fun parseHomeCreditPayment(data: Map<*, *>?): HomeCreditPaymentDetails? {
        if (data == null) return null
        val accountData = data["accountDetails"] as? Map<*, *>
        return HomeCreditPaymentDetails(
            downpaymentAmount = (data["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0,
            downpaymentSource = data["downpaymentSource"] as? String ?: "",
            accountDetails = AccountDetails(
                accountName = accountData?.get("accountName") as? String ?: "",
                accountType = accountData?.get("accountType") as? String ?: ""
            ),
            brandZero = data["brandZero"] as? Boolean ?: false,
            brandZeroSubsidy = (data["brandZeroSubsidy"] as? Number)?.toDouble() ?: 0.0,
            subsidyPercent = (data["subsidyPercent"] as? Number)?.toDouble() ?: 0.0,
            balance = (data["balance"] as? Number)?.toDouble() ?: 0.0,
            isBalancePaid = data["isBalancePaid"] as? Boolean ?: false
        )
    }

    private fun parseSkyroPayment(data: Map<*, *>?): SkyroPaymentDetails? {
        if (data == null) return null
        val accountData = data["accountDetails"] as? Map<*, *>
        return SkyroPaymentDetails(
            downpaymentAmount = (data["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0,
            downpaymentSource = data["downpaymentSource"] as? String ?: "",
            accountDetails = AccountDetails(
                accountName = accountData?.get("accountName") as? String ?: "",
                accountType = accountData?.get("accountType") as? String ?: ""
            ),
            brandZero = data["brandZero"] as? Boolean ?: false,
            brandZeroSubsidy = (data["brandZeroSubsidy"] as? Number)?.toDouble() ?: 0.0,
            subsidyPercent = (data["subsidyPercent"] as? Number)?.toDouble() ?: 0.0,
            balance = (data["balance"] as? Number)?.toDouble() ?: 0.0,
            isBalancePaid = data["isBalancePaid"] as? Boolean ?: false
        )
    }

    private fun parseInHouseInstallment(data: Map<*, *>?): InHouseInstallmentDetails? {
        if (data == null) return null
        val accountData = data["accountDetails"] as? Map<*, *>
        return InHouseInstallmentDetails(
            downpaymentAmount = (data["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0,
            downpaymentSource = data["downpaymentSource"] as? String ?: "",
            accountDetails = AccountDetails(
                accountName = accountData?.get("accountName") as? String ?: "",
                accountType = accountData?.get("accountType") as? String ?: ""
            ),
            interestPercent = (data["interestPercent"] as? Number)?.toDouble() ?: 0.0,
            interestAmount = (data["interestAmount"] as? Number)?.toDouble() ?: 0.0,
            monthsToPay = (data["monthsToPay"] as? Number)?.toInt() ?: 0,
            monthlyAmount = (data["monthlyAmount"] as? Number)?.toDouble() ?: 0.0,
            balance = (data["balance"] as? Number)?.toDouble() ?: 0.0,
            totalAmountDue = (data["totalAmountDue"] as? Number)?.toDouble() ?: 0.0,
            isBalancePaid = data["isBalancePaid"] as? Boolean ?: false,
            remainingBalance = (data["remainingBalance"] as? Number)?.toDouble() ?: 0.0
        )
    }

    // ============================================================================
    // END OF PART 4: DATA LOADING METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 5: FILTER AND DISPLAY METHODS
    // ============================================================================

    private fun applyFilter() {
        val filteredList = when (currentFilter) {
            TransactionTypeFilter.ALL -> transactions
            TransactionTypeFilter.CASH -> transactions.filter {
                it.transaction.transactionType == AppConstants.TRANSACTION_TYPE_CASH
            }
            TransactionTypeFilter.HOME_CREDIT -> transactions.filter {
                it.transaction.transactionType == AppConstants.TRANSACTION_TYPE_HOME_CREDIT
            }
            TransactionTypeFilter.SKYRO -> transactions.filter {
                it.transaction.transactionType == AppConstants.TRANSACTION_TYPE_SKYRO
            }
            TransactionTypeFilter.IN_HOUSE -> transactions.filter {
                it.transaction.transactionType == AppConstants.TRANSACTION_TYPE_IN_HOUSE
            }
        }

        updateSummary(filteredList)
        displayTransactions(filteredList.toMutableList())
    }

    private fun updateSummary(list: List<DeviceTransactionDisplay>) {
        val totalSales = list.sumOf { it.transaction.finalPrice }
        val count = list.size

        binding.totalSalesAmount.text = formatCurrency(totalSales)
        binding.transactionCount.text = count.toString()

        // Update Total Sales label with filter indicator
        val filterLabel = when (currentFilter) {
            TransactionTypeFilter.ALL -> "Total Sales (All)"
            TransactionTypeFilter.CASH -> "Total Sales (Cash)"
            TransactionTypeFilter.HOME_CREDIT -> "Total Sales (HC)"
            TransactionTypeFilter.SKYRO -> "Total Sales (Skyro)"
            TransactionTypeFilter.IN_HOUSE -> "Total Sales (IH)"
        }
        binding.totalSalesLabel.text = filterLabel

        // Show/Hide DP and Balance based on filter
        when (currentFilter) {
            TransactionTypeFilter.HOME_CREDIT,
            TransactionTypeFilter.SKYRO,
            TransactionTypeFilter.IN_HOUSE -> {
                // Show DP and Balance columns
                binding.dpSummaryLayout.visibility = View.VISIBLE
                binding.balanceSummaryLayout.visibility = View.VISIBLE

                // Calculate totals based on transaction type
                var totalDP = 0.0
                var totalBalance = 0.0

                list.forEach { item ->
                    when (item.transaction.transactionType) {
                        AppConstants.TRANSACTION_TYPE_HOME_CREDIT -> {
                            item.transaction.homeCreditPayment?.let { hc ->
                                totalDP += hc.downpaymentAmount
                                totalBalance += hc.balance
                            }
                        }
                        AppConstants.TRANSACTION_TYPE_SKYRO -> {
                            item.transaction.skyroPayment?.let { skyro ->
                                totalDP += skyro.downpaymentAmount
                                totalBalance += skyro.balance
                            }
                        }
                        AppConstants.TRANSACTION_TYPE_IN_HOUSE -> {
                            item.transaction.inHouseInstallment?.let { ih ->
                                totalDP += ih.downpaymentAmount
                                totalBalance += ih.remainingBalance
                            }
                        }
                    }
                }

                binding.dpSummaryAmount.text = formatCurrency(totalDP)
                binding.balanceSummaryAmount.text = formatCurrency(totalBalance)
            }
            else -> {
                // Hide DP and Balance columns for All and Cash
                binding.dpSummaryLayout.visibility = View.GONE
                binding.balanceSummaryLayout.visibility = View.GONE
            }
        }
    }

    private fun displayTransactions(list: MutableList<DeviceTransactionDisplay>) {
        if (list.isEmpty()) {
            binding.emptyMessage.text = "No transactions for this date"
            binding.emptyMessage.visibility = View.VISIBLE
            binding.transactionRecyclerView.visibility = View.GONE
            adapter = null
        } else {
            binding.emptyMessage.visibility = View.GONE
            binding.transactionRecyclerView.visibility = View.VISIBLE

            adapter = DeviceTransactionAdapter(
                items = list,
                toggledEntries = toggledEntries,
                onItemClick = { item ->
                    // Toggle entry background for visual comparison
                    toggleEntryBackground(item.transaction.id)
                }
            )
            binding.transactionRecyclerView.adapter = adapter
        }
    }

    /**
     * Toggle entry background for visual comparison
     */
    private fun toggleEntryBackground(entryId: String) {
        if (toggledEntries.contains(entryId)) {
            toggledEntries.remove(entryId)
        } else {
            toggledEntries.add(entryId)
        }
        // Notify adapter to update the view
        adapter?.notifyDataSetChanged()
    }

    /**
     * Save the new order to Firebase
     */
    private fun saveOrderToFirebase() {
        val currentList = adapter?.getItems() ?: return

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Update sortOrder for each document
                    currentList.forEachIndexed { index, item ->
                        db.collection(AppConstants.COLLECTION_DEVICE_TRANSACTIONS)
                            .document(item.transaction.id)
                            .update("sortOrder", index)
                            .await()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    showMessage("Error saving order: ${e.message}", true)
                }
            }
        }
    }

    // ============================================================================
    // END OF PART 5: FILTER AND DISPLAY METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 6: DATE PICKER METHODS
    // ============================================================================

    private fun showDatePicker() {
        // Parse current selected date
        val format = SimpleDateFormat("M/d/yyyy", Locale.US)
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00"))

        try {
            calendar.time = format.parse(selectedDate) ?: Date()
        } catch (e: Exception) {
            calendar.time = Date()
        }

        val datePickerDialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth)
                selectedDate = format.format(calendar.time)
                updateDateLabel()
                loadTransactions()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        datePickerDialog.show()
    }

    private fun updateDateLabel() {
        val today = getCurrentDatePhilippines()
        if (selectedDate == today) {
            binding.dateLabel.text = "Today"
        } else {
            binding.dateLabel.text = selectedDate
        }
    }

    // ============================================================================
    // END OF PART 6: DATE PICKER METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 7: HELPER METHODS
    // ============================================================================

    private fun getCurrentDatePhilippines(): String {
        val timeZone = TimeZone.getTimeZone("GMT+08:00")
        val format = SimpleDateFormat("M/d/yyyy", Locale.US)
        format.timeZone = timeZone
        return format.format(Date())
    }

    private fun convertDisplayDateToQueryDate(displayDate: String): String {
        // Convert M/d/yyyy to yyyy-MM-dd
        return try {
            val inputFormat = SimpleDateFormat("M/d/yyyy", Locale.US)
            val outputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val date = inputFormat.parse(displayDate)
            outputFormat.format(date ?: Date())
        } catch (e: Exception) {
            // Return today's date in query format if parsing fails
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        }
    }

    private fun formatCurrency(amount: Double): String {
        return "₱${String.format("%,.2f", amount)}"
    }

    private fun showMessage(message: String, isError: Boolean) {
        Toast.makeText(this, message, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    /**
     * Get the color resource for a transaction type
     */
    private fun getTransactionTypeColor(transactionType: String): Int {
        return when (transactionType) {
            AppConstants.TRANSACTION_TYPE_CASH -> COLOR_CASH
            AppConstants.TRANSACTION_TYPE_HOME_CREDIT -> COLOR_HOME_CREDIT
            AppConstants.TRANSACTION_TYPE_SKYRO -> COLOR_SKYRO
            AppConstants.TRANSACTION_TYPE_IN_HOUSE -> COLOR_IN_HOUSE
            else -> COLOR_ALL
        }
    }

    /**
     * Get the badge label for a transaction type (FULL names for card display)
     */
    private fun getTransactionTypeBadgeLabel(transactionType: String): String {
        return when (transactionType) {
            AppConstants.TRANSACTION_TYPE_CASH -> "CASH"
            AppConstants.TRANSACTION_TYPE_HOME_CREDIT -> "HOME CREDIT"
            AppConstants.TRANSACTION_TYPE_SKYRO -> "SKYRO"
            AppConstants.TRANSACTION_TYPE_IN_HOUSE -> "IN-HOUSE"
            else -> "OTHER"
        }
    }

    /**
     * Convert server timestamp to local Philippine time (12-hour format)
     */
    private fun convertServerTimestampToLocalTime(timestamp: Timestamp?): String {
        if (timestamp == null) return ""

        return try {
            val date = timestamp.toDate()
            val outputFormat = SimpleDateFormat("h:mm a", Locale.US)
            outputFormat.timeZone = TimeZone.getTimeZone("GMT+08:00")
            outputFormat.format(date)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Convert server timestamp to local Philippine date
     */
    private fun convertServerTimestampToLocalDate(timestamp: Timestamp?): String {
        if (timestamp == null) return ""

        return try {
            val date = timestamp.toDate()
            val outputFormat = SimpleDateFormat("M/d/yyyy", Locale.US)
            outputFormat.timeZone = TimeZone.getTimeZone("GMT+08:00")
            outputFormat.format(date)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Get the color for a payment source
     */
    private fun getPaymentSourceColor(paymentSource: String): Int {
        return when (paymentSource) {
            AppConstants.PAYMENT_SOURCE_CASH -> R.color.cash_dark_green
            AppConstants.PAYMENT_SOURCE_GCASH -> R.color.techcity_blue
            AppConstants.PAYMENT_SOURCE_PAYMAYA -> R.color.green
            AppConstants.PAYMENT_SOURCE_BANK_TRANSFER -> R.color.purple
            AppConstants.PAYMENT_SOURCE_CREDIT_CARD -> R.color.red
            AppConstants.PAYMENT_SOURCE_OTHERS -> R.color.gray
            else -> R.color.gray
        }
    }

    // ============================================================================
    // END OF PART 7: HELPER METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 8: RECYCLERVIEW ADAPTER
    // ============================================================================

    inner class DeviceTransactionAdapter(
        private val items: MutableList<DeviceTransactionDisplay>,
        private val toggledEntries: MutableSet<String>,
        private val onItemClick: (DeviceTransactionDisplay) -> Unit
    ) : RecyclerView.Adapter<DeviceTransactionAdapter.ViewHolder>() {

        /**
         * Swap items for drag and drop reordering
         */
        fun swapItems(fromPosition: Int, toPosition: Int) {
            if (fromPosition < toPosition) {
                for (i in fromPosition until toPosition) {
                    java.util.Collections.swap(items, i, i + 1)
                }
            } else {
                for (i in fromPosition downTo toPosition + 1) {
                    java.util.Collections.swap(items, i, i - 1)
                }
            }
            notifyItemMoved(fromPosition, toPosition)
        }

        /**
         * Get current items list
         */
        fun getItems(): List<DeviceTransactionDisplay> = items

        inner class ViewHolder(private val binding: ItemDeviceTransactionBinding) :
            RecyclerView.ViewHolder(binding.root) {

            init {
                binding.cardView.setOnClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onItemClick(items[position])
                    }
                }
            }

            /**
             * Set visual feedback when dragging
             */
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

            fun bind(item: DeviceTransactionDisplay, orderNumber: Int) {
                val transaction = item.transaction

                // Set order number
                binding.orderNumberText.text = "#$orderNumber"

                // Set background color based on toggle state
                if (toggledEntries.contains(transaction.id)) {
                    // Toggled state - subtle light blue/gray background
                    binding.cardView.setCardBackgroundColor(
                        ContextCompat.getColor(itemView.context, R.color.toggle_gray)
                    )
                } else {
                    // Normal state - white background
                    binding.cardView.setCardBackgroundColor(
                        ContextCompat.getColor(itemView.context, android.R.color.white)
                    )
                }

                // Set manufacturer and model
                binding.manufacturerText.text = transaction.manufacturer
                binding.modelText.text = transaction.model

                // Set variant (RAM + Storage - Color)
                val ramDisplay = if (transaction.ram.contains("GB", ignoreCase = true)) {
                    transaction.ram
                } else {
                    "${transaction.ram}GB"
                }
                val storageDisplay = if (transaction.storage.contains("GB", ignoreCase = true)) {
                    transaction.storage
                } else {
                    "${transaction.storage}GB"
                }
                binding.variantText.text = "$ramDisplay + $storageDisplay - ${transaction.color}"

                // Set IMEI or Serial Number (right side, blue color)
                val identifier = when {
                    transaction.imei1.isNotEmpty() -> "IMEI: ${transaction.imei1}"
                    transaction.serialNumber.isNotEmpty() -> "S/N: ${transaction.serialNumber}"
                    else -> ""
                }
                if (identifier.isNotEmpty()) {
                    binding.imeiText.text = identifier
                    binding.imeiText.visibility = View.VISIBLE
                } else {
                    binding.imeiText.visibility = View.GONE
                }

                // Set price
                binding.priceText.text = formatCurrency(transaction.finalPrice)

                // Set discount if any
                if (transaction.discountAmount > 0) {
                    binding.discountLayout.visibility = View.VISIBLE
                    binding.discountText.text = "-${formatCurrency(transaction.discountAmount)}"
                } else {
                    binding.discountLayout.visibility = View.GONE
                }

                // Set transaction type badge with proper color (FULL name)
                val badgeLabel = getTransactionTypeBadgeLabel(transaction.transactionType)
                val badgeColor = getTransactionTypeColor(transaction.transactionType)
                binding.transactionTypeBadge.text = badgeLabel
                binding.transactionTypeBadge.backgroundTintList =
                    ContextCompat.getColorStateList(itemView.context, badgeColor)

                // Handle payment info based on transaction type
                when (transaction.transactionType) {
                    AppConstants.TRANSACTION_TYPE_CASH -> {
                        binding.financingDetailsLayout.visibility = View.GONE
                        binding.brandZeroLayout.visibility = View.GONE
                        transaction.cashPayment?.let { cash ->
                            binding.paymentInfoBadge.text = "via ${cash.paymentSource}"
                            binding.paymentInfoBadge.setTextColor(
                                ContextCompat.getColor(itemView.context, getPaymentSourceColor(cash.paymentSource))
                            )
                            binding.paymentInfoBadge.visibility = View.VISIBLE
                        }
                    }
                    AppConstants.TRANSACTION_TYPE_HOME_CREDIT -> {
                        binding.paymentInfoBadge.visibility = View.GONE
                        binding.financingDetailsLayout.visibility = View.VISIBLE
                        transaction.homeCreditPayment?.let { hc ->
                            // Downpayment
                            binding.downpaymentText.text = formatCurrency(hc.downpaymentAmount)

                            // Downpayment source badge
                            if (hc.downpaymentSource.isNotEmpty() && hc.downpaymentAmount > 0) {
                                binding.downpaymentSourceBadge.text = hc.downpaymentSource
                                binding.downpaymentSourceBadge.backgroundTintList =
                                    ContextCompat.getColorStateList(itemView.context, getPaymentSourceColor(hc.downpaymentSource))
                                binding.downpaymentSourceBadge.visibility = View.VISIBLE
                            } else {
                                binding.downpaymentSourceBadge.visibility = View.GONE
                            }

                            // Brand Zero info (between downpayment and balance)
                            if (hc.brandZero) {
                                binding.brandZeroLayout.visibility = View.VISIBLE
                                binding.subsidyText.text = formatCurrency(hc.brandZeroSubsidy)
                            } else {
                                binding.brandZeroLayout.visibility = View.GONE
                            }

                            // Balance
                            binding.balanceText.text = formatCurrency(hc.balance)
                            val balanceColor = if (hc.isBalancePaid) R.color.cash_dark_green else R.color.red
                            binding.balanceText.setTextColor(
                                ContextCompat.getColor(itemView.context, balanceColor)
                            )
                        }
                    }
                    AppConstants.TRANSACTION_TYPE_SKYRO -> {
                        binding.paymentInfoBadge.visibility = View.GONE
                        binding.financingDetailsLayout.visibility = View.VISIBLE
                        transaction.skyroPayment?.let { skyro ->
                            // Downpayment
                            binding.downpaymentText.text = formatCurrency(skyro.downpaymentAmount)

                            // Downpayment source badge
                            if (skyro.downpaymentSource.isNotEmpty() && skyro.downpaymentAmount > 0) {
                                binding.downpaymentSourceBadge.text = skyro.downpaymentSource
                                binding.downpaymentSourceBadge.backgroundTintList =
                                    ContextCompat.getColorStateList(itemView.context, getPaymentSourceColor(skyro.downpaymentSource))
                                binding.downpaymentSourceBadge.visibility = View.VISIBLE
                            } else {
                                binding.downpaymentSourceBadge.visibility = View.GONE
                            }

                            // Brand Zero info (between downpayment and balance)
                            if (skyro.brandZero) {
                                binding.brandZeroLayout.visibility = View.VISIBLE
                                binding.subsidyText.text = formatCurrency(skyro.brandZeroSubsidy)
                            } else {
                                binding.brandZeroLayout.visibility = View.GONE
                            }

                            // Balance
                            binding.balanceText.text = formatCurrency(skyro.balance)
                            val balanceColor = if (skyro.isBalancePaid) R.color.cash_dark_green else R.color.red
                            binding.balanceText.setTextColor(
                                ContextCompat.getColor(itemView.context, balanceColor)
                            )
                        }
                    }
                    AppConstants.TRANSACTION_TYPE_IN_HOUSE -> {
                        binding.paymentInfoBadge.visibility = View.GONE
                        binding.brandZeroLayout.visibility = View.GONE  // In-House doesn't have Brand Zero
                        binding.financingDetailsLayout.visibility = View.VISIBLE
                        transaction.inHouseInstallment?.let { ih ->
                            // Downpayment
                            binding.downpaymentText.text = formatCurrency(ih.downpaymentAmount)

                            // Downpayment source badge
                            if (ih.downpaymentSource.isNotEmpty() && ih.downpaymentAmount > 0) {
                                binding.downpaymentSourceBadge.text = ih.downpaymentSource
                                binding.downpaymentSourceBadge.backgroundTintList =
                                    ContextCompat.getColorStateList(itemView.context, getPaymentSourceColor(ih.downpaymentSource))
                                binding.downpaymentSourceBadge.visibility = View.VISIBLE
                            } else {
                                binding.downpaymentSourceBadge.visibility = View.GONE
                            }

                            // Balance (remaining balance for in-house)
                            binding.balanceText.text = formatCurrency(ih.remainingBalance)
                            val balanceColor = if (ih.isBalancePaid) R.color.cash_dark_green else R.color.red
                            binding.balanceText.setTextColor(
                                ContextCompat.getColor(itemView.context, balanceColor)
                            )
                        }
                    }
                    else -> {
                        binding.financingDetailsLayout.visibility = View.GONE
                        binding.brandZeroLayout.visibility = View.GONE
                        binding.paymentInfoBadge.visibility = View.GONE
                    }
                }

                // Set date and time using server timestamp converted to local Philippine time
                val localDate = convertServerTimestampToLocalDate(item.serverTimestamp)
                val localTime = convertServerTimestampToLocalTime(item.serverTimestamp)

                // Use converted values, fallback to stored values if conversion fails
                binding.dateText.text = if (localDate.isNotEmpty()) localDate else transaction.dateSold
                binding.timeText.text = if (localTime.isNotEmpty()) localTime else transaction.time
                binding.userText.text = "by ${transaction.user}"
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemDeviceTransactionBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], position + 1)  // Pass position + 1 as order number
        }

        override fun getItemCount(): Int = items.size
    }

    // ============================================================================
    // END OF PART 8: RECYCLERVIEW ADAPTER
    // ============================================================================
}