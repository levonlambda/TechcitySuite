package com.techcity.techcitysuite

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityStockTransferDetailBinding
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Stock transfer detail: progress view for every device, receiving actions (scan, manual
 * verify, Confirm, Complete with discrepancies) for the destination device only.
 */
class StockTransferDetailActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    companion object {
        const val EXTRA_TRANSFER_ID = "TRANSFER_ID"
    }

    private lateinit var binding: ActivityStockTransferDetailBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var transferId: String = ""
    private var transfer: StockTransfer? = null
    private var transferListener: ListenerRegistration? = null
    private lateinit var adapter: TransferItemsAdapter

    private val barcodeScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scannedValue = result.data?.getStringExtra(BarcodeScannerActivity.RESULT_BARCODE_VALUE) ?: ""
            if (scannedValue.isNotEmpty()) {
                processScannedBarcode(scannedValue)
            }
        }
    }

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================


    // ============================================================================
    // START OF PART 2: LIFECYCLE METHODS
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityStockTransferDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = Firebase.firestore
        transferId = intent.getStringExtra(EXTRA_TRANSFER_ID) ?: ""

        if (transferId.isEmpty()) {
            showMessage("Error: No transfer ID provided", true)
            finish()
            return
        }

        adapter = TransferItemsAdapter(this, TransferItemsAdapter.Mode.DETAIL) { phone -> onPhoneRowClicked(phone) }
        binding.itemsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.itemsRecyclerView.adapter = adapter

        binding.backButton.setOnClickListener { finish() }
        binding.kebabButton.setOnClickListener { showOverflowMenu() }
        binding.scanFab.setOnClickListener {
            val t = transfer ?: return@setOnClickListener
            if (t.isFullyVerified) showConfirmDialog() else openBarcodeScanner()
        }

        binding.progressBar.visibility = View.VISIBLE
        setupRealtimeListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        transferListener?.remove()
    }

    // ============================================================================
    // END OF PART 2: LIFECYCLE METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 3: DATA LOADING
    // ============================================================================

    private fun setupRealtimeListener() {
        transferListener = db.collection(AppConstants.COLLECTION_STOCK_TRANSFERS)
            .document(transferId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    showMessage("Error listening for updates: ${error.message}", true)
                    return@addSnapshotListener
                }

                if (snapshot == null || !snapshot.exists()) {
                    showMessage("This transfer was deleted", true)
                    finish()
                    return@addSnapshotListener
                }

                val parsed = StockTransfer.fromDocument(snapshot)
                if (parsed == null) {
                    binding.progressBar.visibility = View.GONE
                    binding.emptyStateLayout.visibility = View.VISIBLE
                    binding.emptyMessage.text = "Transfer could not be read"
                    return@addSnapshotListener
                }

                transfer = parsed
                renderTransfer(parsed)
            }
    }

    /**
     * True when this device's store location is the transfer's destination.
     */
    private fun isDestinationDevice(t: StockTransfer): Boolean {
        return StoreLocationHelper.isConfigured(this) &&
            StoreLocationHelper.getStoreLocation(this) == t.toLocation
    }

    // ============================================================================
    // END OF PART 3: DATA LOADING
    // ============================================================================


    // ============================================================================
    // START OF PART 4: UI RENDERING
    // ============================================================================

    private fun renderTransfer(t: StockTransfer) {
        binding.progressBar.visibility = View.GONE

        // Header
        binding.dateLabel.text = formatDisplayDate(t.date)
        binding.routeLabel.text = "${t.fromLocation} → ${t.toLocation}"
        binding.itemCountLabel.text = "${t.phoneCount} phones · ${t.totalAccessoryUnits} units"
        binding.createdByLabel.text = "Created by ${t.createdBy} at ${t.createdTime}"

        if (t.isCompleted) {
            binding.statusBadge.text = if (t.hasDiscrepancies) "Completed · Discrepancies" else "Completed"
            binding.statusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, if (t.hasDiscrepancies) R.color.red else R.color.cash_dark_green)
            )
            binding.statusBadge.setTextColor(ContextCompat.getColor(this, R.color.white))
        } else {
            binding.statusBadge.text = "Pending"
            binding.statusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.white)
            )
            binding.statusBadge.setTextColor(ContextCompat.getColor(this, R.color.techcity_blue))
        }

        // Summary card
        binding.summaryPhonesText.text = "Phones: ${t.verifiedPhoneCount} / ${t.phoneCount} verified"
        binding.summaryAccessoriesText.text = "Accessories: ${t.receivedAccessoryUnits} / ${t.totalAccessoryUnits} units received"
        if (t.hasDiscrepancies) {
            binding.summaryMissingText.visibility = View.VISIBLE
            binding.summaryMissingText.text = "Missing: ${t.missingPhoneCount} phone(s) · ${t.missingAccessoryUnitCount} unit(s)"
        } else {
            binding.summaryMissingText.visibility = View.GONE
        }

        // Item list
        if (t.phones.isEmpty() && t.accessories.isEmpty()) {
            binding.emptyStateLayout.visibility = View.VISIBLE
            binding.itemsRecyclerView.visibility = View.GONE
        } else {
            binding.emptyStateLayout.visibility = View.GONE
            binding.itemsRecyclerView.visibility = View.VISIBLE
            adapter.submit(t.sortedPhones, t.sortedAccessories)
        }

        updateActions(t)
    }

    /**
     * Scan / Confirm FAB and the overflow menu are only available on the destination
     * device while the transfer is pending.
     */
    private fun updateActions(t: StockTransfer) {
        val canAct = t.isPending && isDestinationDevice(t)

        if (!canAct) {
            binding.scanFab.visibility = View.GONE
            binding.kebabButton.visibility = View.GONE
            return
        }

        binding.scanFab.visibility = View.VISIBLE
        if (t.isFullyVerified) {
            binding.scanFab.text = "Confirm"
            binding.scanFab.setIconResource(R.drawable.ic_check)
            binding.scanFab.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.cash_dark_green)
            )
            binding.kebabButton.visibility = View.GONE
        } else {
            binding.scanFab.text = "Scan"
            binding.scanFab.setIconResource(R.drawable.ic_add)
            binding.scanFab.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.techcity_blue)
            )
            binding.kebabButton.visibility = View.VISIBLE
        }
    }

    private fun showOverflowMenu() {
        val t = transfer ?: return
        if (!t.isPending || !isDestinationDevice(t) || t.isFullyVerified) return

        val popup = PopupMenu(this, binding.kebabButton)
        popup.menu.add("Complete with discrepancies")
        popup.setOnMenuItemClickListener {
            showPasswordDialog("Complete with Discrepancies", "Enter password to complete this transfer with missing items") {
                showDiscrepancySummaryDialog()
            }
            true
        }
        popup.show()
    }

    // ============================================================================
    // END OF PART 4: UI RENDERING
    // ============================================================================


    // ============================================================================
    // START OF PART 5: SCANNING AND VERIFICATION
    // ============================================================================

    private fun openBarcodeScanner() {
        val t = transfer ?: return

        // Auto-match against everything expected in this transfer
        val validIdentifiers = ArrayList<String>()
        for (phone in t.phones.values) {
            if (phone.imei1.isNotEmpty()) validIdentifiers.add(phone.imei1)
            if (phone.imei2.isNotEmpty()) validIdentifiers.add(phone.imei2)
            if (phone.serialNumber.isNotEmpty()) validIdentifiers.add(phone.serialNumber)
        }
        for (line in t.accessories.values) {
            if (line.barcode.isNotEmpty()) validIdentifiers.add(line.barcode)
        }

        val intent = Intent(this, BarcodeScannerActivity::class.java)
        intent.putExtra(BarcodeScannerActivity.EXTRA_AUTO_MATCH_ENABLED, true)
        intent.putStringArrayListExtra(BarcodeScannerActivity.EXTRA_VALID_IDENTIFIERS, validIdentifiers)
        barcodeScannerLauncher.launch(intent)
    }

    private fun processScannedBarcode(rawValue: String) {
        val t = transfer ?: return
        if (!t.isPending || !isDestinationDevice(t)) {
            showMessage("Only the destination branch can receive this transfer", true)
            return
        }
        val value = rawValue.trim()

        // Phone in this transfer?
        val phone = t.phones.values.find { it.imei1 == value || it.imei2 == value || it.serialNumber == value }
        if (phone != null) {
            if (phone.verified) {
                showMessage("Already verified by ${phone.verifiedBy}", false)
                return
            }
            val scannedType = when (value) {
                phone.imei1 -> "IMEI1"
                phone.imei2 -> "IMEI2"
                phone.serialNumber -> "Serial"
                else -> "Unknown"
            }
            verifyPhone(phone, scannedType, value)
            return
        }

        // Accessory in this transfer?
        val line = t.accessories.values.find { it.barcode == value }
        if (line != null) {
            val remaining = line.remainingQuantity
            if (remaining <= 0) {
                showMessage("${line.displayName} is already fully received", false)
                return
            }
            TransferQuantityDialog.show(
                activity = this,
                title = "Received Quantity",
                itemName = line.displayName.ifBlank { line.sku },
                stockInfo = "Expected ${line.quantity} · Received ${line.receivedQuantity} · Remaining $remaining",
                prefill = remaining,
                max = remaining,
                submitLabel = "Confirm"
            ) { amount -> receiveAccessory(line, amount) }
            return
        }

        // Not in this transfer: tell a phone box barcode apart from an unknown item
        scope.launch {
            try {
                val isBox = StockTransferRepository.isPhoneBoxBarcode(db, value)
                if (isBox) {
                    AlertDialog.Builder(this@StockTransferDetailActivity)
                        .setTitle("Phone Barcode Detected")
                        .setMessage("This barcode belongs to a phone. Please scan the IMEI or Serial Number instead.")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    showMessage("Item is not part of this transfer", true)
                }
            } catch (e: Exception) {
                showMessage("Item is not part of this transfer", true)
            }
        }
    }

    private fun verifyPhone(phone: TransferPhoneItem, scannedType: String, scannedValue: String) {
        val ctx = StockTransferRepository.contextFrom(this)
        scope.launch {
            try {
                StockTransferRepository.verifyPhone(db, transferId, phone.inventoryId, ctx.userName, scannedType, scannedValue)
                showMessage("✓ Verified!", false)
            } catch (e: Exception) {
                e.printStackTrace()
                showMessage("Error saving verification: ${e.message}", true)
            }
        }
    }

    private fun receiveAccessory(line: TransferAccessoryItem, amount: Int) {
        val ctx = StockTransferRepository.contextFrom(this)
        scope.launch {
            try {
                StockTransferRepository.receiveAccessory(db, transferId, line.sku, amount, ctx.userName)
                showMessage("✓ Received $amount × ${line.displayName}", false)
            } catch (e: Exception) {
                e.printStackTrace()
                showMessage("Error saving receipt: ${e.message}", true)
            }
        }
    }

    /**
     * Tapping an unverified phone on the destination device offers manual verification
     * (password-gated, same as the reconciliation manufacturer screen).
     */
    private fun onPhoneRowClicked(phone: TransferPhoneItem) {
        val t = transfer ?: return
        if (!t.isPending || !isDestinationDevice(t) || phone.verified) return

        showPasswordDialog("Manual Verification", "Enter password to manually verify this item") {
            showManualVerificationConfirmationDialog(phone)
        }
    }

    private fun showManualVerificationConfirmationDialog(phone: TransferPhoneItem) {
        val ramDisplay = if (phone.ram.endsWith("GB", ignoreCase = true)) phone.ram else "${phone.ram}GB"
        val storageDisplay = if (phone.storage.endsWith("GB", ignoreCase = true)) phone.storage else "${phone.storage}GB"

        val details = StringBuilder()
        details.append("Manufacturer: ${phone.manufacturer}\n")
        details.append("Model: ${phone.model}\n")
        details.append("RAM: $ramDisplay\n")
        details.append("Storage: $storageDisplay\n")
        details.append("Color: ${phone.color}")
        if (phone.imei1.isNotEmpty()) details.append("\nIMEI1: ${phone.imei1}")
        if (phone.imei2.isNotEmpty()) details.append("\nIMEI2: ${phone.imei2}")
        if (phone.serialNumber.isNotEmpty()) details.append("\nSerial: ${phone.serialNumber}")

        AlertDialog.Builder(this)
            .setTitle("Confirm Manual Verification")
            .setMessage(details.toString())
            .setPositiveButton("Confirm") { dialog, _ ->
                dialog.dismiss()
                verifyPhone(phone, "Manual", "Manual verification")
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // ============================================================================
    // END OF PART 5: SCANNING AND VERIFICATION
    // ============================================================================


    // ============================================================================
    // START OF PART 6: CONFIRM / COMPLETE WITH DISCREPANCIES
    // ============================================================================

    private fun showConfirmDialog() {
        val t = transfer ?: return
        AlertDialog.Builder(this)
            .setTitle("Confirm Receipt")
            .setMessage(
                "Confirm receipt of ${t.phoneCount} phones and ${t.totalAccessoryUnits} accessory units from ${t.fromLocation}? " +
                    "This will move the stock to ${t.toLocation}."
            )
            .setPositiveButton("Confirm") { dialog, _ ->
                dialog.dismiss()
                confirmTransfer()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun confirmTransfer() {
        val ctx = StockTransferRepository.contextFrom(this)
        binding.progressBar.visibility = View.VISIBLE
        binding.scanFab.isEnabled = false
        scope.launch {
            try {
                StockTransferRepository.confirmTransfer(db, ctx, transferId)
                binding.progressBar.visibility = View.GONE
                binding.scanFab.isEnabled = true
                showMessage("Transfer completed. Stock moved to ${transfer?.toLocation ?: "destination"}.", false)
            } catch (e: Exception) {
                e.printStackTrace()
                binding.progressBar.visibility = View.GONE
                binding.scanFab.isEnabled = true
                showMessage("Error completing transfer: ${e.message}", true)
            }
        }
    }

    private fun showDiscrepancySummaryDialog() {
        val t = transfer ?: return

        val missingPhones = t.sortedPhones.filter { !it.verified }
        val shortLines = t.sortedAccessories.filter { it.receivedQuantity < it.quantity }

        val message = StringBuilder()
        if (missingPhones.isNotEmpty()) {
            message.append("Unverified phones (${missingPhones.size}):\n")
            missingPhones.forEach {
                message.append("• ${it.manufacturer} ${it.model} (${it.imei1.ifBlank { it.serialNumber }})\n")
            }
            message.append("\n")
        }
        if (shortLines.isNotEmpty()) {
            message.append("Short accessories:\n")
            shortLines.forEach {
                message.append("• ${it.displayName}: ${it.receivedQuantity} of ${it.quantity} received, ${it.remainingQuantity} missing\n")
            }
            message.append("\n")
        }
        message.append("Missing items will be returned to ${t.fromLocation}'s records. This cannot be undone.")

        AlertDialog.Builder(this)
            .setTitle("Complete with Discrepancies")
            .setMessage(message.toString())
            .setPositiveButton("Complete") { dialog, _ ->
                dialog.dismiss()
                completeWithDiscrepancies()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun completeWithDiscrepancies() {
        val ctx = StockTransferRepository.contextFrom(this)
        binding.progressBar.visibility = View.VISIBLE
        binding.scanFab.isEnabled = false
        scope.launch {
            try {
                val result = StockTransferRepository.completeWithDiscrepancies(db, ctx, transferId)
                binding.progressBar.visibility = View.GONE
                binding.scanFab.isEnabled = true
                if (result.skippedPhones.isEmpty()) {
                    showMessage("Transfer completed with discrepancies", false)
                } else {
                    AlertDialog.Builder(this@StockTransferDetailActivity)
                        .setTitle("Completed with Discrepancies")
                        .setMessage(
                            "These phones were no longer reserved for this transfer and were left untouched:\n\n" +
                                result.skippedPhones.joinToString("\n") { "• $it" }
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                binding.progressBar.visibility = View.GONE
                binding.scanFab.isEnabled = true
                showMessage("Error completing transfer: ${e.message}", true)
            }
        }
    }

    // ============================================================================
    // END OF PART 6: CONFIRM / COMPLETE WITH DISCREPANCIES
    // ============================================================================


    // ============================================================================
    // START OF PART 7: PASSWORD DIALOG
    // ============================================================================

    private fun showPasswordDialog(title: String, message: String, onSuccess: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_password, null)

        val passwordInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.passwordInput)
        val errorMessage = dialogView.findViewById<android.widget.TextView>(R.id.errorMessage)
        val submitButton = dialogView.findViewById<android.widget.Button>(R.id.submitButton)
        val cancelButton = dialogView.findViewById<android.widget.Button>(R.id.cancelButton)
        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.progressBar)
        val buttonsLayout = dialogView.findViewById<android.widget.LinearLayout>(R.id.buttonsLayout)

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
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
                    Toast.makeText(this@StockTransferDetailActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
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
    // END OF PART 7: PASSWORD DIALOG
    // ============================================================================


    // ============================================================================
    // START OF PART 8: UTILITY METHODS
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
    // END OF PART 8: UTILITY METHODS
    // ============================================================================
}
