package com.techcity.techcitysuite

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityEndOfDayReportBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class EndOfDayReportActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityEndOfDayReportBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val COLLECTION_DEVICE_TRANSACTIONS = "device_transactions"
    private val COLLECTION_ACCESSORY_TRANSACTIONS = "accessory_transactions"
    private val COLLECTION_SERVICE_TRANSACTIONS = "service_transactions"
    private val COLLECTION_DAILY_SUMMARIES = "daily_summaries"

    private var selectedDate: String = ""
    private var queryDate: String = ""

    private var reportData: DailySummaryData? = null
    private var isReportGenerated = false

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================


    // ============================================================================
    // START OF PART 2: DATA CLASSES
    // ============================================================================

    data class DailySummaryData(
        val date: String,
        val displayDate: String,
        val generatedAt: String,
        val generatedBy: String,
        val deviceCount: Int,
        val accessoryCount: Int,
        val serviceCount: Int,
        val deviceTransactionIds: List<String>,
        val accessoryTransactionIds: List<String>,
        val serviceTransactionIds: List<String>,
        val deviceSales: SalesSummary,
        val accessorySales: SalesSummary,
        val serviceSummary: ServiceSummary,
        val deviceCashFlow: CashFlowSummary,
        val accessoryCashFlow: CashFlowSummary,
        val serviceCashFlow: ServiceCashFlowSummary,
        val totalProductSales: Double,
        val totalServiceFees: Double,
        val totalMiscIncome: Double,
        val totalRevenue: Double,
        val totalReceivablesCreated: Double,
        val totalBrandZeroSubsidy: Double,
        val ledgerSummary: LedgerSummaryData,
        val revenueCash: Double,
        val revenueGcash: Double,
        val revenuePaymaya: Double,
        val revenueBankTransfer: Double,
        val revenueCreditCard: Double,
        val revenueOthers: Double
    )

    data class SalesSummary(
        val totalSales: Double,
        val cashSales: TransactionBreakdown,
        val homeCreditSales: InstallmentBreakdown,
        val skyroSales: InstallmentBreakdown,
        val inHouseSales: InstallmentBreakdown
    )

    data class TransactionBreakdown(
        val count: Int,
        val amount: Double
    )

    data class InstallmentBreakdown(
        val count: Int,
        val amount: Double,
        val downpayment: Double,
        val balance: Double
    )

    data class ServiceSummary(
        val totalFees: Double,
        val totalVolume: Double,
        val cashIn: ServiceTypeBreakdown,
        val cashOut: ServiceTypeBreakdown,
        val mobileLoading: ServiceTypeBreakdown,
        val skyroPayment: ServiceTypeBreakdown,
        val hcPayment: ServiceTypeBreakdown,
        val miscPayment: MiscPaymentBreakdown
    )

    data class ServiceTypeBreakdown(
        val count: Int,
        val volume: Double,
        val fees: Double
    )

    data class MiscPaymentBreakdown(
        val count: Int,
        val amount: Double
    )

    data class CashFlowSummary(
        val cash: Double,
        val gcash: Double,
        val paymaya: Double,
        val bankTransfer: Double,
        val creditCard: Double,
        val others: Double,
        val hcReceivable: Double,
        val skyroReceivable: Double,
        val inHouseReceivable: Double,
        val brandZeroSubsidy: Double
    )

    data class ServiceCashFlowSummary(
        val cashInflow: Double,
        val cashOutflow: Double,
        val gcashInflow: Double,
        val gcashOutflow: Double,
        val paymayaInflow: Double,
        val paymayaOutflow: Double,
        val othersInflow: Double,
        val othersOutflow: Double
    )

    data class LedgerSummaryData(
        val cash: LedgerBreakdown,
        val gcash: LedgerBreakdown,
        val paymaya: LedgerBreakdown,
        val bankTransfer: LedgerBreakdown,
        val others: LedgerBreakdown,
        val receivables: ReceivablesBreakdown
    )

    data class LedgerBreakdown(
        val device: Double,
        val accessory: Double,
        val service: Double,
        val total: Double
    )

    data class ReceivablesBreakdown(
        val homeCredit: Double,
        val skyro: Double,
        val inHouse: Double,
        val total: Double
    )

    // ============================================================================
    // END OF PART 2: DATA CLASSES
    // ============================================================================


    // ============================================================================
    // START OF PART 3: LIFECYCLE METHODS
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityEndOfDayReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = Firebase.firestore

        setupUI()

        val intentDate = intent.getStringExtra("selected_date")
        if (intentDate != null) {
            selectedDate = intentDate
        } else {
            selectedDate = getCurrentDatePhilippines()
        }
        queryDate = convertDisplayDateToQueryDate(selectedDate)
        updateDateLabel()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ============================================================================
    // END OF PART 3: LIFECYCLE METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 4: UI SETUP
    // ============================================================================

    private fun setupUI() {
        binding.backButton.setOnClickListener {
            finish()
        }

        binding.dateLabel.setOnClickListener {
            showDatePicker()
        }

        binding.generateButton.setOnClickListener {
            generateReport()
        }

        binding.saveButton.setOnClickListener {
            saveReport()
        }

        // Toggle buttons for Transaction Detail / Transaction Summary
        binding.btnTransactionDetail.setOnClickListener {
            showTransactionDetail()
        }

        binding.btnTransactionSummary.setOnClickListener {
            showTransactionSummary()
        }

        binding.scrollView.visibility = View.GONE
        binding.statusMessage.visibility = View.VISIBLE
        binding.statusMessage.text = "Select a date and tap Generate"
        binding.saveButton.isEnabled = false
    }

    private fun showTransactionDetail() {
        // Update button styles - Transaction Detail is selected
        binding.btnTransactionDetail.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.techcity_blue)
        )
        binding.btnTransactionDetail.setTextColor(ContextCompat.getColor(this, R.color.white))
        binding.btnTransactionDetail.strokeWidth = 0

        binding.btnTransactionSummary.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, android.R.color.transparent)
        )
        binding.btnTransactionSummary.setTextColor(ContextCompat.getColor(this, R.color.techcity_blue))
        binding.btnTransactionSummary.strokeWidth = (1 * resources.displayMetrics.density).toInt()
        binding.btnTransactionSummary.strokeColor = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.techcity_blue)
        )

        // Toggle visibility
        binding.transactionDetailSection.visibility = View.VISIBLE
        binding.transactionSummarySection.visibility = View.GONE
    }

    private fun showTransactionSummary() {
        // Update button styles - Transaction Summary is selected
        binding.btnTransactionSummary.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.techcity_blue)
        )
        binding.btnTransactionSummary.setTextColor(ContextCompat.getColor(this, R.color.white))
        binding.btnTransactionSummary.strokeWidth = 0

        binding.btnTransactionDetail.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, android.R.color.transparent)
        )
        binding.btnTransactionDetail.setTextColor(ContextCompat.getColor(this, R.color.techcity_blue))
        binding.btnTransactionDetail.strokeWidth = (1 * resources.displayMetrics.density).toInt()
        binding.btnTransactionDetail.strokeColor = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.techcity_blue)
        )

        // Toggle visibility
        binding.transactionDetailSection.visibility = View.GONE
        binding.transactionSummarySection.visibility = View.VISIBLE
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila"))

        try {
            val format = SimpleDateFormat("M/d/yyyy", Locale.US)
            val date = format.parse(selectedDate)
            if (date != null) {
                calendar.time = date
            }
        } catch (e: Exception) { }

        val datePickerDialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                selectedDate = "${month + 1}/$dayOfMonth/$year"
                queryDate = convertDisplayDateToQueryDate(selectedDate)
                updateDateLabel()

                isReportGenerated = false
                binding.saveButton.isEnabled = false
                binding.scrollView.visibility = View.GONE
                binding.statusMessage.visibility = View.VISIBLE
                binding.statusMessage.text = "Tap Generate to create report"
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        datePickerDialog.show()
    }

    private fun updateDateLabel() {
        binding.dateLabel.text = selectedDate
    }

    // ============================================================================
    // END OF PART 4: UI SETUP
    // ============================================================================


    // ============================================================================
    // START OF PART 5: REPORT GENERATION
    // ============================================================================

    private fun generateReport() {
        binding.progressBar.visibility = View.VISIBLE
        binding.statusMessage.visibility = View.GONE
        binding.scrollView.visibility = View.GONE
        binding.saveButton.isEnabled = false

        scope.launch {
            try {
                val deviceTransactions = withContext(Dispatchers.IO) {
                    fetchDeviceTransactions()
                }

                val accessoryTransactions = withContext(Dispatchers.IO) {
                    fetchAccessoryTransactions()
                }

                val serviceTransactions = withContext(Dispatchers.IO) {
                    fetchServiceTransactions()
                }

                val data = processTransactionData(
                    deviceTransactions,
                    accessoryTransactions,
                    serviceTransactions
                )

                reportData = data
                isReportGenerated = true

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE

                    if (data.deviceCount == 0 && data.accessoryCount == 0 && data.serviceCount == 0) {
                        binding.statusMessage.visibility = View.VISIBLE
                        binding.statusMessage.text = "No transactions found for $selectedDate"
                        binding.scrollView.visibility = View.GONE
                        binding.saveButton.isEnabled = false
                    } else {
                        displayReport(data)
                        binding.scrollView.visibility = View.VISIBLE
                        binding.statusMessage.visibility = View.GONE
                        binding.saveButton.isEnabled = true
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.statusMessage.visibility = View.VISIBLE
                    binding.statusMessage.text = "Error: ${e.message}"
                    showMessage("Error generating report: ${e.message}", true)
                }
            }
        }
    }

    private suspend fun fetchDeviceTransactions(): List<Map<String, Any?>> {
        val querySnapshot = db.collection(COLLECTION_DEVICE_TRANSACTIONS)
            .whereEqualTo("date", queryDate)
            .whereEqualTo("status", AppConstants.STATUS_COMPLETED)
            .get()
            .await()

        return querySnapshot.documents.map { doc ->
            val data = doc.data?.toMutableMap() ?: mutableMapOf()
            data["_id"] = doc.id
            data
        }
    }

    private suspend fun fetchAccessoryTransactions(): List<Map<String, Any?>> {
        val querySnapshot = db.collection(COLLECTION_ACCESSORY_TRANSACTIONS)
            .whereEqualTo("date", queryDate)
            .whereEqualTo("status", AppConstants.STATUS_COMPLETED)
            .get()
            .await()

        return querySnapshot.documents.map { doc ->
            val data = doc.data?.toMutableMap() ?: mutableMapOf()
            data["_id"] = doc.id
            data
        }
    }

    private suspend fun fetchServiceTransactions(): List<Map<String, Any?>> {
        val querySnapshot = db.collection(COLLECTION_SERVICE_TRANSACTIONS)
            .whereEqualTo("date", queryDate)
            .whereEqualTo("status", AppConstants.STATUS_COMPLETED)
            .get()
            .await()

        return querySnapshot.documents.map { doc ->
            val data = doc.data?.toMutableMap() ?: mutableMapOf()
            data["_id"] = doc.id
            data
        }
    }

    // ============================================================================
    // END OF PART 5: REPORT GENERATION
    // ============================================================================


    // ============================================================================
    // START OF PART 6: DATA PROCESSING (CORRECTED FIELD NAMES)
    // ============================================================================

    private fun processTransactionData(
        deviceTransactions: List<Map<String, Any?>>,
        accessoryTransactions: List<Map<String, Any?>>,
        serviceTransactions: List<Map<String, Any?>>
    ): DailySummaryData {

        val deviceSales = processDeviceSales(deviceTransactions)
        val accessorySales = processAccessorySales(accessoryTransactions)
        val serviceSummary = processServiceSummary(serviceTransactions)

        val deviceCashFlow = processDeviceCashFlow(deviceTransactions)
        val accessoryCashFlow = processAccessoryCashFlow(accessoryTransactions)
        val serviceCashFlow = processServiceCashFlow(serviceTransactions)

        val totalProductSales = deviceSales.totalSales + accessorySales.totalSales
        val totalServiceFees = serviceSummary.totalFees
        val totalMiscIncome = serviceSummary.miscPayment.amount
        val totalRevenue = totalProductSales + totalServiceFees + totalMiscIncome

        val totalReceivables = deviceCashFlow.hcReceivable + deviceCashFlow.skyroReceivable + deviceCashFlow.inHouseReceivable +
                accessoryCashFlow.hcReceivable + accessoryCashFlow.skyroReceivable + accessoryCashFlow.inHouseReceivable

        val totalBrandZeroSubsidy = deviceCashFlow.brandZeroSubsidy + accessoryCashFlow.brandZeroSubsidy

        // Calculate service net cash flows
        val serviceCashNet = serviceCashFlow.cashInflow - serviceCashFlow.cashOutflow
        val serviceGcashNet = serviceCashFlow.gcashInflow - serviceCashFlow.gcashOutflow
        val servicePaymayaNet = serviceCashFlow.paymayaInflow - serviceCashFlow.paymayaOutflow
        val serviceOthersNet = serviceCashFlow.othersInflow - serviceCashFlow.othersOutflow

        // Calculate ledger summary with net receivables (minus Brand Zero subsidy)
        val hcReceivableNet = (deviceCashFlow.hcReceivable + accessoryCashFlow.hcReceivable) -
                (deviceCashFlow.brandZeroSubsidy + accessoryCashFlow.brandZeroSubsidy)
        val skyroReceivableNet = deviceCashFlow.skyroReceivable + accessoryCashFlow.skyroReceivable
        val inHouseReceivableNet = deviceCashFlow.inHouseReceivable + accessoryCashFlow.inHouseReceivable

        val ledgerSummary = LedgerSummaryData(
            cash = LedgerBreakdown(
                device = deviceCashFlow.cash,
                accessory = accessoryCashFlow.cash,
                service = serviceCashNet,
                total = deviceCashFlow.cash + accessoryCashFlow.cash + serviceCashNet
            ),
            gcash = LedgerBreakdown(
                device = deviceCashFlow.gcash,
                accessory = accessoryCashFlow.gcash,
                service = serviceGcashNet,
                total = deviceCashFlow.gcash + accessoryCashFlow.gcash + serviceGcashNet
            ),
            paymaya = LedgerBreakdown(
                device = deviceCashFlow.paymaya,
                accessory = accessoryCashFlow.paymaya,
                service = servicePaymayaNet,
                total = deviceCashFlow.paymaya + accessoryCashFlow.paymaya + servicePaymayaNet
            ),
            bankTransfer = LedgerBreakdown(
                device = deviceCashFlow.bankTransfer,
                accessory = accessoryCashFlow.bankTransfer,
                service = 0.0, // Bank transfer not used in services
                total = deviceCashFlow.bankTransfer + accessoryCashFlow.bankTransfer
            ),
            others = LedgerBreakdown(
                device = deviceCashFlow.others,
                accessory = accessoryCashFlow.others,
                service = serviceOthersNet,
                total = deviceCashFlow.others + accessoryCashFlow.others + serviceOthersNet
            ),
            receivables = ReceivablesBreakdown(
                homeCredit = hcReceivableNet,
                skyro = skyroReceivableNet,
                inHouse = inHouseReceivableNet,
                total = hcReceivableNet + skyroReceivableNet + inHouseReceivableNet
            )
        )

        val revenueCash = deviceCashFlow.cash + accessoryCashFlow.cash + totalServiceFees + totalMiscIncome
        val revenueGcash = deviceCashFlow.gcash + accessoryCashFlow.gcash
        val revenuePaymaya = deviceCashFlow.paymaya + accessoryCashFlow.paymaya
        val revenueBankTransfer = deviceCashFlow.bankTransfer + accessoryCashFlow.bankTransfer
        val revenueCreditCard = deviceCashFlow.creditCard + accessoryCashFlow.creditCard
        val revenueOthers = deviceCashFlow.others + accessoryCashFlow.others

        val deviceIds = deviceTransactions.mapNotNull { it["_id"] as? String }
        val accessoryIds = accessoryTransactions.mapNotNull { it["_id"] as? String }
        val serviceIds = serviceTransactions.mapNotNull { it["_id"] as? String }

        val generatedAt = SimpleDateFormat("M/d/yyyy h:mm a", Locale.US).format(Date())

        val prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE)
        val generatedBy = prefs.getString(AppConstants.KEY_USER, "Admin") ?: "Admin"

        return DailySummaryData(
            date = queryDate,
            displayDate = selectedDate,
            generatedAt = generatedAt,
            generatedBy = generatedBy,
            deviceCount = deviceTransactions.size,
            accessoryCount = accessoryTransactions.size,
            serviceCount = serviceTransactions.size,
            deviceTransactionIds = deviceIds,
            accessoryTransactionIds = accessoryIds,
            serviceTransactionIds = serviceIds,
            deviceSales = deviceSales,
            accessorySales = accessorySales,
            serviceSummary = serviceSummary,
            deviceCashFlow = deviceCashFlow,
            accessoryCashFlow = accessoryCashFlow,
            serviceCashFlow = serviceCashFlow,
            totalProductSales = totalProductSales,
            totalServiceFees = totalServiceFees,
            totalMiscIncome = totalMiscIncome,
            totalRevenue = totalRevenue,
            totalReceivablesCreated = totalReceivables,
            totalBrandZeroSubsidy = totalBrandZeroSubsidy,
            ledgerSummary = ledgerSummary,
            revenueCash = revenueCash,
            revenueGcash = revenueGcash,
            revenuePaymaya = revenuePaymaya,
            revenueBankTransfer = revenueBankTransfer,
            revenueCreditCard = revenueCreditCard,
            revenueOthers = revenueOthers
        )
    }

    /**
     * Process device sales - uses finalPrice and nested payment objects
     */
    private fun processDeviceSales(transactions: List<Map<String, Any?>>): SalesSummary {
        var cashCount = 0
        var cashAmount = 0.0

        var hcCount = 0
        var hcAmount = 0.0
        var hcDownpayment = 0.0
        var hcBalance = 0.0

        var skyroCount = 0
        var skyroAmount = 0.0
        var skyroDownpayment = 0.0
        var skyroBalance = 0.0

        var inHouseCount = 0
        var inHouseAmount = 0.0
        var inHouseDownpayment = 0.0
        var inHouseBalance = 0.0

        for (data in transactions) {
            val transactionType = data["transactionType"] as? String ?: ""
            // Use finalPrice as the total sale amount
            val finalPrice = (data["finalPrice"] as? Number)?.toDouble() ?: 0.0

            when (transactionType) {
                AppConstants.TRANSACTION_TYPE_CASH -> {
                    cashCount++
                    cashAmount += finalPrice
                }
                AppConstants.TRANSACTION_TYPE_HOME_CREDIT -> {
                    // Get nested homeCreditPayment object
                    val hcPayment = data["homeCreditPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (hcPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (hcPayment["balance"] as? Number)?.toDouble() ?: 0.0

                    hcCount++
                    hcAmount += finalPrice
                    hcDownpayment += downpayment
                    hcBalance += balance
                }
                AppConstants.TRANSACTION_TYPE_SKYRO -> {
                    // Get nested skyroPayment object
                    val skyroPayment = data["skyroPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (skyroPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (skyroPayment["balance"] as? Number)?.toDouble() ?: 0.0

                    skyroCount++
                    skyroAmount += finalPrice
                    skyroDownpayment += downpayment
                    skyroBalance += balance
                }
                AppConstants.TRANSACTION_TYPE_IN_HOUSE -> {
                    // Get nested inHouseInstallment object
                    val ihPayment = data["inHouseInstallment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (ihPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    // For In-House, balance is totalAmountDue (includes interest)
                    val balance = (ihPayment["totalAmountDue"] as? Number)?.toDouble()
                        ?: (ihPayment["balance"] as? Number)?.toDouble() ?: 0.0

                    inHouseCount++
                    inHouseAmount += finalPrice
                    inHouseDownpayment += downpayment
                    inHouseBalance += balance
                }
            }
        }

        val totalSales = cashAmount + hcAmount + skyroAmount + inHouseAmount

        return SalesSummary(
            totalSales = totalSales,
            cashSales = TransactionBreakdown(cashCount, cashAmount),
            homeCreditSales = InstallmentBreakdown(hcCount, hcAmount, hcDownpayment, hcBalance),
            skyroSales = InstallmentBreakdown(skyroCount, skyroAmount, skyroDownpayment, skyroBalance),
            inHouseSales = InstallmentBreakdown(inHouseCount, inHouseAmount, inHouseDownpayment, inHouseBalance)
        )
    }

    /**
     * Process accessory sales - uses finalPrice and nested payment objects
     */
    private fun processAccessorySales(transactions: List<Map<String, Any?>>): SalesSummary {
        var cashCount = 0
        var cashAmount = 0.0

        var hcCount = 0
        var hcAmount = 0.0
        var hcDownpayment = 0.0
        var hcBalance = 0.0

        var skyroCount = 0
        var skyroAmount = 0.0
        var skyroDownpayment = 0.0
        var skyroBalance = 0.0

        var inHouseCount = 0
        var inHouseAmount = 0.0
        var inHouseDownpayment = 0.0
        var inHouseBalance = 0.0

        for (data in transactions) {
            val transactionType = data["transactionType"] as? String ?: ""
            val finalPrice = (data["finalPrice"] as? Number)?.toDouble() ?: 0.0

            when (transactionType) {
                AppConstants.TRANSACTION_TYPE_CASH -> {
                    cashCount++
                    cashAmount += finalPrice
                }
                AppConstants.TRANSACTION_TYPE_HOME_CREDIT -> {
                    val hcPayment = data["homeCreditPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (hcPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (hcPayment["balance"] as? Number)?.toDouble() ?: 0.0

                    hcCount++
                    hcAmount += finalPrice
                    hcDownpayment += downpayment
                    hcBalance += balance
                }
                AppConstants.TRANSACTION_TYPE_SKYRO -> {
                    val skyroPayment = data["skyroPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (skyroPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (skyroPayment["balance"] as? Number)?.toDouble() ?: 0.0

                    skyroCount++
                    skyroAmount += finalPrice
                    skyroDownpayment += downpayment
                    skyroBalance += balance
                }
                AppConstants.TRANSACTION_TYPE_IN_HOUSE -> {
                    val ihPayment = data["inHouseInstallment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (ihPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (ihPayment["totalAmountDue"] as? Number)?.toDouble()
                        ?: (ihPayment["balance"] as? Number)?.toDouble() ?: 0.0

                    inHouseCount++
                    inHouseAmount += finalPrice
                    inHouseDownpayment += downpayment
                    inHouseBalance += balance
                }
            }
        }

        val totalSales = cashAmount + hcAmount + skyroAmount + inHouseAmount

        return SalesSummary(
            totalSales = totalSales,
            cashSales = TransactionBreakdown(cashCount, cashAmount),
            homeCreditSales = InstallmentBreakdown(hcCount, hcAmount, hcDownpayment, hcBalance),
            skyroSales = InstallmentBreakdown(skyroCount, skyroAmount, skyroDownpayment, skyroBalance),
            inHouseSales = InstallmentBreakdown(inHouseCount, inHouseAmount, inHouseDownpayment, inHouseBalance)
        )
    }

    private fun processServiceSummary(transactions: List<Map<String, Any?>>): ServiceSummary {
        var cashInCount = 0
        var cashInVolume = 0.0
        var cashInFees = 0.0

        var cashOutCount = 0
        var cashOutVolume = 0.0
        var cashOutFees = 0.0

        var mobileLoadCount = 0
        var mobileLoadVolume = 0.0
        var mobileLoadFees = 0.0

        var skyroCount = 0
        var skyroVolume = 0.0
        var skyroFees = 0.0

        var hcCount = 0
        var hcVolume = 0.0
        var hcFees = 0.0

        var miscCount = 0
        var miscAmount = 0.0

        for (data in transactions) {
            val transactionType = data["transactionType"] as? String ?: ""
            val amount = (data["amount"] as? Number)?.toDouble() ?: 0.0
            val fee = (data["fee"] as? Number)?.toDouble() ?: 0.0

            when (transactionType) {
                "Cash In" -> {
                    cashInCount++
                    cashInVolume += amount
                    cashInFees += fee
                }
                "Cash Out" -> {
                    cashOutCount++
                    cashOutVolume += amount
                    cashOutFees += fee
                }
                "Mobile Loading Service" -> {
                    mobileLoadCount++
                    mobileLoadVolume += amount
                    mobileLoadFees += fee
                }
                "Skyro Payment" -> {
                    skyroCount++
                    skyroVolume += amount
                    skyroFees += fee
                }
                "Home Credit Payment" -> {
                    hcCount++
                    hcVolume += amount
                    hcFees += fee
                }
                "Misc Payment" -> {
                    miscCount++
                    miscAmount += amount
                }
            }
        }

        val totalFees = cashInFees + cashOutFees + mobileLoadFees + skyroFees + hcFees
        val totalVolume = cashInVolume + cashOutVolume + mobileLoadVolume + skyroVolume + hcVolume + miscAmount

        return ServiceSummary(
            totalFees = totalFees,
            totalVolume = totalVolume,
            cashIn = ServiceTypeBreakdown(cashInCount, cashInVolume, cashInFees),
            cashOut = ServiceTypeBreakdown(cashOutCount, cashOutVolume, cashOutFees),
            mobileLoading = ServiceTypeBreakdown(mobileLoadCount, mobileLoadVolume, mobileLoadFees),
            skyroPayment = ServiceTypeBreakdown(skyroCount, skyroVolume, skyroFees),
            hcPayment = ServiceTypeBreakdown(hcCount, hcVolume, hcFees),
            miscPayment = MiscPaymentBreakdown(miscCount, miscAmount)
        )
    }

    /**
     * Process device cash flow - uses nested payment objects for payment sources
     */
    private fun processDeviceCashFlow(transactions: List<Map<String, Any?>>): CashFlowSummary {
        var cash = 0.0
        var gcash = 0.0
        var paymaya = 0.0
        var bankTransfer = 0.0
        var creditCard = 0.0
        var others = 0.0

        var hcReceivable = 0.0
        var skyroReceivable = 0.0
        var inHouseReceivable = 0.0
        var brandZeroSubsidy = 0.0

        for (data in transactions) {
            val transactionType = data["transactionType"] as? String ?: ""
            val finalPrice = (data["finalPrice"] as? Number)?.toDouble() ?: 0.0

            when (transactionType) {
                AppConstants.TRANSACTION_TYPE_CASH -> {
                    // Get payment source from nested cashPayment object
                    val cashPayment = data["cashPayment"] as? Map<String, Any?> ?: emptyMap()
                    val paymentSource = cashPayment["paymentSource"] as? String ?: "Cash"

                    when (paymentSource) {
                        "Cash" -> cash += finalPrice
                        "GCash" -> gcash += finalPrice
                        "PayMaya" -> paymaya += finalPrice
                        "Bank Transfer" -> bankTransfer += finalPrice
                        "Credit Card" -> creditCard += finalPrice
                        "Others" -> others += finalPrice
                        else -> cash += finalPrice
                    }
                }
                AppConstants.TRANSACTION_TYPE_HOME_CREDIT -> {
                    val hcPayment = data["homeCreditPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (hcPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (hcPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val dpSource = hcPayment["downpaymentSource"] as? String ?: "Cash"
                    val subsidy = (hcPayment["brandZeroSubsidy"] as? Number)?.toDouble() ?: 0.0

                    // Downpayment goes to payment source
                    if (downpayment > 0) {
                        when (dpSource) {
                            "Cash" -> cash += downpayment
                            "GCash" -> gcash += downpayment
                            "PayMaya" -> paymaya += downpayment
                            "Bank Transfer" -> bankTransfer += downpayment
                            "Credit Card" -> creditCard += downpayment
                            "Others" -> others += downpayment
                            else -> cash += downpayment
                        }
                    }
                    hcReceivable += balance
                    brandZeroSubsidy += subsidy
                }
                AppConstants.TRANSACTION_TYPE_SKYRO -> {
                    val skyroPayment = data["skyroPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (skyroPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (skyroPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val dpSource = skyroPayment["downpaymentSource"] as? String ?: "Cash"
                    val subsidy = (skyroPayment["brandZeroSubsidy"] as? Number)?.toDouble() ?: 0.0

                    if (downpayment > 0) {
                        when (dpSource) {
                            "Cash" -> cash += downpayment
                            "GCash" -> gcash += downpayment
                            "PayMaya" -> paymaya += downpayment
                            "Bank Transfer" -> bankTransfer += downpayment
                            "Credit Card" -> creditCard += downpayment
                            "Others" -> others += downpayment
                            else -> cash += downpayment
                        }
                    }
                    skyroReceivable += balance
                    brandZeroSubsidy += subsidy
                }
                AppConstants.TRANSACTION_TYPE_IN_HOUSE -> {
                    val ihPayment = data["inHouseInstallment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (ihPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val totalAmountDue = (ihPayment["totalAmountDue"] as? Number)?.toDouble()
                        ?: (ihPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val dpSource = ihPayment["downpaymentSource"] as? String ?: "Cash"

                    if (downpayment > 0) {
                        when (dpSource) {
                            "Cash" -> cash += downpayment
                            "GCash" -> gcash += downpayment
                            "PayMaya" -> paymaya += downpayment
                            "Bank Transfer" -> bankTransfer += downpayment
                            "Credit Card" -> creditCard += downpayment
                            "Others" -> others += downpayment
                            else -> cash += downpayment
                        }
                    }
                    inHouseReceivable += totalAmountDue
                }
            }
        }

        return CashFlowSummary(
            cash = cash,
            gcash = gcash,
            paymaya = paymaya,
            bankTransfer = bankTransfer,
            creditCard = creditCard,
            others = others,
            hcReceivable = hcReceivable,
            skyroReceivable = skyroReceivable,
            inHouseReceivable = inHouseReceivable,
            brandZeroSubsidy = brandZeroSubsidy
        )
    }

    /**
     * Process accessory cash flow - uses nested payment objects
     */
    private fun processAccessoryCashFlow(transactions: List<Map<String, Any?>>): CashFlowSummary {
        var cash = 0.0
        var gcash = 0.0
        var paymaya = 0.0
        var bankTransfer = 0.0
        var creditCard = 0.0
        var others = 0.0

        var hcReceivable = 0.0
        var skyroReceivable = 0.0
        var inHouseReceivable = 0.0
        var brandZeroSubsidy = 0.0

        for (data in transactions) {
            val transactionType = data["transactionType"] as? String ?: ""
            val finalPrice = (data["finalPrice"] as? Number)?.toDouble() ?: 0.0

            when (transactionType) {
                AppConstants.TRANSACTION_TYPE_CASH -> {
                    val cashPayment = data["cashPayment"] as? Map<String, Any?> ?: emptyMap()
                    val paymentSource = cashPayment["paymentSource"] as? String ?: "Cash"

                    when (paymentSource) {
                        "Cash" -> cash += finalPrice
                        "GCash" -> gcash += finalPrice
                        "PayMaya" -> paymaya += finalPrice
                        "Bank Transfer" -> bankTransfer += finalPrice
                        "Credit Card" -> creditCard += finalPrice
                        "Others" -> others += finalPrice
                        else -> cash += finalPrice
                    }
                }
                AppConstants.TRANSACTION_TYPE_HOME_CREDIT -> {
                    val hcPayment = data["homeCreditPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (hcPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (hcPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val dpSource = hcPayment["downpaymentSource"] as? String ?: "Cash"
                    val subsidy = (hcPayment["brandZeroSubsidy"] as? Number)?.toDouble() ?: 0.0

                    if (downpayment > 0) {
                        when (dpSource) {
                            "Cash" -> cash += downpayment
                            "GCash" -> gcash += downpayment
                            "PayMaya" -> paymaya += downpayment
                            "Bank Transfer" -> bankTransfer += downpayment
                            "Credit Card" -> creditCard += downpayment
                            "Others" -> others += downpayment
                            else -> cash += downpayment
                        }
                    }
                    hcReceivable += balance
                    brandZeroSubsidy += subsidy
                }
                AppConstants.TRANSACTION_TYPE_SKYRO -> {
                    val skyroPayment = data["skyroPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (skyroPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (skyroPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val dpSource = skyroPayment["downpaymentSource"] as? String ?: "Cash"
                    val subsidy = (skyroPayment["brandZeroSubsidy"] as? Number)?.toDouble() ?: 0.0

                    if (downpayment > 0) {
                        when (dpSource) {
                            "Cash" -> cash += downpayment
                            "GCash" -> gcash += downpayment
                            "PayMaya" -> paymaya += downpayment
                            "Bank Transfer" -> bankTransfer += downpayment
                            "Credit Card" -> creditCard += downpayment
                            "Others" -> others += downpayment
                            else -> cash += downpayment
                        }
                    }
                    skyroReceivable += balance
                    brandZeroSubsidy += subsidy
                }
                AppConstants.TRANSACTION_TYPE_IN_HOUSE -> {
                    val ihPayment = data["inHouseInstallment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (ihPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val totalAmountDue = (ihPayment["totalAmountDue"] as? Number)?.toDouble()
                        ?: (ihPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val dpSource = ihPayment["downpaymentSource"] as? String ?: "Cash"

                    if (downpayment > 0) {
                        when (dpSource) {
                            "Cash" -> cash += downpayment
                            "GCash" -> gcash += downpayment
                            "PayMaya" -> paymaya += downpayment
                            "Bank Transfer" -> bankTransfer += downpayment
                            "Credit Card" -> creditCard += downpayment
                            "Others" -> others += downpayment
                            else -> cash += downpayment
                        }
                    }
                    inHouseReceivable += totalAmountDue
                }
            }
        }

        return CashFlowSummary(
            cash = cash,
            gcash = gcash,
            paymaya = paymaya,
            bankTransfer = bankTransfer,
            creditCard = creditCard,
            others = others,
            hcReceivable = hcReceivable,
            skyroReceivable = skyroReceivable,
            inHouseReceivable = inHouseReceivable,
            brandZeroSubsidy = brandZeroSubsidy
        )
    }

    private fun processServiceCashFlow(transactions: List<Map<String, Any?>>): ServiceCashFlowSummary {
        var cashIn = 0.0
        var cashOut = 0.0
        var gcashIn = 0.0
        var gcashOut = 0.0
        var paymayaIn = 0.0
        var paymayaOut = 0.0
        var othersIn = 0.0
        var othersOut = 0.0

        for (data in transactions) {
            val creditLedger = data["creditLedgerType"] as? String ?: ""
            val creditAmount = (data["creditAmount"] as? Number)?.toDouble() ?: 0.0
            val debitLedger = data["debitLedgerType"] as? String ?: ""
            val debitAmount = (data["debitAmount"] as? Number)?.toDouble() ?: 0.0

            when (creditLedger) {
                "Cash" -> cashIn += creditAmount
                "GCash" -> gcashIn += creditAmount
                "PayMaya" -> paymayaIn += creditAmount
                "Others" -> othersIn += creditAmount
            }

            when (debitLedger) {
                "Cash" -> cashOut += debitAmount
                "GCash" -> gcashOut += debitAmount
                "PayMaya" -> paymayaOut += debitAmount
                "Others" -> othersOut += debitAmount
            }
        }

        return ServiceCashFlowSummary(
            cashInflow = cashIn,
            cashOutflow = cashOut,
            gcashInflow = gcashIn,
            gcashOutflow = gcashOut,
            paymayaInflow = paymayaIn,
            paymayaOutflow = paymayaOut,
            othersInflow = othersIn,
            othersOutflow = othersOut
        )
    }

    // ============================================================================
    // END OF PART 6: DATA PROCESSING
    // ============================================================================


    // ============================================================================
    // START OF PART 7: DISPLAY METHODS
    // ============================================================================

    private fun displayReport(data: DailySummaryData) {
        binding.deviceCountValue.text = data.deviceCount.toString()
        binding.accessoryCountValue.text = data.accessoryCount.toString()
        binding.serviceCountValue.text = data.serviceCount.toString()

        displayDeviceSales(data.deviceSales)
        displayAccessorySales(data.accessorySales)
        displayServiceSummary(data.serviceSummary)
        displayDeviceCashFlow(data.deviceCashFlow)
        displayAccessoryCashFlow(data.accessoryCashFlow)
        displayServiceCashFlow(data.serviceCashFlow)
        displayDailySummary(data)

        binding.generatedTimestamp.text = "Generated: ${data.generatedAt}"
    }

    private fun displayDeviceSales(sales: SalesSummary) {
        binding.deviceTotalSales.text = formatCurrency(sales.totalSales)

        binding.deviceCashCount.text = "(${sales.cashSales.count})"
        binding.deviceCashAmount.text = formatCurrency(sales.cashSales.amount)

        binding.deviceHcCount.text = "(${sales.homeCreditSales.count})"
        binding.deviceHcAmount.text = formatCurrency(sales.homeCreditSales.amount)

        if (sales.homeCreditSales.downpayment > 0) {
            binding.deviceHcDpRow.visibility = View.VISIBLE
            binding.deviceHcBalanceRow.visibility = View.VISIBLE
            binding.deviceHcDpAmount.text = formatCurrency(sales.homeCreditSales.downpayment)
            binding.deviceHcBalanceAmount.text = formatCurrency(sales.homeCreditSales.balance)
        } else {
            binding.deviceHcDpRow.visibility = View.GONE
            binding.deviceHcBalanceRow.visibility = View.GONE
        }

        binding.deviceSkyroCount.text = "(${sales.skyroSales.count})"
        binding.deviceSkyroAmount.text = formatCurrency(sales.skyroSales.amount)

        if (sales.skyroSales.downpayment > 0) {
            binding.deviceSkyroDpRow.visibility = View.VISIBLE
            binding.deviceSkyroBalanceRow.visibility = View.VISIBLE
            binding.deviceSkyroDpAmount.text = formatCurrency(sales.skyroSales.downpayment)
            binding.deviceSkyroBalanceAmount.text = formatCurrency(sales.skyroSales.balance)
        } else {
            binding.deviceSkyroDpRow.visibility = View.GONE
            binding.deviceSkyroBalanceRow.visibility = View.GONE
        }

        binding.deviceInHouseCount.text = "(${sales.inHouseSales.count})"
        binding.deviceInHouseAmount.text = formatCurrency(sales.inHouseSales.amount)

        if (sales.inHouseSales.downpayment > 0) {
            binding.deviceInHouseDpRow.visibility = View.VISIBLE
            binding.deviceInHouseBalanceRow.visibility = View.VISIBLE
            binding.deviceInHouseDpAmount.text = formatCurrency(sales.inHouseSales.downpayment)
            binding.deviceInHouseBalanceAmount.text = formatCurrency(sales.inHouseSales.balance)
        } else {
            binding.deviceInHouseDpRow.visibility = View.GONE
            binding.deviceInHouseBalanceRow.visibility = View.GONE
        }
    }

    private fun displayAccessorySales(sales: SalesSummary) {
        binding.accessoryTotalSales.text = formatCurrency(sales.totalSales)

        binding.accessoryCashCount.text = "(${sales.cashSales.count})"
        binding.accessoryCashAmount.text = formatCurrency(sales.cashSales.amount)

        binding.accessoryHcCount.text = "(${sales.homeCreditSales.count})"
        binding.accessoryHcAmount.text = formatCurrency(sales.homeCreditSales.amount)

        if (sales.homeCreditSales.downpayment > 0) {
            binding.accessoryHcDpRow.visibility = View.VISIBLE
            binding.accessoryHcBalanceRow.visibility = View.VISIBLE
            binding.accessoryHcDpAmount.text = formatCurrency(sales.homeCreditSales.downpayment)
            binding.accessoryHcBalanceAmount.text = formatCurrency(sales.homeCreditSales.balance)
        } else {
            binding.accessoryHcDpRow.visibility = View.GONE
            binding.accessoryHcBalanceRow.visibility = View.GONE
        }

        binding.accessorySkyroCount.text = "(${sales.skyroSales.count})"
        binding.accessorySkyroAmount.text = formatCurrency(sales.skyroSales.amount)

        if (sales.skyroSales.downpayment > 0) {
            binding.accessorySkyroDpRow.visibility = View.VISIBLE
            binding.accessorySkyroBalanceRow.visibility = View.VISIBLE
            binding.accessorySkyroDpAmount.text = formatCurrency(sales.skyroSales.downpayment)
            binding.accessorySkyroBalanceAmount.text = formatCurrency(sales.skyroSales.balance)
        } else {
            binding.accessorySkyroDpRow.visibility = View.GONE
            binding.accessorySkyroBalanceRow.visibility = View.GONE
        }

        binding.accessoryInHouseCount.text = "(${sales.inHouseSales.count})"
        binding.accessoryInHouseAmount.text = formatCurrency(sales.inHouseSales.amount)

        if (sales.inHouseSales.downpayment > 0) {
            binding.accessoryInHouseDpRow.visibility = View.VISIBLE
            binding.accessoryInHouseBalanceRow.visibility = View.VISIBLE
            binding.accessoryInHouseDpAmount.text = formatCurrency(sales.inHouseSales.downpayment)
            binding.accessoryInHouseBalanceAmount.text = formatCurrency(sales.inHouseSales.balance)
        } else {
            binding.accessoryInHouseDpRow.visibility = View.GONE
            binding.accessoryInHouseBalanceRow.visibility = View.GONE
        }
    }

    private fun displayServiceSummary(summary: ServiceSummary) {
        binding.serviceTotalFees.text = "Fees: ${formatCurrency(summary.totalFees)}"

        if (summary.cashIn.count > 0) {
            binding.serviceCashInRow.visibility = View.VISIBLE
            binding.serviceCashInCount.text = summary.cashIn.count.toString()
            binding.serviceCashInVolume.text = formatCurrencyShort(summary.cashIn.volume)
            binding.serviceCashInFees.text = formatCurrencyShort(summary.cashIn.fees)
        } else {
            binding.serviceCashInRow.visibility = View.GONE
        }

        if (summary.cashOut.count > 0) {
            binding.serviceCashOutRow.visibility = View.VISIBLE
            binding.serviceCashOutCount.text = summary.cashOut.count.toString()
            binding.serviceCashOutVolume.text = formatCurrencyShort(summary.cashOut.volume)
            binding.serviceCashOutFees.text = formatCurrencyShort(summary.cashOut.fees)
        } else {
            binding.serviceCashOutRow.visibility = View.GONE
        }

        if (summary.mobileLoading.count > 0) {
            binding.serviceMobileLoadRow.visibility = View.VISIBLE
            binding.serviceMobileLoadCount.text = summary.mobileLoading.count.toString()
            binding.serviceMobileLoadVolume.text = formatCurrencyShort(summary.mobileLoading.volume)
            binding.serviceMobileLoadFees.text = formatCurrencyShort(summary.mobileLoading.fees)
        } else {
            binding.serviceMobileLoadRow.visibility = View.GONE
        }

        if (summary.skyroPayment.count > 0) {
            binding.serviceSkyroRow.visibility = View.VISIBLE
            binding.serviceSkyroCount.text = summary.skyroPayment.count.toString()
            binding.serviceSkyroVolume.text = formatCurrencyShort(summary.skyroPayment.volume)
            binding.serviceSkyroFees.text = formatCurrencyShort(summary.skyroPayment.fees)
        } else {
            binding.serviceSkyroRow.visibility = View.GONE
        }

        if (summary.hcPayment.count > 0) {
            binding.serviceHcPaymentRow.visibility = View.VISIBLE
            binding.serviceHcPaymentCount.text = summary.hcPayment.count.toString()
            binding.serviceHcPaymentVolume.text = formatCurrencyShort(summary.hcPayment.volume)
            binding.serviceHcPaymentFees.text = formatCurrencyShort(summary.hcPayment.fees)
        } else {
            binding.serviceHcPaymentRow.visibility = View.GONE
        }

        if (summary.miscPayment.count > 0) {
            binding.serviceMiscRow.visibility = View.VISIBLE
            binding.serviceMiscCount.text = summary.miscPayment.count.toString()
            binding.serviceMiscVolume.text = formatCurrencyShort(summary.miscPayment.amount)
            binding.serviceMiscFees.text = formatCurrencyShort(0.0)
        } else {
            binding.serviceMiscRow.visibility = View.GONE
        }

        binding.serviceTotalVolume.text = formatCurrencyShort(summary.totalVolume)
        binding.serviceTotalFeesBottom.text = formatCurrencyShort(summary.totalFees)
    }

    private fun displayDeviceCashFlow(cashFlow: CashFlowSummary) {
        var hasAnyInflow = false
        var hasAnyReceivable = false

        // Calculate total cash flow (payment methods + receivables + subsidy = total sales)
        val totalCashFlow = cashFlow.cash + cashFlow.gcash + cashFlow.paymaya +
                cashFlow.bankTransfer + cashFlow.creditCard + cashFlow.others +
                cashFlow.hcReceivable + cashFlow.skyroReceivable + cashFlow.inHouseReceivable +
                cashFlow.brandZeroSubsidy
        binding.deviceCashFlowTotal.text = formatCurrency(totalCashFlow)

        if (cashFlow.cash > 0) {
            binding.deviceCfCashRow.visibility = View.VISIBLE
            binding.deviceCfCash.text = formatCurrency(cashFlow.cash)
            hasAnyInflow = true
        } else {
            binding.deviceCfCashRow.visibility = View.GONE
        }

        if (cashFlow.gcash > 0) {
            binding.deviceCfGcashRow.visibility = View.VISIBLE
            binding.deviceCfGcash.text = formatCurrency(cashFlow.gcash)
            hasAnyInflow = true
        } else {
            binding.deviceCfGcashRow.visibility = View.GONE
        }

        if (cashFlow.paymaya > 0) {
            binding.deviceCfPaymayaRow.visibility = View.VISIBLE
            binding.deviceCfPaymaya.text = formatCurrency(cashFlow.paymaya)
            hasAnyInflow = true
        } else {
            binding.deviceCfPaymayaRow.visibility = View.GONE
        }

        if (cashFlow.bankTransfer > 0) {
            binding.deviceCfBankRow.visibility = View.VISIBLE
            binding.deviceCfBank.text = formatCurrency(cashFlow.bankTransfer)
            hasAnyInflow = true
        } else {
            binding.deviceCfBankRow.visibility = View.GONE
        }

        if (cashFlow.creditCard > 0) {
            binding.deviceCfCreditCardRow.visibility = View.VISIBLE
            binding.deviceCfCreditCard.text = formatCurrency(cashFlow.creditCard)
            hasAnyInflow = true
        } else {
            binding.deviceCfCreditCardRow.visibility = View.GONE
        }

        if (cashFlow.others > 0) {
            binding.deviceCfOthersRow.visibility = View.VISIBLE
            binding.deviceCfOthers.text = formatCurrency(cashFlow.others)
            hasAnyInflow = true
        } else {
            binding.deviceCfOthersRow.visibility = View.GONE
        }

        if (cashFlow.hcReceivable > 0) {
            binding.deviceCfHcReceivableRow.visibility = View.VISIBLE
            binding.deviceCfHcReceivable.text = formatCurrency(cashFlow.hcReceivable)
            hasAnyReceivable = true
        } else {
            binding.deviceCfHcReceivableRow.visibility = View.GONE
        }

        if (cashFlow.skyroReceivable > 0) {
            binding.deviceCfSkyroReceivableRow.visibility = View.VISIBLE
            binding.deviceCfSkyroReceivable.text = formatCurrency(cashFlow.skyroReceivable)
            hasAnyReceivable = true
        } else {
            binding.deviceCfSkyroReceivableRow.visibility = View.GONE
        }

        if (cashFlow.inHouseReceivable > 0) {
            binding.deviceCfInHouseReceivableRow.visibility = View.VISIBLE
            binding.deviceCfInHouseReceivable.text = formatCurrency(cashFlow.inHouseReceivable)
            hasAnyReceivable = true
        } else {
            binding.deviceCfInHouseReceivableRow.visibility = View.GONE
        }

        if (cashFlow.brandZeroSubsidy > 0) {
            binding.deviceCfBrandZeroRow.visibility = View.VISIBLE
            binding.deviceCfBrandZero.text = formatCurrency(cashFlow.brandZeroSubsidy)
            hasAnyReceivable = true
        } else {
            binding.deviceCfBrandZeroRow.visibility = View.GONE
        }

        binding.deviceCfDivider.visibility = if (hasAnyInflow && hasAnyReceivable) View.VISIBLE else View.GONE
        binding.deviceCfEmptyText.visibility = if (!hasAnyInflow && !hasAnyReceivable) View.VISIBLE else View.GONE
    }

    private fun displayAccessoryCashFlow(cashFlow: CashFlowSummary) {
        var hasAnyInflow = false
        var hasAnyReceivable = false

        // Calculate total cash flow (payment methods + receivables + subsidy = total sales)
        val totalCashFlow = cashFlow.cash + cashFlow.gcash + cashFlow.paymaya +
                cashFlow.bankTransfer + cashFlow.creditCard + cashFlow.others +
                cashFlow.hcReceivable + cashFlow.skyroReceivable + cashFlow.inHouseReceivable +
                cashFlow.brandZeroSubsidy
        binding.accessoryCashFlowTotal.text = formatCurrency(totalCashFlow)

        if (cashFlow.cash > 0) {
            binding.accessoryCfCashRow.visibility = View.VISIBLE
            binding.accessoryCfCash.text = formatCurrency(cashFlow.cash)
            hasAnyInflow = true
        } else {
            binding.accessoryCfCashRow.visibility = View.GONE
        }

        if (cashFlow.gcash > 0) {
            binding.accessoryCfGcashRow.visibility = View.VISIBLE
            binding.accessoryCfGcash.text = formatCurrency(cashFlow.gcash)
            hasAnyInflow = true
        } else {
            binding.accessoryCfGcashRow.visibility = View.GONE
        }

        if (cashFlow.paymaya > 0) {
            binding.accessoryCfPaymayaRow.visibility = View.VISIBLE
            binding.accessoryCfPaymaya.text = formatCurrency(cashFlow.paymaya)
            hasAnyInflow = true
        } else {
            binding.accessoryCfPaymayaRow.visibility = View.GONE
        }

        if (cashFlow.bankTransfer > 0) {
            binding.accessoryCfBankRow.visibility = View.VISIBLE
            binding.accessoryCfBank.text = formatCurrency(cashFlow.bankTransfer)
            hasAnyInflow = true
        } else {
            binding.accessoryCfBankRow.visibility = View.GONE
        }

        if (cashFlow.creditCard > 0) {
            binding.accessoryCfCreditCardRow.visibility = View.VISIBLE
            binding.accessoryCfCreditCard.text = formatCurrency(cashFlow.creditCard)
            hasAnyInflow = true
        } else {
            binding.accessoryCfCreditCardRow.visibility = View.GONE
        }

        if (cashFlow.others > 0) {
            binding.accessoryCfOthersRow.visibility = View.VISIBLE
            binding.accessoryCfOthers.text = formatCurrency(cashFlow.others)
            hasAnyInflow = true
        } else {
            binding.accessoryCfOthersRow.visibility = View.GONE
        }

        if (cashFlow.hcReceivable > 0) {
            binding.accessoryCfHcReceivableRow.visibility = View.VISIBLE
            binding.accessoryCfHcReceivable.text = formatCurrency(cashFlow.hcReceivable)
            hasAnyReceivable = true
        } else {
            binding.accessoryCfHcReceivableRow.visibility = View.GONE
        }

        if (cashFlow.skyroReceivable > 0) {
            binding.accessoryCfSkyroReceivableRow.visibility = View.VISIBLE
            binding.accessoryCfSkyroReceivable.text = formatCurrency(cashFlow.skyroReceivable)
            hasAnyReceivable = true
        } else {
            binding.accessoryCfSkyroReceivableRow.visibility = View.GONE
        }

        if (cashFlow.inHouseReceivable > 0) {
            binding.accessoryCfInHouseReceivableRow.visibility = View.VISIBLE
            binding.accessoryCfInHouseReceivable.text = formatCurrency(cashFlow.inHouseReceivable)
            hasAnyReceivable = true
        } else {
            binding.accessoryCfInHouseReceivableRow.visibility = View.GONE
        }

        if (cashFlow.brandZeroSubsidy > 0) {
            binding.accessoryCfBrandZeroRow.visibility = View.VISIBLE
            binding.accessoryCfBrandZero.text = formatCurrency(cashFlow.brandZeroSubsidy)
            hasAnyReceivable = true
        } else {
            binding.accessoryCfBrandZeroRow.visibility = View.GONE
        }

        binding.accessoryCfDivider.visibility = if (hasAnyInflow && hasAnyReceivable) View.VISIBLE else View.GONE
        binding.accessoryCfEmptyText.visibility = if (!hasAnyInflow && !hasAnyReceivable) View.VISIBLE else View.GONE
    }

    private fun displayServiceCashFlow(cashFlow: ServiceCashFlowSummary) {
        var hasAnyData = false

        val cashTotal = cashFlow.cashInflow + cashFlow.cashOutflow
        if (cashTotal > 0) {
            binding.serviceCfCashRow.visibility = View.VISIBLE
            binding.serviceCfCashIn.text = formatCurrencyShort(cashFlow.cashInflow)
            binding.serviceCfCashOut.text = formatCurrencyShort(cashFlow.cashOutflow)
            val cashNet = cashFlow.cashInflow - cashFlow.cashOutflow
            binding.serviceCfCashNet.text = formatCurrencyShort(cashNet)
            setNetColor(binding.serviceCfCashNet, cashNet)
            hasAnyData = true
        } else {
            binding.serviceCfCashRow.visibility = View.GONE
        }

        val gcashTotal = cashFlow.gcashInflow + cashFlow.gcashOutflow
        if (gcashTotal > 0) {
            binding.serviceCfGcashRow.visibility = View.VISIBLE
            binding.serviceCfGcashIn.text = formatCurrencyShort(cashFlow.gcashInflow)
            binding.serviceCfGcashOut.text = formatCurrencyShort(cashFlow.gcashOutflow)
            val gcashNet = cashFlow.gcashInflow - cashFlow.gcashOutflow
            binding.serviceCfGcashNet.text = formatCurrencyShort(gcashNet)
            setNetColor(binding.serviceCfGcashNet, gcashNet)
            hasAnyData = true
        } else {
            binding.serviceCfGcashRow.visibility = View.GONE
        }

        val paymayaTotal = cashFlow.paymayaInflow + cashFlow.paymayaOutflow
        if (paymayaTotal > 0) {
            binding.serviceCfPaymayaRow.visibility = View.VISIBLE
            binding.serviceCfPaymayaIn.text = formatCurrencyShort(cashFlow.paymayaInflow)
            binding.serviceCfPaymayaOut.text = formatCurrencyShort(cashFlow.paymayaOutflow)
            val paymayaNet = cashFlow.paymayaInflow - cashFlow.paymayaOutflow
            binding.serviceCfPaymayaNet.text = formatCurrencyShort(paymayaNet)
            setNetColor(binding.serviceCfPaymayaNet, paymayaNet)
            hasAnyData = true
        } else {
            binding.serviceCfPaymayaRow.visibility = View.GONE
        }

        val othersTotal = cashFlow.othersInflow + cashFlow.othersOutflow
        if (othersTotal > 0) {
            binding.serviceCfOthersRow.visibility = View.VISIBLE
            binding.serviceCfOthersIn.text = formatCurrencyShort(cashFlow.othersInflow)
            binding.serviceCfOthersOut.text = formatCurrencyShort(cashFlow.othersOutflow)
            val othersNet = cashFlow.othersInflow - cashFlow.othersOutflow
            binding.serviceCfOthersNet.text = formatCurrencyShort(othersNet)
            setNetColor(binding.serviceCfOthersNet, othersNet)
            hasAnyData = true
        } else {
            binding.serviceCfOthersRow.visibility = View.GONE
        }

        binding.serviceCfEmptyText.visibility = if (!hasAnyData) View.VISIBLE else View.GONE
    }

    private fun displayDailySummary(data: DailySummaryData) {
        binding.summaryDeviceSales.text = formatCurrency(data.deviceSales.totalSales)
        binding.summaryAccessorySales.text = formatCurrency(data.accessorySales.totalSales)
        binding.summaryServiceFees.text = formatCurrency(data.totalServiceFees)
        binding.summaryMiscIncome.text = formatCurrency(data.totalMiscIncome)
        binding.summaryTotalRevenue.text = formatCurrency(data.totalRevenue)

        binding.generatedTimestamp.text = "Generated: ${data.generatedAt}"

        // Display Ledger Summary
        displayLedgerSummary(data.ledgerSummary)
    }

    private fun displayLedgerSummary(ledger: LedgerSummaryData) {
        var hasAnyLedger = false

        // Cash Ledger
        if (ledger.cash.total != 0.0) {
            hasAnyLedger = true
            binding.ledgerCashSection.visibility = View.VISIBLE
            binding.ledgerCashTotal.text = formatCurrency(ledger.cash.total)
            binding.ledgerCashTotalExpanded.text = formatCurrency(ledger.cash.total)
            setLedgerTotalColor(binding.ledgerCashTotal, ledger.cash.total)
            setLedgerTotalColor(binding.ledgerCashTotalExpanded, ledger.cash.total)

            // Show non-zero breakdown rows
            if (ledger.cash.device != 0.0) {
                binding.ledgerCashDeviceRow.visibility = View.VISIBLE
                binding.ledgerCashDevice.text = formatCurrency(ledger.cash.device)
            }
            if (ledger.cash.accessory != 0.0) {
                binding.ledgerCashAccessoryRow.visibility = View.VISIBLE
                binding.ledgerCashAccessory.text = formatCurrency(ledger.cash.accessory)
            }
            if (ledger.cash.service != 0.0) {
                binding.ledgerCashServiceRow.visibility = View.VISIBLE
                binding.ledgerCashService.text = formatCurrency(ledger.cash.service)
                setLedgerTotalColor(binding.ledgerCashService, ledger.cash.service)
            }

            setupLedgerExpandCollapse(
                binding.ledgerCashHeader,
                binding.ledgerCashDetails,
                binding.ledgerCashArrow
            )
        } else {
            binding.ledgerCashSection.visibility = View.GONE
        }

        // GCash Ledger
        if (ledger.gcash.total != 0.0) {
            hasAnyLedger = true
            binding.ledgerGcashSection.visibility = View.VISIBLE
            binding.ledgerGcashTotal.text = formatCurrency(ledger.gcash.total)
            binding.ledgerGcashTotalExpanded.text = formatCurrency(ledger.gcash.total)
            setLedgerTotalColor(binding.ledgerGcashTotal, ledger.gcash.total)
            setLedgerTotalColor(binding.ledgerGcashTotalExpanded, ledger.gcash.total)

            if (ledger.gcash.device != 0.0) {
                binding.ledgerGcashDeviceRow.visibility = View.VISIBLE
                binding.ledgerGcashDevice.text = formatCurrency(ledger.gcash.device)
            }
            if (ledger.gcash.accessory != 0.0) {
                binding.ledgerGcashAccessoryRow.visibility = View.VISIBLE
                binding.ledgerGcashAccessory.text = formatCurrency(ledger.gcash.accessory)
            }
            if (ledger.gcash.service != 0.0) {
                binding.ledgerGcashServiceRow.visibility = View.VISIBLE
                binding.ledgerGcashService.text = formatCurrency(ledger.gcash.service)
                setLedgerTotalColor(binding.ledgerGcashService, ledger.gcash.service)
            }

            setupLedgerExpandCollapse(
                binding.ledgerGcashHeader,
                binding.ledgerGcashDetails,
                binding.ledgerGcashArrow
            )
        } else {
            binding.ledgerGcashSection.visibility = View.GONE
        }

        // PayMaya Ledger
        if (ledger.paymaya.total != 0.0) {
            hasAnyLedger = true
            binding.ledgerPaymayaSection.visibility = View.VISIBLE
            binding.ledgerPaymayaTotal.text = formatCurrency(ledger.paymaya.total)
            binding.ledgerPaymayaTotalExpanded.text = formatCurrency(ledger.paymaya.total)
            setLedgerTotalColor(binding.ledgerPaymayaTotal, ledger.paymaya.total)
            setLedgerTotalColor(binding.ledgerPaymayaTotalExpanded, ledger.paymaya.total)

            if (ledger.paymaya.device != 0.0) {
                binding.ledgerPaymayaDeviceRow.visibility = View.VISIBLE
                binding.ledgerPaymayaDevice.text = formatCurrency(ledger.paymaya.device)
            }
            if (ledger.paymaya.accessory != 0.0) {
                binding.ledgerPaymayaAccessoryRow.visibility = View.VISIBLE
                binding.ledgerPaymayaAccessory.text = formatCurrency(ledger.paymaya.accessory)
            }
            if (ledger.paymaya.service != 0.0) {
                binding.ledgerPaymayaServiceRow.visibility = View.VISIBLE
                binding.ledgerPaymayaService.text = formatCurrency(ledger.paymaya.service)
                setLedgerTotalColor(binding.ledgerPaymayaService, ledger.paymaya.service)
            }

            setupLedgerExpandCollapse(
                binding.ledgerPaymayaHeader,
                binding.ledgerPaymayaDetails,
                binding.ledgerPaymayaArrow
            )
        } else {
            binding.ledgerPaymayaSection.visibility = View.GONE
        }

        // Bank Transfer Ledger
        if (ledger.bankTransfer.total != 0.0) {
            hasAnyLedger = true
            binding.ledgerBankSection.visibility = View.VISIBLE
            binding.ledgerBankTotal.text = formatCurrency(ledger.bankTransfer.total)
            binding.ledgerBankTotalExpanded.text = formatCurrency(ledger.bankTransfer.total)

            if (ledger.bankTransfer.device != 0.0) {
                binding.ledgerBankDeviceRow.visibility = View.VISIBLE
                binding.ledgerBankDevice.text = formatCurrency(ledger.bankTransfer.device)
            }
            if (ledger.bankTransfer.accessory != 0.0) {
                binding.ledgerBankAccessoryRow.visibility = View.VISIBLE
                binding.ledgerBankAccessory.text = formatCurrency(ledger.bankTransfer.accessory)
            }
            if (ledger.bankTransfer.service != 0.0) {
                binding.ledgerBankServiceRow.visibility = View.VISIBLE
                binding.ledgerBankService.text = formatCurrency(ledger.bankTransfer.service)
                setLedgerTotalColor(binding.ledgerBankService, ledger.bankTransfer.service)
            }

            setupLedgerExpandCollapse(
                binding.ledgerBankHeader,
                binding.ledgerBankDetails,
                binding.ledgerBankArrow
            )
        } else {
            binding.ledgerBankSection.visibility = View.GONE
        }

        // Others Ledger
        if (ledger.others.total != 0.0) {
            hasAnyLedger = true
            binding.ledgerOthersSection.visibility = View.VISIBLE
            binding.ledgerOthersTotal.text = formatCurrency(ledger.others.total)
            binding.ledgerOthersTotalExpanded.text = formatCurrency(ledger.others.total)
            setLedgerTotalColor(binding.ledgerOthersTotal, ledger.others.total)
            setLedgerTotalColor(binding.ledgerOthersTotalExpanded, ledger.others.total)

            if (ledger.others.device != 0.0) {
                binding.ledgerOthersDeviceRow.visibility = View.VISIBLE
                binding.ledgerOthersDevice.text = formatCurrency(ledger.others.device)
            }
            if (ledger.others.accessory != 0.0) {
                binding.ledgerOthersAccessoryRow.visibility = View.VISIBLE
                binding.ledgerOthersAccessory.text = formatCurrency(ledger.others.accessory)
            }
            if (ledger.others.service != 0.0) {
                binding.ledgerOthersServiceRow.visibility = View.VISIBLE
                binding.ledgerOthersService.text = formatCurrency(ledger.others.service)
                setLedgerTotalColor(binding.ledgerOthersService, ledger.others.service)
            }

            setupLedgerExpandCollapse(
                binding.ledgerOthersHeader,
                binding.ledgerOthersDetails,
                binding.ledgerOthersArrow
            )
        } else {
            binding.ledgerOthersSection.visibility = View.GONE
        }

        // Receivables Ledger
        if (ledger.receivables.total != 0.0) {
            hasAnyLedger = true
            binding.ledgerReceivablesSection.visibility = View.VISIBLE
            binding.ledgerReceivablesTotal.text = formatCurrency(ledger.receivables.total)
            binding.ledgerReceivablesTotalExpanded.text = formatCurrency(ledger.receivables.total)

            if (ledger.receivables.homeCredit != 0.0) {
                binding.ledgerReceivablesHcRow.visibility = View.VISIBLE
                binding.ledgerReceivablesHc.text = formatCurrency(ledger.receivables.homeCredit)
            }
            if (ledger.receivables.skyro != 0.0) {
                binding.ledgerReceivablesSkyroRow.visibility = View.VISIBLE
                binding.ledgerReceivablesSkyro.text = formatCurrency(ledger.receivables.skyro)
            }
            if (ledger.receivables.inHouse != 0.0) {
                binding.ledgerReceivablesInHouseRow.visibility = View.VISIBLE
                binding.ledgerReceivablesInHouse.text = formatCurrency(ledger.receivables.inHouse)
            }

            setupLedgerExpandCollapse(
                binding.ledgerReceivablesHeader,
                binding.ledgerReceivablesDetails,
                binding.ledgerReceivablesArrow
            )
        } else {
            binding.ledgerReceivablesSection.visibility = View.GONE
        }

        // Show/hide empty state
        binding.ledgerEmptyText.visibility = if (hasAnyLedger) View.GONE else View.VISIBLE
    }

    private fun setupLedgerExpandCollapse(
        header: View,
        details: View,
        arrow: android.widget.ImageView
    ) {
        header.setOnClickListener {
            if (details.visibility == View.VISIBLE) {
                details.visibility = View.GONE
                arrow.animate().rotation(0f).setDuration(200).start()
            } else {
                details.visibility = View.VISIBLE
                arrow.animate().rotation(90f).setDuration(200).start()
            }
        }
    }

    private fun setLedgerTotalColor(textView: android.widget.TextView, amount: Double) {
        val color = when {
            amount < 0 -> ContextCompat.getColor(this, R.color.red)
            else -> textView.currentTextColor // Keep original color
        }
        if (amount < 0) {
            textView.setTextColor(color)
        }
    }

    private fun setNetColor(textView: android.widget.TextView, amount: Double) {
        val color = when {
            amount > 0 -> ContextCompat.getColor(this, R.color.cash_dark_green)
            amount < 0 -> ContextCompat.getColor(this, R.color.red)
            else -> ContextCompat.getColor(this, R.color.black)
        }
        textView.setTextColor(color)
    }

    // ============================================================================
    // END OF PART 7: DISPLAY METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 8: SAVE TO FIREBASE
    // ============================================================================

    private fun saveReport() {
        val data = reportData ?: return

        AlertDialog.Builder(this)
            .setTitle("Save Report")
            .setMessage("Save End of Day report for ${data.displayDate}?\n\nThis will overwrite any existing report for this date.")
            .setPositiveButton("Save") { _, _ ->
                performSave(data)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performSave(data: DailySummaryData) {
        binding.progressBar.visibility = View.VISIBLE
        binding.saveButton.isEnabled = false

        scope.launch {
            try {
                val documentData = hashMapOf(
                    "date" to data.date,
                    "displayDate" to data.displayDate,
                    "generatedAt" to data.generatedAt,
                    "generatedBy" to data.generatedBy,
                    "timestamp" to FieldValue.serverTimestamp(),

                    "transactionCounts" to hashMapOf(
                        "devices" to data.deviceCount,
                        "accessories" to data.accessoryCount,
                        "services" to data.serviceCount,
                        "total" to (data.deviceCount + data.accessoryCount + data.serviceCount)
                    ),

                    "transactionIds" to hashMapOf(
                        "devices" to data.deviceTransactionIds,
                        "accessories" to data.accessoryTransactionIds,
                        "services" to data.serviceTransactionIds
                    ),

                    "salesSummary" to hashMapOf(
                        "devices" to hashMapOf(
                            "totalSales" to data.deviceSales.totalSales,
                            "cash" to hashMapOf("count" to data.deviceSales.cashSales.count, "amount" to data.deviceSales.cashSales.amount),
                            "homeCredit" to hashMapOf("count" to data.deviceSales.homeCreditSales.count, "amount" to data.deviceSales.homeCreditSales.amount, "downpayment" to data.deviceSales.homeCreditSales.downpayment, "balance" to data.deviceSales.homeCreditSales.balance),
                            "skyro" to hashMapOf("count" to data.deviceSales.skyroSales.count, "amount" to data.deviceSales.skyroSales.amount, "downpayment" to data.deviceSales.skyroSales.downpayment, "balance" to data.deviceSales.skyroSales.balance),
                            "inHouse" to hashMapOf("count" to data.deviceSales.inHouseSales.count, "amount" to data.deviceSales.inHouseSales.amount, "downpayment" to data.deviceSales.inHouseSales.downpayment, "balance" to data.deviceSales.inHouseSales.balance)
                        ),
                        "accessories" to hashMapOf(
                            "totalSales" to data.accessorySales.totalSales,
                            "cash" to hashMapOf("count" to data.accessorySales.cashSales.count, "amount" to data.accessorySales.cashSales.amount),
                            "homeCredit" to hashMapOf("count" to data.accessorySales.homeCreditSales.count, "amount" to data.accessorySales.homeCreditSales.amount, "downpayment" to data.accessorySales.homeCreditSales.downpayment, "balance" to data.accessorySales.homeCreditSales.balance),
                            "skyro" to hashMapOf("count" to data.accessorySales.skyroSales.count, "amount" to data.accessorySales.skyroSales.amount, "downpayment" to data.accessorySales.skyroSales.downpayment, "balance" to data.accessorySales.skyroSales.balance),
                            "inHouse" to hashMapOf("count" to data.accessorySales.inHouseSales.count, "amount" to data.accessorySales.inHouseSales.amount, "downpayment" to data.accessorySales.inHouseSales.downpayment, "balance" to data.accessorySales.inHouseSales.balance)
                        ),
                        "services" to hashMapOf(
                            "totalFees" to data.serviceSummary.totalFees,
                            "totalVolume" to data.serviceSummary.totalVolume
                        )
                    ),

                    "cashFlowSummary" to hashMapOf(
                        "devices" to hashMapOf("cash" to data.deviceCashFlow.cash, "gcash" to data.deviceCashFlow.gcash, "paymaya" to data.deviceCashFlow.paymaya, "bankTransfer" to data.deviceCashFlow.bankTransfer, "creditCard" to data.deviceCashFlow.creditCard, "others" to data.deviceCashFlow.others, "hcReceivable" to data.deviceCashFlow.hcReceivable, "skyroReceivable" to data.deviceCashFlow.skyroReceivable, "inHouseReceivable" to data.deviceCashFlow.inHouseReceivable),
                        "accessories" to hashMapOf("cash" to data.accessoryCashFlow.cash, "gcash" to data.accessoryCashFlow.gcash, "paymaya" to data.accessoryCashFlow.paymaya, "bankTransfer" to data.accessoryCashFlow.bankTransfer, "creditCard" to data.accessoryCashFlow.creditCard, "others" to data.accessoryCashFlow.others, "hcReceivable" to data.accessoryCashFlow.hcReceivable, "skyroReceivable" to data.accessoryCashFlow.skyroReceivable, "inHouseReceivable" to data.accessoryCashFlow.inHouseReceivable),
                        "services" to hashMapOf("cashInflow" to data.serviceCashFlow.cashInflow, "cashOutflow" to data.serviceCashFlow.cashOutflow, "gcashInflow" to data.serviceCashFlow.gcashInflow, "gcashOutflow" to data.serviceCashFlow.gcashOutflow, "paymayaInflow" to data.serviceCashFlow.paymayaInflow, "paymayaOutflow" to data.serviceCashFlow.paymayaOutflow, "othersInflow" to data.serviceCashFlow.othersInflow, "othersOutflow" to data.serviceCashFlow.othersOutflow)
                    ),

                    "grandTotals" to hashMapOf(
                        "deviceSales" to data.deviceSales.totalSales,
                        "accessorySales" to data.accessorySales.totalSales,
                        "productSales" to data.totalProductSales,
                        "serviceFees" to data.totalServiceFees,
                        "miscIncome" to data.totalMiscIncome,
                        "totalRevenue" to data.totalRevenue,
                        "receivablesCreated" to data.totalReceivablesCreated
                    ),

                    "revenueBreakdown" to hashMapOf(
                        "cash" to data.revenueCash,
                        "gcash" to data.revenueGcash,
                        "paymaya" to data.revenuePaymaya,
                        "bankTransfer" to data.revenueBankTransfer,
                        "creditCard" to data.revenueCreditCard,
                        "others" to data.revenueOthers,
                        "receivables" to data.totalReceivablesCreated
                    )
                )

                withContext(Dispatchers.IO) {
                    db.collection(COLLECTION_DAILY_SUMMARIES)
                        .document(data.date)
                        .set(documentData)
                        .await()
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.saveButton.isEnabled = true
                    showMessage("Report saved successfully", false)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.saveButton.isEnabled = true
                    showMessage("Error saving report: ${e.message}", true)
                }
            }
        }
    }

    // ============================================================================
    // END OF PART 8: SAVE TO FIREBASE
    // ============================================================================


    // ============================================================================
    // START OF PART 9: UTILITY METHODS
    // ============================================================================

    private fun getCurrentDatePhilippines(): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila"))
        return SimpleDateFormat("M/d/yyyy", Locale.US).format(calendar.time)
    }

    private fun convertDisplayDateToQueryDate(displayDate: String): String {
        return try {
            val inputFormat = SimpleDateFormat("M/d/yyyy", Locale.US)
            val outputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val date = inputFormat.parse(displayDate)
            outputFormat.format(date!!)
        } catch (e: Exception) {
            displayDate
        }
    }

    private fun formatCurrency(amount: Double): String {
        val format = NumberFormat.getCurrencyInstance(Locale("en", "PH"))
        return format.format(amount)
    }

    private fun formatCurrencyShort(amount: Double): String {
        return if (amount == 0.0) {
            "₱0"
        } else {
            val format = NumberFormat.getNumberInstance(Locale.US)
            format.maximumFractionDigits = 0
            "₱${format.format(amount)}"
        }
    }

    private fun showMessage(message: String, isError: Boolean) {
        Toast.makeText(this, message, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    // ============================================================================
    // END OF PART 9: UTILITY METHODS
    // ============================================================================
}