# Technical Plan: Salmon Financing Company Integration

Based on `_specs/salmon-financing-company.md`. All open questions are resolved:

- **Fee:** Fixed **₱18** (same model as Skyro's fixed ₱15).
- **Logo:** Use a placeholder/generic orange icon for now (real logo supplied later).
- **Filter rows:** Keep equal-weight buttons that shrink to fit; allow horizontal scroll as a fallback only if labels truncate.
- **Dropdown order:** Alphabetical (A→Z).
- **Payment methods/sources:** Same as Home Credit / Skyro (GCash, PayMaya, Others).

### Scope expansion (added per follow-up)

Salmon must also be a full **financing option for product sales** (Device and Accessory), and the **End-of-Day report** must reflect Salmon. Confirmed decisions:

- **Add Salmon to the sale-creation screens** (`DeviceTransactionActivity`, `AccessoryTransactionActivity`) as a new financing type, so Salmon-financed sales can actually be created.
- **Salmon device/accessory financing is structured exactly like Skyro:** downpayment + balance (receivable), reuses the shared Home Credit/Skyro details UI card, and **includes Brand Zero subsidy**. Persists a new nested `salmonPayment` object (mirror of `skyroPayment`).
- **End-of-Day report:** In **Transaction Detail**, Salmon gets its own line in Device Sales and Accessory Sales (mirroring the Skyro rows). In **Transaction Summary**, Salmon sales are rolled into the existing Device Sales / Accessory Sales totals (automatic once Salmon is added to `totalSales`), and **Receivables** gains a Salmon line.

Canonical strings (must match exactly everywhere):
- Service payment transaction type = **`Salmon Payment`**
- Financing company (financing accounts) = **`Salmon`**
- **Device/accessory sale financing type = `Salmon Transaction`** (new) — paired with constant `AppConstants.TRANSACTION_TYPE_SALMON`.

---

## Constants & Brand Color

- The color `orange` (`#FF9800`) **already exists** in `app/src/main/res/values/colors.xml` — reuse `R.color.orange`. **No new color needed.**
- **`Constants.kt` (`AppConstants`):** add `const val TRANSACTION_TYPE_SALMON = "Salmon Transaction"` in the `TRANSACTION TYPES` block (after `TRANSACTION_TYPE_SKYRO`).

---

## Files to Modify

### A. Service Transaction List — SLM filter toggle (Feature 1)

**1. `app/src/main/res/layout/activity_service_transaction_list.xml`**
- Add a new `Button` with `android:id="@+id/filterSlmButton"`, `android:text="SLM"`, `android:backgroundTint="@color/orange"`, positioned **between `filterHCButton` and `filterMiscButton`**.
- Copy the exact attribute pattern of the existing `filterHCButton` (weight=1, `textSize` 9–10sp, insets 0, `minWidth` 0, `style="@style/Widget.MaterialComponents.Button"`, `layout_marginEnd="2dp"`).
- **Crowding:** With 8 weighted buttons they shrink to fit. Primary approach = keep the weighted `LinearLayout` as-is. *Fallback (only if labels truncate on test devices):* wrap the filter `LinearLayout` in a `HorizontalScrollView` and give each button a fixed `minWidth` instead of weight. Decide after a visual check during implementation; default to no scroll.

**2. `app/src/main/java/com/techcity/techcitysuite/ServiceTransactionListActivity.kt`**
- `enum class TransactionTypeFilter` (~line 65): add `SALMON_PAYMENT`.
- Filter button setup (~line 473, after the `filterHCButton` listener): add a `filterSlmButton` click listener that sets `currentFilter = TransactionTypeFilter.SALMON_PAYMENT`, calls `updateFilterButtonStates`, `applyFilter`.
- `updateFilterButtonStates` (~line 486): add `binding.filterSlmButton.alpha = 0.5f` to the reset block and a `SALMON_PAYMENT -> binding.filterSlmButton.alpha = 1.0f` case in the `when`.
- `applyFilter` (~line 646): add a `SALMON_PAYMENT -> transactions.filter { it.transaction.transactionType == "Salmon Payment" }` branch.
- `updateSummary` label `when` (~line 683): add `SALMON_PAYMENT -> "Total Amount (SLM)"`.
- `getTransactionTypeColor` (~line 855): add `"Salmon Payment" -> R.color.orange`.
- `getSourceLabel` (~line 867): add `"Salmon Payment"` to the existing `"Skyro Payment", "Home Credit Payment" -> "Payment Method:"` case.

> Note: `when` statements on the enum are exhaustive, so adding `SALMON_PAYMENT` will force the compiler to flag every branch that needs the new case — a built-in safety net.

---

### B. New Transaction flow — Salmon Payment card (Feature 2)

**3. `app/src/main/res/layout/activity_transaction_type.xml`**
- Add a new `MaterialCardView` with `android:id="@+id/salmonCard"` containing a clickable `LinearLayout` `android:id="@+id/salmonButton"`, positioned **between `homeCreditCard` and `miscPaymentCard`**.
- Mirror the `skyroCard`/`homeCreditCard` structure: left icon (use a **placeholder**, e.g. an existing generic drawable tinted with `@color/orange` / a circle background, since no Salmon logo yet), bold title **"Salmon Payment"** in `@color/orange`, subtitle **"Process Salmon installments"**.
- Move the `layout_marginBottom="24dp"` consideration: `homeCreditCard` currently has only top margin; `miscPaymentCard` keeps the bottom margin — no change needed there. Give `salmonCard` `layout_marginTop="16dp"` like the others.

**4. `app/src/main/java/com/techcity/techcitysuite/TransactionTypeActivity.kt`**
- In `onCreate` (~line 52, after the `homeCreditButton` listener): add `binding.salmonButton.setOnClickListener { openTransactionDetails("Salmon Payment") }`.

**5. `app/src/main/java/com/techcity/techcitysuite/TransactionDetailsActivity.kt`** — the payment entry screen. Add `"Salmon Payment"` everywhere `"Skyro Payment"` is handled (fixed-fee model), with fee = **18.0**:
- `setupUI` title `when` (~line 86): `"Salmon Payment" -> "Salmon Payment"`.
- `setupUI` title color `when` (~line 96): add `"Salmon Payment" -> binding.titleText.setTextColor(ContextCompat.getColor(this, R.color.orange))`.
- `setupUI` "Paid with" visibility group (~line 123): add `"Salmon Payment"` to the visible case alongside Cash In / Mobile Loading / Skyro / Home Credit.
- `setupUI` fee-options `when` (~line 143): add `"Salmon Payment"` to the `"Skyro Payment", "Home Credit Payment", "Mobile Loading Service"` group, and in the inner branch set `transactionFee = 18.0; binding.feeInput.setText(formatCurrency(transactionFee))` (parallel to the Skyro `15.0` branch).
- `setupSourceOfFundsDropdown` sources (~line 235): Salmon already falls into the `else -> arrayOf("GCash", "PayMaya", "Others")` branch — **no change needed**, but verify.
- `setupSourceOfFundsDropdown` label `when` (~line 249): add `"Salmon Payment"` to the `"Skyro Payment", "Home Credit Payment" -> "Payment Method"` case.
- Amount `afterTextChanged` empty-input guard (~line 333): change `if (transactionType != "Skyro Payment" && transactionType != "Home Credit Payment")` to **also** exclude `"Salmon Payment"`, so the fixed fee is not reset to 0 when the amount field is cleared.
- `calculateFee` (~line 415): add `if (transactionType == "Salmon Payment") { return 18.0 }`.
- `calculateFeeAndTotal` zero-amount branch (~line 492): add `else if (transactionType == "Salmon Payment") { transactionFee = 18.0 }` so the fee shows even at amount 0.
- `showConfirmationDialog` transaction-type color `when` (~line 663): add `"Salmon Payment" -> ContextCompat.getColor(this, R.color.orange)`.
- `showConfirmationDialog` "Paid With" visibility group (~line 716): add `"Salmon Payment"` to the Cash In / Mobile Loading / Skyro / Home Credit case.
- `showConfirmationDialog` customer-message `when` (~line 842): add a `"Salmon Payment"` branch mirroring the Skyro branch, but with the leading text **"Salmon payment "** (e.g. `"Salmon payment ₱X.XX paid via [source]"`).
- `calculateLedgerEntries` `when` (~line 1333): add a `"Salmon Payment"` branch mirroring the Skyro branch: `creditDescription = "Salmon Payment"`, `debitLedgerType = sourceOfFunds`, `debitAmount = amount`, `debitDescription = "Salmon Payment via $sourceOfFunds"`.

> The `TransactionProcessor.processTransaction` call passes `transactionType` straight through, so no change is needed there for the data flow. (Verify `TransactionProcessor` has no Skyro/HC-specific branch that Salmon must join — see Risks.)

---

### C. Financing Account List — Salmon filter toggle (Feature 3)

**6. `app/src/main/res/values/strings.xml`**
- Add `<string name="filter_salmon">Salmon</string>` (next to the existing `filter_*` entries).

**7. `app/src/main/res/layout/activity_financing_account_list.xml`**
- Add a `Button` `android:id="@+id/filterSalmonButton"`, `android:text="@string/filter_salmon"`, `android:backgroundTint="@color/orange"`, positioned **between `filterHomeCreditButton` and `filterSkyroButton`**.
- Mirror the existing filter-button attributes (weight=1, insets 0, `minWidth` 0, margins). 5 weighted buttons shrink to fit; same scroll fallback note as Feature 1.

**8. `app/src/main/java/com/techcity/techcitysuite/FinancingAccountListActivity.kt`**
- `enum class FinancingFilter` (~line 42): add `SALMON`.
- `setupFilterButtons` (~line 104): add `binding.filterSalmonButton.setOnClickListener { selectFilter(FinancingFilter.SALMON) }`.
- `applyFilters` company `when` (~line 325): add `FinancingFilter.SALMON -> account.financingCompany == "Salmon"`.
- `updateFilterButtonStyles` (~line 404): add `binding.filterSalmonButton.alpha = if (currentFilter == FinancingFilter.SALMON) activeAlpha else inactiveAlpha`.
- Badge color mapping in the adapter `onBindViewHolder` (~line 699): add `"Salmon" -> ContextCompat.getColor(this@FinancingAccountListActivity, R.color.orange)`.

---

### D. Add/Edit Financing Account — dropdown + detail badge (Feature 4)

**9. `app/src/main/java/com/techcity/techcitysuite/AddFinancingAccountActivity.kt`**
- `financingCompanies` list (~line 36): add `"Salmon"` and order alphabetically → `listOf("Home Credit", "Salmon", "Samsung Finance", "Skyro")`.
- No other change: edit-mode pre-fill uses `setText(financingCompany, false)`, save reads the dropdown text directly — both already handle any value.

**10. `app/src/main/java/com/techcity/techcitysuite/FinancingAccountDetailActivity.kt`**
- `companyColor` `when` (~line 53): add `"Salmon" -> ContextCompat.getColor(this, R.color.orange)` so the detail screen badge is orange, not the gray fallback.

---

### E. Device sale creation — Salmon financing option

Salmon reuses the **shared Home Credit / Skyro details card** (`homeCreditCard` + `hcDownPaymentInput` / `hcDownPaymentSourceDropdown` / `hcBalanceInput`), retitled and recolored — exactly how Skyro already reuses it. **No new layout views are needed for the details section**, and the type selector is a dropdown fed from an array, so adding the option requires no layout change either.

**11. `app/src/main/java/com/techcity/techcitysuite/DeviceTransactionActivity.kt`**
- `transactionTypes` array (~line 47): add `"Salmon Transaction"` (place after `"Skyro Transaction"`).
- `updateTransactionTypeColor` (~line 210): add `"Salmon Transaction" -> ContextCompat.getColor(this, R.color.orange)`.
- `showAppropriateCard` (~line 269): add a `"Salmon Transaction"` case mirroring the `"Skyro Transaction"` case — show `homeCreditCard`, set stroke/title color to `R.color.orange`, `homeCreditTitle.text = "Salmon Details"`, `hcBalanceInput` text color orange, then `updateHCBalance()` + `updateSubsidyVisibility()`.
- `validateFormForTransactionType` `when` (~line 316): add `"Salmon Transaction"` to the same handling Skyro uses (the HC/Skyro validation path).
- `updateAllCalculations` `when` (~line 515): add `"Salmon Transaction"` to the `"Home Credit Transaction", "Skyro Transaction"` group (balance + subsidy).
- Save validation `when` (~line 1200): add `AppConstants.TRANSACTION_TYPE_SALMON` to the `TRANSACTION_TYPE_HOME_CREDIT, TRANSACTION_TYPE_SKYRO` case (downpayment-source check + non-negative balance check, including Brand Zero subsidy math).
- Save data `when` (~lines 1432–1560): add an `AppConstants.TRANSACTION_TYPE_SALMON ->` branch that builds `transactionData["salmonPayment"] = hashMapOf(...)` as an exact copy of the Skyro branch (same keys: `downpaymentAmount`, `downpaymentSource`, `accountDetails`, `brandZero`, `brandZeroSubsidy`, `subsidyPercent`, `balance`, `isBalancePaid`, …), and sets `cashPayment/homeCreditPayment/skyroPayment/inHouseInstallment = null`.
- **Also add `transactionData["salmonPayment"] = null`** to the existing Cash, Home Credit, Skyro, and In-House branches (mirror how each branch already nulls the other three objects), so the field is consistent across all transaction types.
- Display→constant mapping: ensure `"Salmon Transaction"` maps to `AppConstants.TRANSACTION_TYPE_SALMON` (same `when` pattern as `AccessoryTransactionActivity` ~line 1309–1313; locate the equivalent in this file and add the case).

> The transaction-type `when` blocks here are **string-based, not enum-exhaustive**, so the compiler will NOT flag missed branches. Each listed site must be updated manually — use this list as the checklist.

---

### F. Accessory sale creation — Salmon financing option

Same pattern as Device; `AccessoryTransactionActivity` also reuses the shared HC/Skyro details card.

**12. `app/src/main/java/com/techcity/techcitysuite/AccessoryTransactionActivity.kt`**
- `transactionTypes` array (~line 67): add `"Salmon Transaction"`.
- Transaction-type color `when` (~line 192): add `"Salmon Transaction" -> R.color.orange`.
- `showAppropriateCard` (~line 259): add `"Salmon Transaction"` case mirroring Skyro (orange, title `"Salmon Details"`).
- Balance-update `when` (~line 663): add `"Salmon Transaction" -> updateHCBalance()` (join the HC/Skyro handling).
- Save data (~line 1189): add the `"Salmon Transaction"` / `TRANSACTION_TYPE_SALMON` branch building `salmonPayment` as a copy of the Skyro branch; null it in the other branches.
- Display→constant mapping (~line 1309–1313): add `"Salmon Transaction" -> AppConstants.TRANSACTION_TYPE_SALMON`.
- Mirror Brand Zero / subsidy handling wherever Skyro has it in this file (verify the accessory subsidy path matches the device one).

---

### G. End-of-Day report — Salmon sales, cash flow, receivables

**13. `app/src/main/java/com/techcity/techcitysuite/EndOfDayReportActivity.kt`**

*Data classes (Part 2):*
- `SalesSummary`: add `val salmonSales: InstallmentBreakdown`.
- `CashFlowSummary`: add `val salmonReceivable: Double`.
- `ReceivablesBreakdown`: add `val salmon: Double`.

*Processing (Part 6):*
- `processDeviceSales` & `processAccessorySales` (~lines 920, 1001): add salmon counters; add an `AppConstants.TRANSACTION_TYPE_SALMON` branch reading the nested `salmonPayment` object (`downpaymentAmount`, `balance`) exactly like Skyro; add `salmonAmount` to `totalSales`; return the new `salmonSales = InstallmentBreakdown(...)`.
- `processDeviceCashFlow` & `processAccessoryCashFlow` (~lines 1154, 1270): add an `AppConstants.TRANSACTION_TYPE_SALMON` branch mirroring Skyro — route downpayment to the correct payment source, `salmonReceivable += balance`, `brandZeroSubsidy += subsidy`; return `salmonReceivable`.
- `processTransactionData` (~line 793): include salmon in `totalReceivables`; add `salmonReceivableNet = deviceCashFlow.salmonReceivable + accessoryCashFlow.salmonReceivable` (mirror Skyro — no subsidy subtraction); pass `salmon = salmonReceivableNet` into `ReceivablesBreakdown` and add it to the receivables `total`.
- `buildLedgerSummaryFromCashFlow` (~line 504): add `salmon = deviceCashFlow.salmonReceivable + accessoryCashFlow.salmonReceivable` to `ReceivablesBreakdown` and include in `total`.

*Load / Save (Parts 3 & 8):*
- `parseSalesSummary` (~line 403): parse the `"salmon"` sub-map (default `InstallmentBreakdown(0,0.0,0.0,0.0)` when absent — backward compatible with old reports).
- `parseCashFlowSummary` (~line 466): parse `"salmonReceivable"` (default `0.0`).
- `performSave` (~line 2141): add `"salmon"` to the device & accessory `salesSummary` maps (count/amount/downpayment/balance) and `"salmonReceivable"` to the device & accessory `cashFlowSummary` maps.
- Every other constructor of `SalesSummary` / `CashFlowSummary` / `ReceivablesBreakdown` (the empty/default returns in the parse functions) must be updated for the new fields — the compiler will flag these since these are concrete constructors.

*Display (Part 7) — requires new layout views, see file 14:*
- `displayDeviceSales` (~line 1449) & `displayAccessorySales` (~line 1495): add a Salmon block mirroring the Skyro block (`deviceSalmonCount`/`deviceSalmonAmount` + conditional `deviceSalmonDpRow`/`deviceSalmonBalanceRow`; and `accessorySalmon*` equivalents).
- `displayDeviceCashFlow` (~line 1602) & `displayAccessoryCashFlow` (~line 1697): add the Salmon receivable row (`deviceCfSalmonReceivableRow`/`deviceCfSalmonReceivable`, `accessoryCf*`) and **include `salmonReceivable` in the `totalCashFlow` sum**.
- `displayLedgerSummary` receivables block (~line 2026): add the Salmon row (`ledgerReceivablesSalmonRow`/`ledgerReceivablesSalmon`).
- `displayDailySummary` (~line 1850): **no change needed** — `summaryDeviceSales`/`summaryAccessorySales` already bind to `totalSales`, which now includes Salmon (this satisfies the "rolled into the totals" requirement).

**14. `app/src/main/res/layout/activity_end_of_day_report.xml`**
Add new rows by copying the existing Skyro row blocks and renaming IDs. Insert each **after** its Skyro counterpart:
- Device Sales section (after `deviceSkyroBalanceRow`, ~line 596): `deviceSalmonRow`, `deviceSalmonCount`, `deviceSalmonAmount`, `deviceSalmonDpRow`, `deviceSalmonDpAmount`, `deviceSalmonBalanceRow`, `deviceSalmonBalanceAmount`.
- Accessory Sales section (after `accessorySkyroBalanceRow`, ~line 596/1375): `accessorySalmonRow` + the same six IDs prefixed `accessory`.
- Device cash flow (after `deviceCfSkyroReceivableRow`, ~line 1017): `deviceCfSalmonReceivableRow`, `deviceCfSalmonReceivable`.
- Accessory cash flow (after `accessoryCfSkyroReceivableRow`, ~line 1796): `accessoryCfSalmonReceivableRow`, `accessoryCfSalmonReceivable`.
- Ledger Receivables (after `ledgerReceivablesSkyroRow`, ~line 3745): `ledgerReceivablesSalmonRow`, `ledgerReceivablesSalmon`.
- Use an orange label/accent for the Salmon rows to match the brand color; otherwise copy the Skyro row styling verbatim.

*Service Transactions summary (added after follow-up — Salmon Payment service txns were not tallied):*
- `ServiceSummary` data class: add `val salmonPayment: ServiceTypeBreakdown` (after `skyroPayment`).
- `processServiceSummary` (~line 1125): add salmon counters (`salmonCount`/`salmonVolume`/`salmonFees`), a `"Salmon Payment"` branch mirroring `"Skyro Payment"`, include `salmonFees` in `totalFees` and `salmonVolume` in `totalVolume`, and return `salmonPayment = ServiceTypeBreakdown(...)`.
- `parseServiceSummaryFromSaved` (~line 463): add `salmonPayment = ServiceTypeBreakdown(0, 0.0, 0.0)` (breakdowns are not persisted; only totals are — same as Skyro/HC).
- `displayServiceSummary` (~line 1674): add a `serviceSalmonRow` block mirroring the Skyro block, inserted before the HC block.
- Layout: add `serviceSalmonRow`/`serviceSalmonCount`/`serviceSalmonVolume`/`serviceSalmonFees` in the Service Transactions section between the Skyro and HC rows (orange label). To stay under the ViewBinding ~254-ID cap, removed 3 unused container IDs (`deviceCashFlowCard`, `accessoryCashFlowCard`, `serviceCashFlowCard`); net layout IDs = 253.

---

### H. Accounts Receivable — Salmon receivables (added after follow-up)

Salmon device/accessory sales create a `salmonPayment.balance` receivable. `AccountReceivableActivity` lists and settles unpaid HC/Skyro/In-House balances, so Salmon must join it or its receivables are invisible and un-settleable. Salmon mirrors Skyro; this screen's layout has only 4 filter buttons, so no ViewBinding-limit concern.

**15. `app/src/main/java/com/techcity/techcitysuite/AccountReceivableActivity.kt`**
- `enum class TransactionTypeFilter` (~line 42): add `SALMON` (after `SKYRO`).
- `setupFilterButtons` (~line 130): add `filterSalmonButton` listener → `setTransactionTypeFilter(SALMON)`.
- `updateTransactionTypeButtonStates` (~line 170): add `filterSalmonButton` to the reset block and a `SALMON -> …selectedAlpha` case.
- `loadDeviceReceivables` (~line 239) & `loadAccessoryReceivables` (~line 371): add a Salmon query mirroring the Skyro block — `whereEqualTo("transactionType", AppConstants.TRANSACTION_TYPE_SALMON)`, read `salmonPayment`, skip if `isBalancePaid`, else `parseDeviceReceivable(... "Salmon", salmonPayment)` / `parseAccessoryReceivable(...)`.
- `parseDeviceReceivable` brand-zero `when` (~line 325): add `"Salmon"` to the `"Home Credit", "Skyro"` case. (`parseAccessoryReceivable` needs no change — Salmon flows through its generic `else`/balance paths; accessories carry no Brand Zero.)
- `applyFilters` company `when` (~line 496): add `SALMON -> filter { it.transactionType == "Salmon" }`.
- `updateSummary` filter-label `when` (~line 568): add `SALMON -> "Salmon Receivable"`. *(Exhaustive enum `when` — compiler flags this if missed.)*
- Adapter badge `when` (~line 860): add `"Salmon" -> "Salmon" to R.color.orange`.
- Mark-as-paid field-path `when` (~line 741): add `"Salmon" -> "salmonPayment.isBalancePaid"`.

**16. `app/src/main/res/layout/activity_account_receivable.xml`**
- Add `filterSalmonButton` (orange, text "Salmon") between `filterSkyroButton` and `filterInHouseButton`, copying the Skyro button attributes.

---

### I. Device & Accessory transaction LIST screens — Salmon filter + display (added after follow-up)

`DeviceTransactionListActivity` and `AccessoryTransactionListActivity` each have a filter toggle row (All / Cash / HC / Skyro / In-House) and per-card display that were missed in the original plan. Salmon mirrors Skyro; Salmon reuses Skyro's payment-details type and parser (identical shape), so no new model class/parse function.

**Models:**
- `DeviceTransaction.kt`: add `val salmonPayment: SkyroPaymentDetails? = null` (after `skyroPayment`).
- `AccessoryTransaction.kt`: add `val salmonPayment: AccessorySkyroPaymentDetails? = null` (after `skyroPayment`).

**17 & 18. `DeviceTransactionListActivity.kt` / `AccessoryTransactionListActivity.kt`** (identical pattern):
- `enum TransactionTypeFilter`: add `SALMON` (after `SKYRO`).
- Add `private val COLOR_SALMON = R.color.orange`.
- Document parse: add `salmonPayment = parseSkyroPayment(data["salmonPayment"] as? Map<*, *>)` (reuse the Skyro parser — same shape).
- Filter button listener (`filterSalmonButton`), `updateFilterButtonStates` reset + highlight `when`, `applyFilter` `when`, and the Total-Sales `filterLabel` `when` (`SALMON -> "Total Sales (SLM)"`).
- DP/Balance summary: add `SALMON` to the `HOME_CREDIT, SKYRO, IN_HOUSE` group `when` and a `TRANSACTION_TYPE_SALMON` branch in the inner `forEach` (reads `salmonPayment`).
- `getTransactionTypeColor` → `TRANSACTION_TYPE_SALMON -> COLOR_SALMON`; `getTransactionTypeBadgeLabel` → `"SALMON"`.
- Adapter detail card `when`: add a `TRANSACTION_TYPE_SALMON` branch mirroring Skyro (device shows Brand Zero; accessory does not).

**19 & 20. `activity_device_transaction_list.xml` / `activity_accessory_transaction_list.xml`:**
- Add `filterSalmonButton` (orange, text "SLM") between `filterSkyroButton` and `filterInHouseButton`.

---

## Files to Create

None. (No new color, no new activity, no new model class. Salmon reuses the existing shared HC/Skyro details UI on the sale screens and the existing `salmonPayment`/receivable patterns. A real Salmon logo drawable will be added later by the user; a placeholder is used for the Salmon Payment card only.)

---

## Implementation Order

1. **`Constants.kt`:** add `TRANSACTION_TYPE_SALMON` (shared dependency for the sale screens + EOD).
2. **Feature 4 (lowest risk, self-contained):** `AddFinancingAccountActivity` dropdown + `FinancingAccountDetailActivity` badge color.
3. **Feature 3:** `strings.xml` → `activity_financing_account_list.xml` → `FinancingAccountListActivity.kt`.
4. **Feature 1:** `activity_service_transaction_list.xml` → `ServiceTransactionListActivity.kt`.
5. **Feature 2:** `activity_transaction_type.xml` → `TransactionTypeActivity.kt` → `TransactionDetailsActivity.kt` (all `Salmon Payment` branches).
6. **Section E — Device sale:** `DeviceTransactionActivity.kt` (produces `salmonPayment`).
7. **Section F — Accessory sale:** `AccessoryTransactionActivity.kt`.
8. **Section G — End-of-Day (most complex, do last):** `EndOfDayReportActivity.kt` data classes → processing → load/save → display, then `activity_end_of_day_report.xml` rows. Doing the sale screens (6–7) first means there is real Salmon data to verify the report against.
9. Build (`./gradlew assembleDebug`) and smoke-test each flow end-to-end (create a Salmon device sale + accessory sale → generate EOD → confirm Salmon rows, totals, and receivables).

---

## Data / Migration Considerations

- **No schema migration or backfill.** New string values (`"Salmon Payment"`, `"Salmon"`, `"Salmon Transaction"`) and the new nested `salmonPayment` object flow into the existing collections (`service_transactions`, `financing_accounts`, `device_transactions`, `accessory_transactions`).
- **Backward compatibility:** existing device/accessory documents have no `salmonPayment` field → EOD readers default to `0.0`/empty. Existing saved daily summaries have no salmon sub-maps → parse functions default to zero. No existing record is rewritten.
- Existing records are untouched; exact-string `transactionType` matching keeps Cash/HC/Skyro/In-House/Salmon mutually exclusive.

---

## Risks / Things to Watch

1. **`TransactionProcessor.processTransaction`** — confirm it does not branch on `"Skyro Payment"`/`"Home Credit Payment"` in a way Salmon must also join. The ledger math in `TransactionDetailsActivity.calculateLedgerEntries` is what gets persisted; `processTransaction` appears to be legacy numbering. Verify during implementation and add a Salmon branch only if one exists.
2. **String-based `when` (no compiler safety) on the sale screens & EOD processing:** unlike the enum `when`s in Features 1/3, the device/accessory/EOD `when` blocks key off string/constant values with an `else`, so a missed branch compiles silently and shows wrong totals. The site lists in Sections E/F/G are the checklist — treat them as exhaustive.
3. **Brand Zero subsidy net quirk (existing behavior):** in `processTransactionData`, `hcReceivableNet` subtracts the *combined* `brandZeroSubsidy`, while Skyro's net does not subtract subsidy. Mirror **Skyro** for Salmon (`salmonReceivableNet` = raw salmon receivable, no subsidy subtraction), so Salmon behaves consistently with the company it was modeled on. The subsidy still accumulates into the brand-zero total.
4. **EOD data-class constructors:** adding fields to `SalesSummary`/`CashFlowSummary`/`ReceivablesBreakdown` breaks every constructor call until updated — the compiler will list them; that's the safety net for Section G's Kotlin side.
5. **Filter-row width:** 8 buttons (service) / 5 buttons (financing) may truncate labels on small screens. Verify visually; apply the `HorizontalScrollView` fallback only if needed.
6. **Fixed-fee edge case (service payment):** ensure the ₱18 fee displays on entry and at amount 0 (mirroring Skyro), and that clearing the amount field does not zero it — the `afterTextChanged` guard change covers this.
7. **Placeholder logo:** keep the placeholder swap-out trivial (single `ImageView` `src`) so replacing it with the real logo later is a one-line change.

---

## Out of Scope (per CLAUDE.md scope rules)

- Any refactor of the existing Cash/HC/Skyro/In-House code paths beyond adding the parallel Salmon branch.
- Real Salmon logo asset (supplied later).
- Brand Zero subsidy *percentage* for Salmon — assumed identical to Skyro/HC (phone/tablet/laptop rates in `AppConstants`); flag if Salmon needs different rates.
