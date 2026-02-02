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
import com.techcity.techcitysuite.databinding.ActivityManufacturerDetailBinding
import com.techcity.techcitysuite.databinding.ItemReconciliationPhoneBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.NumberFormat
import java.util.*

class ManufacturerDetailActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityManufacturerDetailBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Firebase collection names
    private val COLLECTION_INVENTORY_RECONCILIATIONS = "inventory_reconciliations"
    private val COLLECTION_INVENTORY = "inventory"

    // Intent extras
    private var reconciliationId: String = ""
    private var manufacturerName: String = ""
    private var statusFilter: String = "All"

    // All phones for this manufacturer
    private var phones: MutableList<PhoneItem> = mutableListOf()
    private var filteredPhones: MutableList<PhoneItem> = mutableListOf()
    private var adapter: PhoneAdapter? = null

    // Verification filter state: "All", "Verified", "Unverified"
    private var verificationFilter: String = "All"

    // Verified items map (from Firebase)
    private var verifiedItems: MutableMap<String, VerificationInfo> = mutableMapOf()

    // All inventory IDs in this reconciliation
    private var allInventoryIds: List<String> = emptyList()

    // Snapshot listener for real-time updates
    private var reconciliationListener: ListenerRegistration? = null

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
        binding = ActivityManufacturerDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firebase
        db = Firebase.firestore

        // Get intent extras
        reconciliationId = intent.getStringExtra("RECONCILIATION_ID") ?: ""
        manufacturerName = intent.getStringExtra("MANUFACTURER_NAME") ?: ""
        statusFilter = intent.getStringExtra("STATUS_FILTER") ?: "All"

        if (reconciliationId.isEmpty() || manufacturerName.isEmpty()) {
            showMessage("Error: Missing required data", true)
            finish()
            return
        }

        // Setup UI
        setupUI()

        // Setup RecyclerView
        binding.phonesRecyclerView.layoutManager = LinearLayoutManager(this)

        // Setup back button
        binding.backButton.setOnClickListener {
            finish()
        }

        // Setup scan FAB
        binding.scanFab.setOnClickListener {
            openBarcodeScanner()
        }

        // Load data
        loadData()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        reconciliationListener?.remove()
    }

    // ============================================================================
    // END OF PART 2: LIFECYCLE METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 3: UI SETUP
    // ============================================================================

    private fun setupUI() {
        // Set manufacturer name in header
        binding.manufacturerLabel.text = manufacturerName

        // Set summary title
        binding.summaryTitle.text = "${manufacturerName.uppercase()} SUMMARY"

        // Show/hide summary rows based on status filter
        when (statusFilter) {
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

        // Setup filter toggle button
        binding.filterToggleButton.setOnClickListener {
            toggleVerificationFilter()
        }
        updateFilterButtonText()
    }

    /**
     * Toggle between Show All -> Show Verified -> Show Unverified -> Show All
     */
    private fun toggleVerificationFilter() {
        verificationFilter = when (verificationFilter) {
            "All" -> "Verified"
            "Verified" -> "Unverified"
            else -> "All"
        }
        updateFilterButtonText()
        updateUI()
    }

    /**
     * Update the filter button text based on current filter state
     */
    private fun updateFilterButtonText() {
        binding.filterToggleButton.text = when (verificationFilter) {
            "Verified" -> "Show Verified"
            "Unverified" -> "Show Unverified"
            else -> "Show All"
        }
    }

    // ============================================================================
    // END OF PART 3: UI SETUP
    // ============================================================================


    // ============================================================================
    // START OF PART 4: DATA LOADING
    // ============================================================================

    private fun loadData() {
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyStateLayout.visibility = View.GONE
        binding.phonesRecyclerView.visibility = View.GONE

        scope.launch {
            try {
                // First, get the inventory IDs from reconciliation
                val inventoryIds = withContext(Dispatchers.IO) {
                    fetchInventoryIds()
                }

                if (inventoryIds.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        binding.emptyStateLayout.visibility = View.VISIBLE
                        binding.emptyMessage.text = "No items found"
                    }
                    return@launch
                }

                allInventoryIds = inventoryIds

                // Fetch phones for this manufacturer
                val fetchedPhones = withContext(Dispatchers.IO) {
                    fetchPhonesForManufacturer(inventoryIds)
                }

                phones.clear()
                phones.addAll(fetchedPhones)

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

    private suspend fun fetchInventoryIds(): List<String> {
        val doc = db.collection(COLLECTION_INVENTORY_RECONCILIATIONS)
            .document(reconciliationId)
            .get()
            .await()

        if (!doc.exists()) {
            return emptyList()
        }

        @Suppress("UNCHECKED_CAST")
        return (doc.get("inventoryIds") as? List<String>) ?: emptyList()
    }

    private suspend fun fetchPhonesForManufacturer(inventoryIds: List<String>): List<PhoneItem> {
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
                val manufacturer = data["manufacturer"] as? String ?: ""

                // Only include phones from this manufacturer
                if (manufacturer.equals(manufacturerName, ignoreCase = true)) {
                    phonesList.add(
                        PhoneItem(
                            documentId = doc.id,
                            manufacturer = manufacturer,
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
        }

        // Sort by model
        return phonesList.sortedBy { it.model }
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

                    // Update UI
                    updateUI()
                }
            }
    }

    // ============================================================================
    // END OF PART 4: DATA LOADING
    // ============================================================================


    // ============================================================================
    // START OF PART 5: UI UPDATE METHODS
    // ============================================================================

    private fun updateUI() {
        binding.progressBar.visibility = View.GONE

        if (phones.isEmpty()) {
            binding.emptyStateLayout.visibility = View.VISIBLE
            binding.phonesRecyclerView.visibility = View.GONE
            binding.itemCountLabel.text = "0 items"
            binding.verifiedCountLabel.text = "0 verified"
            return
        }

        // Calculate counts
        var qtyOnDisplay = 0
        var qtyOnDisplayVerified = 0
        var qtyOnStock = 0
        var qtyOnStockVerified = 0

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

        val totalVerified = qtyOnDisplayVerified + qtyOnStockVerified
        val totalItems = phones.size
        val isComplete = totalItems > 0 && totalVerified == totalItems

        // Update header
        binding.itemCountLabel.text = "$totalItems item${if (totalItems > 1) "s" else ""}"
        binding.verifiedCountLabel.text = "$totalVerified verified"

        // Show/hide completion badge
        binding.completionBadge.visibility = if (isComplete) View.VISIBLE else View.GONE

        // Update summary card
        binding.summaryOnDisplayQty.text = "Qty: $qtyOnDisplay"
        binding.summaryOnDisplayVerified.text = "Ver: $qtyOnDisplayVerified"
        binding.summaryOnDisplayReconciled.text = "Rec: 0"

        binding.summaryOnStockQty.text = "Qty: $qtyOnStock"
        binding.summaryOnStockVerified.text = "Ver: $qtyOnStockVerified"
        binding.summaryOnStockReconciled.text = "Rec: 0"

        // Apply verification filter to phones
        filteredPhones.clear()
        filteredPhones.addAll(
            when (verificationFilter) {
                "Verified" -> phones.filter { verifiedItems.containsKey(it.documentId) }
                "Unverified" -> phones.filter { !verifiedItems.containsKey(it.documentId) }
                else -> phones // "All" - show all phones
            }
        )

        // Show RecyclerView or empty state based on filtered results
        if (filteredPhones.isEmpty()) {
            binding.emptyStateLayout.visibility = View.VISIBLE
            binding.phonesRecyclerView.visibility = View.GONE
            binding.emptyMessage.text = when (verificationFilter) {
                "Verified" -> "No verified items"
                "Unverified" -> "All items verified! 🎉"
                else -> "No items found"
            }
        } else {
            binding.emptyStateLayout.visibility = View.GONE
            binding.phonesRecyclerView.visibility = View.VISIBLE

            // Update adapter with filtered list
            if (adapter == null) {
                adapter = PhoneAdapter(filteredPhones)
                binding.phonesRecyclerView.adapter = adapter
            } else {
                adapter?.updateItems(filteredPhones)
            }
        }
    }

    // ============================================================================
    // END OF PART 5: UI UPDATE METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 6: ADAPTER
    // ============================================================================

    inner class PhoneAdapter(
        private var items: List<PhoneItem>
    ) : RecyclerView.Adapter<PhoneAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemReconciliationPhoneBinding) : RecyclerView.ViewHolder(binding.root)

        fun updateItems(newItems: List<PhoneItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemReconciliationPhoneBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val verification = verifiedItems[item.documentId]
            val isVerified = verification != null

            with(holder.binding) {
                // Manufacturer
                manufacturerText.text = item.manufacturer

                // Model
                modelText.text = item.model

                // RAM + Storage
                val ramDisplay = if (item.ram.endsWith("GB", ignoreCase = true)) item.ram else "${item.ram}GB"
                val storageDisplay = if (item.storage.endsWith("GB", ignoreCase = true)) item.storage else "${item.storage}GB"
                ramStorageText.text = "$ramDisplay + $storageDisplay"

                // Color
                colorText.text = item.color

                // Price
                retailPriceText.text = formatCurrency(item.retailPrice)

                // Status badge - On-Hand displays as "In-Stock" with Blue color
                // On-Display shows with Yellow color
                when (item.status) {
                    "On-Hand" -> {
                        statusBadge.text = "In-Stock"
                        statusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(this@ManufacturerDetailActivity, R.color.techcity_blue)
                        )
                        statusBadge.setTextColor(ContextCompat.getColor(this@ManufacturerDetailActivity, R.color.white))
                    }
                    "On-Display" -> {
                        statusBadge.text = "On-Display"
                        statusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(this@ManufacturerDetailActivity, R.color.yellow)
                        )
                        statusBadge.setTextColor(ContextCompat.getColor(this@ManufacturerDetailActivity, R.color.black))
                    }
                    else -> {
                        statusBadge.text = item.status
                        statusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(this@ManufacturerDetailActivity, R.color.gray)
                        )
                        statusBadge.setTextColor(ContextCompat.getColor(this@ManufacturerDetailActivity, R.color.white))
                    }
                }

                // IMEI1 Row - Show if available
                if (item.imei1.isNotEmpty()) {
                    imei1Row.visibility = View.VISIBLE
                    imei1Text.text = item.imei1
                } else {
                    imei1Row.visibility = View.GONE
                }

                // IMEI2 Row - Show if available
                if (item.imei2.isNotEmpty()) {
                    imei2Row.visibility = View.VISIBLE
                    imei2Text.text = item.imei2
                } else {
                    imei2Row.visibility = View.GONE
                }

                // Serial Number Row - Show if available
                if (item.serialNumber.isNotEmpty()) {
                    serialRow.visibility = View.VISIBLE
                    serialText.text = item.serialNumber
                } else {
                    serialRow.visibility = View.GONE
                }

                // Location
                locationText.text = item.location

                // Verification status
                if (isVerified) {
                    notVerifiedText.visibility = View.GONE
                    verifiedLayout.visibility = View.VISIBLE
                    verifiedByText.text = "Verified by ${verification?.verifiedBy ?: ""}"
                } else {
                    notVerifiedText.visibility = View.VISIBLE
                    verifiedLayout.visibility = View.GONE
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    // ============================================================================
    // END OF PART 6: ADAPTER
    // ============================================================================


    // ============================================================================
    // START OF PART 7: SCANNING METHODS
    // ============================================================================

    private fun openBarcodeScanner() {
        // Collect all valid identifiers from phones in this manufacturer
        val validIdentifiers = ArrayList<String>()
        for (phone in phones) {
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
        // Search in current manufacturer's phones first
        var matchingPhone = phones.find { phone ->
            phone.imei1 == scannedValue ||
                    phone.imei2 == scannedValue ||
                    phone.serialNumber == scannedValue
        }

        // If not found in this manufacturer, need to search across all inventory IDs
        // But for now, we only verify items in the current view
        if (matchingPhone == null) {
            showMessage("Item not found for $manufacturerName", true)
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
    // END OF PART 7: SCANNING METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 8: UTILITY METHODS
    // ============================================================================

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