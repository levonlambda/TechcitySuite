package com.techcity.techcitysuite

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
import com.techcity.techcitysuite.databinding.ActivityLedgerViewBinding
import com.techcity.techcitysuite.databinding.ItemLedgerEntryBinding
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.*


class LedgerViewActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityLedgerViewBinding
    private var currentLedgerType: LedgerType = LedgerType.CASH
    private var showingAllCredits = false
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var currentAdapter: LedgerEntriesAdapter? = null
    private var selectedDate: String = ""

    // Track toggled entries by their ID
    private val toggledEntries = mutableSetOf<String>()

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize View Binding
        binding = ActivityLedgerViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up UI
        setupUI()

        // Set up drag and drop
        setupDragAndDrop()

        // Load initial ledger (Cash)
        loadLedger(LedgerType.CASH)
    }

    override fun onResume() {
        super.onResume()
        // Refresh the current ledger view when returning to this activity
        if (!showingAllCredits) {
            loadLedger(currentLedgerType)
        } else {
            showAllCredits()
        }
    }

    private fun setupUI() {
        // Initialize selected date to today
        selectedDate = getCurrentDate()
        updateDateLabel()

        // Set up ledger selection buttons
        binding.cashButton.setOnClickListener {
            showingAllCredits = false
            loadLedger(LedgerType.CASH)
        }

        binding.gcashButton.setOnClickListener {
            showingAllCredits = false
            loadLedger(LedgerType.GCASH)
        }

        binding.paymayaButton.setOnClickListener {
            showingAllCredits = false
            loadLedger(LedgerType.PAYMAYA)
        }

        binding.othersButton.setOnClickListener {
            showingAllCredits = false
            loadLedger(LedgerType.OTHERS)
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

                    if (showingAllCredits) {
                        // Save the new order for All Credits view
                        currentAdapter?.getEntries()?.let { orderedEntries ->
                            LedgerManager.updateAllCreditsOrder(orderedEntries)
                        }
                    } else {
                        // Save the new order to the specific ledger
                        val ledger = LedgerManager.getLedger(currentLedgerType)
                        currentAdapter?.getEntries()?.let { orderedEntries ->
                            ledger.updateCustomOrder(orderedEntries)
                        }
                    }

                    return true
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                if (direction == ItemTouchHelper.LEFT) {
                    val position = viewHolder.adapterPosition
                    val entry = currentAdapter?.getEntries()?.get(position)

                    entry?.let {
                        showDeleteConfirmationDialog(it.transactionNumber, position)
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
                    // Draw red background with delete icon when swiping
                    val itemView = viewHolder.itemView
                    val paint = Paint()

                    if (dX < 0) {  // Swiping left
                        // Draw red background
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

    private fun showDeleteConfirmationDialog(transactionNumber: Int, position: Int) {
        // Get all entries for this transaction
        val entries = LedgerManager.getTransactionDetails(transactionNumber)

        if (entries.isNotEmpty()) {
            val firstEntry = entries.first()

            // Build message showing what will be deleted
            val message = StringBuilder()
            message.append("Delete Transaction #${transactionNumber.toString().padStart(3, '0')}?\n\n")
            message.append("Type: ${firstEntry.transactionType}\n")
            message.append("This will remove:\n")

            entries.forEach { entry ->
                message.append("• ${entry.entryType} of ${formatCurrency(entry.amount)} from ${entry.ledgerSource} ledger\n")
            }

            AlertDialog.Builder(this)
                .setTitle("Confirm Delete")
                .setMessage(message.toString())
                .setPositiveButton("Delete") { _, _ ->
                    // Delete the transaction
                    if (LedgerManager.deleteTransaction(transactionNumber)) {
                        // Refresh the current view
                        if (showingAllCredits) {
                            showAllCredits()
                        } else {
                            loadLedger(currentLedgerType)
                        }

                        // Show confirmation message
                        Toast.makeText(
                            this,
                            "Transaction #${transactionNumber.toString().padStart(3, '0')} deleted",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
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
    }

    private fun loadLedger(ledgerType: LedgerType) {
        currentLedgerType = ledgerType
        showingAllCredits = false

        // Clear toggled entries when switching ledgers
        toggledEntries.clear()

        // Update button states
        updateButtonStates(ledgerType)

        // Get ledger
        val ledger = LedgerManager.getLedger(ledgerType)

        // Update title
        binding.ledgerTitle.text = "${ledgerType.name} Ledger"

        // Get entries and filter by selected date
        val allEntries = ledger.getEntriesSorted()
        val entries = allEntries.filter { it.date == selectedDate }

        // Calculate totals from filtered entries
        val creditTotal = entries.filter { it.entryType == LedgerEntryType.CREDIT }
            .sumOf { it.amount }
        val debitTotal = entries.filter { it.entryType == LedgerEntryType.DEBIT }
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

        // Get all credit entries and filter by selected date
        val allCreditEntries = LedgerManager.getAllCreditsOrdered()
        val entries = allCreditEntries.filter { it.date == selectedDate }

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

    private fun updateButtonStates(selectedType: LedgerType) {
        // Reset all button alphas
        binding.cashButton.alpha = 0.6f
        binding.gcashButton.alpha = 0.6f
        binding.paymayaButton.alpha = 0.6f
        binding.othersButton.alpha = 0.6f

        // Highlight selected button
        when (selectedType) {
            LedgerType.CASH -> binding.cashButton.alpha = 1.0f
            LedgerType.GCASH -> binding.gcashButton.alpha = 1.0f
            LedgerType.PAYMAYA -> binding.paymayaButton.alpha = 1.0f
            LedgerType.OTHERS -> binding.othersButton.alpha = 1.0f
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

            // Refresh current view with new date filter
            if (showingAllCredits) {
                showAllCredits()
            } else {
                loadLedger(currentLedgerType)
            }

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
        private val entries: MutableList<LedgerEntry>,
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

        fun getEntries(): List<LedgerEntry> = entries

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

            fun bind(entry: LedgerEntry) {
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

                // Set transaction number
                binding.transactionNumber.text = "#${entry.transactionNumber.toString().padStart(3, '0')}"

                // Set transaction type with color coding
                binding.transactionType.text = entry.transactionType

                // Apply color to transaction type based on the type
                val transactionTypeColor = when (entry.transactionType) {
                    "Cash In" -> ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark)
                    "Cash Out" -> ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark)
                    "Mobile Loading Service" -> ContextCompat.getColor(itemView.context, R.color.mobile_loading_purple)
                    "Skyro Payment" -> ContextCompat.getColor(itemView.context, android.R.color.holo_blue_dark)
                    "Home Credit Payment" -> ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark)
                    "Misc Payment" -> ContextCompat.getColor(itemView.context, android.R.color.darker_gray)
                    else -> ContextCompat.getColor(itemView.context, android.R.color.black)
                }
                binding.transactionType.setTextColor(transactionTypeColor)

                // Show payment source badge
                if (showPaymentSource) {
                    binding.paymentSource.visibility = View.VISIBLE
                    binding.paymentSource.text = entry.ledgerSource.name

                    // Create rounded background with appropriate color
                    val shape = GradientDrawable()
                    shape.cornerRadius = 20f
                    when (entry.ledgerSource) {
                        LedgerType.CASH -> shape.setColor(ContextCompat.getColor(itemView.context, R.color.cash_dark_green))
                        LedgerType.GCASH -> shape.setColor(ContextCompat.getColor(itemView.context, android.R.color.holo_blue_dark))
                        LedgerType.PAYMAYA -> shape.setColor(ContextCompat.getColor(itemView.context, android.R.color.holo_green_light))
                        LedgerType.OTHERS -> shape.setColor(ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark))
                    }
                    binding.paymentSource.background = shape
                } else {
                    binding.paymentSource.visibility = View.GONE
                }

                // Set entry type (CREDIT or DEBIT)
                binding.entryType.text = entry.entryType.name

                // Set entry type color and background
                if (entry.entryType == LedgerEntryType.CREDIT) {
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
                if (entry.entryType == LedgerEntryType.CREDIT) {
                    binding.amount.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.green)
                    )
                } else {
                    binding.amount.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.red)
                    )
                }

                // Set notes if available
                if (entry.notes.isNotEmpty()) {
                    binding.notes.visibility = View.VISIBLE
                    binding.notes.text = "Notes: ${entry.notes}"
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