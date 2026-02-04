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

    // Manufacturers to exclude from reconciliation (case-insensitive)
    private val EXCLUDED_MANUFACTURERS = listOf("Techcity")

    // Reconciliation ID passed from list activity
    private var reconciliationId: String = ""

    // Inventory status setting from Program Settings
    private var inventoryStatusSetting: String = "On-Display"

    // Reconciliation data
    private var reconciliationData: ReconciliationData? = null

    // All phones in this reconciliation
    private var allPhones: MutableList<PhoneItem> = mutableListOf()

    // Manufacturers grouped data
    private var manufacturers: MutableList<ManufacturerSummary> = mutableListOf()
    private var adapter: ManufacturerAdapter? = null

    // Verified items map (from Firebase) - now includes verification status
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
        val totalItems: Int,
        val isComplete: Boolean,
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
     * Data class for manufacturer summary
     * totalItems is calculated based on statusFilter
     * isComplete is true ONLY when all items are verified (not counting for_reconciliation)
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

    /**
     * Data class for verification info
     * verificationStatus can be "verified" or "for_reconciliation"
     */
    data class VerificationInfo(
        val verifiedBy: String,
        val verifiedAt: Timestamp?,
        val scannedType: String,
        val scannedValue: String,
        val verificationStatus: String = "verified"  // "verified" or "for_reconciliation"
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

        // Load inventory status setting from SharedPreferences
        val prefs = getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        inventoryStatusSetting = prefs.getString(AppConstants.KEY_INVENTORY_STATUS_FILTER, "On-Display") ?: "On-Display"

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

        val qtyOnDisplay = (data["qtyOnDisplay"] as? Number)?.toInt() ?: 0
        val qtyOnDisplayVerified = (data["qtyOnDisplayVerified"] as? Number)?.toInt() ?: 0
        val qtyOnDisplayReconciled = (data["qtyOnDisplayReconciled"] as? Number)?.toInt() ?: 0
        val qtyOnStock = (data["qtyOnStock"] as? Number)?.toInt() ?: 0
        val qtyOnStockVerified = (data["qtyOnStockVerified"] as? Number)?.toInt() ?: 0
        val qtyOnStockReconciled = (data["qtyOnStockReconciled"] as? Number)?.toInt() ?: 0

        val statusFilter = data["statusFilter"] as? String ?: "All"

        // Calculate totalItems based on statusFilter
        val totalItems = when (statusFilter) {
            "On-Display" -> qtyOnDisplay
            "On-Hand" -> qtyOnStock
            else -> qtyOnDisplay + qtyOnStock
        }

        // isComplete only counts verified items (NOT for_reconciliation)
        val totalVerified = qtyOnDisplayVerified + qtyOnStockVerified
        val isComplete = totalItems > 0 && totalVerified == totalItems

        return ReconciliationData(
            documentId = doc.id,
            date = data["date"] as? String ?: "",
            location = data["location"] as? String ?: "",
            statusFilter = statusFilter,
            qtyOnDisplay = qtyOnDisplay,
            qtyOnDisplayVerified = qtyOnDisplayVerified,
            qtyOnDisplayReconciled = qtyOnDisplayReconciled,
            qtyOnStock = qtyOnStock,
            qtyOnStockVerified = qtyOnStockVerified,
            qtyOnStockReconciled = qtyOnStockReconciled,
            inventoryIds = inventoryIds,
            totalItems = totalItems,
            isComplete = isComplete,
            createdAt = data["createdAt"] as? String ?: ""
        )
    }

    private suspend fun fetchPhones(inventoryIds: List<String>): List<PhoneItem> {
        if (inventoryIds.isEmpty()) return emptyList()

        val phonesList = mutableListOf<PhoneItem>()

        // Firestore "in" queries are limited to 30 items, so we need to batch
        val batches = inventoryIds.chunked(30)

        for (batch in batches) {
            val querySnapshot = db.collection(COLLECTION_INVENTORY)
                .whereIn(com.google.firebase.firestore.FieldPath.documentId(), batch)
                .get()
                .await()

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
                            scannedValue = info["scannedValue"] as? String ?: "",
                            verificationStatus = info["verificationStatus"] as? String ?: "verified"
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

        // Item count - based on statusFilter
        binding.itemCountLabel.text = "${data.totalItems} items"

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

    /**
     * Check if a manufacturer should be excluded from reconciliation
     */
    private fun isExcludedManufacturer(manufacturer: String): Boolean {
        return EXCLUDED_MANUFACTURERS.any { excluded ->
            manufacturer.equals(excluded, ignoreCase = true)
        }
    }

    private fun updateOverallSummary() {
        val statusFilter = reconciliationData?.statusFilter ?: "All"

        // Calculate from actual phones and verified items
        // EXCLUDE Techcity manufacturer from counts
        var qtyOnDisplay = 0
        var qtyOnDisplayVerified = 0
        var qtyOnDisplayReconciled = 0
        var qtyOnStock = 0
        var qtyOnStockVerified = 0
        var qtyOnStockReconciled = 0

        for (phone in allPhones) {
            // Skip excluded manufacturers
            if (isExcludedManufacturer(phone.manufacturer)) {
                continue
            }

            val verification = verifiedItems[phone.documentId]
            when (phone.status) {
                "On-Display" -> {
                    qtyOnDisplay++
                    if (verification != null) {
                        if (verification.verificationStatus == "verified") {
                            qtyOnDisplayVerified++
                        } else if (verification.verificationStatus == "for_reconciliation") {
                            qtyOnDisplayReconciled++
                        }
                    }
                }
                "On-Hand" -> {
                    qtyOnStock++
                    if (verification != null) {
                        if (verification.verificationStatus == "verified") {
                            qtyOnStockVerified++
                        } else if (verification.verificationStatus == "for_reconciliation") {
                            qtyOnStockReconciled++
                        }
                    }
                }
            }
        }

        // Update summary card
        binding.summaryOnDisplayQty.text = "Qty: $qtyOnDisplay"
        binding.summaryOnDisplayVerified.text = "Ver: $qtyOnDisplayVerified"
        binding.summaryOnDisplayReconciled.text = "Rec: $qtyOnDisplayReconciled"

        binding.summaryOnStockQty.text = "Qty: $qtyOnStock"
        binding.summaryOnStockVerified.text = "Ver: $qtyOnStockVerified"
        binding.summaryOnStockReconciled.text = "Rec: $qtyOnStockReconciled"

        // Update total item count in header based on status filter
        val totalItems = when (statusFilter) {
            "On-Display" -> qtyOnDisplay
            "On-Hand" -> qtyOnStock
            else -> qtyOnDisplay + qtyOnStock
        }
        binding.itemCountLabel.text = "$totalItems items"

        // Sync counts to Firebase if they differ from stored values
        syncCountsToFirebase(qtyOnDisplay, qtyOnDisplayVerified, qtyOnDisplayReconciled,
            qtyOnStock, qtyOnStockVerified, qtyOnStockReconciled)
    }

    /**
     * Sync calculated counts to Firebase if they differ from stored values
     * This ensures the list activity shows the correct counts
     */
    private fun syncCountsToFirebase(
        qtyOnDisplay: Int,
        qtyOnDisplayVerified: Int,
        qtyOnDisplayReconciled: Int,
        qtyOnStock: Int,
        qtyOnStockVerified: Int,
        qtyOnStockReconciled: Int
    ) {
        val data = reconciliationData ?: return

        // Check if any counts differ
        val needsUpdate = data.qtyOnDisplay != qtyOnDisplay ||
                data.qtyOnDisplayVerified != qtyOnDisplayVerified ||
                data.qtyOnDisplayReconciled != qtyOnDisplayReconciled ||
                data.qtyOnStock != qtyOnStock ||
                data.qtyOnStockVerified != qtyOnStockVerified ||
                data.qtyOnStockReconciled != qtyOnStockReconciled

        if (needsUpdate) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        db.collection(COLLECTION_INVENTORY_RECONCILIATIONS)
                            .document(reconciliationId)
                            .update(
                                mapOf(
                                    "qtyOnDisplay" to qtyOnDisplay,
                                    "qtyOnDisplayVerified" to qtyOnDisplayVerified,
                                    "qtyOnDisplayReconciled" to qtyOnDisplayReconciled,
                                    "qtyOnStock" to qtyOnStock,
                                    "qtyOnStockVerified" to qtyOnStockVerified,
                                    "qtyOnStockReconciled" to qtyOnStockReconciled
                                )
                            )
                            .await()
                    }

                    // Update local reconciliationData
                    reconciliationData = data.copy(
                        qtyOnDisplay = qtyOnDisplay,
                        qtyOnDisplayVerified = qtyOnDisplayVerified,
                        qtyOnDisplayReconciled = qtyOnDisplayReconciled,
                        qtyOnStock = qtyOnStock,
                        qtyOnStockVerified = qtyOnStockVerified,
                        qtyOnStockReconciled = qtyOnStockReconciled
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Silent failure - counts will sync on next open
                }
            }
        }
    }

    private fun updateManufacturerList() {
        binding.progressBar.visibility = View.GONE

        if (allPhones.isEmpty()) {
            binding.emptyStateLayout.visibility = View.VISIBLE
            binding.manufacturerRecyclerView.visibility = View.GONE
            return
        }

        // Get the status filter
        val statusFilter = reconciliationData?.statusFilter ?: "All"

        // Group phones by manufacturer and calculate counts
        // EXCLUDE Techcity manufacturer
        val manufacturerMap = mutableMapOf<String, MutableList<PhoneItem>>()
        for (phone in allPhones) {
            // Skip excluded manufacturers
            if (isExcludedManufacturer(phone.manufacturer)) {
                continue
            }

            val list = manufacturerMap.getOrPut(phone.manufacturer) { mutableListOf() }
            list.add(phone)
        }

        // Build manufacturer summaries with verification counts
        manufacturers.clear()
        for ((name, phoneList) in manufacturerMap) {
            var qtyOnDisplay = 0
            var qtyOnDisplayVerified = 0
            var qtyOnDisplayReconciled = 0
            var qtyOnStock = 0
            var qtyOnStockVerified = 0
            var qtyOnStockReconciled = 0

            for (phone in phoneList) {
                val verification = verifiedItems[phone.documentId]
                val isVerified = verification != null && verification.verificationStatus == "verified"
                val isForReconciliation = verification != null && verification.verificationStatus == "for_reconciliation"

                when (phone.status) {
                    "On-Display" -> {
                        qtyOnDisplay++
                        if (isVerified) qtyOnDisplayVerified++
                        if (isForReconciliation) qtyOnDisplayReconciled++
                    }
                    "On-Hand" -> {
                        qtyOnStock++
                        if (isVerified) qtyOnStockVerified++
                        if (isForReconciliation) qtyOnStockReconciled++
                    }
                }
            }

            // Calculate totalItems based on statusFilter (this is what the badge shows)
            val totalItems = when (statusFilter) {
                "On-Display" -> qtyOnDisplay
                "On-Hand" -> qtyOnStock
                else -> qtyOnDisplay + qtyOnStock
            }

            // Skip this manufacturer if no items match the filter
            if (totalItems == 0) {
                continue
            }

            // Check if manufacturer is complete - ONLY count verified items (NOT for_reconciliation)
            val totalVerified = when (statusFilter) {
                "On-Display" -> qtyOnDisplayVerified
                "On-Hand" -> qtyOnStockVerified
                else -> qtyOnDisplayVerified + qtyOnStockVerified
            }
            val isComplete = totalItems > 0 && totalVerified == totalItems

            manufacturers.add(
                ManufacturerSummary(
                    name = name,
                    totalItems = totalItems,
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

        // Sort by manufacturer name
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

                // Item count badge - shows total items based on status filter
                itemCountBadge.text = "${item.totalItems} item${if (item.totalItems > 1) "s" else ""}"

                // Completion status - show checkmark and green background ONLY if all items are verified
                // (not counting for_reconciliation items)
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

    /**
     * Check if an item's status matches the inventory status setting
     */
    private fun doesStatusMatchSetting(itemStatus: String): Boolean {
        return when (inventoryStatusSetting) {
            "On-Display" -> itemStatus == "On-Display"
            "In-Stock" -> itemStatus == "On-Hand"
            "Both" -> true
            else -> true
        }
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

        // Check if already verified or marked for reconciliation
        val existingVerification = verifiedItems[matchingPhone.documentId]
        if (existingVerification != null) {
            // Check if user with "Both" setting can upgrade "for_reconciliation" to "verified"
            if (existingVerification.verificationStatus == "for_reconciliation" && inventoryStatusSetting == "Both") {
                // User with "Both" setting can resolve reconciliation items
                val scannedType = when (scannedValue) {
                    matchingPhone.imei1 -> "IMEI1"
                    matchingPhone.imei2 -> "IMEI2"
                    matchingPhone.serialNumber -> "Serial"
                    else -> "Unknown"
                }

                val prefs = getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
                val username = prefs.getString(AppConstants.KEY_USER, "") ?: ""
                val verifiedBy = if (username.isNotEmpty()) username else "Unknown"

                // Upgrade from "for_reconciliation" to "verified"
                upgradeToVerified(matchingPhone.documentId, verifiedBy, scannedType, scannedValue, matchingPhone.status)
                return
            }

            // Already verified or marked for reconciliation (and user can't upgrade it)
            val statusText = if (existingVerification.verificationStatus == "for_reconciliation") {
                "Already marked for reconciliation by ${existingVerification.verifiedBy}"
            } else {
                "Already verified by ${existingVerification.verifiedBy}"
            }
            showMessage(statusText, false)
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

        // Determine if this should be verified or marked for reconciliation
        val statusMatches = doesStatusMatchSetting(matchingPhone.status)

        if (statusMatches) {
            // Item status matches setting - mark as verified
            saveVerification(matchingPhone.documentId, verifiedBy, scannedType, scannedValue, matchingPhone.status, "verified")
        } else {
            // Item status doesn't match setting - mark for reconciliation
            saveVerification(matchingPhone.documentId, verifiedBy, scannedType, scannedValue, matchingPhone.status, "for_reconciliation")
            showMessage("Marked for reconciliation (status mismatch)", false)
        }
    }

    private fun saveVerification(
        inventoryId: String,
        verifiedBy: String,
        scannedType: String,
        scannedValue: String,
        itemStatus: String,
        verificationStatus: String  // "verified" or "for_reconciliation"
    ) {
        scope.launch {
            try {
                val verificationData = mapOf(
                    "verifiedBy" to verifiedBy,
                    "verifiedAt" to Timestamp.now(),
                    "scannedType" to scannedType,
                    "scannedValue" to scannedValue,
                    "verificationStatus" to verificationStatus
                )

                // Determine which count field to increment based on verification status
                val countField = if (verificationStatus == "verified") {
                    when (itemStatus) {
                        "On-Display" -> "qtyOnDisplayVerified"
                        "On-Hand" -> "qtyOnStockVerified"
                        else -> null
                    }
                } else {
                    // For reconciliation
                    when (itemStatus) {
                        "On-Display" -> "qtyOnDisplayReconciled"
                        "On-Hand" -> "qtyOnStockReconciled"
                        else -> null
                    }
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
                        if (countField != null) {
                            val currentCount = (snapshot.get(countField) as? Number)?.toInt() ?: 0
                            transaction.update(docRef, countField, currentCount + 1)
                        }

                        null
                    }.await()
                }

                withContext(Dispatchers.Main) {
                    if (verificationStatus == "verified") {
                        showMessage("✓ Verified!", false)
                    }
                    // For reconciliation, message is already shown in processScannedBarcode
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    showMessage("Error saving verification: ${e.message}", true)
                }
            }
        }
    }

    /**
     * Upgrade an item from "for_reconciliation" to "verified"
     * This is used when a user with "Both" setting scans an item that was previously marked for reconciliation
     */
    private fun upgradeToVerified(
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
                    "scannedValue" to scannedValue,
                    "verificationStatus" to "verified"
                )

                // Determine which count fields to adjust
                val verifiedCountField = when (itemStatus) {
                    "On-Display" -> "qtyOnDisplayVerified"
                    "On-Hand" -> "qtyOnStockVerified"
                    else -> null
                }
                val reconciledCountField = when (itemStatus) {
                    "On-Display" -> "qtyOnDisplayReconciled"
                    "On-Hand" -> "qtyOnStockReconciled"
                    else -> null
                }

                withContext(Dispatchers.IO) {
                    // Use a transaction to update the map and adjust counts
                    db.runTransaction { transaction ->
                        val docRef = db.collection(COLLECTION_INVENTORY_RECONCILIATIONS).document(reconciliationId)
                        val snapshot = transaction.get(docRef)

                        // Get current verified items
                        @Suppress("UNCHECKED_CAST")
                        val currentVerifiedItems = snapshot.get("verifiedItems") as? MutableMap<String, Any> ?: mutableMapOf()

                        // Update verification status
                        currentVerifiedItems[inventoryId] = verificationData

                        // Update the document
                        transaction.update(docRef, "verifiedItems", currentVerifiedItems)

                        // Decrement reconciled count and increment verified count
                        if (reconciledCountField != null) {
                            val currentReconciledCount = (snapshot.get(reconciledCountField) as? Number)?.toInt() ?: 0
                            if (currentReconciledCount > 0) {
                                transaction.update(docRef, reconciledCountField, currentReconciledCount - 1)
                            }
                        }
                        if (verifiedCountField != null) {
                            val currentVerifiedCount = (snapshot.get(verifiedCountField) as? Number)?.toInt() ?: 0
                            transaction.update(docRef, verifiedCountField, currentVerifiedCount + 1)
                        }

                        null
                    }.await()
                }

                withContext(Dispatchers.Main) {
                    showMessage("✓ Verified! (Reconciliation resolved)", false)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    showMessage("Error upgrading verification: ${e.message}", true)
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