package com.techcity.techcitysuite

import android.app.DatePickerDialog
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
        val totalRevenue: Double
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

    private fun openReportActivity(date: String) {
        val intent = Intent(this, EndOfDayReportActivity::class.java)
        intent.putExtra("selected_date", date)
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
                            // Open report for viewing
                            openReportActivity(report.displayDate)
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

        return querySnapshot.documents.mapNotNull { doc ->
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

                EODReportSummary(
                    documentId = doc.id,
                    date = data["date"] as? String ?: "",
                    displayDate = data["displayDate"] as? String ?: "",
                    generatedAt = data["generatedAt"] as? String ?: "",
                    generatedBy = data["generatedBy"] as? String ?: "",
                    deviceCount = deviceCount,
                    accessoryCount = accessoryCount,
                    serviceCount = serviceCount,
                    totalRevenue = totalRevenue
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