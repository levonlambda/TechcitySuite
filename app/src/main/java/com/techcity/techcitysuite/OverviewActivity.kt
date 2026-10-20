package com.techcity.techcitysuite

import android.app.DatePickerDialog
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityOverviewBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.min

/**
 * Overview module: cash on hand and sales (daily / monthly / yearly) for every branch.
 *
 * Daily figures and today's cash are computed live from transactions with
 * DailySummaryCalculator (the same math as the End of Day report). Monthly, yearly and the
 * Running cash on hand are summed from the saved End of Day documents (daily_summaries).
 */
class OverviewActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityOverviewBinding
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val db: FirebaseFirestore = Firebase.firestore

    // Active store locations and the primary branch name (legacy records fall back to it)
    private var locations: List<StoreLocationHelper.StoreLocationOption> = emptyList()
    private var primaryName: String = ""

    // Periods (yyyy-MM-dd / yyyy-MM, Asia/Manila)
    private var todayDate: String = ""
    private var dailyDate: String = ""
    private var monthlyMonth: String = ""

    // Today's live figures per branch (shared by Daily, Monthly, Yearly and Cash on Hand).
    // Kept current by snapshot listeners on today's device, accessory and service transactions.
    private var todayLiveSales: Map<String, SalesFigures> = emptyMap()
    private var todayLiveCash: Map<String, Double> = emptyMap()
    private var todayLiveLoaded = false
    private val todayListeners = mutableListOf<ListenerRegistration>()
    private var todayDeviceTx: List<Map<String, Any?>> = emptyList()
    private var todayAccessoryTx: List<Map<String, Any?>> = emptyList()
    private var todayServiceTx: List<Map<String, Any?>> = emptyList()

    // Figures currently shown in each period block of the branch cards, and each section's state
    private var dailyFigures: Map<String, SalesFigures> = emptyMap()
    private var monthlyFigures: Map<String, SalesFigures> = emptyMap()
    private var yearlyFigures: Map<String, SalesFigures> = emptyMap()
    private val sectionStates = mutableMapOf(
        Section.CASH to SectionState.LOADING,
        Section.DAILY to SectionState.LOADING,
        Section.MONTHLY to SectionState.LOADING,
        Section.YEARLY to SectionState.LOADING
    )

    // Which period block is expanded in each branch card: at most one at a time, Daily by
    // default, null when the user collapsed the open one (so all three can be collapsed)
    private val expandedByBranch = mutableMapOf<String, Section?>()

    // End of Day documents covering [summaryCacheStart, yesterday]
    private var summaryCache: List<Map<String, Any?>> = emptyList()
    private var summaryCacheStart: String = ""
    private var summaryCacheLoaded = false

    // Latest retrieval per branch and the resulting Running figures
    private var latestRetrievals: Map<String, CashRetrieval?> = emptyMap()
    private var runningByBranch: Map<String, RunningResult> = emptyMap()

    // Section jobs so a re-fetch cancels the previous one
    private var mainJob: Job? = null
    private var dailyJob: Job? = null
    private var monthlyJob: Job? = null

    // Card currently swiped left (animated back when the dialogs close)
    private var swipedCard: View? = null

    /**
     * Sale amounts per transaction type, reduced from DailySummaryCalculator.SalesSummary.
     * Each financing figure is the sale amount (the "Home Credit Sale" line of End of Day).
     */
    data class SalesFigures(
        val cash: Double = 0.0,
        val homeCredit: Double = 0.0,
        val skyro: Double = 0.0,
        val salmon: Double = 0.0,
        val inHouse: Double = 0.0
    ) {
        /** Total sales for the period, the same sum End of Day shows as the card total. */
        val total: Double
            get() = cash + homeCredit + skyro + salmon + inHouse

        operator fun plus(other: SalesFigures) = SalesFigures(
            cash + other.cash,
            homeCredit + other.homeCredit,
            skyro + other.skyro,
            salmon + other.salmon,
            inHouse + other.inHouse
        )

        companion object {
            val ZERO = SalesFigures()

            fun from(summary: DailySummaryCalculator.SalesSummary) = SalesFigures(
                cash = summary.cashSales.amount,
                homeCredit = summary.homeCreditSales.amount,
                skyro = summary.skyroSales.amount,
                salmon = summary.salmonSales.amount,
                inHouse = summary.inHouseSales.amount
            )
        }
    }

    /** Per-branch totals summed from End of Day documents over a date range. */
    data class SummaryTotals(
        val sales: SalesFigures,
        val cash: Double,
        val missingDays: Int
    )

    /** Running cash on hand for one branch. rangeStart > rangeEnd means an empty range. */
    data class RunningResult(
        val amount: Double,
        val rangeStart: String,
        val rangeEnd: String,
        val missingDays: Int
    ) {
        val isEmpty: Boolean get() = rangeStart > rangeEnd
    }

    private enum class Section { CASH, DAILY, MONTHLY, YEARLY }
    private enum class SectionState { LOADING, CONTENT, ERROR }

    companion object {
        private const val SWIPE_THRESHOLD = 0.4f
        private const val LOAD_ERROR_MESSAGE = "Could not load. Check your connection and try again."
        private const val NO_LOCATIONS_MESSAGE = "No store locations found"
    }

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================


    // ============================================================================
    // START OF PART 2: LIFECYCLE METHODS
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOverviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        todayDate = today()
        dailyDate = todayDate
        monthlyMonth = currentMonth()

        binding.backButton.setOnClickListener { finish() }
        binding.refreshButton.setOnClickListener { loadAll() }
        binding.dailyDateButton.setOnClickListener { showDailyDatePicker() }
        binding.monthlyMonthButton.setOnClickListener { showMonthPicker() }

        binding.screenRetry.setOnClickListener { loadAll() }
        binding.cashRetry.setOnClickListener { retrySection(Section.CASH) }
        binding.dailyRetry.setOnClickListener { retrySection(Section.DAILY) }
        binding.monthlyRetry.setOnClickListener { retrySection(Section.MONTHLY) }
        binding.yearlyRetry.setOnClickListener { retrySection(Section.YEARLY) }

        updatePeriodLabels()
        loadAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTodayListeners()
        scope.cancel()
    }

    // ============================================================================
    // END OF PART 2: LIFECYCLE METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 3: LOADING ORCHESTRATION
    // ============================================================================

    /**
     * Full load: locations, today's live transactions, retrievals and End of Day documents.
     */
    private fun loadAll() {
        mainJob?.cancel()
        dailyJob?.cancel()
        monthlyJob?.cancel()
        todayLiveLoaded = false
        summaryCacheLoaded = false
        binding.screenErrorRow.visibility = View.GONE
        Section.values().forEach { setSectionState(it, SectionState.LOADING) }

        mainJob = scope.launch {
            // 1. Locations (needed by every section)
            try {
                loadLocations()
            } catch (e: Exception) {
                e.printStackTrace()
                showScreenError(LOAD_ERROR_MESSAGE)
                return@launch
            }
            if (locations.isEmpty()) {
                showScreenError(NO_LOCATIONS_MESSAGE)
                return@launch
            }

            // 2. Today's live figures
            val liveOk = try {
                loadTodayLive()
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }

            if (dailyDate == todayDate) {
                if (liveOk) renderDaily(todayLiveSales) else setSectionState(Section.DAILY, SectionState.ERROR)
            } else {
                loadDaily()
            }

            if (!liveOk) {
                setSectionState(Section.MONTHLY, SectionState.ERROR)
                setSectionState(Section.YEARLY, SectionState.ERROR)
                setSectionState(Section.CASH, SectionState.ERROR)
                return@launch
            }

            // 3. Retrievals + End of Day documents → Cash on Hand, Yearly, Monthly (current month)
            loadSummariesAndRender()

            // 4. A past month has its own query
            if (monthlyMonth != currentMonth()) {
                loadMonthly()
            }
        }
    }

    /**
     * Re-run only what the section needs. Falls back to a full load when the shared
     * prerequisites (locations, today's live figures) are missing.
     */
    private fun retrySection(section: Section) {
        if (locations.isEmpty() || !todayLiveLoaded) {
            loadAll()
            return
        }
        when (section) {
            Section.DAILY -> if (dailyDate == todayDate) renderDaily(todayLiveSales) else loadDaily()
            Section.MONTHLY -> if (monthlyMonth == currentMonth()) {
                if (summaryCacheLoaded) renderMonthlyFromCache() else scope.launch { loadSummariesAndRender() }
            } else {
                loadMonthly()
            }
            Section.YEARLY, Section.CASH -> scope.launch { loadSummariesAndRender() }
        }
    }

    private suspend fun loadLocations() {
        val list = withContext(Dispatchers.IO) { StoreLocationHelper.loadActiveLocations(db) }
        locations = list.sortedBy { StoreLocationHelper.getDisplayName(it.name).lowercase() }
        primaryName = list.firstOrNull { it.isPrimary }?.name ?: ""
    }

    /**
     * Attach snapshot listeners to today's completed device, accessory and service
     * transactions and suspend until each has delivered its first result. Later snapshots
     * recompute today's figures and refresh the blocks that include today.
     */
    private suspend fun loadTodayLive() {
        stopTodayListeners()
        val collections = listOf(
            AppConstants.COLLECTION_DEVICE_TRANSACTIONS,
            AppConstants.COLLECTION_ACCESSORY_TRANSACTIONS,
            "service_transactions"
        )
        val pending = collections.toMutableSet()
        val firstSnapshots = CompletableDeferred<Unit>()

        for (collection in collections) {
            val registration = db.collection(collection)
                .whereEqualTo("date", todayDate)
                .whereEqualTo("status", AppConstants.STATUS_COMPLETED)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) {
                        error?.printStackTrace()
                        if (!firstSnapshots.isCompleted) {
                            firstSnapshots.completeExceptionally(error ?: IllegalStateException("No snapshot"))
                        }
                        return@addSnapshotListener
                    }
                    val docs = snapshot.documents.map { doc ->
                        val data = doc.data?.toMutableMap() ?: mutableMapOf()
                        data["_id"] = doc.id
                        data as Map<String, Any?>
                    }
                    when (collection) {
                        AppConstants.COLLECTION_DEVICE_TRANSACTIONS -> todayDeviceTx = docs
                        AppConstants.COLLECTION_ACCESSORY_TRANSACTIONS -> todayAccessoryTx = docs
                        else -> todayServiceTx = docs
                    }
                    pending.remove(collection)
                    if (pending.isNotEmpty()) return@addSnapshotListener

                    recomputeTodayLive()
                    if (!firstSnapshots.isCompleted) firstSnapshots.complete(Unit) else onTodayLiveChanged()
                }
            todayListeners.add(registration)
        }

        firstSnapshots.await()
        todayLiveLoaded = true
    }

    private fun stopTodayListeners() {
        todayListeners.forEach { it.remove() }
        todayListeners.clear()
    }

    private fun recomputeTodayLive() {
        todayLiveSales = aggregateSalesByBranch(todayDeviceTx, todayAccessoryTx)
        todayLiveCash = aggregateCashByBranch(todayDeviceTx, todayAccessoryTx, todayServiceTx)
    }

    /** A later snapshot changed today's figures: refresh every block that includes today. */
    private fun onTodayLiveChanged() {
        if (!todayLiveLoaded) return
        if (dailyDate == todayDate) renderDaily(todayLiveSales)
        if (summaryCacheLoaded) {
            renderYearly()
            if (monthlyMonth == currentMonth()) renderMonthlyFromCache()
        }
        if (sectionStates[Section.CASH] == SectionState.CONTENT) renderCashOnHand()
    }

    /**
     * Fetch each branch's latest retrieval and the End of Day documents covering the union of
     * the Yearly range and every Running range, then render Cash on Hand, Yearly and (for the
     * current month) Monthly from that single result.
     */
    private suspend fun loadSummariesAndRender() {
        setSectionState(Section.CASH, SectionState.LOADING)
        setSectionState(Section.YEARLY, SectionState.LOADING)
        if (monthlyMonth == currentMonth()) setSectionState(Section.MONTHLY, SectionState.LOADING)

        try {
            loadLatestRetrievals()

            val yesterday = yesterday()
            val runningStarts = runningByBranch.values.map { it.rangeStart }
            val unionStart = (runningStarts + firstDayOfYear()).minOrNull() ?: firstDayOfYear()

            summaryCache = if (unionStart <= yesterday) {
                withContext(Dispatchers.IO) { fetchDailySummaries(unionStart, yesterday) }
            } else {
                emptyList()
            }
            summaryCacheStart = unionStart
            summaryCacheLoaded = true

            computeRunning()
            renderCashOnHand()
            renderYearly()
            if (monthlyMonth == currentMonth()) renderMonthlyFromCache()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            setSectionState(Section.CASH, SectionState.ERROR)
            setSectionState(Section.YEARLY, SectionState.ERROR)
            if (monthlyMonth == currentMonth()) setSectionState(Section.MONTHLY, SectionState.ERROR)
        }
    }

    private suspend fun loadLatestRetrievals() {
        val results = withContext(Dispatchers.IO) {
            locations.map { location ->
                async { location.name to fetchLatestRetrieval(location.name) }
            }.awaitAll()
        }
        latestRetrievals = results.toMap()

        val yesterday = yesterday()
        runningByBranch = locations.associate { location ->
            val last = latestRetrievals[location.name]
            val start = if (last != null && last.rangeEnd.isNotBlank()) {
                maxDate(AppConstants.OVERVIEW_RUNNING_START_DATE, dayAfter(last.rangeEnd))
            } else {
                AppConstants.OVERVIEW_RUNNING_START_DATE
            }
            location.name to RunningResult(0.0, start, yesterday, 0)
        }
    }

    /** Daily section for a date other than today (device + accessory only). */
    private fun loadDaily() {
        dailyJob?.cancel()
        setSectionState(Section.DAILY, SectionState.LOADING)
        val date = dailyDate
        dailyJob = scope.launch {
            try {
                val (deviceTx, accessoryTx) = withContext(Dispatchers.IO) {
                    val d = async { fetchTransactions(AppConstants.COLLECTION_DEVICE_TRANSACTIONS, date) }
                    val a = async { fetchTransactions(AppConstants.COLLECTION_ACCESSORY_TRANSACTIONS, date) }
                    d.await() to a.await()
                }
                if (date != dailyDate) return@launch  // picker moved on while loading
                renderDaily(aggregateSalesByBranch(deviceTx, accessoryTx))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                setSectionState(Section.DAILY, SectionState.ERROR)
            }
        }
    }

    /** Monthly section for a past month (own End of Day range query). */
    private fun loadMonthly() {
        monthlyJob?.cancel()
        setSectionState(Section.MONTHLY, SectionState.LOADING)
        val month = monthlyMonth
        monthlyJob = scope.launch {
            try {
                val start = firstDayOfMonth(month)
                val end = lastDayOfMonth(month)
                val docs = withContext(Dispatchers.IO) { fetchDailySummaries(start, end) }
                if (month != monthlyMonth) return@launch
                val totals = aggregateSummaries(docs, start, end)
                renderPeriodSection(Section.MONTHLY, totals, includeToday = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                setSectionState(Section.MONTHLY, SectionState.ERROR)
            }
        }
    }

    // ============================================================================
    // END OF PART 3: LOADING ORCHESTRATION
    // ============================================================================


    // ============================================================================
    // START OF PART 4: FIRESTORE FETCHERS
    // ============================================================================

    /** Completed transactions for one date across all branches. */
    private suspend fun fetchTransactions(collection: String, date: String): List<Map<String, Any?>> {
        val snapshot = db.collection(collection)
            .whereEqualTo("date", date)
            .whereEqualTo("status", AppConstants.STATUS_COMPLETED)
            .get()
            .await()
        return snapshot.documents.map { doc ->
            val data = doc.data?.toMutableMap() ?: mutableMapOf()
            data["_id"] = doc.id
            data
        }
    }

    /** End of Day documents for all branches with date in [startDate, endDate]. */
    private suspend fun fetchDailySummaries(startDate: String, endDate: String): List<Map<String, Any?>> {
        val snapshot = db.collection(AppConstants.COLLECTION_DAILY_SUMMARIES)
            .whereGreaterThanOrEqualTo("date", startDate)
            .whereLessThanOrEqualTo("date", endDate)
            .get()
            .await()
        return snapshot.documents.mapNotNull { doc ->
            val data = doc.data?.toMutableMap() ?: return@mapNotNull null
            data["_id"] = doc.id
            data
        }
    }

    /** Latest retrieval for a branch: greatest rangeEnd, ties broken by recordedAt. */
    private suspend fun fetchLatestRetrieval(storeLocation: String): CashRetrieval? {
        val snapshot = db.collection(AppConstants.COLLECTION_CASH_RETRIEVALS)
            .whereEqualTo("storeLocation", storeLocation)
            .get()
            .await()
        return snapshot.documents
            .map { CashRetrieval.fromDocument(it) }
            .maxWithOrNull(compareBy<CashRetrieval> { it.rangeEnd }.thenBy { it.recordedAt?.seconds ?: 0L })
    }

    // ============================================================================
    // END OF PART 4: FIRESTORE FETCHERS
    // ============================================================================


    // ============================================================================
    // START OF PART 5: AGGREGATION
    // ============================================================================

    private fun branchKey(location: String?): String {
        return if (location.isNullOrBlank()) primaryName else location
    }

    private fun aggregateSalesByBranch(
        deviceTx: List<Map<String, Any?>>,
        accessoryTx: List<Map<String, Any?>>
    ): Map<String, SalesFigures> {
        val result = mutableMapOf<String, SalesFigures>()
        deviceTx.groupBy { branchKey(it["userLocation"] as? String) }.forEach { (branch, list) ->
            val figures = SalesFigures.from(DailySummaryCalculator.processDeviceSales(list))
            result[branch] = (result[branch] ?: SalesFigures.ZERO) + figures
        }
        accessoryTx.groupBy { branchKey(it["userLocation"] as? String) }.forEach { (branch, list) ->
            val figures = SalesFigures.from(DailySummaryCalculator.processAccessorySales(list))
            result[branch] = (result[branch] ?: SalesFigures.ZERO) + figures
        }
        return result
    }

    /**
     * Cash ledger line per branch: device cash + accessory cash + service cash in − cash out.
     * Matches the End of Day daily ledger summary "Cash" total.
     */
    private fun aggregateCashByBranch(
        deviceTx: List<Map<String, Any?>>,
        accessoryTx: List<Map<String, Any?>>,
        serviceTx: List<Map<String, Any?>>
    ): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        deviceTx.groupBy { branchKey(it["userLocation"] as? String) }.forEach { (branch, list) ->
            result[branch] = (result[branch] ?: 0.0) + DailySummaryCalculator.processDeviceCashFlow(list).cash
        }
        accessoryTx.groupBy { branchKey(it["userLocation"] as? String) }.forEach { (branch, list) ->
            result[branch] = (result[branch] ?: 0.0) + DailySummaryCalculator.processAccessoryCashFlow(list).cash
        }
        serviceTx.groupBy { branchKey(it["userLocation"] as? String) }.forEach { (branch, list) ->
            val flow = DailySummaryCalculator.processServiceCashFlow(list)
            result[branch] = (result[branch] ?: 0.0) + (flow.cashInflow - flow.cashOutflow)
        }
        return result
    }

    /** Sales figures from one saved End of Day document (devices + accessories). */
    private fun salesFromSummary(doc: Map<String, Any?>): SalesFigures {
        val salesSummary = doc["salesSummary"] as? Map<*, *>
        val devices = DailySummaryCalculator.parseSalesSummary(salesSummary?.get("devices") as? Map<*, *>)
        val accessories = DailySummaryCalculator.parseSalesSummary(salesSummary?.get("accessories") as? Map<*, *>)
        return SalesFigures.from(devices) + SalesFigures.from(accessories)
    }

    /** Cash ledger line from one saved End of Day document. */
    private fun cashFromSummary(doc: Map<String, Any?>): Double {
        val cashFlow = doc["cashFlowSummary"] as? Map<*, *>
        val devices = DailySummaryCalculator.parseCashFlowSummary(cashFlow?.get("devices") as? Map<*, *>)
        val accessories = DailySummaryCalculator.parseCashFlowSummary(cashFlow?.get("accessories") as? Map<*, *>)
        val services = DailySummaryCalculator.parseServiceCashFlowSummary(cashFlow?.get("services") as? Map<*, *>)
        return devices.cash + accessories.cash + (services.cashInflow - services.cashOutflow)
    }

    /**
     * Sum End of Day documents per branch over [startDate, endDate] (or a per-branch start).
     * Legacy documents (no storeLocation) count for the primary branch unless a per-store
     * document exists for the same date. Missing days are counted per branch.
     */
    private fun aggregateSummaries(
        docs: List<Map<String, Any?>>,
        startDate: String,
        endDate: String,
        startFor: (String) -> String = { startDate }
    ): Map<String, SummaryTotals> {
        // (branch, date) -> doc, preferring per-store documents over legacy ones
        val byBranchDate = mutableMapOf<Pair<String, String>, Map<String, Any?>>()
        val legacy = mutableListOf<Map<String, Any?>>()
        for (doc in docs) {
            val date = doc["date"] as? String ?: continue
            val location = doc["storeLocation"] as? String
            if (location.isNullOrBlank()) {
                legacy.add(doc)
            } else {
                byBranchDate[location to date] = doc
            }
        }
        for (doc in legacy) {
            val date = doc["date"] as? String ?: continue
            val key = primaryName to date
            if (!byBranchDate.containsKey(key)) byBranchDate[key] = doc
        }

        val sales = mutableMapOf<String, SalesFigures>()
        val cash = mutableMapOf<String, Double>()
        val datesSeen = mutableMapOf<String, MutableSet<String>>()
        for ((key, doc) in byBranchDate) {
            val (branch, date) = key
            if (date < startFor(branch) || date > endDate) continue
            sales[branch] = (sales[branch] ?: SalesFigures.ZERO) + salesFromSummary(doc)
            cash[branch] = (cash[branch] ?: 0.0) + cashFromSummary(doc)
            datesSeen.getOrPut(branch) { mutableSetOf() }.add(date)
        }

        val branches = (locations.map { it.name } + sales.keys).toSet()
        return branches.associateWith { branch ->
            val start = startFor(branch)
            val expected = if (start <= endDate) daysBetweenInclusive(start, endDate).size else 0
            val seen = datesSeen[branch]?.size ?: 0
            SummaryTotals(
                sales = sales[branch] ?: SalesFigures.ZERO,
                cash = cash[branch] ?: 0.0,
                missingDays = (expected - seen).coerceAtLeast(0)
            )
        }
    }

    /** Fill runningByBranch amounts and missing-day counts from the summary cache. */
    private fun computeRunning() {
        val yesterday = yesterday()
        val totals = aggregateSummaries(
            summaryCache,
            summaryCacheStart,
            yesterday,
            startFor = { branch -> runningByBranch[branch]?.rangeStart ?: AppConstants.OVERVIEW_RUNNING_START_DATE }
        )
        runningByBranch = runningByBranch.mapValues { (branch, range) ->
            if (range.isEmpty) {
                range
            } else {
                val t = totals[branch]
                range.copy(amount = t?.cash ?: 0.0, missingDays = t?.missingDays ?: 0)
            }
        }
    }

    // ============================================================================
    // END OF PART 5: AGGREGATION
    // ============================================================================


    // ============================================================================
    // START OF PART 6: RENDERING
    // ============================================================================

    private fun renderDaily(byBranch: Map<String, SalesFigures>) {
        dailyFigures = byBranch
        setSectionState(Section.DAILY, SectionState.CONTENT)
    }

    private fun renderMonthlyFromCache() {
        val start = firstDayOfMonth(monthlyMonth)
        val totals = aggregateSummaries(summaryCache, start, yesterday())
        renderPeriodSection(Section.MONTHLY, totals, includeToday = true)
    }

    private fun renderYearly() {
        val totals = aggregateSummaries(summaryCache, firstDayOfYear(), yesterday())
        renderPeriodSection(Section.YEARLY, totals, includeToday = true)
    }

    /** Monthly / Yearly: saved totals (+ today's live figures when the period includes today). */
    private fun renderPeriodSection(section: Section, totals: Map<String, SummaryTotals>, includeToday: Boolean) {
        val byBranch = mutableMapOf<String, SalesFigures>()
        totals.forEach { (branch, t) -> byBranch[branch] = t.sales }
        if (includeToday) {
            todayLiveSales.forEach { (branch, figures) ->
                byBranch[branch] = (byBranch[branch] ?: SalesFigures.ZERO) + figures
            }
        }
        if (section == Section.MONTHLY) monthlyFigures = byBranch else yearlyFigures = byBranch
        setSectionState(section, SectionState.CONTENT)
    }

    /** Cash on Hand figures are ready (Running computed); show them in every card. */
    private fun renderCashOnHand() {
        setSectionState(Section.CASH, SectionState.CONTENT)
    }

    /**
     * One card per active branch (sorted): Cash on Hand block, then the Daily / Monthly /
     * Yearly blocks of which at most one is expanded. Re-run whenever a section's figures or
     * state change so every card stays in sync.
     */
    private fun renderBranchCards() {
        val container = binding.branchContainer
        container.removeAllViews()
        swipedCard = null

        val anyLoading = sectionStates.values.any { it == SectionState.LOADING }
        binding.branchProgress.visibility = if (locations.isEmpty() && anyLoading) View.VISIBLE else View.GONE

        val dailyLabel = if (dailyDate == todayDate) "Today" else formatDisplayDate(dailyDate)
        val monthlyLabel = formatMonthAbbreviation(monthlyMonth)  // "OCT": the header pill carries the year
        val yearlyLabel = todayDate.substring(0, 4)

        for (location in locations) {
            val card = layoutInflater.inflate(R.layout.item_overview_branch_card, container, false)
            card.findViewById<TextView>(R.id.branchTitle).text = StoreLocationHelper.getDisplayName(location.name)
            bindCashBlock(card, location)

            val periods = card.findViewById<LinearLayout>(R.id.periodsContainer)
            val expanded = expandedPeriod(location.name)
            val blocks = mapOf(
                Section.DAILY to inflatePeriodBlock(periods, "Daily Sales", dailyLabel,
                    sectionStates[Section.DAILY], dailyFigures[location.name]),
                Section.MONTHLY to inflatePeriodBlock(periods, "Monthly Sales", monthlyLabel,
                    sectionStates[Section.MONTHLY], monthlyFigures[location.name]),
                Section.YEARLY to inflatePeriodBlock(periods, "Yearly Sales", yearlyLabel,
                    sectionStates[Section.YEARLY], yearlyFigures[location.name])
            )
            blocks.forEach { (section, block) ->
                periods.addView(block)
                applyExpansion(block, section == expanded)
                block.findViewById<View>(R.id.periodHeader).setOnClickListener {
                    // Tapping the open block collapses it; tapping another opens that one instead
                    val next = if (expandedPeriod(location.name) == section) null else section
                    expandedByBranch[location.name] = next
                    TransitionManager.beginDelayedTransition(card as android.view.ViewGroup, AutoTransition().setDuration(150))
                    blocks.forEach { (s, b) -> applyExpansion(b, s == next) }
                }
            }
            container.addView(card)
        }
    }

    /** Cash on Hand block inside a branch card: Today, Running, last retrieved, warning, swipe. */
    private fun bindCashBlock(card: View, location: StoreLocationHelper.StoreLocationOption) {
        val panel = card.findViewById<View>(R.id.cashPanel)
        val status = card.findViewById<TextView>(R.id.cashStatus)
        val content = card.findViewById<View>(R.id.cashContent)

        val state = sectionStates[Section.CASH]
        if (state != SectionState.CONTENT) {
            status.text = if (state == SectionState.ERROR) "Could not load" else "Loading…"
            status.visibility = View.VISIBLE
            content.visibility = View.GONE
            return
        }
        status.visibility = View.GONE
        content.visibility = View.VISIBLE

        val running = runningByBranch[location.name]
        val last = latestRetrievals[location.name]
        card.findViewById<TextView>(R.id.todayValue).text = formatCurrency(todayLiveCash[location.name] ?: 0.0)
        card.findViewById<TextView>(R.id.runningValue).text = formatCurrency(running?.amount ?: 0.0)
        card.findViewById<TextView>(R.id.lastRetrievedText).text =
            if (last?.recordedAt != null) "Last retrieved: ${formatDateTime(last.recordedAt)}" else "Never retrieved"

        val missingText = card.findViewById<TextView>(R.id.missingReportsText)
        val missing = running?.missingDays ?: 0
        if (missing > 0 && running != null && !running.isEmpty) {
            missingText.text = "$missing ${if (missing == 1) "day" else "days"} without End of Day report"
            missingText.visibility = View.VISIBLE
        } else {
            missingText.visibility = View.GONE
        }

        setupSwipeToRetrieve(panel, location)
    }

    /** One period block inside a branch card: collapsible header with total, then the rows. */
    private fun inflatePeriodBlock(
        parent: LinearLayout,
        title: String,
        periodLabel: String,
        state: SectionState?,
        figures: SalesFigures?
    ): View {
        val view = layoutInflater.inflate(R.layout.item_overview_period_block, parent, false)
        view.findViewById<TextView>(R.id.periodTitle).text = title
        view.findViewById<TextView>(R.id.periodLabel).text = periodLabel

        val total = view.findViewById<TextView>(R.id.periodTotal)
        val status = view.findViewById<TextView>(R.id.periodStatus)
        val rows = view.findViewById<View>(R.id.periodRows)

        if (state != SectionState.CONTENT) {
            total.text = ""
            status.text = if (state == SectionState.ERROR) "Could not load" else "Loading…"
            status.visibility = View.VISIBLE
            rows.visibility = View.GONE
            return view
        }

        val f = figures ?: SalesFigures.ZERO
        total.text = formatCurrency(f.total)
        status.visibility = View.GONE
        rows.visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.cashValue).text = formatCurrency(f.cash)
        view.findViewById<TextView>(R.id.homeCreditValue).text = formatCurrency(f.homeCredit)
        view.findViewById<TextView>(R.id.skyroValue).text = formatCurrency(f.skyro)
        view.findViewById<TextView>(R.id.salmonValue).text = formatCurrency(f.salmon)
        view.findViewById<TextView>(R.id.inHouseValue).text = formatCurrency(f.inHouse)
        view.findViewById<TextView>(R.id.cashPercent).text = formatPercent(f.cash, f.total)
        view.findViewById<TextView>(R.id.homeCreditPercent).text = formatPercent(f.homeCredit, f.total)
        view.findViewById<TextView>(R.id.skyroPercent).text = formatPercent(f.skyro, f.total)
        view.findViewById<TextView>(R.id.salmonPercent).text = formatPercent(f.salmon, f.total)
        view.findViewById<TextView>(R.id.inHousePercent).text = formatPercent(f.inHouse, f.total)
        return view
    }

    /** Show or hide a period block's content and point its chevron accordingly. */
    private fun applyExpansion(block: View, expanded: Boolean) {
        block.findViewById<View>(R.id.periodContent).visibility = if (expanded) View.VISIBLE else View.GONE
        block.findViewById<ImageView>(R.id.periodChevron).rotation = if (expanded) 0f else -90f
    }

    /** Expand the same period in every branch card (used after a picker changes that period). */
    private fun expandPeriodEverywhere(section: Section) {
        locations.forEach { expandedByBranch[it.name] = section }
    }

    /** The branch card's open block: Daily until the user changes it, null when all are collapsed. */
    private fun expandedPeriod(branch: String): Section? {
        return if (expandedByBranch.containsKey(branch)) expandedByBranch[branch] else Section.DAILY
    }

    /**
     * Record a section's state, toggle its error row above the cards, and re-render the
     * cards so each block shows figures, "Loading…" or "Could not load".
     */
    private fun setSectionState(section: Section, state: SectionState, errorMessage: String = LOAD_ERROR_MESSAGE) {
        val errorRow: LinearLayout
        val error: TextView
        val name: String
        when (section) {
            Section.CASH -> { errorRow = binding.cashErrorRow; error = binding.cashError; name = "Cash on Hand" }
            Section.DAILY -> { errorRow = binding.dailyErrorRow; error = binding.dailyError; name = "Daily Sales" }
            Section.MONTHLY -> { errorRow = binding.monthlyErrorRow; error = binding.monthlyError; name = "Monthly Sales" }
            Section.YEARLY -> { errorRow = binding.yearlyErrorRow; error = binding.yearlyError; name = "Yearly Sales" }
        }
        sectionStates[section] = state
        when (state) {
            SectionState.LOADING -> {
                errorRow.visibility = View.GONE
            }
            SectionState.CONTENT -> {
                errorRow.visibility = View.GONE
            }
            SectionState.ERROR -> {
                error.text = "$name: $errorMessage"
                errorRow.visibility = View.VISIBLE
            }
        }
        renderBranchCards()
    }

    /** Nothing can be shown at all (store locations failed or none exist). */
    private fun showScreenError(message: String) {
        Section.values().forEach { sectionStates[it] = SectionState.ERROR }
        listOf(binding.cashErrorRow, binding.dailyErrorRow, binding.monthlyErrorRow, binding.yearlyErrorRow)
            .forEach { it.visibility = View.GONE }
        binding.branchProgress.visibility = View.GONE
        binding.branchContainer.removeAllViews()
        binding.screenError.text = message
        binding.screenErrorRow.visibility = View.VISIBLE
    }

    private fun updatePeriodLabels() {
        binding.dailyPeriodLabel.text = if (dailyDate == todayDate) "Today" else formatDisplayDate(dailyDate)
        binding.monthlyPeriodLabel.text = formatShortMonthLabel(monthlyMonth)
        binding.yearlyPeriodLabel.text = todayDate.substring(0, 4)
    }

    private fun formatCurrency(amount: Double): String {
        return String.format(Locale.US, "₱%,.2f", amount)
    }

    /** Share of the period total, whole percent (0% when there are no sales). */
    private fun formatPercent(amount: Double, total: Double): String {
        val percent = if (total > 0) Math.round(amount / total * 100) else 0L
        return String.format(Locale.US, "%d%%", percent)
    }

    // ============================================================================
    // END OF PART 6: RENDERING
    // ============================================================================


    // ============================================================================
    // START OF PART 7: PICKERS
    // ============================================================================

    private fun showDailyDatePicker() {
        val calendar = manilaCalendar()
        parseDate(dailyDate)?.let { calendar.time = it }
        val dialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val picked = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth)
                if (picked == dailyDate) return@DatePickerDialog
                dailyDate = picked
                updatePeriodLabels()
                expandPeriodEverywhere(Section.DAILY)
                if (dailyDate == todayDate && todayLiveLoaded) renderDaily(todayLiveSales) else loadDaily()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        dialog.datePicker.maxDate = System.currentTimeMillis()
        dialog.show()
    }

    private fun showMonthPicker() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_month_picker, null)
        val monthPicker = dialogView.findViewById<NumberPicker>(R.id.monthPicker)
        val yearPicker = dialogView.findViewById<NumberPicker>(R.id.yearPicker)

        val now = manilaCalendar()
        val currentYear = now.get(Calendar.YEAR)
        val currentMonthNumber = now.get(Calendar.MONTH) + 1
        val selectedYear = monthlyMonth.substring(0, 4).toIntOrNull() ?: currentYear
        val selectedMonth = monthlyMonth.substring(5, 7).toIntOrNull() ?: currentMonthNumber

        val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        monthPicker.minValue = 1
        monthPicker.maxValue = 12
        monthPicker.displayedValues = monthNames
        monthPicker.wrapSelectorWheel = false
        monthPicker.value = selectedMonth

        yearPicker.minValue = 2024
        yearPicker.maxValue = currentYear
        yearPicker.wrapSelectorWheel = false
        yearPicker.value = selectedYear.coerceIn(2024, currentYear)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("OK") { _, _ ->
                var year = yearPicker.value
                var month = monthPicker.value
                // Future months in the current year are clamped to the current month
                if (year == currentYear && month > currentMonthNumber) month = currentMonthNumber
                val picked = String.format(Locale.US, "%04d-%02d", year, month)
                if (picked == monthlyMonth) return@setPositiveButton
                monthlyMonth = picked
                updatePeriodLabels()
                expandPeriodEverywhere(Section.MONTHLY)
                if (monthlyMonth == currentMonth()) {
                    if (summaryCacheLoaded) renderMonthlyFromCache() else scope.launch { loadSummariesAndRender() }
                } else {
                    loadMonthly()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ============================================================================
    // END OF PART 7: PICKERS
    // ============================================================================


    // ============================================================================
    // START OF PART 8: SWIPE-TO-RETRIEVE AND RETRIEVAL
    // ============================================================================

    /**
     * Left swipe on a Cash on Hand card. The direction (horizontal vs. vertical) is decided
     * once per touch after the move exceeds touch slop; a vertical drag is left to the
     * ScrollView (which then sends ACTION_CANCEL here).
     */
    private fun setupSwipeToRetrieve(card: View, location: StoreLocationHelper.StoreLocationOption) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var decided = false
        var swiping = false

        card.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (swipedCard != null) return@setOnTouchListener true  // dialogs are up
                    startX = event.rawX
                    startY = event.rawY
                    decided = false
                    swiping = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!decided && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        decided = true
                        swiping = abs(dx) > abs(dy)
                        if (swiping) view.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    if (swiping) view.translationX = min(0f, dx)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val threshold = view.width * SWIPE_THRESHOLD
                    if (swiping && view.translationX <= -threshold) {
                        view.animate().translationX(-threshold).setDuration(150).start()
                        swipedCard = view
                        onSwipeComplete(location)
                    } else {
                        snapBack(view)
                    }
                    swiping = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    snapBack(view)
                    swiping = false
                    true
                }
                else -> false
            }
        }
    }

    private fun snapBack(view: View) {
        view.animate().translationX(0f).setDuration(150).start()
    }

    private fun resetSwipedCard() {
        swipedCard?.let { snapBack(it) }
        swipedCard = null
    }

    private fun onSwipeComplete(location: StoreLocationHelper.StoreLocationOption) {
        PasswordDialogHelper.show(
            this,
            scope,
            onSuccess = { showRetrievalConfirmation(location) },
            onDismiss = { resetSwipedCard() }
        )
    }

    private fun showRetrievalConfirmation(location: StoreLocationHelper.StoreLocationOption) {
        val display = StoreLocationHelper.getDisplayName(location.name)
        val running = runningByBranch[location.name]

        if (running == null || running.isEmpty || running.amount <= 0.0) {
            AlertDialog.Builder(this)
                .setTitle("Retrieve Cash — $display")
                .setMessage("Nothing to retrieve. Running cash is ${formatCurrency(0.0)}.")
                .setPositiveButton("Close") { _, _ -> resetSwipedCard() }
                .setOnCancelListener { resetSwipedCard() }
                .show()
            return
        }

        val todayCash = todayLiveCash[location.name] ?: 0.0
        val message = StringBuilder()
        message.append("Running cash to retrieve: ${formatCurrency(running.amount)}\n")
        message.append("Covers: ${formatRangeDate(running.rangeStart)} – ${formatRangeDate(running.rangeEnd)}\n")
        if (running.missingDays > 0) {
            message.append("\n${running.missingDays} ${if (running.missingDays == 1) "day" else "days"} in this range ")
            message.append("${if (running.missingDays == 1) "has" else "have"} no End of Day report. The amount may be understated.\n")
        }
        message.append("\nToday's cash (${formatCurrency(todayCash)}) is not included.")

        AlertDialog.Builder(this)
            .setTitle("Retrieve Cash — $display")
            .setMessage(message.toString())
            .setPositiveButton("Confirm Retrieval") { _, _ -> recordRetrieval(location, running) }
            .setNegativeButton("Cancel") { _, _ -> resetSwipedCard() }
            .setOnCancelListener { resetSwipedCard() }
            .show()
    }

    private fun recordRetrieval(location: StoreLocationHelper.StoreLocationOption, running: RunningResult) {
        val prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE)
        val retrieval = CashRetrieval(
            storeLocation = location.name,
            storeLocationId = location.id,
            rangeStart = running.rangeStart,
            rangeEnd = running.rangeEnd,
            amount = running.amount,
            daysMissingReport = running.missingDays,
            recordedBy = prefs.getString(AppConstants.KEY_USER, "") ?: "",
            recordedByAccount = AuthManager.currentEmail()
        )
        val display = StoreLocationHelper.getDisplayName(location.name)

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.collection(AppConstants.COLLECTION_CASH_RETRIEVALS).add(retrieval.toFirestoreMap()).await()
                }
                Toast.makeText(this@OverviewActivity, "Retrieved ${formatCurrency(running.amount)} from $display", Toast.LENGTH_SHORT).show()
                resetSwipedCard()
                setSectionState(Section.CASH, SectionState.LOADING)
                loadLatestRetrievals()
                computeRunning()
                renderCashOnHand()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                resetSwipedCard()
                Toast.makeText(this@OverviewActivity, "Could not record retrieval. Check your connection and try again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ============================================================================
    // END OF PART 8: SWIPE-TO-RETRIEVE AND RETRIEVAL
    // ============================================================================


    // ============================================================================
    // START OF PART 9: DATE UTILITIES (Asia/Manila, yyyy-MM-dd strings)
    // ============================================================================

    private val manilaZone: TimeZone = TimeZone.getTimeZone("Asia/Manila")

    private fun manilaCalendar(): Calendar = Calendar.getInstance(manilaZone)

    private fun dateFormat(pattern: String): SimpleDateFormat {
        return SimpleDateFormat(pattern, Locale.US).apply { timeZone = manilaZone }
    }

    private fun parseDate(date: String): java.util.Date? {
        return try { dateFormat("yyyy-MM-dd").parse(date) } catch (e: Exception) { null }
    }

    private fun today(): String = dateFormat("yyyy-MM-dd").format(manilaCalendar().time)

    private fun currentMonth(): String = todayDate.substring(0, 7)

    private fun yesterday(): String = shiftDate(todayDate, -1)

    private fun dayAfter(date: String): String = shiftDate(date, 1)

    private fun shiftDate(date: String, days: Int): String {
        val calendar = manilaCalendar()
        parseDate(date)?.let { calendar.time = it }
        calendar.add(Calendar.DAY_OF_MONTH, days)
        return dateFormat("yyyy-MM-dd").format(calendar.time)
    }

    private fun firstDayOfMonth(yyyyMM: String): String = "$yyyyMM-01"

    private fun lastDayOfMonth(yyyyMM: String): String {
        val calendar = manilaCalendar()
        parseDate("$yyyyMM-01")?.let { calendar.time = it }
        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
        return dateFormat("yyyy-MM-dd").format(calendar.time)
    }

    private fun firstDayOfYear(): String = "${todayDate.substring(0, 4)}-01-01"

    private fun daysBetweenInclusive(start: String, end: String): List<String> {
        if (start > end) return emptyList()
        val days = mutableListOf<String>()
        var current = start
        while (current <= end) {
            days.add(current)
            current = dayAfter(current)
        }
        return days
    }

    private fun maxDate(a: String, b: String): String = if (a >= b) a else b

    private fun formatDisplayDate(date: String): String {
        val parsed = parseDate(date) ?: return date
        return dateFormat("M/d/yyyy").format(parsed)
    }

    private fun formatRangeDate(date: String): String {
        val parsed = parseDate(date) ?: return date
        return dateFormat("d MMM yyyy").format(parsed)
    }

    private fun formatMonthLabel(yyyyMM: String): String {
        val parsed = parseDate("$yyyyMM-01") ?: return yyyyMM
        return dateFormat("MMMM yyyy").format(parsed)
    }

    private fun formatMonthAbbreviation(yyyyMM: String): String {
        val parsed = parseDate("$yyyyMM-01") ?: return yyyyMM
        return dateFormat("MMM").format(parsed).uppercase(Locale.US)
    }

    private fun formatShortMonthLabel(yyyyMM: String): String {
        val parsed = parseDate("$yyyyMM-01") ?: return yyyyMM
        return dateFormat("MMM yyyy").format(parsed)
    }

    private fun formatDateTime(timestamp: com.google.firebase.Timestamp): String {
        return dateFormat("M/d/yyyy h:mm a").format(timestamp.toDate())
    }

    // ============================================================================
    // END OF PART 9: DATE UTILITIES
    // ============================================================================
}
