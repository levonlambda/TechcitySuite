package com.techcity.techcitysuite

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityFinancingAccountListBinding
import com.techcity.techcitysuite.databinding.ItemFinancingAccountBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class FinancingAccountListActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityFinancingAccountListBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    enum class FinancingFilter {
        ALL, HOME_CREDIT, SKYRO, SAMSUNG_FINANCE
    }

    private var currentFilter: FinancingFilter = FinancingFilter.ALL
    private var allAccounts: MutableList<FinancingAccount> = mutableListOf()
    private var filteredAccounts: MutableList<FinancingAccount> = mutableListOf()
    private var adapter: FinancingAccountAdapter? = null

    private val displayDateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
    private val storageDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFinancingAccountListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = Firebase.firestore

        setupBackButton()
        setupSearch()
        setupFilterButtons()
        setupRecyclerView()
        setupSwipeActions()
        setupFab()
        loadAccounts()
    }

    override fun onResume() {
        super.onResume()
        loadAccounts()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================


    // ============================================================================
    // START OF PART 2: SETUP METHODS
    // ============================================================================

    private fun setupBackButton() {
        binding.backButton.setOnClickListener { finish() }
    }

    private fun setupSearch() {
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilters()
            }
        })
    }

    private fun setupFilterButtons() {
        binding.filterAllButton.setOnClickListener { selectFilter(FinancingFilter.ALL) }
        binding.filterHomeCreditButton.setOnClickListener { selectFilter(FinancingFilter.HOME_CREDIT) }
        binding.filterSkyroButton.setOnClickListener { selectFilter(FinancingFilter.SKYRO) }
        binding.filterSamsungButton.setOnClickListener { selectFilter(FinancingFilter.SAMSUNG_FINANCE) }

        updateFilterButtonStyles()
    }

    private fun setupRecyclerView() {
        adapter = FinancingAccountAdapter()
        binding.accountsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.accountsRecyclerView.adapter = adapter
    }

    private fun setupFab() {
        binding.addButton.setOnClickListener {
            val intent = Intent(this, AddFinancingAccountActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupSwipeActions() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val account = filteredAccounts[position]

                if (direction == ItemTouchHelper.LEFT) {
                    showPasswordDialogForDelete(account, position)
                } else if (direction == ItemTouchHelper.RIGHT) {
                    openEditMode(account, position)
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

                    if (dX < 0) {
                        // Swiping left — red background + delete icon
                        paint.color = ContextCompat.getColor(
                            this@FinancingAccountListActivity,
                            R.color.red
                        )
                        c.drawRect(
                            itemView.right.toFloat() + dX,
                            itemView.top.toFloat(),
                            itemView.right.toFloat(),
                            itemView.bottom.toFloat(),
                            paint
                        )

                        val deleteIcon = ContextCompat.getDrawable(
                            this@FinancingAccountListActivity,
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
                    } else if (dX > 0) {
                        // Swiping right — teal background + edit icon
                        paint.color = ContextCompat.getColor(
                            this@FinancingAccountListActivity,
                            R.color.financing_teal
                        )
                        c.drawRect(
                            itemView.left.toFloat(),
                            itemView.top.toFloat(),
                            itemView.left.toFloat() + dX,
                            itemView.bottom.toFloat(),
                            paint
                        )

                        val editIcon = ContextCompat.getDrawable(
                            this@FinancingAccountListActivity,
                            R.drawable.ic_edit
                        )
                        editIcon?.let { icon ->
                            val iconMargin = (itemView.height - icon.intrinsicHeight) / 2
                            val iconTop = itemView.top + iconMargin
                            val iconBottom = iconTop + icon.intrinsicHeight
                            val iconLeft = itemView.left + iconMargin
                            val iconRight = iconLeft + icon.intrinsicWidth

                            icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                            icon.setTint(Color.WHITE)
                            icon.draw(c)
                        }
                    }
                }

                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }

        val itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(binding.accountsRecyclerView)
    }

    // ============================================================================
    // END OF PART 2: SETUP METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 3: DATA LOADING
    // ============================================================================

    private fun loadAccounts() {
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyMessage.visibility = View.GONE
        binding.accountsRecyclerView.visibility = View.GONE

        scope.launch {
            try {
                val accounts = withContext(Dispatchers.IO) {
                    val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila"))
                    calendar.add(Calendar.YEAR, -2)
                    val twoYearsAgo = com.google.firebase.Timestamp(calendar.time)

                    val snapshot = db.collection(AppConstants.COLLECTION_FINANCING_ACCOUNTS)
                        .whereGreaterThan("createdAt", twoYearsAgo)
                        .orderBy("createdAt", Query.Direction.DESCENDING)
                        .get()
                        .await()

                    snapshot.documents.map { doc ->
                        FinancingAccount(
                            id = doc.id,
                            financingCompany = doc.getString("financingCompany") ?: "",
                            customerName = doc.getString("customerName") ?: "",
                            accountNumber = doc.getString("accountNumber") ?: "",
                            purchaseDate = doc.getString("purchaseDate") ?: "",
                            contactNumber = doc.getString("contactNumber") ?: "",
                            devicePurchased = doc.getString("devicePurchased"),
                            monthlyPayment = doc.getDouble("monthlyPayment"),
                            term = doc.getString("term"),
                            downpayment = doc.getDouble("downpayment"),
                            createdAt = doc.getTimestamp("createdAt"),
                            createdBy = doc.getString("createdBy") ?: "",
                            storeLocation = doc.getString("storeLocation") ?: ""
                        )
                    }
                }

                allAccounts.clear()
                allAccounts.addAll(accounts)
                applyFilters()

            } catch (e: Exception) {
                Toast.makeText(this@FinancingAccountListActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    // ============================================================================
    // END OF PART 3: DATA LOADING
    // ============================================================================


    // ============================================================================
    // START OF PART 4: FILTERING
    // ============================================================================

    private fun selectFilter(filter: FinancingFilter) {
        currentFilter = filter
        updateFilterButtonStyles()
        applyFilters()
    }

    // Month name mappings for date search
    private val monthNames = mapOf(
        "jan" to 1, "january" to 1,
        "feb" to 2, "february" to 2,
        "mar" to 3, "march" to 3,
        "apr" to 4, "april" to 4,
        "may" to 5,
        "jun" to 6, "june" to 6,
        "jul" to 7, "july" to 7,
        "aug" to 8, "august" to 8,
        "sep" to 9, "september" to 9,
        "oct" to 10, "october" to 10,
        "nov" to 11, "november" to 11,
        "dec" to 12, "december" to 12
    )

    private fun applyFilters() {
        val searchText = binding.searchEditText.text?.toString()?.trim()?.lowercase(Locale.getDefault()) ?: ""

        filteredAccounts.clear()
        filteredAccounts.addAll(allAccounts.filter { account ->
            // Apply company filter
            val matchesFilter = when (currentFilter) {
                FinancingFilter.ALL -> true
                FinancingFilter.HOME_CREDIT -> account.financingCompany == "Home Credit"
                FinancingFilter.SKYRO -> account.financingCompany == "Skyro"
                FinancingFilter.SAMSUNG_FINANCE -> account.financingCompany == "Samsung Finance"
            }

            // Apply search
            val matchesSearch = if (searchText.isEmpty()) {
                true
            } else {
                account.customerName.lowercase(Locale.getDefault()).contains(searchText) ||
                account.accountNumber.lowercase(Locale.getDefault()).contains(searchText) ||
                account.contactNumber.lowercase(Locale.getDefault()).contains(searchText) ||
                (account.devicePurchased?.lowercase(Locale.getDefault())?.contains(searchText) == true) ||
                matchesDateSearch(account.purchaseDate, searchText)
            }

            matchesFilter && matchesSearch
        })

        adapter?.notifyDataSetChanged()
        updateEmptyState()
    }

    /**
     * Checks if the account's purchase date matches the search text.
     * - If search text is a month name (e.g., "feb", "february"), matches that month in the current year only.
     * - Otherwise, matches against the formatted display date (e.g., "Feb 14, 2026") for specific date searches.
     */
    private fun matchesDateSearch(purchaseDate: String, searchText: String): Boolean {
        if (purchaseDate.isEmpty()) return false

        // Check if search text matches a month name — filter to current year only
        val matchedMonth = monthNames[searchText]
        if (matchedMonth != null) {
            val currentYear = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila")).get(Calendar.YEAR)
            // purchaseDate format is "yyyy-MM-dd"
            val parts = purchaseDate.split("-")
            if (parts.size == 3) {
                val year = parts[0].toIntOrNull()
                val month = parts[1].toIntOrNull()
                return year == currentYear && month == matchedMonth
            }
            return false
        }

        // Otherwise, match against the formatted display date (e.g., "Feb 14, 2026")
        val displayDate = formatDisplayDate(purchaseDate).lowercase(Locale.getDefault())
        return displayDate.contains(searchText)
    }

    private fun updateEmptyState() {
        if (filteredAccounts.isEmpty()) {
            binding.accountsRecyclerView.visibility = View.GONE
            binding.emptyMessage.visibility = View.VISIBLE
        } else {
            binding.accountsRecyclerView.visibility = View.VISIBLE
            binding.emptyMessage.visibility = View.GONE
        }
    }

    private fun updateFilterButtonStyles() {
        val activeAlpha = 1.0f
        val inactiveAlpha = 0.5f

        binding.filterAllButton.alpha = if (currentFilter == FinancingFilter.ALL) activeAlpha else inactiveAlpha
        binding.filterHomeCreditButton.alpha = if (currentFilter == FinancingFilter.HOME_CREDIT) activeAlpha else inactiveAlpha
        binding.filterSkyroButton.alpha = if (currentFilter == FinancingFilter.SKYRO) activeAlpha else inactiveAlpha
        binding.filterSamsungButton.alpha = if (currentFilter == FinancingFilter.SAMSUNG_FINANCE) activeAlpha else inactiveAlpha
    }

    // ============================================================================
    // END OF PART 4: FILTERING
    // ============================================================================


    // ============================================================================
    // START OF PART 5: SWIPE ACTIONS (DELETE + EDIT)
    // ============================================================================

    private fun showPasswordDialogForDelete(account: FinancingAccount, position: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_password, null)

        val passwordInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.passwordInput)
        val errorMessage = dialogView.findViewById<android.widget.TextView>(R.id.errorMessage)
        val submitButton = dialogView.findViewById<android.widget.Button>(R.id.submitButton)
        val cancelButton = dialogView.findViewById<android.widget.Button>(R.id.cancelButton)
        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.progressBar)
        val buttonsLayout = dialogView.findViewById<android.widget.LinearLayout>(R.id.buttonsLayout)

        // Update subtitle
        val subtitleView = dialogView.findViewById<android.widget.TextView>(android.R.id.text1)
        subtitleView?.text = getString(R.string.password_required_to_delete)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        cancelButton.setOnClickListener {
            dialog.dismiss()
            adapter?.notifyItemChanged(position)
        }

        submitButton.setOnClickListener {
            val password = passwordInput.text.toString()

            if (password.isEmpty()) {
                errorMessage.text = "Please enter a password"
                errorMessage.visibility = View.VISIBLE
                return@setOnClickListener
            }

            errorMessage.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            buttonsLayout.visibility = View.GONE
            passwordInput.isEnabled = false

            scope.launch {
                try {
                    val isValid = AppSettingsManager.verifyPassword(password)

                    if (isValid) {
                        dialog.dismiss()
                        showDeleteConfirmationDialog(account, position)
                    } else {
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
                    Toast.makeText(this@FinancingAccountListActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                submitButton.performClick()
                true
            } else {
                false
            }
        }

        dialog.setOnCancelListener {
            adapter?.notifyItemChanged(position)
        }

        dialog.show()
    }

    private fun showDeleteConfirmationDialog(account: FinancingAccount, position: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_delete_account, null)

        dialogView.findViewById<android.widget.TextView>(R.id.deleteCompanyValue).text = account.financingCompany
        dialogView.findViewById<android.widget.TextView>(R.id.deleteCustomerValue).text = account.customerName
        dialogView.findViewById<android.widget.TextView>(R.id.deleteAccountValue).text = account.accountNumber
        dialogView.findViewById<android.widget.TextView>(R.id.deleteContactValue).text = account.contactNumber

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<android.widget.Button>(R.id.cancelButton).setOnClickListener {
            dialog.dismiss()
            adapter?.notifyItemChanged(position)
        }

        dialogView.findViewById<android.widget.Button>(R.id.deleteButton).setOnClickListener {
            dialog.dismiss()
            deleteAccountFromFirestore(account.id, position)
        }

        dialog.setOnCancelListener {
            adapter?.notifyItemChanged(position)
        }

        dialog.show()
    }

    private fun deleteAccountFromFirestore(documentId: String, position: Int) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.collection(AppConstants.COLLECTION_FINANCING_ACCOUNTS)
                        .document(documentId)
                        .delete()
                        .await()
                }

                // Remove from both lists
                allAccounts.removeAll { it.id == documentId }
                filteredAccounts.removeAll { it.id == documentId }
                adapter?.notifyDataSetChanged()
                updateEmptyState()

                Toast.makeText(
                    this@FinancingAccountListActivity,
                    getString(R.string.account_deleted_successfully),
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                adapter?.notifyItemChanged(position)
                Toast.makeText(
                    this@FinancingAccountListActivity,
                    "Error: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun openEditMode(account: FinancingAccount, position: Int) {
        adapter?.notifyItemChanged(position)

        val intent = Intent(this, AddFinancingAccountActivity::class.java)
        intent.putExtra("edit_mode", true)
        intent.putExtra("document_id", account.id)
        intent.putExtra("financing_company", account.financingCompany)
        intent.putExtra("customer_name", account.customerName)
        intent.putExtra("account_number", account.accountNumber)
        intent.putExtra("purchase_date", account.purchaseDate)
        intent.putExtra("contact_number", account.contactNumber)
        intent.putExtra("device_purchased", account.devicePurchased ?: "")
        intent.putExtra("term", account.term ?: "")
        intent.putExtra("has_monthly_payment", account.monthlyPayment != null)
        if (account.monthlyPayment != null) {
            intent.putExtra("monthly_payment", account.monthlyPayment)
        }
        intent.putExtra("has_downpayment", account.downpayment != null)
        if (account.downpayment != null) {
            intent.putExtra("downpayment", account.downpayment)
        }
        intent.putExtra("created_by", account.createdBy)
        intent.putExtra("store_location", account.storeLocation)
        startActivity(intent)
    }

    // ============================================================================
    // END OF PART 5: SWIPE ACTIONS (DELETE + EDIT)
    // ============================================================================


    // ============================================================================
    // START OF PART 6: RECYCLER VIEW ADAPTER
    // ============================================================================

    inner class FinancingAccountAdapter : RecyclerView.Adapter<FinancingAccountAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemFinancingAccountBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemFinancingAccountBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val account = filteredAccounts[position]
            val b = holder.binding

            // Customer name
            b.customerNameText.text = account.customerName

            // Account number
            b.accountNumberValue.text = account.accountNumber

            // Contact number
            b.contactNumberValue.text = account.contactNumber

            // Device purchased (conditional visibility)
            if (!account.devicePurchased.isNullOrBlank()) {
                b.devicePurchasedValue.text = account.devicePurchased
                b.deviceColumn.visibility = View.VISIBLE
            } else {
                b.deviceColumn.visibility = View.GONE
            }

            // Financial summary row (conditional visibility)
            val hasMonthly = account.monthlyPayment != null && account.monthlyPayment > 0
            val hasTerm = !account.term.isNullOrBlank()
            val hasDownpayment = account.downpayment != null && account.downpayment > 0
            val hasFinancialData = hasMonthly || hasTerm || hasDownpayment

            if (hasFinancialData) {
                b.financialDivider.visibility = View.VISIBLE
                b.financialSummaryRow.visibility = View.VISIBLE

                // Monthly payment
                if (hasMonthly) {
                    b.monthlyPaymentValue.text = "₱${String.format("%,.2f", account.monthlyPayment)}"
                    b.monthlyPaymentValue.setTextColor(ContextCompat.getColor(this@FinancingAccountListActivity, R.color.cash_dark_green))
                } else {
                    b.monthlyPaymentValue.text = "—"
                    b.monthlyPaymentValue.setTextColor(ContextCompat.getColor(this@FinancingAccountListActivity, R.color.gray))
                }

                // Term
                if (hasTerm) {
                    b.termValue.text = account.term
                    b.termValue.setTextColor(ContextCompat.getColor(this@FinancingAccountListActivity, R.color.black))
                } else {
                    b.termValue.text = "—"
                    b.termValue.setTextColor(ContextCompat.getColor(this@FinancingAccountListActivity, R.color.gray))
                }

                // Downpayment
                if (hasDownpayment) {
                    b.downpaymentValue.text = "₱${String.format("%,.2f", account.downpayment)}"
                    b.downpaymentValue.setTextColor(ContextCompat.getColor(this@FinancingAccountListActivity, R.color.black))
                } else {
                    b.downpaymentValue.text = "—"
                    b.downpaymentValue.setTextColor(ContextCompat.getColor(this@FinancingAccountListActivity, R.color.gray))
                }
            } else {
                b.financialDivider.visibility = View.GONE
                b.financialSummaryRow.visibility = View.GONE
            }

            // Purchase date (right side of account number row)
            b.purchaseDateText.text = formatDisplayDate(account.purchaseDate)

            // Align Date column width to match Phone column width
            val phoneValueWidth = b.contactNumberValue.paint.measureText(account.contactNumber)
            val phoneLabelWidth = b.contactNumberLabel.paint.measureText("Phone")
            b.purchaseDateColumn.minimumWidth = maxOf(phoneValueWidth, phoneLabelWidth).toInt()

            // Financing company badge
            b.financingCompanyBadge.text = account.financingCompany
            val badgeColor = when (account.financingCompany) {
                "Home Credit" -> ContextCompat.getColor(this@FinancingAccountListActivity, R.color.red)
                "Skyro" -> ContextCompat.getColor(this@FinancingAccountListActivity, R.color.skyro_light_blue)
                "Samsung Finance" -> ContextCompat.getColor(this@FinancingAccountListActivity, R.color.financing_teal)
                else -> ContextCompat.getColor(this@FinancingAccountListActivity, R.color.gray)
            }
            val badgeDrawable = b.financingCompanyBadge.background.mutate()
            badgeDrawable.setTint(badgeColor)
            b.financingCompanyBadge.background = badgeDrawable

            // Item click — open detail screen
            holder.itemView.setOnClickListener {
                val intent = Intent(this@FinancingAccountListActivity, FinancingAccountDetailActivity::class.java)
                intent.putExtra("financing_company", account.financingCompany)
                intent.putExtra("customer_name", account.customerName)
                intent.putExtra("account_number", account.accountNumber)
                intent.putExtra("contact_number", account.contactNumber)
                intent.putExtra("has_monthly_payment", account.monthlyPayment != null)
                if (account.monthlyPayment != null) {
                    intent.putExtra("monthly_payment", account.monthlyPayment)
                }
                startActivity(intent)
            }
        }

        override fun getItemCount(): Int = filteredAccounts.size
    }

    private fun formatDisplayDate(dateString: String): String {
        return try {
            val date = storageDateFormat.parse(dateString)
            if (date != null) displayDateFormat.format(date) else dateString
        } catch (e: Exception) {
            dateString
        }
    }

    // ============================================================================
    // END OF PART 6: RECYCLER VIEW ADAPTER
    // ============================================================================
}
