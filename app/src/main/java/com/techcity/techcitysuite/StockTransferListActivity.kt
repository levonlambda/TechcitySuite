package com.techcity.techcitysuite

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityStockTransferListBinding
import com.techcity.techcitysuite.databinding.ItemStockTransferBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class StockTransferListActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityStockTransferListBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var transfers: MutableList<StockTransfer> = mutableListOf()
    private var adapter: TransferAdapter? = null

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================


    // ============================================================================
    // START OF PART 2: LIFECYCLE METHODS
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityStockTransferListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = Firebase.firestore

        binding.transferRecyclerView.layoutManager = LinearLayoutManager(this)

        binding.addButton.setOnClickListener {
            if (!StoreLocationHelper.isConfigured(this)) {
                showMessage(StoreLocationHelper.NOT_CONFIGURED_MESSAGE, true)
                return@setOnClickListener
            }
            showPasswordDialog {
                startActivity(Intent(this, NewStockTransferActivity::class.java))
            }
        }

        loadTransfers()
    }

    override fun onResume() {
        super.onResume()
        // Reload when returning from the detail or new-transfer screens
        loadTransfers()
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

    /**
     * Settings-password gate (same dialog and verification as Phone Inventory).
     */
    private fun showPasswordDialog(onSuccess: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_password, null)

        val passwordInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.passwordInput)
        val errorMessage = dialogView.findViewById<android.widget.TextView>(R.id.errorMessage)
        val submitButton = dialogView.findViewById<android.widget.Button>(R.id.submitButton)
        val cancelButton = dialogView.findViewById<android.widget.Button>(R.id.cancelButton)
        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.progressBar)
        val buttonsLayout = dialogView.findViewById<android.widget.LinearLayout>(R.id.buttonsLayout)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        cancelButton.setOnClickListener { dialog.dismiss() }

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
                        onSuccess()
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
                    Toast.makeText(this@StockTransferListActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
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

        dialog.show()
    }

    // ============================================================================
    // END OF PART 3: PASSWORD DIALOG
    // ============================================================================


    // ============================================================================
    // START OF PART 4: DATA LOADING
    // ============================================================================

    private fun loadTransfers() {
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyStateLayout.visibility = View.GONE
        binding.transferRecyclerView.visibility = View.GONE

        scope.launch {
            try {
                val fetched = withContext(Dispatchers.IO) { fetchTransfers() }

                transfers.clear()
                transfers.addAll(fetched)

                binding.progressBar.visibility = View.GONE

                if (transfers.isEmpty()) {
                    binding.emptyStateLayout.visibility = View.VISIBLE
                    binding.emptyMessage.text = "No stock transfers"
                    binding.transferRecyclerView.visibility = View.GONE
                } else {
                    binding.emptyStateLayout.visibility = View.GONE
                    binding.transferRecyclerView.visibility = View.VISIBLE

                    adapter = TransferAdapter(
                        transfers,
                        onItemClick = { transfer -> openDetailActivity(transfer.id) },
                        onItemLongClick = { transfer -> attemptDelete(transfer) }
                    )
                    binding.transferRecyclerView.adapter = adapter
                }
            } catch (e: Exception) {
                e.printStackTrace()
                binding.progressBar.visibility = View.GONE
                binding.emptyStateLayout.visibility = View.VISIBLE
                binding.emptyMessage.text = "Error loading records: ${e.message}"
                showMessage("Error: ${e.message}", true)
            }
        }
    }

    /**
     * All transfers, newest first. Every device sees every transfer (spec Rule 5).
     */
    private suspend fun fetchTransfers(): List<StockTransfer> {
        val querySnapshot = db.collection(AppConstants.COLLECTION_STOCK_TRANSFERS)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .await()

        return querySnapshot.documents.mapNotNull { doc ->
            try {
                StockTransfer.fromDocument(doc)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun openDetailActivity(transferId: String) {
        val intent = Intent(this, StockTransferDetailActivity::class.java)
        intent.putExtra(StockTransferDetailActivity.EXTRA_TRANSFER_ID, transferId)
        startActivity(intent)
    }

    // ============================================================================
    // END OF PART 4: DATA LOADING
    // ============================================================================


    // ============================================================================
    // START OF PART 5: DELETE
    // ============================================================================

    private fun attemptDelete(transfer: StockTransfer) {
        if (!transfer.isPending) {
            showMessage("Completed transfers cannot be deleted", true)
            return
        }
        if (!StoreLocationHelper.isConfigured(this)) {
            showMessage(StoreLocationHelper.NOT_CONFIGURED_MESSAGE, true)
            return
        }
        showPasswordDialog { showDeleteConfirmationDialog(transfer) }
    }

    private fun showDeleteConfirmationDialog(transfer: StockTransfer) {
        val message = StringBuilder()
        message.append("Delete this stock transfer?\n\n")
        message.append("Reference: ${transfer.reference}\n")
        message.append("From: ${transfer.fromLocation}\n")
        message.append("To: ${transfer.toLocation}\n")
        message.append("Phones: ${transfer.phoneCount}\n")
        message.append("Accessory units: ${transfer.accessoryUnitCount}\n\n")
        message.append("This will return the items to ${transfer.fromLocation}. This action cannot be undone.")

        AlertDialog.Builder(this)
            .setTitle("Delete Stock Transfer")
            .setMessage(message.toString())
            .setPositiveButton("Delete") { dialog, _ ->
                dialog.dismiss()
                deleteTransfer(transfer)
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun deleteTransfer(transfer: StockTransfer) {
        scope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                val ctx = StockTransferRepository.contextFrom(this@StockTransferListActivity)
                StockTransferRepository.deleteTransfer(db, ctx, transfer.id)
                binding.progressBar.visibility = View.GONE
                showMessage("Stock transfer deleted", false)
                loadTransfers()
            } catch (e: Exception) {
                e.printStackTrace()
                binding.progressBar.visibility = View.GONE
                showMessage("Error deleting transfer: ${e.message}", true)
            }
        }
    }

    // ============================================================================
    // END OF PART 5: DELETE
    // ============================================================================


    // ============================================================================
    // START OF PART 6: ADAPTER
    // ============================================================================

    inner class TransferAdapter(
        private val items: List<StockTransfer>,
        private val onItemClick: (StockTransfer) -> Unit,
        private val onItemLongClick: (StockTransfer) -> Unit
    ) : RecyclerView.Adapter<TransferAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemStockTransferBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemStockTransferBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val deviceStore = StoreLocationHelper.getStoreLocation(this@StockTransferListActivity)

            with(holder.binding) {
                transferDate.text = formatDisplayDate(item.date)

                // Status badge
                if (item.isCompleted) {
                    statusBadge.text = "Completed"
                    statusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(this@StockTransferListActivity, R.color.cash_dark_green)
                    )
                } else {
                    statusBadge.text = "Pending"
                    statusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(this@StockTransferListActivity, R.color.techcity_blue)
                    )
                }
                discrepancyBadge.visibility = if (item.hasDiscrepancies) View.VISIBLE else View.GONE

                routeText.text = "${item.fromLocation} → ${item.toLocation}"

                // Direction hint relative to this device's store
                when {
                    deviceStore.isNotBlank() && deviceStore == item.toLocation -> {
                        directionText.visibility = View.VISIBLE
                        directionText.text = "Incoming"
                        directionText.setTextColor(ContextCompat.getColor(this@StockTransferListActivity, R.color.cash_dark_green))
                    }
                    deviceStore.isNotBlank() && deviceStore == item.fromLocation -> {
                        directionText.visibility = View.VISIBLE
                        directionText.text = "Outgoing"
                        directionText.setTextColor(ContextCompat.getColor(this@StockTransferListActivity, R.color.orange))
                    }
                    else -> directionText.visibility = View.GONE
                }

                val phonesLine = StringBuilder("Phones: ${item.verifiedPhoneCount} / ${item.phoneCount} verified")
                if (item.missingPhoneCount > 0) phonesLine.append(" · ${item.missingPhoneCount} missing")
                phonesProgressText.text = phonesLine.toString()

                val accLine = StringBuilder("Accessories: ${item.receivedAccessoryUnits} / ${item.totalAccessoryUnits} received")
                if (item.missingAccessoryUnitCount > 0) accLine.append(" · ${item.missingAccessoryUnitCount} missing")
                accessoriesProgressText.text = accLine.toString()

                createdInfo.text = if (item.isCompleted) {
                    "Created at ${item.createdTime} by ${item.createdBy} · Received by ${item.completedBy}"
                } else {
                    "Created at ${item.createdTime} by ${item.createdBy}"
                }

                root.setOnClickListener { onItemClick(item) }
                root.setOnLongClickListener {
                    onItemLongClick(item)
                    true
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    // ============================================================================
    // END OF PART 6: ADAPTER
    // ============================================================================


    // ============================================================================
    // START OF PART 7: UTILITY METHODS
    // ============================================================================

    private fun formatDisplayDate(dateStr: String): String {
        return try {
            val inputFormat = SimpleDateFormat("M/d/yyyy", Locale.US)
            val outputFormat = SimpleDateFormat("MMMM d, yyyy", Locale.US)
            val date = inputFormat.parse(dateStr)
            outputFormat.format(date!!)
        } catch (e: Exception) {
            dateStr
        }
    }

    private fun showMessage(message: String, isError: Boolean) {
        Toast.makeText(this, message, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    // ============================================================================
    // END OF PART 7: UTILITY METHODS
    // ============================================================================
}
