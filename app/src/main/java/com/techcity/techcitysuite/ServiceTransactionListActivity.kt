package com.techcity.techcitysuite

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import com.techcity.techcitysuite.databinding.ActivityServiceTransactionListBinding
import com.techcity.techcitysuite.databinding.ItemServiceTransactionBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class ServiceTransactionListActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityServiceTransactionListBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Firebase collection name
    private val COLLECTION_SERVICE_TRANSACTIONS = "service_transactions"

    // Current filter
    private var currentFilter: TransactionTypeFilter = TransactionTypeFilter.ALL

    // Selected date for filtering
    private var selectedDate: String = ""

    // List of transactions
    private var transactions: MutableList<ServiceTransactionDisplay> = mutableListOf()
    private var adapter: ServiceTransactionAdapter? = null

    // ItemTouchHelper for drag and drop
    private lateinit var itemTouchHelper: ItemTouchHelper

    // Track toggled entries by their document ID (for visual comparison)
    private val toggledEntries = mutableSetOf<String>()

    // Filter enum for service transaction types
    enum class TransactionTypeFilter {
        ALL,
        CASH_IN,
        CASH_OUT,
        MOBILE_LOADING,
        SKYRO_PAYMENT,
        HOME_CREDIT_PAYMENT,
        MISC_PAYMENT
    }

    // Data class to hold transaction with server timestamp for local time conversion
    data class ServiceTransactionDisplay(
        val transaction: ServiceTransaction,
        val serverTimestamp: Timestamp?,
        var sortOrder: Int = 0
    )

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================


    // ============================================================================
    // START OF PART 2: LIFECYCLE METHODS
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityServiceTransactionListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firestore
        db = Firebase.firestore

        // Set up UI components
        setupRecyclerView()
        setupDragAndDrop()
        setupFilterButtons()
        setupAddButton()
        setupMenuButton()

        // Initialize date to today (Philippine timezone)
        selectedDate = getCurrentDatePhilippines()
        updateDateLabel()

        // Load transactions for today
        loadTransactions()
    }

    override fun onResume() {
        super.onResume()
        // Refresh data when returning to this activity
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
            ItemTouchHelper.LEFT  // Swipe left for delete
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
                    saveOrderToFirebase()
                    return true
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                if (direction == ItemTouchHelper.LEFT) {
                    val position = viewHolder.adapterPosition
                    val item = adapter?.getItems()?.get(position)

                    item?.let {
                        showDeleteConfirmationDialog(it, position)
                    }
                }
            }

            override fun isLongPressDragEnabled(): Boolean {
                return true
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)

                when (actionState) {
                    ItemTouchHelper.ACTION_STATE_DRAG -> {
                        viewHolder?.let {
                            if (it is ServiceTransactionAdapter.ViewHolder) {
                                it.setDragging(true)
                            }
                        }
                    }
                    ItemTouchHelper.ACTION_STATE_SWIPE -> {
                        viewHolder?.let {
                            if (it is ServiceTransactionAdapter.ViewHolder) {
                                it.setSwiping(true)
                            }
                        }
                    }
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)

                if (viewHolder is ServiceTransactionAdapter.ViewHolder) {
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
                    val itemView = viewHolder.itemView
                    val paint = Paint()

                    if (dX < 0) {  // Swiping left
                        // Draw blue background (matching Ledger Activity)
                        paint.color = ContextCompat.getColor(
                            this@ServiceTransactionListActivity,
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
                            this@ServiceTransactionListActivity,
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
        itemTouchHelper.attachToRecyclerView(binding.transactionRecyclerView)
    }

    private fun showDeleteConfirmationDialog(item: ServiceTransactionDisplay, position: Int) {
        val transaction = item.transaction

        val message = StringBuilder()
        message.append("Delete this transaction?\n\n")
        message.append("Type: ${transaction.transactionType}\n")
        message.append("Amount: ${formatCurrency(transaction.amount)}\n")
        message.append("Fee: ${formatCurrency(transaction.fee)}\n\n")
        message.append("This action cannot be undone.")

        AlertDialog.Builder(this)
            .setTitle("Confirm Delete")
            .setMessage(message.toString())
            .setPositiveButton("Delete") { _, _ ->
                deleteTransactionFromFirebase(transaction.id, position)
            }
            .setNegativeButton("Cancel") { _, _ ->
                adapter?.notifyItemChanged(position)
            }
            .setOnCancelListener {
                adapter?.notifyItemChanged(position)
            }
            .show()
    }

    private fun deleteTransactionFromFirebase(documentId: String, position: Int) {
        binding.progressBar.visibility = View.VISIBLE

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.collection(COLLECTION_SERVICE_TRANSACTIONS)
                        .document(documentId)
                        .delete()
                        .await()
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    adapter?.removeItem(position)

                    val currentList = adapter?.getItems() ?: emptyList()
                    updateSummary(currentList)

                    if (currentList.isEmpty()) {
                        binding.emptyMessage.text = "No transactions for this date"
                        binding.emptyMessage.visibility = View.VISIBLE
                        binding.transactionRecyclerView.visibility = View.GONE
                    }

                    showMessage("Transaction deleted successfully", false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    adapter?.notifyItemChanged(position)
                    showMessage("Error deleting transaction: ${e.message}", true)
                }
            }
        }
    }

    private fun setupFilterButtons() {
        updateFilterButtonStates(TransactionTypeFilter.ALL)

        binding.filterAllButton.setOnClickListener {
            currentFilter = TransactionTypeFilter.ALL
            updateFilterButtonStates(currentFilter)
            applyFilter()
        }

        binding.filterCashInButton.setOnClickListener {
            currentFilter = TransactionTypeFilter.CASH_IN
            updateFilterButtonStates(currentFilter)
            applyFilter()
        }

        binding.filterCashOutButton.setOnClickListener {
            currentFilter = TransactionTypeFilter.CASH_OUT
            updateFilterButtonStates(currentFilter)
            applyFilter()
        }

        binding.filterLoadButton.setOnClickListener {
            currentFilter = TransactionTypeFilter.MOBILE_LOADING
            updateFilterButtonStates(currentFilter)
            applyFilter()
        }

        binding.filterSkyroButton.setOnClickListener {
            currentFilter = TransactionTypeFilter.SKYRO_PAYMENT
            updateFilterButtonStates(currentFilter)
            applyFilter()
        }

        binding.filterHCButton.setOnClickListener {
            currentFilter = TransactionTypeFilter.HOME_CREDIT_PAYMENT
            updateFilterButtonStates(currentFilter)
            applyFilter()
        }

        binding.filterMiscButton.setOnClickListener {
            currentFilter = TransactionTypeFilter.MISC_PAYMENT
            updateFilterButtonStates(currentFilter)
            applyFilter()
        }
    }

    private fun updateFilterButtonStates(selected: TransactionTypeFilter) {
        // Reset all buttons to dimmed state
        binding.filterAllButton.alpha = 0.5f
        binding.filterCashInButton.alpha = 0.5f
        binding.filterCashOutButton.alpha = 0.5f
        binding.filterLoadButton.alpha = 0.5f
        binding.filterSkyroButton.alpha = 0.5f
        binding.filterHCButton.alpha = 0.5f
        binding.filterMiscButton.alpha = 0.5f

        // Highlight selected button
        when (selected) {
            TransactionTypeFilter.ALL -> binding.filterAllButton.alpha = 1.0f
            TransactionTypeFilter.CASH_IN -> binding.filterCashInButton.alpha = 1.0f
            TransactionTypeFilter.CASH_OUT -> binding.filterCashOutButton.alpha = 1.0f
            TransactionTypeFilter.MOBILE_LOADING -> binding.filterLoadButton.alpha = 1.0f
            TransactionTypeFilter.SKYRO_PAYMENT -> binding.filterSkyroButton.alpha = 1.0f
            TransactionTypeFilter.HOME_CREDIT_PAYMENT -> binding.filterHCButton.alpha = 1.0f
            TransactionTypeFilter.MISC_PAYMENT -> binding.filterMiscButton.alpha = 1.0f
        }
    }

    private fun setupAddButton() {
        binding.addButton.setOnClickListener {
            // Open TransactionTypeActivity to select transaction type
            val intent = Intent(this, TransactionTypeActivity::class.java)
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
        binding.emptyMessage.visibility = View.GONE

        // Convert selected date to query format (yyyy-MM-dd)
        val queryDate = convertDisplayDateToQueryFormat(selectedDate)

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    db.collection(COLLECTION_SERVICE_TRANSACTIONS)
                        .whereEqualTo("date", queryDate)
                        .whereEqualTo("status", "completed")
                        .orderBy("timestamp", Query.Direction.ASCENDING)
                        .get()
                        .await()
                }

                val loadedTransactions = mutableListOf<ServiceTransactionDisplay>()

                for (document in result.documents) {
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
                            // Embedded Ledger Entries
                            creditLedgerType = data["creditLedgerType"] as? String ?: "",
                            creditAmount = (data["creditAmount"] as? Number)?.toDouble() ?: 0.0,
                            creditDescription = data["creditDescription"] as? String ?: "",
                            debitLedgerType = data["debitLedgerType"] as? String ?: "",
                            debitAmount = (data["debitAmount"] as? Number)?.toDouble() ?: 0.0,
                            debitDescription = data["debitDescription"] as? String ?: "",
                            // Transaction Numbering
                            ledgerTransactionNumber = (data["ledgerTransactionNumber"] as? Number)?.toInt() ?: 0,
                            sortOrder = (data["sortOrder"] as? Number)?.toInt() ?: 0,
                            status = data["status"] as? String ?: "completed",
                            notes = data["notes"] as? String ?: "",
                            createdBy = data["createdBy"] as? String ?: "",
                            createdAt = data["createdAt"] as? Timestamp
                        )

                        loadedTransactions.add(
                            ServiceTransactionDisplay(
                                transaction = transaction,
                                serverTimestamp = transaction.timestamp,
                                sortOrder = transaction.sortOrder
                            )
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // Sort: by sortOrder (if set), then by timestamp
                loadedTransactions.sortWith(compareBy(
                    { it.sortOrder },
                    { it.serverTimestamp?.seconds ?: 0L }
                ))

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    transactions = loadedTransactions

                    if (transactions.isEmpty()) {
                        binding.emptyMessage.text = "No transactions for this date"
                        binding.emptyMessage.visibility = View.VISIBLE
                        binding.transactionRecyclerView.visibility = View.GONE
                        adapter = null
                        updateSummary(emptyList())
                    } else {
                        binding.emptyMessage.visibility = View.GONE
                        binding.transactionRecyclerView.visibility = View.VISIBLE
                        applyFilter()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.emptyMessage.text = "Error loading transactions"
                    binding.emptyMessage.visibility = View.VISIBLE
                    binding.transactionRecyclerView.visibility = View.GONE
                    showMessage("Error loading transactions: ${e.message}", true)
                }
            }
        }
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
            TransactionTypeFilter.CASH_IN -> transactions.filter {
                it.transaction.transactionType == "Cash In"
            }
            TransactionTypeFilter.CASH_OUT -> transactions.filter {
                it.transaction.transactionType == "Cash Out"
            }
            TransactionTypeFilter.MOBILE_LOADING -> transactions.filter {
                it.transaction.transactionType == "Mobile Loading Service"
            }
            TransactionTypeFilter.SKYRO_PAYMENT -> transactions.filter {
                it.transaction.transactionType == "Skyro Payment"
            }
            TransactionTypeFilter.HOME_CREDIT_PAYMENT -> transactions.filter {
                it.transaction.transactionType == "Home Credit Payment"
            }
            TransactionTypeFilter.MISC_PAYMENT -> transactions.filter {
                it.transaction.transactionType == "Misc Payment"
            }
        }

        updateSummary(filteredList)
        displayTransactions(filteredList.toMutableList())
    }

    private fun updateSummary(list: List<ServiceTransactionDisplay>) {
        val totalAmount = list.sumOf { it.transaction.amount }
        val totalFee = list.sumOf { it.transaction.fee }
        val count = list.size

        binding.totalAmountValue.text = formatCurrency(totalAmount)
        binding.totalFeeValue.text = formatCurrency(totalFee)
        binding.transactionCount.text = count.toString()

        // Update label with filter indicator
        val filterLabel = when (currentFilter) {
            TransactionTypeFilter.ALL -> "Total Amount (All)"
            TransactionTypeFilter.CASH_IN -> "Total Amount (In)"
            TransactionTypeFilter.CASH_OUT -> "Total Amount (Out)"
            TransactionTypeFilter.MOBILE_LOADING -> "Total Amount (Load)"
            TransactionTypeFilter.SKYRO_PAYMENT -> "Total Amount (Skyro)"
            TransactionTypeFilter.HOME_CREDIT_PAYMENT -> "Total Amount (HC)"
            TransactionTypeFilter.MISC_PAYMENT -> "Total Amount (Misc)"
        }
        binding.totalAmountLabel.text = filterLabel
    }

    private fun displayTransactions(list: MutableList<ServiceTransactionDisplay>) {
        if (list.isEmpty()) {
            binding.emptyMessage.text = "No transactions for this date"
            binding.emptyMessage.visibility = View.VISIBLE
            binding.transactionRecyclerView.visibility = View.GONE
            adapter = null
        } else {
            binding.emptyMessage.visibility = View.GONE
            binding.transactionRecyclerView.visibility = View.VISIBLE

            adapter = ServiceTransactionAdapter(
                items = list,
                toggledEntries = toggledEntries,
                onItemClick = { item ->
                    toggleEntryBackground(item.transaction.id)
                }
            )
            binding.transactionRecyclerView.adapter = adapter
        }
    }

    private fun toggleEntryBackground(entryId: String) {
        if (toggledEntries.contains(entryId)) {
            toggledEntries.remove(entryId)
        } else {
            toggledEntries.add(entryId)
        }
        adapter?.notifyDataSetChanged()
    }

    private fun saveOrderToFirebase() {
        val currentList = adapter?.getItems() ?: return

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    currentList.forEachIndexed { index, item ->
                        if (item.sortOrder != index) {
                            db.collection(COLLECTION_SERVICE_TRANSACTIONS)
                                .document(item.transaction.id)
                                .update("sortOrder", index)
                                .await()

                            item.sortOrder = index
                        }
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
                selectedDate = "${month + 1}/$dayOfMonth/$year"
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
        val format = SimpleDateFormat("M/d/yyyy", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT+08:00")
        return format.format(Date())
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

    private fun convertServerTimestampToLocalDate(timestamp: Timestamp?): String {
        if (timestamp == null) return ""
        return try {
            val date = timestamp.toDate()
            val format = SimpleDateFormat("M/d/yyyy", Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT+08:00")
            format.format(date)
        } catch (e: Exception) {
            ""
        }
    }

    private fun convertServerTimestampToLocalTime(timestamp: Timestamp?): String {
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

    private fun formatCurrency(amount: Double): String {
        return "₱${String.format("%,.2f", amount)}"
    }

    private fun showMessage(message: String, isError: Boolean) {
        Toast.makeText(this, message, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun getTransactionTypeColor(transactionType: String): Int {
        return when (transactionType) {
            "Cash In" -> R.color.cash_dark_green
            "Cash Out" -> R.color.red
            "Mobile Loading Service" -> R.color.mobile_loading_purple
            "Skyro Payment" -> R.color.skyro_light_blue
            "Home Credit Payment" -> R.color.red
            "Misc Payment" -> android.R.color.darker_gray
            else -> R.color.techcity_blue
        }
    }

    private fun getSourceLabel(transactionType: String): String {
        return when (transactionType) {
            "Cash In" -> "Transfer To:"
            "Cash Out" -> "Source:"
            "Mobile Loading Service" -> "Load Source:"
            "Skyro Payment", "Home Credit Payment" -> "Payment Method:"
            "Misc Payment" -> "Payment Method:"
            else -> "Source:"
        }
    }

    private fun getSourceColor(source: String): Int {
        return when (source) {
            "Cash" -> R.color.cash_dark_green
            "GCash" -> android.R.color.holo_blue_dark
            "PayMaya" -> android.R.color.holo_green_light
            "Others" -> R.color.red
            "Reloader SIM" -> R.color.mobile_loading_purple
            else -> android.R.color.black
        }
    }

    // ============================================================================
    // END OF PART 7: HELPER METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 8: RECYCLERVIEW ADAPTER
    // ============================================================================

    inner class ServiceTransactionAdapter(
        private val items: MutableList<ServiceTransactionDisplay>,
        private val toggledEntries: MutableSet<String>,
        private val onItemClick: (ServiceTransactionDisplay) -> Unit
    ) : RecyclerView.Adapter<ServiceTransactionAdapter.ViewHolder>() {

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

            // Update order numbers for affected range
            val start = minOf(fromPosition, toPosition)
            val end = maxOf(fromPosition, toPosition)
            notifyItemRangeChanged(start, end - start + 1)
        }

        fun removeItem(position: Int) {
            if (position >= 0 && position < items.size) {
                items.removeAt(position)
                notifyItemRemoved(position)
                notifyItemRangeChanged(position, items.size - position)
            }
        }

        fun getItems(): List<ServiceTransactionDisplay> = items

        inner class ViewHolder(private val binding: ItemServiceTransactionBinding) :
            RecyclerView.ViewHolder(binding.root) {

            init {
                binding.cardView.setOnClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onItemClick(items[position])
                    }
                }
            }

            fun setDragging(isDragging: Boolean) {
                if (isDragging) {
                    binding.cardView.strokeColor = ContextCompat.getColor(itemView.context, android.R.color.holo_blue_dark)
                    binding.cardView.strokeWidth = 4
                    binding.cardView.cardElevation = 8f
                } else {
                    binding.cardView.strokeColor = ContextCompat.getColor(itemView.context, R.color.light_gray)
                    binding.cardView.strokeWidth = 2
                    binding.cardView.cardElevation = 2f
                }
            }

            fun setSwiping(isSwiping: Boolean) {
                if (isSwiping) {
                    binding.cardView.alpha = 0.8f
                } else {
                    binding.cardView.alpha = 1.0f
                }
            }

            fun bind(item: ServiceTransactionDisplay, orderNumber: Int) {
                val transaction = item.transaction

                // Set order number
                binding.orderNumberText.text = "#$orderNumber"

                // Set background color based on toggle state
                if (toggledEntries.contains(transaction.id)) {
                    binding.cardView.setCardBackgroundColor(
                        ContextCompat.getColor(itemView.context, R.color.toggle_gray)
                    )
                } else {
                    binding.cardView.setCardBackgroundColor(
                        ContextCompat.getColor(itemView.context, android.R.color.white)
                    )
                }

                // Set transaction type text and color
                binding.transactionTypeText.text = transaction.transactionType
                binding.transactionTypeText.setTextColor(
                    ContextCompat.getColor(itemView.context, getTransactionTypeColor(transaction.transactionType))
                )

                // Set badge
                binding.transactionTypeBadge.text = transaction.transactionType.uppercase()
                binding.transactionTypeBadge.backgroundTintList =
                    ContextCompat.getColorStateList(itemView.context, getTransactionTypeColor(transaction.transactionType))

                // Set amounts
                binding.amountText.text = formatCurrency(transaction.amount)
                binding.feeText.text = formatCurrency(transaction.fee)
                binding.customerPaysText.text = formatCurrency(transaction.customerPays)

                // Set source label and value
                binding.sourceLabel.text = getSourceLabel(transaction.transactionType)
                binding.sourceText.text = transaction.sourceOfFunds
                binding.sourceText.setTextColor(
                    ContextCompat.getColor(itemView.context, getSourceColor(transaction.sourceOfFunds))
                )

                // Handle Paid With
                if (transaction.isPaidWithChecked && transaction.paidWith != null) {
                    binding.paidWithLayout.visibility = View.VISIBLE
                    binding.paidWithText.text = transaction.paidWith
                    binding.paidWithText.setTextColor(
                        ContextCompat.getColor(itemView.context, getSourceColor(transaction.paidWith ?: "Cash"))
                    )
                } else {
                    binding.paidWithLayout.visibility = View.GONE
                }

                // Handle Notes
                if (transaction.notes.isNotEmpty()) {
                    binding.notesText.visibility = View.VISIBLE
                    binding.notesText.text = "Notes: ${transaction.notes}"
                } else {
                    binding.notesText.visibility = View.GONE
                }

                // Set date, time, user
                val localDate = convertServerTimestampToLocalDate(item.serverTimestamp)
                val localTime = convertServerTimestampToLocalTime(item.serverTimestamp)

                binding.dateText.text = if (localDate.isNotEmpty()) localDate else transaction.dateSaved
                binding.timeText.text = if (localTime.isNotEmpty()) localTime else transaction.time
                binding.userText.text = "by ${transaction.user}"
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemServiceTransactionBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], position + 1)
        }

        override fun getItemCount(): Int = items.size
    }

    // ============================================================================
    // END OF PART 8: RECYCLERVIEW ADAPTER
    // ============================================================================
}