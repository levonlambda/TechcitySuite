# Technical Plan: Overview Module

Spec: `_specs/overview-module.md`

## Approach Summary

- **One new Activity** (`OverviewActivity`) with a single vertical `ScrollView`. Four sections are stacked inside it: Cash on Hand, Daily Sales, Monthly Sales, Yearly Sales. Each section is a `LinearLayout` container that the Activity fills by inflating card layouts programmatically (branch count is small, so no `RecyclerView`). Each section has its own progress bar, error message and Retry button.
- **One new shared calculator** (`DailySummaryCalculator`) is created as a **verbatim lift** of the End of Day report's processing and parsing functions and their result data classes: same function names, same signatures, same bodies, same field names. The Overview calls it now. `EndOfDayReportActivity` is **not** modified in this feature; it keeps its private copies. The object is shaped so that a later, separate task can switch End of Day over by deleting its private copies and pointing at `DailySummaryCalculator`, with no behaviour change. Overview-specific reductions (the per-branch sales figures and period total, the day-cash line, branch attribution) stay in `OverviewActivity`, built on top of the calculator's result types.
- **Reads:**
  - Live: today's device, accessory and service transactions via three **snapshot listeners** (equality on `date` + `status`, all branches, no index needed), kept for the life of the screen and re-created on refresh. Each later snapshot recomputes today's figures and re-renders Daily (when today), Cash on Hand and the Monthly/Yearly blocks that include today. The Daily section for another date is a one-shot fetch of the device and accessory queries.
  - End of Day documents: range queries on `daily_summaries.date` (same shape the End of Day list already uses). The initial load issues **one** range query covering the union of the Yearly range and every branch's Running range, and feeds Monthly (current month), Yearly and Running from that single result. A past month picked in the Monthly section issues its own range query.
  - Retrievals: one equality query per branch on `cash_retrievals.storeLocation`, latest picked client-side (avoids a composite index).
- **Write:** one `cash_retrievals` document per retrieval.
- **Password gate:** a new small `PasswordDialogHelper` reuses `dialog_password.xml` and `AppSettingsManager.verifyPassword()` so the Overview can ask for the settings password. `MenuActivity`'s own copy is left untouched.
- **Swipe-to-retrieve:** each Cash on Hand card sits in a `FrameLayout` over a "Retrieve" hint view. A touch listener on the card tracks horizontal drag: once the move exceeds touch slop and is more horizontal than vertical, the card claims the gesture (`parent.requestDisallowInterceptTouchEvent(true)`) and follows the finger via `translationX` (left only). On release past the threshold (40% of card width) the flow starts; otherwise the card animates back. A mostly vertical move is never claimed, so the `ScrollView` scrolls as usual.
- **Toggle and card** follow the existing feature-flag pattern exactly (Stock Transfer is the template).

---

## Files to Create

### 1. `app/src/main/java/com/techcity/techcitysuite/OverviewActivity.kt`
Activity-based MVC like the rest of the app. Sections marked with the project's `START OF PART n` banners.

**Part 1 — Properties**
- `binding: ActivityOverviewBinding`, `scope = CoroutineScope(Dispatchers.Main + Job())`, `db = Firebase.firestore`.
- `locations: List<StoreLocationHelper.StoreLocationOption>`, `primaryName: String`.
- `todayDate: String` (yyyy-MM-dd, Asia/Manila), `dailyDate: String` (picker value, defaults to today), `monthlyMonth: String` (yyyy-MM, defaults to current).
- `todayLiveSales: Map<String, SalesFigures>` (branch → figures; `SalesFigures` is the Activity-level reduction described under file 2) and `todayLiveCash: Map<String, Double>` — computed once from today's transactions and reused by Daily (when date == today), Monthly, Yearly and Cash on Hand.
- `summaryCache: List<Map<String, Any?>>` — End of Day docs from the union range fetch.
- `latestRetrievals: Map<String, CashRetrieval?>` (branch name → latest).
- `runningByBranch: Map<String, RunningResult>` where `RunningResult(amount, rangeStart, rangeEnd, missingDays)`.
- Per-section `Job?` handles so a re-fetch cancels the previous one (picker change while loading).
- `swipedCard: View?` — the card currently translated left, so it can be animated back when the dialogs close.

