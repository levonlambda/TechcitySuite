package com.techcity.techcitysuite

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityReconciliationDetailBinding
import com.techcity.techcitysuite.databinding.ItemManufacturerBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class ReconciliationDetailActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityReconciliationDetailBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Firebase collection names
    private val COLLECTION_INVENTORY_RECONCILIATIONS = "inventory_reconciliations"
    private val COLLECTION_INVENTORY = "inventory"

    // Reconciliation ID passed from list activity
    private var reconciliationId: String = ""

    // Reconciliation data
    private var reconciliationData: ReconciliationData? = null

    // All phones in this reconciliation
    private var allPhones: MutableList<PhoneItem> = mutableListOf()

    // Manufacturers grouped data
    private var manufacturers: MutableList<ManufacturerSummary> = mutableListOf()
    private var adapter: ManufacturerAdapter? = null

    // Verified items map (from Firebase)
    private var verifiedItems: MutableMap<String, VerificationInfo> = mutableMapOf()

    // Snapshot listener for real-time updates
    private var reconciliationListener: ListenerRegistration? = null

    /**
     * Data class for reconciliation data
     */
    data class ReconciliationData(
        val documentId: String,
        val date: String,
        val location: String,
        val statusFilter: String,
        val qtyOnDisplay: Int,
        val qtyOnDisplayVerified: Int,
        val qtyOnDisplayReconciled: Int,
        val qtyOnStock: Int,
        val qtyOnStockVerified: Int,
        val qtyOnStockReconciled: Int,
        val inventoryIds: List<String>,
        val createdAt: String
    )

    /**
     * Data class for phone item
     */
    data class PhoneItem(
        val documentId: String,
        val manufacturer: String,
        val model: String,
        val ram: String,
        val storage: String,
        val color: String,
        val imei1: String,
        val imei2: String,
        val serialNumber: String,
        val location: String,
        val status: String,
        val retailPrice: Double
    )

    /**
     * Data class for verification info
     */
    data class VerificationInfo(
        val verifiedBy: String,
        val verifiedAt: Timestamp?,
        val scannedType: String,
        val scannedValue: String
    )

    /**
     * Data class for manufacturer summary
     */
    data class ManufacturerSummary(
        val name: String,
        val totalItems: Int,
        val qtyOnDisplay: Int,
        val qtyOnDisplayVerified: Int,
        val qtyOnDisplayReconciled: Int,
        val qtyOnStock: Int,
        val qtyOnStockVerified: Int,
        val qtyOnStockReconciled: Int,
        val isComplete: Boolean
    )

    // Barcode scanner launcher
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

        // Initialize View Binding
        binding = ActivityReconciliationDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firebase
        db = Firebase.firestore

        // Get reconciliation ID from intent
        reconciliationId = intent.getStringExtra("RECONCILIATION_ID") ?: ""

        if (reconciliationId.isEmpty()) {
            showMessage("Error: No reconciliation ID provided", true)
            finish()
            return
        }

        // Setup RecyclerView
        binding.manufacturerRecyclerView.layoutManager = LinearLayoutManager(this)

        // Setup back button
        binding.backButton.setOnClickListener {
            finish()
        }

        // Setup scan FAB
        binding.scanFab.setOnClickListener {
            openBarcodeScanner()
        }

        // Load initial data then setup listener
        loadInitialData()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        // Remove snapshot listener
        reconciliationListener?.remove()
    }

    // ============================================================================
    // END OF PART 2: LIFECYCLE METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 3: DATA LOADING
    // ============================================================================

    private fun loadInitialData() {
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyStateLayout.visibility = View.GONE
        binding.manufacturerRecyclerView.visibility = View.GONE

        scope.launch {
            try {
                // First, fetch reconciliation document (one-time)
                val reconciliation = withContext(Dispatchers.IO) {
                    fetchReconciliation()
                }

                if (reconciliation == null) {
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        binding.emptyStateLayout.visibility = View.VISIBLE
                        binding.emptyMessage.text = "Reconciliation not found"
                    }
                    return@launch
                }

                reconciliationData = reconciliation

                // Update header UI
                withContext(Dispatchers.Main) {
                    updateHeaderUI(reconciliation)
                }

                // Fetch all phones
                val fetchedPhones = withContext(Dispatchers.IO) {
                    fetchPhones(reconciliation.inventoryIds)
                }

                allPhones.clear()
                allPhones.addAll(fetchedPhones)

                // Setup real-time listener for verification updates
                setupRealtimeListener()

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.emptyStateLayout.visibility = View.VISIBLE
                    binding.emptyMessage.text = "Error loading data: ${e.message}"
                    showMessage("Error: ${e.message}", true)
                }
            }
        }
    }

    private suspend fun fetchReconciliation(): ReconciliationData? {
        val doc = db.collection(COLLECTION_INVENTORY_RECONCILIATIONS)
            .document(reconciliationId)
            .get()
            .await()

        if (!doc.exists()) {
            return null
        }

        val data = doc.data ?: return null

        @Suppress("UNCHECKED_CAST")
        val inventoryIds = (data["inventoryIds"] as? List<String>) ?: emptyList()

        return ReconciliationData(
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
            inventoryIds = inventoryIds,
            createdAt = data["createdAt"] as? String ?: ""
        )
    }

    private suspend fun fetchPhones(inventoryIds: List<String>): List<PhoneItem> {
        if (inventoryIds.isEmpty()) {
            return emptyList()
        }

        // Firestore supports up to 30 items for whereIn queries
        val batchSize = 30
        val batches = inventoryIds.chunked(batchSize)

        // Fetch all batches in parallel for faster loading
        val deferredResults = batches.map { batch ->
            scope.async(Dispatchers.IO) {
                try {
                    db.collection(COLLECTION_INVENTORY)
                        .whereIn(com.google.firebase.firestore.FieldPath.documentId(), batch)
                        .get()
                        .await()
                } catch (e: Exception) {
                    null
                }
            }
        }

        // Collect results from all parallel requests
        val phonesList = mutableListOf<PhoneItem>()
        for (deferred in deferredResults) {
            val querySnapshot = deferred.await() ?: continue

            for (doc in querySnapshot.documents) {
                val data = doc.data ?: continue

                phonesList.add(
                    PhoneItem(
                        documentId = doc.id,
                        manufacturer = data["manufacturer"] as? String ?: "",
                        model = data["model"] as? String ?: "",
                        ram = data["ram"] as? String ?: "",
                        storage = data["storage"] as? String ?: "",
                        color = data["color"] as? String ?: "",
                        imei1 = data["imei1"] as? String ?: "",
                        imei2 = data["imei2"] as? String ?: "",
                        serialNumber = data["serialNumber"] as? String ?: "",
                        location = data["location"] as? String ?: "",
                        status = data["status"] as? String ?: "",
                        retailPrice = (data["retailPrice"] as? Number)?.toDouble() ?: 0.0
                    )
                )
            }
        }

        return phonesList.sortedBy { it.manufacturer }
    }

    private fun setupRealtimeListener() {
        reconciliationListener = db.collection(COLLECTION_INVENTORY_RECONCILIATIONS)
            .document(reconciliationId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    showMessage("Error listening for updates: ${error.message}", true)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    // Parse verified items from the document
                    @Suppress("UNCHECKED_CAST")
                    val verifiedItemsMap = snapshot.get("verifiedItems") as? Map<String, Map<String, Any>> ?: emptyMap()

                    verifiedItems.clear()
                    for ((inventoryId, info) in verifiedItemsMap) {
                        verifiedItems[inventoryId] = VerificationInfo(
                            verifiedBy = info["verifiedBy"] as? String ?: "",
                            verifiedAt = info["verifiedAt"] as? Timestamp,
                            scannedType = info["scannedType"] as? String ?: "",
                            scannedValue = info["scannedValue"] as? String ?: ""
                        )
                    }

                    // Recalculate and update UI
                    updateManufacturerList()
                    updateOverallSummary()
                }
            }
    }

    // ============================================================================
    // END OF PART 3: DATA LOADING
    // ============================================================================


    // ============================================================================
    // START OF PART 4: UI UPDATE METHODS
    // ============================================================================

    private fun updateHeaderUI(data: ReconciliationData) {
        // Format and display date
        binding.dateLabel.text = formatDisplayDate(data.date)

        // Location
        binding.locationLabel.text = "Location: ${data.location}"

        // Status filter badge
        binding.statusFilterBadge.text = data.statusFilter
        binding.statusFilterBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
            when (data.statusFilter) {
                "On-Display" -> ContextCompat.getColor(this, R.color.yellow)
                "On-Hand" -> ContextCompat.getColor(this, R.color.techcity_blue)
                else -> ContextCompat.getColor(this, R.color.white)
            }
        )
        binding.statusFilterBadge.setTextColor(
            when (data.statusFilter) {
                "All" -> ContextCompat.getColor(this, R.color.techcity_blue)
                "On-Display" -> ContextCompat.getColor(this, R.color.black)
                else -> ContextCompat.getColor(this, R.color.white)
            }
        )

        // Item count
        binding.itemCountLabel.text = "${data.inventoryIds.size} items"

        // Show/hide summary rows based on status filter
        when (data.statusFilter) {
            "On-Display" -> {
                binding.summaryOnDisplayRow.visibility = View.VISIBLE
                binding.summaryOnStockRow.visibility = View.GONE
            }
            "On-Hand" -> {
                binding.summaryOnDisplayRow.visibility = View.GONE
                binding.summaryOnStockRow.visibility = View.VISIBLE
            }
            "All" -> {
                binding.summaryOnDisplayRow.visibility = View.VISIBLE
                binding.summaryOnStockRow.visibility = View.VISIBLE
            }
        }
    }

    private fun updateOverallSummary() {
        val statusFilter = reconciliationData?.statusFilter ?: "All"

        // Calculate totals from manufacturers
        var totalOnDisplay = 0
        var totalOnDisplayVerified = 0
        var totalOnDisplayReconciled = 0
        var totalOnStock = 0
        var totalOnStockVerified = 0
        var totalOnStockReconciled = 0

        for (manufacturer in manufacturers) {
            totalOnDisplay += manufacturer.qtyOnDisplay
            totalOnDisplayVerified += manufacturer.qtyOnDisplayVerified
            totalOnDisplayReconciled += manufacturer.qtyOnDisplayReconciled
            totalOnStock += manufacturer.qtyOnStock
            totalOnStockVerified += manufacturer.qtyOnStockVerified
            totalOnStockReconciled += manufacturer.qtyOnStockReconciled
        }

        // Update On-Display row
        binding.summaryOnDisplayQty.text = "Qty: $totalOnDisplay"
        binding.summaryOnDisplayVerified.text = "Ver: $totalOnDisplayVerified"
        binding.summaryOnDisplayReconciled.text = "Rec: $totalOnDisplayReconciled"

        // Update In-Stock row
        binding.summaryOnStockQty.text = "Qty: $totalOnStock"
        binding.summaryOnStockVerified.text = "Ver: $totalOnStockVerified"
        binding.summaryOnStockReconciled.text = "Rec: $totalOnStockReconciled"
    }

    private fun updateManufacturerList() {
        binding.progressBar.visibility = View.GONE

        if (allPhones.isEmpty()) {
            binding.emptyStateLayout.visibility = View.VISIBLE
            binding.manufacturerRecyclerView.visibility = View.GONE
            binding.manufacturerCountLabel.text = "0 manufacturers"
            return
        }

        // Group phones by manufacturer
        val groupedPhones = allPhones.groupBy { it.manufacturer }

        manufacturers.clear()

        for ((manufacturerName, phones) in groupedPhones) {
            // Calculate counts based on status and verification
            var qtyOnDisplay = 0
            var qtyOnDisplayVerified = 0
            var qtyOnDisplayReconciled = 0
            var qtyOnStock = 0
            var qtyOnStockVerified = 0
            var qtyOnStockReconciled = 0

            for (phone in phones) {
                val isVerified = verifiedItems.containsKey(phone.documentId)

                when (phone.status) {
                    "On-Display" -> {
                        qtyOnDisplay++
                        if (isVerified) qtyOnDisplayVerified++
                    }
                    "On-Hand" -> {
                        qtyOnStock++
                        if (isVerified) qtyOnStockVerified++
                    }
                }
            }

            // Check if manufacturer is complete (all items verified)
            val totalQty = qtyOnDisplay + qtyOnStock
            val totalVerified = qtyOnDisplayVerified + qtyOnStockVerified
            val isComplete = totalQty > 0 && totalVerified == totalQty

            manufacturers.add(
                ManufacturerSummary(
                    name = manufacturerName,
                    totalItems = phones.size,
                    qtyOnDisplay = qtyOnDisplay,
                    qtyOnDisplayVerified = qtyOnDisplayVerified,
                    qtyOnDisplayReconciled = qtyOnDisplayReconciled,
                    qtyOnStock = qtyOnStock,
                    qtyOnStockVerified = qtyOnStockVerified,
                    qtyOnStockReconciled = qtyOnStockReconciled,
                    isComplete = isComplete
                )
            )
        }

        // Sort manufacturers alphabetically
        manufacturers.sortBy { it.name }

        // Update manufacturer count
        binding.manufacturerCountLabel.text = "${manufacturers.size} manufacturer${if (manufacturers.size > 1) "s" else ""}"

        // Show RecyclerView
        binding.emptyStateLayout.visibility = View.GONE
        binding.manufacturerRecyclerView.visibility = View.VISIBLE

        // Update adapter
        if (adapter == null) {
            adapter = ManufacturerAdapter(manufacturers) { manufacturer ->
                openManufacturerDetail(manufacturer.name)
            }
            binding.manufacturerRecyclerView.adapter = adapter
        } else {
            adapter?.notifyDataSetChanged()
        }
    }

    // ============================================================================
    // END OF PART 4: UI UPDATE METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 5: ADAPTER
    // ============================================================================

    inner class ManufacturerAdapter(
        private val items: List<ManufacturerSummary>,
        private val onItemClick: (ManufacturerSummary) -> Unit
    ) : RecyclerView.Adapter<ManufacturerAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemManufacturerBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemManufacturerBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val statusFilter = reconciliationData?.statusFilter ?: "All"

            with(holder.binding) {
                // Manufacturer name
                manufacturerName.text = item.name

                // Item count badge
                itemCountBadge.text = "${item.totalItems} item${if (item.totalItems > 1) "s" else ""}"

                // Completion status
                if (item.isComplete) {
                    completedCheckmark.visibility = View.VISIBLE
                    manufacturerCard.setCardBackgroundColor(
                        ContextCompat.getColor(this@ReconciliationDetailActivity, R.color.verified_green_light)
                    )
                } else {
                    completedCheckmark.visibility = View.GONE
                    manufacturerCard.setCardBackgroundColor(
                        ContextCompat.getColor(this@ReconciliationDetailActivity, R.color.white)
                    )
                }

                // Show/hide rows based on status filter
                when (statusFilter) {
                    "On-Display" -> {
                        onDisplayRow.visibility = View.VISIBLE
                        inStockRow.visibility = View.GONE
                    }
                    "On-Hand" -> {
                        onDisplayRow.visibility = View.GONE
                        inStockRow.visibility = View.VISIBLE
                    }
                    "All" -> {
                        onDisplayRow.visibility = View.VISIBLE
                        inStockRow.visibility = View.VISIBLE
                    }
                }

                // On-Display row values
                onDisplayQty.text = "Qty: ${item.qtyOnDisplay}"
                onDisplayVerified.text = "Ver: ${item.qtyOnDisplayVerified}"
                onDisplayReconciled.text = "Rec: ${item.qtyOnDisplayReconciled}"

                // In-Stock row values
                inStockQty.text = "Qty: ${item.qtyOnStock}"
                inStockVerified.text = "Ver: ${item.qtyOnStockVerified}"
                inStockReconciled.text = "Rec: ${item.qtyOnStockReconciled}"

                // Click listener
                root.setOnClickListener {
                    onItemClick(item)
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    // ============================================================================
    // END OF PART 5: ADAPTER
    // ============================================================================


    // ============================================================================
    // START OF PART 6: SCANNING METHODS
    // ============================================================================

    private fun openBarcodeScanner() {
        // Collect all valid identifiers from phones
        val validIdentifiers = ArrayList<String>()
        for (phone in allPhones) {
            if (phone.imei1.isNotEmpty()) validIdentifiers.add(phone.imei1)
            if (phone.imei2.isNotEmpty()) validIdentifiers.add(phone.imei2)
            if (phone.serialNumber.isNotEmpty()) validIdentifiers.add(phone.serialNumber)
        }

        val intent = Intent(this, BarcodeScannerActivity::class.java)
        intent.putExtra(BarcodeScannerActivity.EXTRA_AUTO_MATCH_ENABLED, true)
        intent.putStringArrayListExtra(BarcodeScannerActivity.EXTRA_VALID_IDENTIFIERS, validIdentifiers)
        barcodeScannerLauncher.launch(intent)
    }

    private fun processScannedBarcode(scannedValue: String) {
        // Search for matching phone by IMEI1, IMEI2, or Serial Number
        val matchingPhone = allPhones.find { phone ->
            phone.imei1 == scannedValue ||
                    phone.imei2 == scannedValue ||
                    phone.serialNumber == scannedValue
        }

        if (matchingPhone == null) {
            showMessage("Item not found in this reconciliation", true)
            return
        }

        // Check if already verified
        val existingVerification = verifiedItems[matchingPhone.documentId]
        if (existingVerification != null) {
            showMessage("Already verified by ${existingVerification.verifiedBy}", false)
            return
        }

        // Determine which field was scanned
        val scannedType = when (scannedValue) {
            matchingPhone.imei1 -> "IMEI1"
            matchingPhone.imei2 -> "IMEI2"
            matchingPhone.serialNumber -> "Serial"
            else -> "Unknown"
        }

        // Get username from SharedPreferences
        val prefs = getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val username = prefs.getString(AppConstants.KEY_USER, "") ?: ""
        val verifiedBy = if (username.isNotEmpty()) username else "Unknown"

        // Save verification to Firebase
        saveVerification(matchingPhone.documentId, verifiedBy, scannedType, scannedValue, matchingPhone.status)
    }

    private fun saveVerification(
        inventoryId: String,
        verifiedBy: String,
        scannedType: String,
        scannedValue: String,
        itemStatus: String
    ) {
        scope.launch {
            try {
                val verificationData = mapOf(
                    "verifiedBy" to verifiedBy,
                    "verifiedAt" to Timestamp.now(),
                    "scannedType" to scannedType,
                    "scannedValue" to scannedValue
                )

                // Determine which verified count to increment
                val verifiedCountField = when (itemStatus) {
                    "On-Display" -> "qtyOnDisplayVerified"
                    "On-Hand" -> "qtyOnStockVerified"
                    else -> null
                }

                withContext(Dispatchers.IO) {
                    // Use a transaction to update both the map and the count
                    db.runTransaction { transaction ->
                        val docRef = db.collection(COLLECTION_INVENTORY_RECONCILIATIONS).document(reconciliationId)
                        val snapshot = transaction.get(docRef)

                        // Get current verified items
                        @Suppress("UNCHECKED_CAST")
                        val currentVerifiedItems = snapshot.get("verifiedItems") as? MutableMap<String, Any> ?: mutableMapOf()

                        // Add new verification
                        currentVerifiedItems[inventoryId] = verificationData

                        // Update the document
                        transaction.update(docRef, "verifiedItems", currentVerifiedItems)

                        // Update the count if applicable
                        if (verifiedCountField != null) {
                            val currentCount = (snapshot.get(verifiedCountField) as? Number)?.toInt() ?: 0
                            transaction.update(docRef, verifiedCountField, currentCount + 1)
                        }

                        null
                    }.await()
                }

                withContext(Dispatchers.Main) {
                    showMessage("✓ Verified!", false)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    showMessage("Error saving verification: ${e.message}", true)
                }
            }
        }
    }

    // ============================================================================
    // END OF PART 6: SCANNING METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 7: NAVIGATION METHODS
    // ============================================================================

    private fun openManufacturerDetail(manufacturerName: String) {
        val intent = Intent(this, ManufacturerDetailActivity::class.java)
        intent.putExtra("RECONCILIATION_ID", reconciliationId)
        intent.putExtra("MANUFACTURER_NAME", manufacturerName)
        intent.putExtra("STATUS_FILTER", reconciliationData?.statusFilter ?: "All")
        startActivity(intent)
    }

    // ============================================================================
    // END OF PART 7: NAVIGATION METHODS
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