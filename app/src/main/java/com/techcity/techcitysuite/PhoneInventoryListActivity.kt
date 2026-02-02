package com.techcity.techcitysuite

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityPhoneInventoryListBinding
import com.techcity.techcitysuite.databinding.ItemReconciliationBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class PhoneInventoryListActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityPhoneInventoryListBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Firebase collection names
    private val COLLECTION_INVENTORY_RECONCILIATIONS = "inventory_reconciliations"
    private val COLLECTION_INVENTORY = "inventory"

    // Manufacturers to exclude from reconciliation (case-insensitive)
    private val EXCLUDED_MANUFACTURERS = listOf("Techcity")

    // List of reconciliations
    private var reconciliations: MutableList<ReconciliationSummary> = mutableListOf()
    private var adapter: ReconciliationAdapter? = null

    /**
     * Data class for reconciliation summary (for list display)
     */
    data class ReconciliationSummary(
        val documentId: String,
        val date: String,                    // M/d/yyyy
        val location: String,                // Location filter or "All"
        val statusFilter: String,            // "On-Display", "On-Hand", or "All"
        val qtyOnDisplay: Int,
        val qtyOnDisplayVerified: Int,
        val qtyOnDisplayReconciled: Int,
        val qtyOnStock: Int,
        val qtyOnStockVerified: Int,
        val qtyOnStockReconciled: Int,
        val createdAt: String                // Time created
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
        binding = ActivityPhoneInventoryListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firebase
        db = Firebase.firestore

        // Setup RecyclerView
        binding.reconciliationRecyclerView.layoutManager = LinearLayoutManager(this)

        // Setup FAB click listener
        binding.addButton.setOnClickListener {
            showPasswordDialog()
        }

        // Load reconciliations
        loadReconciliations()
    }

    override fun onResume() {
        super.onResume()
        // Reload when returning from detail view
        loadReconciliations()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ============================================================================
    // END OF PART 2: LIFECYCLE METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 3: PASSWORD DIALOG
    // ============================================================================

    private fun showPasswordDialog() {
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
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // Cancel button
        cancelButton.setOnClickListener {
            dialog.dismiss()
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
                        // Password correct - show new reconciliation dialog
                        showNewReconciliationDialog()
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
                    Toast.makeText(this@PhoneInventoryListActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
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

        dialog.show()
    }

    // ============================================================================
    // END OF PART 3: PASSWORD DIALOG
    // ============================================================================


    // ============================================================================
    // START OF PART 4: NEW RECONCILIATION DIALOG
    // ============================================================================

    private fun showNewReconciliationDialog() {
        // Inflate the dialog layout
        val dialogView = layoutInflater.inflate(R.layout.dialog_new_reconciliation, null)

        // Get references to dialog views
        val locationInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.locationInput)
        val statusFilterDropdown = dialogView.findViewById<AutoCompleteTextView>(R.id.statusFilterDropdown)
        val errorMessage = dialogView.findViewById<android.widget.TextView>(R.id.errorMessage)
        val submitButton = dialogView.findViewById<android.widget.Button>(R.id.submitButton)
        val cancelButton = dialogView.findViewById<android.widget.Button>(R.id.cancelButton)
        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.progressBar)
        val buttonsLayout = dialogView.findViewById<android.widget.LinearLayout>(R.id.buttonsLayout)

        // Setup status filter dropdown
        val statusOptions = arrayOf("All", "On-Display", "On-Hand")
        val statusAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, statusOptions)
        statusFilterDropdown.setAdapter(statusAdapter)
        statusFilterDropdown.setText("All", false)

        // Set default location value
        locationInput.setText("All")

        // Create the dialog
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // Cancel button
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        // Submit button
        submitButton.setOnClickListener {
            val location = locationInput.text.toString().trim()
            val statusFilter = statusFilterDropdown.text.toString()

            // Validate location
            if (location.isEmpty()) {
                errorMessage.text = "Please enter a location"
                errorMessage.visibility = View.VISIBLE
                return@setOnClickListener
            }

            // Hide error and show progress
            errorMessage.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            buttonsLayout.visibility = View.GONE
            locationInput.isEnabled = false
            statusFilterDropdown.isEnabled = false

            // Create the reconciliation
            scope.launch {
                try {
                    val success = createReconciliation(location, statusFilter)

                    if (success) {
                        dialog.dismiss()
                        showMessage("Reconciliation created successfully", false)
                        loadReconciliations()
                    } else {
                        progressBar.visibility = View.GONE
                        buttonsLayout.visibility = View.VISIBLE
                        locationInput.isEnabled = true
                        statusFilterDropdown.isEnabled = true
                        errorMessage.text = "No inventory items found matching criteria"
                        errorMessage.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    progressBar.visibility = View.GONE
                    buttonsLayout.visibility = View.VISIBLE
                    locationInput.isEnabled = true
                    statusFilterDropdown.isEnabled = true
                    errorMessage.text = "Error: ${e.message}"
                    errorMessage.visibility = View.VISIBLE
                }
            }
        }

        dialog.show()
    }

    // ============================================================================
    // END OF PART 4: NEW RECONCILIATION DIALOG
    // ============================================================================


    // ============================================================================
    // START OF PART 5: CREATE RECONCILIATION
    // ============================================================================

    private suspend fun createReconciliation(location: String, statusFilter: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Build query based on filters
                var query = db.collection(COLLECTION_INVENTORY)
                    .whereIn("status", listOf("On-Hand", "On-Display"))

                // Apply location filter (case-insensitive check for "All")
                val isAllLocations = location.equals("All", ignoreCase = true)

                // Fetch inventory items
                val querySnapshot = query.get().await()

                // Filter results based on location, status, and exclude certain manufacturers
                val filteredItems = querySnapshot.documents.filter { doc ->
                    val itemStatus = doc.getString("status") ?: ""
                    val itemLocation = doc.getString("location") ?: ""
                    val itemManufacturer = doc.getString("manufacturer") ?: ""

                    // Check if manufacturer is excluded (case-insensitive)
                    val isExcludedManufacturer = EXCLUDED_MANUFACTURERS.any { excluded ->
                        itemManufacturer.equals(excluded, ignoreCase = true)
                    }

                    // Skip excluded manufacturers
                    if (isExcludedManufacturer) {
                        return@filter false
                    }

                    // Check location filter
                    val locationMatch = isAllLocations || itemLocation.equals(location, ignoreCase = true)

                    // Check status filter
                    val statusMatch = when (statusFilter) {
                        "On-Display" -> itemStatus == "On-Display"
                        "On-Hand" -> itemStatus == "On-Hand"
                        "All" -> itemStatus == "On-Hand" || itemStatus == "On-Display"
                        else -> false
                    }

                    locationMatch && statusMatch
                }

                // If no items found, return false
                if (filteredItems.isEmpty()) {
                    return@withContext false
                }

                // Count items by status
                var qtyOnDisplay = 0
                var qtyOnStock = 0
                val inventoryIds = mutableListOf<String>()

                filteredItems.forEach { doc ->
                    val itemStatus = doc.getString("status") ?: ""
                    inventoryIds.add(doc.id)

                    when (itemStatus) {
                        "On-Display" -> qtyOnDisplay++
                        "On-Hand" -> qtyOnStock++
                    }
                }

                // Get current date and time
                val dateFormat = SimpleDateFormat("M/d/yyyy", Locale.US)
                val timeFormat = SimpleDateFormat("h:mm a", Locale.US)
                val now = Date()
                val currentDate = dateFormat.format(now)
                val currentTime = timeFormat.format(now)

                // Create reconciliation document
                val reconciliationData = hashMapOf(
                    "date" to currentDate,
                    "location" to if (isAllLocations) "All" else location,
                    "statusFilter" to statusFilter,
                    "qtyOnDisplay" to qtyOnDisplay,
                    "qtyOnDisplayVerified" to 0,
                    "qtyOnDisplayReconciled" to 0,
                    "qtyOnStock" to qtyOnStock,
                    "qtyOnStockVerified" to 0,
                    "qtyOnStockReconciled" to 0,
                    "inventoryIds" to inventoryIds,
                    "verifiedItems" to emptyMap<String, Any>(),
                    "createdAt" to currentTime,
                    "dateCreated" to Timestamp.now()
                )

                // Save to Firebase
                db.collection(COLLECTION_INVENTORY_RECONCILIATIONS)
                    .add(reconciliationData)
                    .await()

                true
            } catch (e: Exception) {
                e.printStackTrace()
                throw e
            }
        }
    }

    // ============================================================================
    // END OF PART 5: CREATE RECONCILIATION
    // ============================================================================


    // ============================================================================
    // START OF PART 6: DATA LOADING
    // ============================================================================

    private fun loadReconciliations() {
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyStateLayout.visibility = View.GONE
        binding.reconciliationRecyclerView.visibility = View.GONE

        scope.launch {
            try {
                val fetchedReconciliations = withContext(Dispatchers.IO) {
                    fetchReconciliations()
                }

                reconciliations.clear()
                reconciliations.addAll(fetchedReconciliations)

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE

                    if (reconciliations.isEmpty()) {
                        binding.emptyStateLayout.visibility = View.VISIBLE
                        binding.reconciliationRecyclerView.visibility = View.GONE
                        binding.recordCountLabel.text = "0 reconciliation records"
                    } else {
                        binding.emptyStateLayout.visibility = View.GONE
                        binding.reconciliationRecyclerView.visibility = View.VISIBLE
                        binding.recordCountLabel.text = "${reconciliations.size} reconciliation record${if (reconciliations.size > 1) "s" else ""}"

                        adapter = ReconciliationAdapter(reconciliations) { reconciliation ->
                            // Open detail view
                            openDetailActivity(reconciliation.documentId)
                        }
                        binding.reconciliationRecyclerView.adapter = adapter
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.emptyStateLayout.visibility = View.VISIBLE
                    binding.emptyMessage.text = "Error loading records: ${e.message}"
                    showMessage("Error: ${e.message}", true)
                }
            }
        }
    }

    private suspend fun fetchReconciliations(): List<ReconciliationSummary> {
        // Query Firebase - order by dateCreated descending
        val querySnapshot = db.collection(COLLECTION_INVENTORY_RECONCILIATIONS)
            .orderBy("dateCreated", Query.Direction.DESCENDING)
            .get()
            .await()

        return querySnapshot.documents.mapNotNull { doc ->
            try {
                val data = doc.data ?: return@mapNotNull null

                ReconciliationSummary(
                    documentId = doc.id,
                    date = data["date"] as? String ?: "",
                    location = data["location"] as? String ?: "",
                    statusFilter = data["statusFilter"] as? String ?: "All",
                    qtyOnDisplay = (data["qtyOnDisplay"] as? Number)?.toInt() ?: 0,
                    qtyOnDisplayVerified = (data["qtyOnDisplayVerified"] as? Number)?.toInt() ?: 0,
                    qtyOnDisplayReconciled = (data["qtyOnDisplayReconciled"] as? Number)?.toInt() ?: 0,
                    qtyOnStock = (data["qtyOnStock"] as? Number)?.toInt() ?: 0,
                    qtyOnStockVerified = (data["qtyOnStockVerified"] as? Number)?.toInt() ?: 0,
                    qtyOnStockReconciled = (data["qtyOnStockReconciled"] as? Number)?.toInt() ?: 0,
                    createdAt = data["createdAt"] as? String ?: ""
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun openDetailActivity(documentId: String) {
        val intent = Intent(this, ReconciliationDetailActivity::class.java)
        intent.putExtra("RECONCILIATION_ID", documentId)
        startActivity(intent)
    }

    // ============================================================================
    // END OF PART 6: DATA LOADING
    // ============================================================================


    // ============================================================================
    // START OF PART 7: ADAPTER
    // ============================================================================

    inner class ReconciliationAdapter(
        private val items: List<ReconciliationSummary>,
        private val onItemClick: (ReconciliationSummary) -> Unit
    ) : RecyclerView.Adapter<ReconciliationAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemReconciliationBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemReconciliationBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]

            with(holder.binding) {
                // Format and display date
                val formattedDate = formatDisplayDate(item.date)
                reconciliationDate.text = formattedDate

                // Location
                locationText.text = item.location

                // Status filter badge
                statusFilterBadge.text = item.statusFilter
                statusFilterBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    when (item.statusFilter) {
                        "On-Display" -> getColor(R.color.purple)
                        "On-Hand" -> getColor(R.color.cash_dark_green)
                        else -> getColor(R.color.teal_700)
                    }
                )

                // Show/hide sections based on status filter
                when (item.statusFilter) {
                    "On-Display" -> {
                        onDisplaySection.visibility = View.VISIBLE
                        onStockSection.visibility = View.GONE
                    }
                    "On-Hand" -> {
                        onDisplaySection.visibility = View.GONE
                        onStockSection.visibility = View.VISIBLE
                    }
                    "All" -> {
                        onDisplaySection.visibility = View.VISIBLE
                        onStockSection.visibility = View.VISIBLE
                    }
                }

                // On Display counts
                qtyOnDisplay.text = item.qtyOnDisplay.toString()
                qtyOnDisplayVerified.text = item.qtyOnDisplayVerified.toString()
                qtyOnDisplayReconciled.text = item.qtyOnDisplayReconciled.toString()

                // On Stock counts
                qtyOnStock.text = item.qtyOnStock.toString()
                qtyOnStockVerified.text = item.qtyOnStockVerified.toString()
                qtyOnStockReconciled.text = item.qtyOnStockReconciled.toString()

                // Created info
                createdInfo.text = "Created at ${item.createdAt}"

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
            // Parse M/d/yyyy
            val inputFormat = SimpleDateFormat("M/d/yyyy", Locale.US)
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
        Toast.makeText(this, message, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    // ============================================================================
    // END OF PART 8: UTILITY METHODS
    // ============================================================================
}