**Part 2 — Lifecycle / setup**
- `onCreate`: inflate binding, back button → `finish()`, refresh button → `loadAll()`, Daily calendar button → `showDailyDatePicker()`, Monthly button → `showMonthPicker()`, set period labels, then `loadAll()`.
- `onDestroy`: cancel `scope`.

**Part 3 — Loading orchestration**
- `loadAll()`: shows all four section loaders; launches:
  1. `loadLocations()` (required by every section; on failure every section shows the error + Retry).
  2. `loadTodayLive()` → attaches the three listeners (`todayListeners`), suspends until each delivers its first snapshot, fills `todayLiveSales` / `todayLiveCash` (`recomputeTodayLive`); renders **Daily** immediately if `dailyDate == todayDate`. Later snapshots call `onTodayLiveChanged()`. `stopTodayListeners()` runs on refresh and in `onDestroy`.
  3. `loadLatestRetrievals()` → computes each branch's Running range (`start = max(OVERVIEW_RUNNING_START_DATE, dayAfter(lastRetrieval.rangeEnd))`, `end = yesterday`).
  4. `loadSummaries(unionStart, yesterday)` where `unionStart = min(firstDayOfYear, min over branches of running start)`; stores `summaryCache`; then renders **Monthly** (if current month), **Yearly** and **Cash on Hand** from the cache.
  - If `dailyDate != todayDate`, also `loadDaily(dailyDate)` (device + accessory for that date).
  - If `monthlyMonth` is not the current month, `loadMonthly(monthlyMonth)` issues its own `daily_summaries` range query for that month.
- Each section's Retry re-runs only the steps it needs.

**Part 4 — Fetchers (suspend, `Dispatchers.IO`)**
- `fetchTransactions(collection, date)`: `whereEqualTo("date", date).whereEqualTo("status", AppConstants.STATUS_COMPLETED)`; returns `List<Map<String, Any?>>`. No location filter (all branches).
- `fetchDailySummaries(startDate, endDate)`: `whereGreaterThanOrEqualTo("date", start).whereLessThanOrEqualTo("date", end)` on `AppConstants.COLLECTION_DAILY_SUMMARIES`. Returns doc data maps (storeLocation may be absent on legacy docs).
- `fetchLatestRetrieval(storeLocation)`: `whereEqualTo("storeLocation", name)` on `AppConstants.COLLECTION_CASH_RETRIEVALS`; pick the doc with the greatest `rangeEnd` (ties → greatest `recordedAt`) client-side. Returns `CashRetrieval?`.

**Part 5 — Aggregation (pure, uses `DailySummaryCalculator`)**
- `aggregateSalesByBranch(deviceTx, accessoryTx): Map<String, SalesFigures>` — group each list by `branchKey(userLocation, primaryName)`, then per branch call `DailySummaryCalculator.processDeviceSales(group)` and `processAccessorySales(group)` and reduce both to `SalesFigures`.
- `aggregateCashByBranch(deviceTx, accessoryTx, serviceTx): Map<String, Double>` — group by branch, then per branch `processDeviceCashFlow`, `processAccessoryCashFlow`, `processServiceCashFlow` and reduce to the day-cash line.
- `aggregateSummaries(docs, startDate, endDate, perBranchStart: ((String) -> String)?)`: for each doc, key = `branchKey(doc["storeLocation"], primaryName)`; accumulate `salesFromSummary(doc)` and `cashFromSummary(doc)`; track which dates each branch has; `missingDays(branch) = daysBetween(start, end) − datesSeen`. Returns per-branch `SummaryTotals(sales, cash, missingDays)`.
- Monthly/Yearly result for a branch = summary totals for days before today **+** `todayLiveSales[branch]` when today falls inside the period. `missingDays` is used only for the Running warning (no Monthly/Yearly coverage note).
- Running result for a branch = summary cash over that branch's own range (filtered from the cache by date ≥ its start), with its own `missingDays`. Empty range → 0, no warning.
- No "All Branches" totals card; figures for names not in the active list are not shown.

