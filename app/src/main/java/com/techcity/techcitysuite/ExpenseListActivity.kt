package com.techcity.techcitysuite

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityExpenseListBinding
import com.techcity.techcitysuite.databinding.ItemExpenseBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class ExpenseListActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityExpenseListBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Current month for filtering
    private var currentYear: Int = 0
    private var currentMonth: Int = 0  // 0-indexed

    // List of expenses
    private var expenses: MutableList<Expense> = mutableListOf()
    private var adapter: ExpenseAdapter? = null

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================


    // ============================================================================
    // START OF PART 2: LIFECYCLE METHODS
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityExpenseListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firestore
        db = Firebase.firestore

        // Initialize to current month (Philippine time)
        initializeCurrentMonth()

        // Setup UI
        setupUI()
        setupRecyclerView()
        setupSwipeToDelete()

        // Load expenses
        loadExpenses()
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

    private fun initializeCurrentMonth() {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila"))
        currentYear = calendar.get(Calendar.YEAR)
        currentMonth = calendar.get(Calendar.MONTH)
    }

    private fun setupUI() {
        // Previous month button
        binding.prevMonthButton.setOnClickListener {
            navigateMonth(-1)
        }

        // Next month button
        binding.nextMonthButton.setOnClickListener {
            navigateMonth(1)
        }

        // Add button - opens add expense dialog
        binding.addButton.setOnClickListener {
            showExpenseDialog(null)
        }

        // Update month label
        updateMonthLabel()
    }

    private fun setupRecyclerView() {
        binding.expensesRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun setupSwipeToDelete() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT  // Swipe left for delete
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                if (direction == ItemTouchHelper.LEFT) {
                    val position = viewHolder.adapterPosition
                    val item = expenses.getOrNull(position)

                    item?.let {
                        attemptDelete(it, position)
                    }
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
                    // Draw background with delete icon when swiping left
                    val itemView = viewHolder.itemView
                    val paint = Paint()

                    if (dX < 0) {  // Swiping left
                        // Draw blue background (matching Device Transaction list)
                        paint.color = ContextCompat.getColor(
                            this@ExpenseListActivity,
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
                            this@ExpenseListActivity,
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

        ItemTouchHelper(callback).attachToRecyclerView(binding.expensesRecyclerView)
    }

    private fun updateMonthLabel() {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.YEAR, currentYear)
        calendar.set(Calendar.MONTH, currentMonth)

        val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.US)
        binding.monthLabel.text = monthFormat.format(calendar.time)

        // Disable the next button when the displayed month is the current month
        // (no navigation into future months)
        val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila"))
        val isCurrentMonth = currentYear == now.get(Calendar.YEAR) && currentMonth == now.get(Calendar.MONTH)
        binding.nextMonthButton.isEnabled = !isCurrentMonth
        binding.nextMonthButton.alpha = if (isCurrentMonth) 0.3f else 1.0f
    }

    private fun navigateMonth(delta: Int) {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.YEAR, currentYear)
        calendar.set(Calendar.MONTH, currentMonth)
        calendar.add(Calendar.MONTH, delta)

        currentYear = calendar.get(Calendar.YEAR)
        currentMonth = calendar.get(Calendar.MONTH)

        updateMonthLabel()
        loadExpenses()
    }

    // ============================================================================
    // END OF PART 3: UI SETUP METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 4: DATA LOADING
    // ============================================================================

    private fun loadExpenses() {
        binding.progressBar.visibility = View.VISIBLE
        binding.expensesRecyclerView.visibility = View.GONE
        binding.emptyStateLayout.visibility = View.GONE

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    fetchExpensesForMonth()
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    expenses = result.toMutableList()

                    updateTotalLabel()

                    if (expenses.isEmpty()) {
                        binding.emptyStateLayout.visibility = View.VISIBLE
                        binding.expensesRecyclerView.visibility = View.GONE
                        binding.emptyMessage.text = "No expenses recorded for ${binding.monthLabel.text}"
                    } else {
                        binding.emptyStateLayout.visibility = View.GONE
                        binding.expensesRecyclerView.visibility = View.VISIBLE

                        adapter = ExpenseAdapter(expenses) { expense ->
                            showExpenseDialog(expense)
                        }
                        binding.expensesRecyclerView.adapter = adapter
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.emptyStateLayout.visibility = View.VISIBLE
                    binding.emptyMessage.text = "Error loading expenses: ${e.message}"
                    showMessage("Error: ${e.message}", true)
                }
            }
        }
    }

    private suspend fun fetchExpensesForMonth(): List<Expense> {
        val monthKey = String.format("%04d-%02d", currentYear, currentMonth + 1)

        // Single equality query; sorted client-side to avoid needing a composite index
        val querySnapshot = db.collection(AppConstants.COLLECTION_EXPENSES)
            .whereEqualTo("monthKey", monthKey)
            .get()
            .await()

        return querySnapshot.documents.mapNotNull { doc ->
            try {
                val data = doc.data ?: return@mapNotNull null

                Expense(
                    documentId = doc.id,
                    description = data["description"] as? String ?: "",
                    amount = (data["amount"] as? Number)?.toDouble() ?: 0.0,
                    date = data["date"] as? String ?: "",
                    monthKey = data["monthKey"] as? String ?: "",
                    createdBy = data["createdBy"] as? String ?: "",
                    storeLocation = data["storeLocation"] as? String ?: "",
                    storeLocationId = data["storeLocationId"] as? String ?: "",
                    timestamp = data["timestamp"] as? com.google.firebase.Timestamp
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }.sortedWith(compareByDescending { it.timestamp?.toDate()?.time ?: 0L })
    }

    private fun updateTotalLabel() {
        val total = expenses.sumOf { it.amount }
        binding.totalLabel.text = formatCurrency(total)
    }

    // ============================================================================
    // END OF PART 4: DATA LOADING
    // ============================================================================


    // ============================================================================
    // START OF PART 5: ADD / EDIT / DELETE FLOWS
    // ============================================================================

    /**
     * Shows the add/edit expense dialog.
     * Pass null to add a new expense, or an existing Expense to edit it.
     */
    private fun showExpenseDialog(expenseToEdit: Expense?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_expense, null)

        val dialogTitle = dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle)
        val descriptionInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.descriptionInput)
        val amountInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.amountInput)
        val errorMessage = dialogView.findViewById<android.widget.TextView>(R.id.errorMessage)
        val saveButton = dialogView.findViewById<android.widget.Button>(R.id.saveButton)
        val cancelButton = dialogView.findViewById<android.widget.Button>(R.id.cancelButton)
        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.progressBar)
        val buttonsLayout = dialogView.findViewById<android.widget.LinearLayout>(R.id.buttonsLayout)

        val isEdit = expenseToEdit != null
        if (isEdit) {
            dialogTitle.text = "Edit Expense"
            descriptionInput.setText(expenseToEdit!!.description)
            amountInput.setText(String.format(Locale.US, "%.2f", expenseToEdit.amount))
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        saveButton.setOnClickListener {
            val description = descriptionInput.text.toString().trim()
            val amount = amountInput.text.toString().trim().toDoubleOrNull()

            // Validate description
            if (description.isEmpty()) {
                errorMessage.text = "Please enter a description"
                errorMessage.visibility = View.VISIBLE
                return@setOnClickListener
            }

            // Validate amount
            if (amount == null || amount <= 0) {
                errorMessage.text = "Please enter a valid amount greater than zero"
                errorMessage.visibility = View.VISIBLE
                return@setOnClickListener
            }

            // Round to 2 decimal places
            val roundedAmount = Math.round(amount * 100) / 100.0

            errorMessage.visibility = View.GONE

            if (isEdit) {
                // Editing requires password verification
                dialog.dismiss()
                showPasswordDialog(onVerified = {
                    updateExpense(expenseToEdit!!, description, roundedAmount)
                })
            } else {
                // Show progress while saving
                progressBar.visibility = View.VISIBLE
                buttonsLayout.visibility = View.GONE
                descriptionInput.isEnabled = false
                amountInput.isEnabled = false

                saveExpense(description, roundedAmount,
                    onSuccess = {
                        dialog.dismiss()
                    },
                    onFailure = { message ->
                        progressBar.visibility = View.GONE
                        buttonsLayout.visibility = View.VISIBLE
                        descriptionInput.isEnabled = true
                        amountInput.isEnabled = true
                        errorMessage.text = message
                        errorMessage.visibility = View.VISIBLE
                    }
                )
            }
        }

        dialog.show()
    }

    /**
     * Saves a new expense to the current month (Philippine time),
     * regardless of which month is being viewed.
     */
    private fun saveExpense(description: String, amount: Double, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila"))
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val date = String.format("%04d-%02d-%02d", year, month + 1, calendar.get(Calendar.DAY_OF_MONTH))
        val monthKey = String.format("%04d-%02d", year, month + 1)

        val prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE)
        val createdBy = prefs.getString(AppConstants.KEY_USER, "") ?: ""
        val storeLocation = prefs.getString(AppConstants.KEY_STORE_LOCATION, "") ?: ""
        val storeLocationId = prefs.getString(AppConstants.KEY_STORE_LOCATION_ID, "") ?: ""

        val expenseData = hashMapOf(
            "description" to description,
            "amount" to amount,
            "date" to date,
            "monthKey" to monthKey,
            "createdBy" to createdBy,
            "storeLocation" to storeLocation,
            "storeLocationId" to storeLocationId,
            "timestamp" to FieldValue.serverTimestamp()
        )

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.collection(AppConstants.COLLECTION_EXPENSES)
                        .add(expenseData)
                        .await()
                }

                onSuccess()

                // Snap back to the current month so the new entry is visible
                currentYear = year
                currentMonth = month
                updateMonthLabel()
                loadExpenses()

            } catch (e: Exception) {
                e.printStackTrace()
                onFailure("Error saving expense: ${e.message}")
            }
        }
    }

    /**
     * Updates the description and amount of an existing expense.
     * date/monthKey are unchanged so an edit never moves an entry to another month.
     */
    private fun updateExpense(expense: Expense, description: String, amount: Double) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.collection(AppConstants.COLLECTION_EXPENSES)
                        .document(expense.documentId)
                        .update(
                            mapOf(
                                "description" to description,
                                "amount" to amount
                            )
                        )
                        .await()
                }

                showMessage("Expense updated", false)
                loadExpenses()

            } catch (e: Exception) {
                e.printStackTrace()
                showMessage("Error updating expense: ${e.message}", true)
            }
        }
    }

    /**
     * Attempt to delete a swiped expense - password is always required,
     * then a confirmation dialog. Cancelling restores the swiped item.
     */
    private fun attemptDelete(expense: Expense, position: Int) {
        showPasswordDialog(
            onVerified = {
                showDeleteConfirmationDialog(expense, position)
            },
            onCancelled = {
                // Restore the swiped item
                adapter?.notifyItemChanged(position)
            }
        )
    }

    private fun showDeleteConfirmationDialog(expense: Expense, position: Int) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete Expense")
            .setMessage("Delete this expense?\n\n${expense.description}\n${formatCurrency(expense.amount)}\n\nThis action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                deleteExpense(expense)
            }
            .setNegativeButton("Cancel") { _, _ ->
                // Refresh to restore the swiped item
                adapter?.notifyItemChanged(position)
            }
            .setOnCancelListener {
                // Refresh to restore the swiped item if dialog is cancelled
                adapter?.notifyItemChanged(position)
            }
            .show()
    }

    private fun deleteExpense(expense: Expense) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.collection(AppConstants.COLLECTION_EXPENSES)
                        .document(expense.documentId)
                        .delete()
                        .await()
                }

                showMessage("Expense deleted", false)
                loadExpenses()

            } catch (e: Exception) {
                e.printStackTrace()
                showMessage("Error deleting expense: ${e.message}", true)
            }
        }
    }

    // ============================================================================
    // END OF PART 5: ADD / EDIT / DELETE FLOWS
    // ============================================================================


    // ============================================================================
    // START OF PART 6: PASSWORD DIALOG
    // ============================================================================

    private fun showPasswordDialog(onVerified: () -> Unit, onCancelled: (() -> Unit)? = null) {
        // Inflate the dialog layout
        val dialogView = layoutInflater.inflate(R.layout.dialog_password, null)

        // Get references to dialog views
        val passwordInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.passwordInput)
        val errorMessage = dialogView.findViewById<android.widget.TextView>(R.id.errorMessage)
        val submitButton = dialogView.findViewById<android.widget.Button>(R.id.submitButton)
        val cancelButton = dialogView.findViewById<android.widget.Button>(R.id.cancelButton)
        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.progressBar)
        val buttonsLayout = dialogView.findViewById<android.widget.LinearLayout>(R.id.buttonsLayout)

        // Create the dialog
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // Cancel button
        cancelButton.setOnClickListener {
            dialog.dismiss()
            onCancelled?.invoke()
        }

        // Submit button
        submitButton.setOnClickListener {
            val password = passwordInput.text.toString()

            // Validate password is not empty
            if (password.isEmpty()) {
                errorMessage.text = "Please enter a password"
                errorMessage.visibility = View.VISIBLE
                return@setOnClickListener
            }

            // Hide error and show progress
            errorMessage.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            buttonsLayout.visibility = View.GONE
            passwordInput.isEnabled = false

            // Verify password
            scope.launch {
                try {
                    val isValid = AppSettingsManager.verifyPassword(password)

                    if (isValid) {
                        dialog.dismiss()
                        onVerified()
                    } else {
                        // Show error
                        progressBar.visibility = View.GONE
                        buttonsLayout.visibility = View.VISIBLE
                        passwordInput.isEnabled = true
                        errorMessage.text = "Incorrect password"
                        errorMessage.visibility = View.VISIBLE
                        passwordInput.setText("")
                        passwordInput.requestFocus()
                    }
                } catch (e: Exception) {
                    progressBar.visibility = View.GONE
                    buttonsLayout.visibility = View.VISIBLE
                    passwordInput.isEnabled = true
                    errorMessage.text = "Error verifying password"
                    errorMessage.visibility = View.VISIBLE
                    Toast.makeText(this@ExpenseListActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Handle Enter key on password input
        passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                submitButton.performClick()
                true
            } else {
                false
            }
        }

        // Show the dialog
        dialog.show()
    }

    // ============================================================================
    // END OF PART 6: PASSWORD DIALOG
    // ============================================================================


    // ============================================================================
    // START OF PART 7: ADAPTER
    // ============================================================================

    inner class ExpenseAdapter(
        private val items: List<Expense>,
        private val onItemClick: (Expense) -> Unit
    ) : RecyclerView.Adapter<ExpenseAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemExpenseBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemExpenseBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]

            with(holder.binding) {
                expenseDescription.text = item.description
                expenseDate.text = formatDisplayDate(item.date)
                expenseAmount.text = formatCurrency(item.amount)

                // Hide the location line for entries saved without a location tag
                if (item.storeLocation.isNotEmpty()) {
                    expenseLocation.text = item.storeLocation
                    expenseLocation.visibility = View.VISIBLE
                } else {
                    expenseLocation.visibility = View.GONE
                }

                // Click listener
                root.setOnClickListener {
                    onItemClick(item)
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    // ============================================================================
    // END OF PART 7: ADAPTER
    // ============================================================================


    // ============================================================================
    // START OF PART 8: UTILITY METHODS
    // ============================================================================

    private fun formatDisplayDate(dateStr: String): String {
        return try {
            // Parse yyyy-MM-dd
            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val outputFormat = SimpleDateFormat("MMMM d, yyyy", Locale.US)
            val date = inputFormat.parse(dateStr)
            outputFormat.format(date!!)
        } catch (e: Exception) {
            dateStr
        }
    }

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
}
