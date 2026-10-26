package com.techcity.techcitysuite

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityEndOfDayListBinding
import com.techcity.techcitysuite.databinding.ItemEndOfDayReportBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class EndOfDayListActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityEndOfDayListBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Firebase collection name
    private val COLLECTION_DAILY_SUMMARIES = "daily_summaries"

    // Current month for filtering
    private var currentYear: Int = 0
    private var currentMonth: Int = 0  // 0-indexed

    // List of reports
    private var reports: MutableList<EODReportSummary> = mutableListOf()
    private var adapter: EODReportAdapter? = null

    /**
     * Data class for EOD report summary (for list display)
     */
    data class EODReportSummary(
        val documentId: String,
        val date: String,              // yyyy-MM-dd
        val displayDate: String,       // M/d/yyyy
        val generatedAt: String,
        val generatedBy: String,
        val deviceCount: Int,
        val accessoryCount: Int,
        val serviceCount: Int,
        val totalRevenue: Double,
        // Spreadsheet export figures (long-press to copy), see _specs/eod-report-clipboard-export.md
        val deviceCashCollected: Double = 0.0,      // Column 1
        val accessoryCashCollected: Double = 0.0,   // Column 2
        val homeCreditBalance: Double = 0.0,        // Column 3
        val skyroBalance: Double = 0.0,             // Column 4
        val creditCardTotal: Double = 0.0,          // Column 5
        val inHouseSalmonBalance: Double = 0.0,     // Column 6
        val hasBreakdownData: Boolean = false       // false for legacy docs without salesSummary/cashFlowSummary
    )

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================


    // ============================================================================
    // START OF PART 2: LIFECYCLE METHODS
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityEndOfDayListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firestore
        db = Firebase.firestore

        // Initialize to current month (Philippine time)
        initializeCurrentMonth()

        // Setup UI
        setupUI()
        setupRecyclerView()

        // Load reports
        loadReports()
    }

    override fun onResume() {
        super.onResume()
        // Refresh when returning (e.g., after creating a new report)
        loadReports()
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

        // Add button - opens date picker
        binding.addButton.setOnClickListener {
            showDatePicker()
        }

        // Update month label
        updateMonthLabel()
    }

    private fun setupRecyclerView() {
        binding.reportsRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun updateMonthLabel() {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.YEAR, currentYear)
        calendar.set(Calendar.MONTH, currentMonth)

        val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.US)
        binding.monthLabel.text = monthFormat.format(calendar.time)
    }

    private fun navigateMonth(delta: Int) {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.YEAR, currentYear)
        calendar.set(Calendar.MONTH, currentMonth)
        calendar.add(Calendar.MONTH, delta)

        currentYear = calendar.get(Calendar.YEAR)
        currentMonth = calendar.get(Calendar.MONTH)

        updateMonthLabel()
        loadReports()
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila"))

        val datePickerDialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                // Format selected date and open report activity
                val selectedDate = "${month + 1}/$dayOfMonth/$year"
                openReportActivity(selectedDate)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        datePickerDialog.show()
    }

    private fun openReportActivity(date: String, documentId: String? = null) {
        val intent = Intent(this, EndOfDayReportActivity::class.java)
        intent.putExtra("selected_date", date)
        if (documentId != null) {
            intent.putExtra("document_id", documentId)
        }
        startActivity(intent)
    }

    // ============================================================================
    // END OF PART 3: UI SETUP METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 4: DATA LOADING
    // ============================================================================

    private fun loadReports() {
        binding.progressBar.visibility = View.VISIBLE
        binding.reportsRecyclerView.visibility = View.GONE
        binding.emptyStateLayout.visibility = View.GONE

        // Store location must be configured; only this store's reports are listed
        if (!StoreLocationHelper.isConfigured(this)) {
            binding.progressBar.visibility = View.GONE
            binding.emptyStateLayout.visibility = View.VISIBLE
            binding.emptyMessage.text = StoreLocationHelper.NOT_CONFIGURED_MESSAGE
            binding.reportCountLabel.text = "0 reports generated"
            return
        }

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    fetchReportsForMonth()
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    reports = result.toMutableList()

                    if (reports.isEmpty()) {
                        binding.emptyStateLayout.visibility = View.VISIBLE
                        binding.reportsRecyclerView.visibility = View.GONE
                        binding.reportCountLabel.text = "0 reports generated"
                    } else {
                        binding.emptyStateLayout.visibility = View.GONE
                        binding.reportsRecyclerView.visibility = View.VISIBLE
                        binding.reportCountLabel.text = "${reports.size} report${if (reports.size > 1) "s" else ""} generated"

                        adapter = EODReportAdapter(reports) { report ->
                            // Open report for viewing - pass document ID for existing reports
                            openReportActivity(report.displayDate, report.documentId)
                        }
                        binding.reportsRecyclerView.adapter = adapter
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.emptyStateLayout.visibility = View.VISIBLE
                    binding.emptyMessage.text = "Error loading reports: ${e.message}"
                    showMessage("Error: ${e.message}", true)
                }
            }
        }
    }

    private suspend fun fetchReportsForMonth(): List<EODReportSummary> {
        // Build date range for current month
        // Format: yyyy-MM-dd
        val startDate = String.format("%04d-%02d-01", currentYear, currentMonth + 1)

        // Calculate last day of month
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.YEAR, currentYear)
        calendar.set(Calendar.MONTH, currentMonth)
        val lastDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val endDate = String.format("%04d-%02d-%02d", currentYear, currentMonth + 1, lastDay)

        // Query Firebase
        val querySnapshot = db.collection(COLLECTION_DAILY_SUMMARIES)
            .whereGreaterThanOrEqualTo("date", startDate)
            .whereLessThanOrEqualTo("date", endDate)
            .orderBy("date", Query.Direction.DESCENDING)
            .get()
            .await()

        // Only this store's reports (legacy reports without storeLocation only on a primary-store device)
        val documents = querySnapshot.documents.filter { doc ->
            StoreLocationHelper.matches(this, doc.getString("storeLocation"))
        }

        // When a legacy and a per-store report exist for the same date, keep the per-store one
        val datesWithPerStoreReport = documents
            .filter { !it.getString("storeLocation").isNullOrBlank() }
            .mapNotNull { it.getString("date") }
            .toSet()
        val dedupedDocuments = documents.filter { doc ->
            !doc.getString("storeLocation").isNullOrBlank() ||
                doc.getString("date") !in datesWithPerStoreReport
        }

        return dedupedDocuments.mapNotNull { doc ->
            try {
                val data = doc.data ?: return@mapNotNull null

                // Extract transaction counts
                val counts = data["transactionCounts"] as? Map<*, *>
                val deviceCount = (counts?.get("devices") as? Number)?.toInt() ?: 0
                val accessoryCount = (counts?.get("accessories") as? Number)?.toInt() ?: 0
                val serviceCount = (counts?.get("services") as? Number)?.toInt() ?: 0

                // Extract grand totals
                val totals = data["grandTotals"] as? Map<*, *>
                val totalRevenue = (totals?.get("totalRevenue") as? Number)?.toDouble() ?: 0.0

                // Extract spreadsheet export figures (columns 1-6) from the saved summary
                val cashFlowSummary = data["cashFlowSummary"] as? Map<*, *>
                val deviceCashFlow = cashFlowSummary?.get("devices") as? Map<*, *>
                val accessoryCashFlow = cashFlowSummary?.get("accessories") as? Map<*, *>

                val salesSummary = data["salesSummary"] as? Map<*, *>
                val deviceSales = salesSummary?.get("devices") as? Map<*, *>
                val accessorySales = salesSummary?.get("accessories") as? Map<*, *>

                // Columns 1, 2: all collections except Credit Card
                val deviceCashCollected = nonCreditCardCollections(deviceCashFlow)
                val accessoryCashCollected = nonCreditCardCollections(accessoryCashFlow)

                // Column 5: Credit Card collections (devices + accessories)
                val creditCardTotal = numberOrZero(deviceCashFlow, "creditCard") +
                    numberOrZero(accessoryCashFlow, "creditCard")

                // Columns 3, 4, 6: balances (devices + accessories)
                val homeCreditBalance = salesBalance(deviceSales, "homeCredit") +
                    salesBalance(accessorySales, "homeCredit")
                val skyroBalance = salesBalance(deviceSales, "skyro") +
                    salesBalance(accessorySales, "skyro")
                val inHouseSalmonBalance = salesBalance(deviceSales, "inHouse") +
                    salesBalance(accessorySales, "inHouse") +
                    salesBalance(deviceSales, "salmon") +
                    salesBalance(accessorySales, "salmon")

                val hasBreakdownData = salesSummary != null || cashFlowSummary != null

                EODReportSummary(
                    documentId = doc.id,
                    date = data["date"] as? String ?: "",
                    displayDate = data["displayDate"] as? String ?: "",
                    generatedAt = data["generatedAt"] as? String ?: "",
                    generatedBy = data["generatedBy"] as? String ?: "",
                    deviceCount = deviceCount,
                    accessoryCount = accessoryCount,
                    serviceCount = serviceCount,
                    totalRevenue = totalRevenue,
                    deviceCashCollected = deviceCashCollected,
                    accessoryCashCollected = accessoryCashCollected,
                    homeCreditBalance = homeCreditBalance,
                    skyroBalance = skyroBalance,
                    creditCardTotal = creditCardTotal,
                    inHouseSalmonBalance = inHouseSalmonBalance,
                    hasBreakdownData = hasBreakdownData
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    // ============================================================================
    // END OF PART 4: DATA LOADING
    // ============================================================================


    // ============================================================================
    // START OF PART 5: ADAPTER
    // ============================================================================

    inner class EODReportAdapter(
        private val items: List<EODReportSummary>,
        private val onItemClick: (EODReportSummary) -> Unit
    ) : RecyclerView.Adapter<EODReportAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemEndOfDayReportBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemEndOfDayReportBinding.inflate(
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
                reportDate.text = formattedDate

                // Day of week
                dayOfWeek.text = getDayOfWeek(item.date)

                // Transaction counts
                deviceCount.text = item.deviceCount.toString()
                accessoryCount.text = item.accessoryCount.toString()
                serviceCount.text = item.serviceCount.toString()

                // Total revenue
                totalRevenue.text = formatCurrency(item.totalRevenue)

                // Generated info
                generatedInfo.text = "Generated by ${item.generatedBy} at ${formatTimeFromGenerated(item.generatedAt)}"

                // Click listener
                root.setOnClickListener {
                    onItemClick(item)
                }

                // Long-press copies the spreadsheet figures (columns 1-6) to the clipboard.
                // Return true so the click does not also fire and open the report.
                root.setOnLongClickListener {
                    copyReportFiguresToClipboard(item)
                    true
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    // ============================================================================
    // END OF PART 5: ADAPTER
    // ============================================================================


    // ============================================================================
    // START OF PART 6: UTILITY METHODS
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

    private fun getDayOfWeek(dateStr: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val dayFormat = SimpleDateFormat("EEEE", Locale.US)
            val date = inputFormat.parse(dateStr)
            dayFormat.format(date!!)
        } catch (e: Exception) {
            ""
        }
    }

    private fun formatTimeFromGenerated(generatedAt: String): String {
        return try {
            // generatedAt format: "M/d/yyyy h:mm a"
            // Extract just the time part
            val parts = generatedAt.split(" ")
            if (parts.size >= 3) {
                "${parts[1]} ${parts[2]}"
            } else {
                generatedAt
            }
        } catch (e: Exception) {
            generatedAt
        }
    }

    private fun formatCurrency(amount: Double): String {
        val format = NumberFormat.getCurrencyInstance(Locale("en", "PH"))
        return format.format(amount)
    }

    /**
     * Reads a numeric field from a saved summary map, defaulting to 0.0 when missing.
     */
    private fun numberOrZero(map: Map<*, *>?, key: String): Double {
        return (map?.get(key) as? Number)?.toDouble() ?: 0.0
    }

    /**
     * Sum of all payment-source buckets except Credit Card from a cashFlowSummary section
     * (devices or accessories). Used for spreadsheet columns 1 and 2.
     */
    private fun nonCreditCardCollections(cashFlow: Map<*, *>?): Double {
        return numberOrZero(cashFlow, "cash") +
            numberOrZero(cashFlow, "gcash") +
            numberOrZero(cashFlow, "paymaya") +
            numberOrZero(cashFlow, "bankTransfer") +
            numberOrZero(cashFlow, "others")
    }

    /**
     * Reads the balance of a transaction type (e.g. "homeCredit") from a salesSummary
     * section (devices or accessories), defaulting to 0.0 when missing.
     */
    private fun salesBalance(sales: Map<*, *>?, typeKey: String): Double {
        val typeMap = sales?.get(typeKey) as? Map<*, *>
        return numberOrZero(typeMap, "balance")
    }

    /**
     * Builds the tab-separated clipboard line for the six spreadsheet columns, in order 1-6.
     * Uses Locale.US so the decimal separator is always a period and there is no grouping,
     * which lets Excel treat the pasted values as numbers.
     */
    private fun buildClipboardLine(item: EODReportSummary): String {
        val values = listOf(
            item.deviceCashCollected,
            item.accessoryCashCollected,
            item.homeCreditBalance,
            item.skyroBalance,
            item.creditCardTotal,
            item.inHouseSalmonBalance
        )
        return values.joinToString("\t") { String.format(Locale.US, "%.2f", it) }
    }

    /**
     * Copies the six spreadsheet figures of a report to the clipboard and confirms with a toast.
     */
    private fun copyReportFiguresToClipboard(item: EODReportSummary) {
        if (!item.hasBreakdownData) {
            showMessage("This report has no breakdown data to copy", true)
            return
        }

        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("EOD figures", buildClipboardLine(item)))
            showMessage("Copied EOD figures for ${formatDisplayDate(item.date)}", false)
        } catch (e: Exception) {
            e.printStackTrace()
            showMessage("Unable to copy: ${e.message}", true)
        }
    }

    private fun showMessage(message: String, isError: Boolean) {
        Toast.makeText(
            this,
            message,
            if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    // ============================================================================
    // END OF PART 6: UTILITY METHODS
    // ============================================================================
}