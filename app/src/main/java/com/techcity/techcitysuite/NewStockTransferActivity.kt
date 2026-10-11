package com.techcity.techcitysuite

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityNewStockTransferBinding
import kotlinx.coroutines.*

/**
 * Origin-side draft builder: scan phones (by IMEI / serial) and accessories (by product
 * barcode + quantity), pick the destination branch, and create the pending transfer.
 * The draft lives only in memory until Create succeeds.
 */
class NewStockTransferActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityNewStockTransferBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var ctx: StockTransferRepository.TransferContext
    private lateinit var adapter: TransferItemsAdapter

    // Draft state
    private val draftPhones = mutableListOf<TransferPhoneItem>()
    private val draftAccessories = linkedMapOf<String, TransferAccessoryItem>()
    private val originAvailability = mutableMapOf<String, Pair<Int, Int>>()

    private val barcodeScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scannedValue = result.data?.getStringExtra(BarcodeScannerActivity.RESULT_BARCODE_VALUE) ?: ""
            if (scannedValue.isNotEmpty()) {
                handleScannedValue(scannedValue)
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

        binding = ActivityNewStockTransferBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = Firebase.firestore
        ctx = StockTransferRepository.contextFrom(this)

        if (ctx.storeLocation.isBlank() || ctx.storeLocationId.isBlank()) {
            showMessage(StoreLocationHelper.NOT_CONFIGURED_MESSAGE, true)
            finish()
            return
        }

        binding.fromLocationLabel.text = "From: ${ctx.storeLocation}"

        adapter = TransferItemsAdapter(this, TransferItemsAdapter.Mode.DRAFT)
        binding.draftRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.draftRecyclerView.adapter = adapter
        setupSwipeToRemove()

        binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.scanFab.setOnClickListener { openBarcodeScanner() }
        binding.createButton.setOnClickListener { showCreateTransferDialog() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (draftPhones.isEmpty() && draftAccessories.isEmpty()) {
                    finish()
                } else {
                    showDiscardDialog()
                }
            }
        })

        refreshDraftUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ============================================================================
    // END OF PART 2: LIFECYCLE METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 3: DRAFT UI
    // ============================================================================

    private fun refreshDraftUI() {
        val units = draftAccessories.values.sumOf { it.quantity }
        binding.countsLabel.text = "${draftPhones.size} phones · $units accessory units"

        val isEmpty = draftPhones.isEmpty() && draftAccessories.isEmpty()
        binding.createButton.isEnabled = !isEmpty
        binding.emptyStateLayout.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.draftRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE

        adapter.submit(
            phones = draftPhones.toList(),
            accessories = draftAccessories.values.toList(),
            availability = originAvailability
        )
    }

    /**
     * Swipe left on a phone or accessory row to remove it from the draft (after confirming).
     */
    private fun setupSwipeToRemove() {
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                return if (adapter.isSwipeable(viewHolder.adapterPosition)) ItemTouchHelper.LEFT else 0
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                when (val row = adapter.rowAt(position)) {
                    is TransferItemsAdapter.Row.Phone -> confirmRemove(
                        "Remove ${row.item.manufacturer} ${row.item.model} from this transfer?",
                        onConfirm = {
                            draftPhones.removeAll { it.inventoryId == row.item.inventoryId }
                            refreshDraftUI()
                        },
                        onCancel = { adapter.notifyItemChanged(position) }
                    )
                    is TransferItemsAdapter.Row.Accessory -> confirmRemove(
                        "Remove ${row.item.displayName} from this transfer?",
                        onConfirm = {
                            draftAccessories.remove(row.item.sku)
                            originAvailability.remove(row.item.sku)
                            refreshDraftUI()
                        },
                        onCancel = { adapter.notifyItemChanged(position) }
                    )
                    else -> adapter.notifyItemChanged(position)
                }
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.draftRecyclerView)
    }

    private fun confirmRemove(message: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Remove Item")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Remove") { dialog, _ ->
                dialog.dismiss()
                onConfirm()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                onCancel()
            }
            .show()
    }

    private fun showDiscardDialog() {
        AlertDialog.Builder(this)
            .setTitle("Discard Transfer?")
            .setMessage("Discard this transfer? Scanned items will be lost.")
            .setPositiveButton("Discard") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setNegativeButton("Keep Editing") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // ============================================================================
    // END OF PART 3: DRAFT UI
    // ============================================================================


    // ============================================================================
    // START OF PART 4: SCANNING
    // ============================================================================

    private fun openBarcodeScanner() {
        if (draftPhones.size >= AppConstants.TRANSFER_MAX_PHONES &&
            draftAccessories.size >= AppConstants.TRANSFER_MAX_ACCESSORY_SKUS
        ) {
            showSizeCapMessage()
            return
        }
        // Default scanner mode: IMEI prioritised, product codes allowed, one value per launch
        val intent = Intent(this, BarcodeScannerActivity::class.java)
        barcodeScannerLauncher.launch(intent)
    }

    private fun showSizeCapMessage() {
        showMessage(
            "Maximum ${AppConstants.TRANSFER_MAX_PHONES} phones / ${AppConstants.TRANSFER_MAX_ACCESSORY_SKUS} accessory items per transfer. " +
                "Please create another transfer for the rest.",
            true
        )
    }

    private fun handleScannedValue(scannedValue: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.scanFab.isEnabled = false

        scope.launch {
            try {
                val draftIds = draftPhones.map { it.inventoryId }.toSet()
                val result = StockTransferRepository.classifyScan(db, scannedValue, ctx.storeLocation, draftIds)

                binding.progressBar.visibility = View.GONE
                binding.scanFab.isEnabled = true

                when (result) {
                    is StockTransferRepository.ScanResult.PhoneFound -> addPhone(result.item)
                    is StockTransferRepository.ScanResult.PhoneBoxBarcode -> showPhoneBoxDialog(result.manufacturer, result.model)
                    is StockTransferRepository.ScanResult.AccessoryFound -> promptAccessoryQuantity(result)
                    is StockTransferRepository.ScanResult.Ineligible -> showMessage(result.message, true)
                    StockTransferRepository.ScanResult.NotFound -> showMessage(
                        "Barcode not found. Phones must be scanned by IMEI or Serial Number; accessories must exist in the accessory catalog.",
                        true
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                binding.progressBar.visibility = View.GONE
                binding.scanFab.isEnabled = true
                showMessage("Error looking up barcode: ${e.message}", true)
            }
        }
    }

    private fun addPhone(item: InventoryItem) {
        if (draftPhones.size >= AppConstants.TRANSFER_MAX_PHONES) {
            showSizeCapMessage()
            return
        }
        draftPhones.add(
            TransferPhoneItem(
                inventoryId = item.id,
                manufacturer = item.manufacturer,
                model = item.model,
                ram = item.ram,
                storage = item.storage,
                color = item.color,
                imei1 = item.imei1,
                imei2 = item.imei2,
                serialNumber = item.serialNumber,
                originalStatus = item.status,
                originalLocation = item.location
            )
        )
        refreshDraftUI()
        showMessage("Added ${item.manufacturer} ${item.model}", false)
    }

    private fun showPhoneBoxDialog(manufacturer: String, model: String) {
        AlertDialog.Builder(this)
            .setTitle("Phone Barcode Detected")
            .setMessage("This barcode belongs to a phone ($manufacturer $model). Please scan the IMEI or Serial Number instead.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun promptAccessoryQuantity(found: StockTransferRepository.ScanResult.AccessoryFound) {
        val existing = draftAccessories[found.sku]
        if (existing == null && draftAccessories.size >= AppConstants.TRANSFER_MAX_ACCESSORY_SKUS) {
            showSizeCapMessage()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        scope.launch {
            try {
                val stock = StockTransferRepository.readOriginStock(db, found.sku, ctx.storeLocationId)
                binding.progressBar.visibility = View.GONE

                val name = "${found.manufacturer} ${found.model}".trim().ifBlank { found.sku }
                if (stock == null || (stock.first + stock.second) <= 0) {
                    AlertDialog.Builder(this@NewStockTransferActivity)
                        .setTitle("No Stock")
                        .setMessage("No stock recorded at ${ctx.storeLocation} for $name.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@launch
                }

                val max = stock.first + stock.second
                TransferQuantityDialog.show(
                    activity = this@NewStockTransferActivity,
                    title = "Transfer Quantity",
                    itemName = name,
                    stockInfo = "Available at ${ctx.storeLocation}: ${stock.first} on hand, ${stock.second} on display",
                    prefill = existing?.quantity ?: 1,
                    max = max,
                    submitLabel = if (existing == null) "Add" else "Update"
                ) { quantity ->
                    draftAccessories[found.sku] = TransferAccessoryItem(
                        sku = found.sku,
                        barcode = found.barcode,
                        manufacturer = found.manufacturer,
                        model = found.model,
                        category = found.category,
                        quantity = quantity
                    )
                    originAvailability[found.sku] = stock
                    refreshDraftUI()
                    showMessage(if (existing == null) "Added $name × $quantity" else "Updated $name to $quantity", false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                binding.progressBar.visibility = View.GONE
                showMessage("Error reading stock: ${e.message}", true)
            }
        }
    }

    // ============================================================================
    // END OF PART 4: SCANNING
    // ============================================================================


    // ============================================================================
    // START OF PART 5: CREATE TRANSFER DIALOG
    // ============================================================================

    private fun showCreateTransferDialog() {
        if (draftPhones.isEmpty() && draftAccessories.isEmpty()) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_create_stock_transfer, null)

        val locationInput = dialogView.findViewById<AutoCompleteTextView>(R.id.locationInput)
        val summaryText = dialogView.findViewById<android.widget.TextView>(R.id.summaryText)
        val errorMessage = dialogView.findViewById<android.widget.TextView>(R.id.errorMessage)
        val submitButton = dialogView.findViewById<android.widget.Button>(R.id.submitButton)
        val cancelButton = dialogView.findViewById<android.widget.Button>(R.id.cancelButton)
        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.progressBar)
        val buttonsLayout = dialogView.findViewById<android.widget.LinearLayout>(R.id.buttonsLayout)

        val units = draftAccessories.values.sumOf { it.quantity }
        summaryText.text = "From ${ctx.storeLocation} · ${draftPhones.size} phones · $units accessory units"

        // Destination dropdown: active store locations other than this device's own store
        val nameToId = mutableMapOf<String, String>()
        submitButton.isEnabled = false
        scope.launch {
            try {
                val locations = withContext(Dispatchers.IO) { StoreLocationHelper.loadActiveLocations(db) }
                    .filter { it.name != ctx.storeLocation }
                locations.forEach { nameToId[it.name] = it.id }

                if (locations.isEmpty()) {
                    errorMessage.text = "No other branch available"
                    errorMessage.visibility = View.VISIBLE
                    return@launch
                }

                val locationAdapter = ArrayAdapter(
                    this@NewStockTransferActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    locations.map { it.name }
                )
                locationInput.setAdapter(locationAdapter)
                submitButton.isEnabled = true
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage.text = "Error loading store locations: ${e.message}"
                errorMessage.visibility = View.VISIBLE
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        cancelButton.setOnClickListener { dialog.dismiss() }

        submitButton.setOnClickListener {
            val toLocation = locationInput.text.toString().trim()
            val toLocationId = nameToId[toLocation]

            if (toLocation.isEmpty() || toLocationId == null) {
                errorMessage.text = "Please select a branch"
                errorMessage.visibility = View.VISIBLE
                return@setOnClickListener
            }

            errorMessage.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            buttonsLayout.visibility = View.GONE
            locationInput.isEnabled = false

            scope.launch {
                try {
                    StockTransferRepository.createTransfer(
                        db = db,
                        ctx = ctx,
                        phones = draftPhones.toList(),
                        accessories = draftAccessories.values.toList(),
                        toLocation = toLocation,
                        toLocationId = toLocationId
                    )
                    dialog.dismiss()
                    showMessage("Stock transfer created for $toLocation", false)
                    // Draft is now saved; clear it so Back does not ask to discard
                    draftPhones.clear()
                    draftAccessories.clear()
                    finish()
                } catch (e: Exception) {
                    e.printStackTrace()
                    progressBar.visibility = View.GONE
                    buttonsLayout.visibility = View.VISIBLE
                    locationInput.isEnabled = true
                    errorMessage.text = "Error: ${e.message}"
                    errorMessage.visibility = View.VISIBLE
                }
            }
        }

        dialog.show()
    }

    // ============================================================================
    // END OF PART 5: CREATE TRANSFER DIALOG
    // ============================================================================


    // ============================================================================
    // START OF PART 6: UTILITY METHODS
    // ============================================================================

    private fun showMessage(message: String, isError: Boolean) {
        Toast.makeText(this, message, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    // ============================================================================
    // END OF PART 6: UTILITY METHODS
    // ============================================================================
}