**Part 6 — Rendering**
- `renderBranchCards()`: clear `branchContainer`; for each active location (sorted by display name) inflate `item_overview_branch_card.xml`, set title = `StoreLocationHelper.getDisplayName(name)`, bind the Cash on Hand block (`bindCashBlock`), then add three `item_overview_period_block.xml` blocks (Daily / Monthly / Yearly) via `inflatePeriodBlock(...)`. Each block binds its title, period label, period total (`SalesFigures.total`) and the five color-coded rows (sale amounts plus each row's whole-percent share of the period total via `formatPercent`, 0% when the total is zero) from `dailyFigures` / `monthlyFigures` / `yearlyFigures`. A block whose period is LOADING or ERROR shows "Loading…" / "Could not load" instead of rows. Only the block in `expandedPeriod(name)` (default Daily, null once the user collapses the open one) has its content visible (`applyExpansion`); tapping a header expands it and collapses the other two in that card, tapping the open header collapses it, both animated with `TransitionManager`. Re-run on every section state change.
- `bindCashBlock(card, location)`: Today = `todayLiveCash[name] ?: 0.0`, Running, "Last retrieved: M/d/yyyy h:mm a" or "Never retrieved", warning line "N days without End of Day report" (visible only when N > 0), "Loading…" / "Could not load" while the Cash section is not CONTENT, and the swipe listener on the panel (`setupSwipeToRetrieve(cashPanel, location)`). `renderCashOnHand()` just marks the Cash section CONTENT.
- `setSectionState(section, LOADING | CONTENT | ERROR)`: records the state in `sectionStates`, toggles that section's error row above the cards (message prefixed with the section name + Retry), and calls `renderBranchCards()`. `branchProgress` shows only while the branch list itself has not loaded. `showScreenError(message)` replaces everything with one message + Retry when locations fail or are empty.
- `expandPeriodEverywhere(section)` is called after a Daily date or Monthly month is picked so every card opens that period.
- Period labels: Daily "Today" or `M/d/yyyy`; Monthly `MMMM yyyy`; Yearly `yyyy`.
- `formatCurrency(amount)`: `"₱" + String.format("%,.2f", amount)` (same as the other Activities).

**Part 7 — Pickers**
- `showDailyDatePicker()`: `android.app.DatePickerDialog` seeded with `dailyDate`, `datePicker.maxDate = now`; on set → update `dailyDate`, label, `loadDaily()`.
- `showMonthPicker()`: `AlertDialog` with `dialog_month_picker.xml` (two `NumberPicker`s: month 1–12 with display names, year from 2024 to current year); months after the current month in the current year are clamped on OK; on OK → update `monthlyMonth`, label, `loadMonthly()`.

**Part 8 — Swipe-to-retrieve and retrieval**
- `setupSwipeToRetrieve(cardFrame, location)`: `foreground` card inside the frame gets `setOnTouchListener`:
  - `ACTION_DOWN`: record `startX`, `startY`; `swiping = false`; return `true`.
  - `ACTION_MOVE`: `dx = x − startX`, `dy = y − startY`. If not yet swiping and `|dx| > touchSlop` and `|dx| > |dy|` → `swiping = true`, `parent.requestDisallowInterceptTouchEvent(true)`. If swiping: `card.translationX = min(0f, dx)`; the hint view behind is static and becomes visible as the card moves. If not swiping and `|dy| > touchSlop` → let go (return `false`) so the `ScrollView` takes over.
  - `ACTION_UP`: if swiping and `translationX <= −0.4 × card.width` → animate to `−card.width × 0.4` (hint stays revealed while the dialogs are up), set `swipedCard = card`, call `onSwipeComplete(location)`; else animate `translationX` back to 0.
  - `ACTION_CANCEL`: animate back to 0.
  - Right-ward drag is clamped at 0 (no effect).
- `resetSwipedCard()`: animates `swipedCard` back to `translationX = 0` and clears it. Called when the password dialog is dismissed without success, when the confirmation dialog is cancelled or closed, and after a retrieval completes (before re-rendering the section).
- `onSwipeComplete(location)`: `PasswordDialogHelper.show(this, scope, onSuccess = { showRetrievalConfirmation(location) }, onDismiss = { resetSwipedCard() })`.
- `showRetrievalConfirmation(location)`: reads `runningByBranch[location.name]`:
  - If amount ≤ 0 or range empty: `AlertDialog` "Retrieve Cash — <display>" / "Nothing to retrieve. Running cash is ₱0.00." / **Close**.
  - Else: title "Retrieve Cash — <display>"; message lines: "Running cash to retrieve: ₱X", "Covers: d MMM yyyy – d MMM yyyy", optional "N days in this range have no End of Day report. The amount may be understated.", "Today's cash (₱Y) is not included."; buttons **Cancel** / **Confirm Retrieval** → `recordRetrieval(location, running)`.
- `recordRetrieval(location, running)`: builds a `CashRetrieval` document (see file 3) and `db.collection(COLLECTION_CASH_RETRIEVALS).add(map).await()`; on success toast "Retrieved ₱X from <display>" and re-run `loadLatestRetrievals()` + re-render Cash on Hand from the existing `summaryCache` (no re-fetch of summaries needed); on failure toast "Could not record retrieval. Check your connection and try again."

**Part 9 — Date utilities (private)**
- `manilaCalendar()`, `today()`, `yesterday()`, `dayAfter(date)`, `firstDayOfMonth(yyyyMM)`, `lastDayOfMonth(yyyyMM)`, `firstDayOfYear()`, `daysBetweenInclusive(start, end): List<String>`, `minDate(a, b)`, `maxDate(a, b)` — all on `yyyy-MM-dd` strings with `TimeZone.getTimeZone("Asia/Manila")`.

### 2. `app/src/main/java/com/techcity/techcitysuite/DailySummaryCalculator.kt`
`object DailySummaryCalculator` — pure functions and data classes, no Android or Firestore SDK dependencies, unit-testable. **Everything in it is copied verbatim from `EndOfDayReportActivity`** (only the `private` modifier is dropped and the nested declarations move into this object). No logic is rewritten, reordered or "cleaned up", so the later End of Day switch is a pure delete-and-reference change.

**Result types** (copied from the Activity's nested data classes, same names and fields):
- `TransactionBreakdown(count, amount)`
- `InstallmentBreakdown(count, amount, downpayment, balance)`
- `SalesSummary(totalSales, cashSales, homeCreditSales, skyroSales, salmonSales, inHouseSales)`
- `CashFlowSummary(cash, gcash, paymaya, bankTransfer, creditCard, others, hcReceivable, skyroReceivable, salmonReceivable, inHouseReceivable, brandZeroSubsidy)`
- `ServiceCashFlowSummary(cashInflow, cashOutflow, gcashInflow, gcashOutflow, paymayaInflow, paymayaOutflow, othersInflow, othersOutflow)`

**Live-transaction processing** (copied from the Activity's Part 6, same signatures):
- `fun processDeviceSales(transactions: List<Map<String, Any?>>): SalesSummary` — from `EndOfDayReportActivity.processDeviceSales` (line ~975).
- `fun processAccessorySales(transactions: List<Map<String, Any?>>): SalesSummary` — from `processAccessorySales` (line ~1073).
- `fun processDeviceCashFlow(transactions: List<Map<String, Any?>>): CashFlowSummary` — from `processDeviceCashFlow` (line ~1252).
- `fun processAccessoryCashFlow(transactions: List<Map<String, Any?>>): CashFlowSummary` — from `processAccessoryCashFlow` (line ~1391).
- `fun processServiceCashFlow(transactions: List<Map<String, Any?>>): ServiceCashFlowSummary` — from `processServiceCashFlow` (line ~1525).

**Saved-document parsing** (copied from the Activity's load path, same signatures):
- `fun parseSalesSummary(data: Map<*, *>?): SalesSummary` — from line ~440.
- `fun parseCashFlowSummary(data: Map<*, *>?): CashFlowSummary` — from line ~512.
- `fun parseServiceCashFlowSummary(data: Map<*, *>?): ServiceCashFlowSummary` — from line ~533.

These functions each process **one branch's** list (End of Day filters to the device's branch before calling them). The Overview therefore groups transactions by branch first and calls them once per group.

**Not lifted now** (End of Day only; listed so the future merge knows what remains): `processServiceSummary`, `parseServiceSummaryFromSaved`, `buildLedgerSummaryFromCashFlow`, `processTransactionData`, and the `DailySummaryData`, `ServiceSummary`, `ServiceTypeBreakdown`, `MiscPaymentBreakdown`, `LedgerSummaryData`, `LedgerBreakdown` types. The Overview does not need them.

**Overview-side reductions** (live in `OverviewActivity`, not in the calculator, so the calculator stays an exact End of Day mirror):
- `SalesFigures(cash, downpayment, homeCredit, skyro, salmon, inHouse)` with `plus` and `ZERO`, built from a `SalesSummary`: `cash = cashSales.amount`, `downpayment = homeCreditSales.downpayment + skyroSales.downpayment + salmonSales.downpayment + inHouseSales.downpayment`, `homeCredit = homeCreditSales.balance`, `skyro = skyroSales.balance`, `salmon = salmonSales.balance`, `inHouse = inHouseSales.balance`. Devices and accessories are each converted and then added.
- Day cash for a branch = `deviceCashFlow.cash + accessoryCashFlow.cash + (serviceCashFlow.cashInflow − serviceCashFlow.cashOutflow)`, which is exactly End of Day's `ledgerSummary.cash.total` (see `buildLedgerSummaryFromCashFlow`).
- From a saved document: `parseSalesSummary(salesSummary.devices)` + `parseSalesSummary(salesSummary.accessories)` → `SalesFigures`; `parseCashFlowSummary(cashFlowSummary.devices).cash + parseCashFlowSummary(cashFlowSummary.accessories).cash + parseServiceCashFlowSummary(cashFlowSummary.services)` netted → day cash.
- `branchKey(location: String?, primaryName: String)` — blank/null → `primaryName`, else unchanged.

### 3. `app/src/main/java/com/techcity/techcitysuite/CashRetrieval.kt`
`data class CashRetrieval(id, storeLocation, storeLocationId, recordedAt: Timestamp?, rangeStart, rangeEnd, amount, daysMissingReport: Int, recordedBy, recordedByAccount)` with the Firestore no-arg constructor, plus `fun toFirestoreMap()` (uses `FieldValue.serverTimestamp()` for `recordedAt`) and `companion fun fromDocument(doc)`.

### 4. `app/src/main/java/com/techcity/techcitysuite/PasswordDialogHelper.kt`
`object PasswordDialogHelper { fun show(activity: Activity, scope: CoroutineScope, onSuccess: () -> Unit, onDismiss: (() -> Unit)? = null) }` — inflates `R.layout.dialog_password`, same view ids, same messages ("Please enter a password", "Incorrect password", "Error verifying password"), same Enter-key handling and `setCancelable(false)`, verifies with `AppSettingsManager.verifyPassword`. `onDismiss` fires only when the dialog closes without a correct password (same `accepted` flag pattern as `MenuActivity.showPasswordDialog`). Behaviour copied from MenuActivity; MenuActivity itself is not changed.

### 5. `app/src/main/res/layout/activity_overview.xml`
Root `ConstraintLayout`:
- Blue header (`@color/techcity_blue`, vertical): a title row (back `ImageButton` `@+id/backButton`, title "Overview" `@+id/titleText`, refresh `ImageButton` `@+id/refreshButton` with `@android:drawable/ic_popup_sync`, white tint), then a **period selector row** of three equal pills using `@drawable/date_selector_background` (the End of Day date selector look): `@+id/dailyDateButton` (caption "Daily", `dailyPeriodLabel`, dropdown arrow), `@+id/monthlyMonthButton` (caption "Monthly", `monthlyPeriodLabel`, dropdown arrow) and a static Yearly pill (`yearlyPeriodLabel`). The two clickable pills use `?attr/selectableItemBackground` as foreground.
- `ScrollView` `@+id/scrollView` → vertical `LinearLayout` padding 16dp:
  - `screenErrorRow` (`screenError` + `screenRetry`, gone) for the locations failure.
  - One hidden error row per section (`cashErrorRow`/`cashError`/`cashRetry`, `dailyErrorRow`/…, `monthlyErrorRow`/…, `yearlyErrorRow`/…), each a message + outlined Retry.
  - `branchProgress` (gone) and the cards container `branchContainer`.
- Roughly 25 ids total; far below the ViewBinding cap.

### 6. `app/src/main/res/layout/item_overview_branch_card.xml` and `item_overview_period_block.xml`
Branch card: `MaterialCardView` (corner 12dp, elevation 2dp, 1dp `techcity_blue_light` stroke, marginBottom 10dp) → vertical `LinearLayout` padding 16dp:
- `@+id/branchTitle` (18sp bold TechCity blue, single line, ellipsize end).
- `@+id/periodsContainer` (vertical) that the Activity fills with three period blocks.
- **Cash on Hand block** (below the period blocks, 12dp top margin): `FrameLayout` `@+id/cashFrame` with two children: first the **hint** `@+id/retrieveHint` (`MaterialCardView`, `@color/orange`, corner 8dp, `gravity=center_vertical|end`, padding end 24dp, white `@android:drawable/ic_menu_send` icon + white bold "Retrieve"); second the **panel** `@+id/cashPanel` (`MaterialCardView`, `@color/toggle_gray` background, corner 8dp, elevation 0, `clickable`/`focusable`) drawn on top. Inside the panel: title "Cash on Hand" (13sp bold), `cashStatus` (12sp gray, gone), and `cashContent` holding the two-column row "Today" / `@+id/todayValue` and "Running" / `@+id/runningValue` (18sp bold, the prominent figure), `@+id/lastRetrievedText` (12sp gray) and `@+id/missingReportsText` (12sp `@color/orange`, gone). As the panel translates left the hint shows through on the right.

Period block (`item_overview_period_block.xml`), mirroring the End of Day sales card styling:
- `periodDivider` (1dp light gray).
- `periodHeader` (clickable, ripple, min height 44dp): `periodChevron` (`@android:drawable/arrow_down_float`, gray, rotated −90° when collapsed), `periodTitle` (14sp bold TechCity blue), `periodLabel` (12sp gray, weight 1), `periodTotal` (16sp bold TechCity blue).
- `periodContent` (24dp start indent; visible only when expanded): `periodStatus` (12sp gray, gone) for "Loading…" / "Could not load", then `periodRows` with five rows (label weight 1, amount `wrap_content` end-aligned, then a 12sp gray percent TextView `<row>Percent` with 34dp minWidth and 4dp start margin): "Cash Sale" (`cash_dark_green`) / `cashValue`, "Home Credit Sale" (`red`) / `homeCreditValue`, "Skyro Sale" (`skyro_light_blue`) / `skyroValue`, "Salmon Sale" (`orange`) / `salmonValue`, "In-House Sale" (`purple`) / `inHouseValue`. No Downpayment / Balance sub-rows.

### 7. (removed) `item_cash_on_hand_card.xml`
The standalone Cash on Hand card was folded into the branch card above.

### 8. `app/src/main/res/layout/dialog_month_picker.xml`
Vertical `LinearLayout` padding 24dp white: title "Select Month" (styled like `dialog_password.xml`), horizontal row with `NumberPicker` `@+id/monthPicker` and `@+id/yearPicker`. Buttons come from `AlertDialog.Builder` (Cancel / OK).

---

## Files to Modify

### 9. `app/src/main/java/com/techcity/techcitysuite/Constants.kt`
Add to `AppConstants`:
- `const val KEY_OVERVIEW_ENABLED = "overview_enabled"` (next to the other feature flags).
- `const val COLLECTION_DAILY_SUMMARIES = "daily_summaries"` and `const val COLLECTION_CASH_RETRIEVALS = "cash_retrievals"` (collection block). The End of Day Activities keep their private copies; they are not touched.
- `const val OVERVIEW_RUNNING_START_DATE = "2026-10-16"`.

### 10. `app/src/main/res/layout/activity_program_settings.xml`
After the Stock Transfer toggle `LinearLayout` (inside the Feature Settings card, before the card's closing tag): a divider `View` identical to the existing ones, then an "Overview" toggle row copied from the Stock Transfer row with title "Overview", description "Show Overview in main menu", switch id `@+id/overviewSwitch`, `checked=false`.

### 11. `app/src/main/java/com/techcity/techcitysuite/ProgramSettingsActivity.kt`
- Companion: `private const val KEY_OVERVIEW_ENABLED = "overview_enabled"` after `KEY_STOCK_TRANSFER_ENABLED`.
- `loadSettings` (line ~191 area): `binding.overviewSwitch.isChecked = prefs.getBoolean(KEY_OVERVIEW_ENABLED, false)`.
- `saveSettings` (line ~241 area): `editor.putBoolean(KEY_OVERVIEW_ENABLED, binding.overviewSwitch.isChecked)`.

### 12. `app/src/main/res/layout/activity_menu.xml`
After the Expenses card (before the `LinearLayout` closing tag): an "Overview" `MaterialCardView` copied from the Expenses card with ids `overviewCard`, `overviewButton`, `overviewIcon`, `overviewTitle`; icon `@android:drawable/ic_menu_sort_by_size` tinted `@color/techcity_blue`; title "Overview"; description "Cash on hand and sales across all branches".

### 13. `app/src/main/java/com/techcity/techcitysuite/MenuActivity.kt`
- `updateFeatureVisibility()`: after the Stock Transfer block, add an Overview block reading `AppConstants.KEY_OVERVIEW_ENABLED` (default `false`) and toggling `binding.overviewCard.visibility`.
- `setupMenuButtons()`: after the Stock Transfer button, `binding.overviewButton.setOnClickListener { startActivity(Intent(this, OverviewActivity::class.java)) }`.

### 14. `app/src/main/AndroidManifest.xml`
Register `<activity android:name=".OverviewActivity" android:exported="false" android:label="Overview" />` after `FinancingAccountDetailActivity`.

---

## Files Explicitly Unchanged

- `EndOfDayReportActivity.kt`, `EndOfDayListActivity.kt` — **not touched in this feature.** Their processing and parsing functions are the source that `DailySummaryCalculator` is copied from, and they keep their private copies for now. Switching End of Day to call `DailySummaryCalculator` is a separate, later task.
- `MenuActivity.showPasswordDialog` — the Overview uses the new `PasswordDialogHelper`; the menu keeps its own copy.
- `StoreLocationHelper.kt` — reused as is (`loadActiveLocations`, `getDisplayName(String)`).
- `firestore.rules` — the existing authenticated-or-nothing rule already covers the new `cash_retrievals` collection. No change, and nothing to deploy.
- `dialog_password.xml` — reused unchanged.

---

## Implementation Order

1. `Constants.kt` — new keys and collection names.
2. `DailySummaryCalculator.kt` — copy the listed End of Day data classes and functions **verbatim** (drop `private`, nest in the object). Diff each copied body against its End of Day source before moving on; the diff should show only the modifier and indentation.
3. `CashRetrieval.kt`, `PasswordDialogHelper.kt`.
4. Layouts: `item_overview_period_block.xml`, `item_overview_branch_card.xml`, `dialog_month_picker.xml`, `activity_overview.xml`.
5. `OverviewActivity.kt` in the part order above: lifecycle → fetchers → aggregation → rendering → pickers → swipe/retrieval → dates.
6. `AndroidManifest.xml` registration.
7. Program Settings toggle (layout + Kotlin).
8. Main Menu card (layout + Kotlin).
9. `./gradlew assembleDebug`; fix compile errors only.
10. Manual checks: toggle off hides card / on shows it; Daily figures equal the End of Day report for the same date and branch; Monthly/Yearly equal the sum of saved reports plus today; recording a sale on another device updates Daily, Today's cash and the Monthly/Yearly totals without a refresh; Cash on Hand Today equals today's End of Day ledger Cash; Running for a never-retrieved branch sums from 16 Oct 2026; short swipe snaps back, vertical drag scrolls, right swipe does nothing; full left swipe → password → confirmation → retrieval saved → card snaps back, Running ₱0.00 and "Last retrieved" updated; cancelling password or confirmation snaps the card back; second swipe same day → "Nothing to retrieve"; airplane mode → section errors with Retry and retrieval save error.

---

## Dependencies / Libraries

None new. Uses existing AppCompat, Material, ConstraintLayout, Firestore, Coroutines, and platform `DatePickerDialog`, `NumberPicker`, `ViewPropertyAnimator`.

---

## Migration and Data Considerations

- **New collection `cash_retrievals`** is created implicitly on the first retrieval. Fields: `storeLocation`, `storeLocationId`, `recordedAt` (server timestamp), `rangeStart`, `rangeEnd`, `amount`, `daysMissingReport`, `recordedBy`, `recordedByAccount`.
- **No index required.** Range queries on `daily_summaries.date` alone and equality on `cash_retrievals.storeLocation` alone use automatic single-field indexes. `orderBy` is deliberately avoided on the retrieval query.
- **Legacy End of Day documents** (id = date, no `storeLocation`) are returned by the range query and attributed to the primary branch via `branchKey`.
- **Running start date** is a constant (`2026-10-16`). Branches never retrieved start there.
- No existing data is modified.

---

## Risks and Things to Watch

- **Temporary duplication until End of Day is switched over.** Until the later task points `EndOfDayReportActivity` at `DailySummaryCalculator`, the same math exists in two places. Any change to End of Day's processing in the meantime must be mirrored in the calculator, or the two screens will disagree. Keeping the copy verbatim (same names, same bodies) is what makes that later switch safe and the interim mirroring easy to check by diff.
- **Verbatim means verbatim.** Do not fix, simplify or reorder anything while copying, even if something looks odd (for example the `else -> cash` default for an unknown payment source, or In-House using `totalAmountDue` with a `balance` fallback). Those behaviours are what make the Overview agree with End of Day today.
- **Union-range fetch size.** The initial `daily_summaries` query spans from Jan 1 (or the earliest Running start) to yesterday for all branches. With a handful of branches this is a few hundred to about a thousand documents; acceptable, and far fewer than raw transactions. If branch count grows a lot, split Running into its own smaller query.
- **Retrieval query growth.** Per-branch equality query returns all of that branch's retrievals; with daily retrievals this is ~365 docs per branch per year. If that becomes a cost concern, add a composite index (`storeLocation` asc, `rangeEnd` desc) and `limit(1)`.
- **Swipe vs. scroll.** The direction test (`|dx| > |dy|` past touch slop) decides who owns the gesture. Decide once per touch and do not flip afterwards, or the card will jitter. Calling `requestDisallowInterceptTouchEvent(true)` only after the horizontal decision keeps vertical scrolling natural. Test on a real device with diagonal drags.
- **PasswordDialogHelper needs an `onDismiss` hook** (same pattern as `MenuActivity.showPasswordDialog`) so the card snaps back when the password dialog is cancelled.
- **Stale Running at confirmation.** The confirmation uses the Running figure loaded on screen; if another device retrieved in between, the record may duplicate. Spec accepts this; the refresh button re-syncs.
- **Yesterday with no report.** As the spec notes, retrieving before yesterday's End of Day exists drops that day from tracking. The confirmation warning line is the only guard; make sure it is shown whenever `missingDays > 0`.
- **Date strings, not timestamps.** All period math is on `yyyy-MM-dd` strings in Asia/Manila; never use the device default timezone.
- **Section independence.** Daily/Monthly/Yearly/Cash each have their own state views; a failure in the summaries fetch must mark Monthly, Yearly and Cash as errored but leave Daily (live) showing.
