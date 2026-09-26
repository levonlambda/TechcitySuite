# Technical Plan: Store Location Data Separation

Spec: `_specs/store-location-data-separation.md`

## Approach Summary

- **Single helper for the visibility rule.** A new `StoreLocationHelper` object reads the device's store location and "is primary" flag from SharedPreferences and exposes one matching function used by every scoped screen: a record matches when its stored location name equals the device's store location, or when the device is primary and the record's location is missing/empty (legacy rule). It also exposes an "is configured" check and a shared loader for active store locations from `accessory_locations`.
- **Client-side filtering, no new Firestore indexes.** Every scoped query today is already narrowed server-side by date, month, or status. The store location condition is applied in Kotlin to that already-narrow result. This avoids creating new composite indexes in the Firebase console (there is no `firestore.indexes.json` in the repo) and is the only way to implement the legacy rule, since Firestore cannot query for a missing field. The reconciliation list, which today fetches the whole `inventory_reconciliations` collection, keeps that query and filters client-side too.
- **"Is primary" evaluated at settings save.** Program Settings reads `isPrimary` from `accessory_locations` while building the dropdown and stores a boolean preference next to the store location name.
- **End of Day document ID becomes `<yyyy-MM-dd>_<storeLocationName>`.** Legacy date-only documents are never written to.

---

## Files to Create

### 1. `app/src/main/java/com/techcity/techcitysuite/StoreLocationHelper.kt`
Kotlin `object` with:
- `getStoreLocation(context): String` — reads `AppConstants.KEY_STORE_LOCATION`.
- `isPrimary(context): Boolean` — reads the new `AppConstants.KEY_STORE_LOCATION_IS_PRIMARY` (default false).
- `isConfigured(context): Boolean` — store location non-blank.
- `matches(context, recordLocation: String?): Boolean` — the visibility rule described above (exact, case-sensitive name match; legacy fallback when primary and `recordLocation` is null or blank).
- `data class StoreLocationOption(name, id, isPrimary)` and `suspend fun loadActiveLocations(db): List<StoreLocationOption>` — fetches `accessory_locations`, skips `active == false`, reads `name`, `isPrimary` (default false), and the document id. Used by the reconciliation dialog (and available to Program Settings).
- `const val NOT_CONFIGURED_MESSAGE = "Please configure Store Location in Settings first"` — reused for every block/empty-state message.

---

## Files to Modify

### 2. `app/src/main/java/com/techcity/techcitysuite/Constants.kt`
- Add `const val KEY_STORE_LOCATION_IS_PRIMARY = "store_location_is_primary"` under "User and Location".

### 3. `app/src/main/java/com/techcity/techcitysuite/ProgramSettingsActivity.kt`
- Add a private `KEY_STORE_LOCATION_IS_PRIMARY` constant beside the existing private keys (this file duplicates keys locally).
- Add a `locationNameToIsPrimary` map beside `locationNameToId`; populate it in `setupStoreLocationDropdown()` from `document.getBoolean("isPrimary") ?: false`.
- In `saveSettings()`:
  - If the dropdown map is non-empty and the selected text is not a key in it, show a validation message ("Please select a store location from the list"), do not save, do not finish.
  - If the dropdown map is empty (load failed / offline), leave the three location preferences (`store_location`, `store_location_id`, `store_location_is_primary`) untouched and save everything else.
  - Otherwise save `store_location`, `store_location_id` (as today) and `store_location_is_primary`.
  - Return a Boolean so the caller knows whether to `finish()`.
- In `setupButtonListeners()` Save click: compare the selected location with the saved `store_location`. If it differs and a previous value existed, show an `AlertDialog` ("Change Store Location?" — explains the device will only show records for the new store and that existing records are not moved) with Confirm/Cancel; Confirm runs the save. If unchanged or previously empty, save directly.
- The layout already uses `android:inputType="none"` on `storeLocationInput`, so no layout change is needed for dropdown-only input.

### 4. `app/src/main/res/layout/dialog_new_reconciliation.xml`
- Change the Location `TextInputLayout` style to `Widget.MaterialComponents.TextInputLayout.OutlinedBox.ExposedDropdownMenu`, hint "Store Location", helper text "Store location for this reconciliation".
- Replace the `TextInputEditText` (`@+id/locationInput`) with an `AutoCompleteTextView` keeping the same id, `inputType="none"`, `focusable="false"`, `cursorVisible="false"` (same pattern as `statusFilterDropdown`).
- Update the subtitle text to "Select store location and inventory status to reconcile".

### 5. `app/src/main/java/com/techcity/techcitysuite/PhoneInventoryListActivity.kt`
- `onCreate` / `loadReconciliations()`: if `StoreLocationHelper.isConfigured` is false, show the not-configured message in the empty state, hide the list, and skip the query. Also block the add button with the same message.
- `showNewReconciliationDialog()`:
  - Change `locationInput` lookup to `AutoCompleteTextView`.
  - Load options via `StoreLocationHelper.loadActiveLocations(db)` in a coroutine; bind an `ArrayAdapter` of names; pre-select the device's store location; on load failure show an error in the dialog and keep the Create button disabled.
  - Remove the `setText("All")` default; validation message becomes "Please select a store location".
  - After a successful create, if the chosen location differs from the device's store location, show the message "Reconciliation created for <location>. It will only appear on devices set to that store."; otherwise the existing success message.
- `createReconciliation(location, statusFilter)`:
  - Remove the `isAllLocations` branch; `locationMatch` is `itemLocation.equals(location, ignoreCase = true)` only.
  - Write `"location" to location` (no "All" normalisation).
- `fetchReconciliations()`: after mapping, filter with `StoreLocationHelper.matches(this, location)`. Note: on a primary device this also admits records whose `location` is empty. Legacy "All" records must be admitted on a primary device too, so the filter for this screen is: `matches(...) || (isPrimary && location.equals("All", ignoreCase = true))`.

### 6. `app/src/main/java/com/techcity/techcitysuite/ReconciliationDetailActivity.kt`
- After `fetchReconciliation()` returns data (in the existing load flow), apply the same reconciliation visibility check as the list (match, or primary + "All", or primary + empty). If it fails, show "This reconciliation belongs to another store location" and `finish()`.
- No other changes; `ManufacturerDetailActivity` is only reachable from this screen and needs no guard.

### 7. `app/src/main/java/com/techcity/techcitysuite/DeviceTransactionListActivity.kt`
- In `loadTransactions()` (the snapshot listener setup around line 596): if not configured, show the not-configured message in `emptyMessage`, clear the list, and return without registering a listener.
- After `parseSnapshotDocuments(...)`, filter results with `StoreLocationHelper.matches(this, it.transaction.userLocation)` before assigning to `transactions`. (Check the display wrapper's field name during implementation; the parsed `DeviceTransaction` carries `userLocation`.)

### 8. `app/src/main/java/com/techcity/techcitysuite/AccessoryTransactionListActivity.kt`
- Same two changes as the device list, in its `loadTransactions()` listener (around line 600) and its parse step.

### 9. `app/src/main/java/com/techcity/techcitysuite/ServiceTransactionListActivity.kt`
- Same not-configured guard in its listener setup (around line 550).
- Inside the document loop, `continue` when `StoreLocationHelper.matches(this, data["userLocation"] as? String)` is false, before constructing the display item.

### 10. `app/src/main/java/com/techcity/techcitysuite/TransactionDetailsActivity.kt`
- In the save flow (around line 1151, next to the existing `user.isEmpty()` check), add the same block for `userLocation.isEmpty()` using the shared message, restoring the progress bar and Save button as the user check does.

### 11. `app/src/main/java/com/techcity/techcitysuite/LedgerViewActivity.kt`
- `loadTransactionsFromFirebase()`: if not configured, show the not-configured message in `emptyMessage`, hide the list, and return.
- Filter the three loaded sets before caching:
  - service: skip documents whose `userLocation` does not match (in the parse loop);
  - device and accessory raw maps: filter on `data["userLocation"] as? String`.
- No change to display numbering code; sequence numbers are already assigned after the cache is built.

### 12. `app/src/main/java/com/techcity/techcitysuite/MenuActivity.kt`
- In `setupDeviceTransactionListener()`, inside the `ADDED` branch, only call `NotificationHelper.showDeviceTransactionNotification` when `StoreLocationHelper.matches(this, data["userLocation"] as? String)` is true.

### 13. `app/src/main/java/com/techcity/techcitysuite/ExpenseListActivity.kt`
- `loadExpenses()` (the caller of `fetchExpensesForMonth`): if not configured, show the not-configured message as the empty state, set total to zero, and skip the query.
- `fetchExpensesForMonth()`: after mapping, filter with `StoreLocationHelper.matches(this, it.storeLocation)`.
- `saveExpense(...)`: if `storeLocation` is blank, call `onFailure(NOT_CONFIGURED_MESSAGE)` and return before writing.

### 14. `app/src/main/java/com/techcity/techcitysuite/EndOfDayReportActivity.kt`
- Add private `storeLocation` (read once in `onCreate` via the helper) and a `reportDocumentId` computed as `"$queryDate" + "_" + storeLocation`, recomputed whenever `queryDate` changes (date picker).
- `onCreate`: if not configured, set `statusMessage` to the not-configured message, disable Generate and Save, and skip the existing-report check.
- Header: set `binding.titleText` to "End of Day Report – <storeLocation>" (reuses an existing id; the layout is at the ViewBinding id cap, so no new views).
- `checkForExistingReport()`: look up `reportDocumentId`; if it does not exist and the device is primary, fall back to the legacy id `queryDate`; load whichever exists.
- `fetchDeviceTransactions()`, `fetchAccessoryTransactions()`, `fetchServiceTransactions()`: keep the date + status queries; filter the mapped list with `StoreLocationHelper.matches(this, data["userLocation"] as? String)` before returning. All downstream processing (`processTransactionData` and every `process*` / `display*` function) is unchanged and therefore operates only on the filtered set, including the transaction id lists.
- `DailySummaryData`: add `storeLocation: String`; populate it in `processTransactionData`.
- `saveReport()`: confirmation message becomes "Save End of Day report for <displayDate> (<storeLocation>)? This will overwrite any existing report for this date and store."
- `performSave()`: add `"storeLocation" to data.storeLocation` to the document map; write to `.document(reportDocumentId)` instead of `.document(data.date)`.
- `loadExistingReport(...)`: unchanged (legacy documents simply have no `storeLocation`).

### 15. `app/src/main/java/com/techcity/techcitysuite/EndOfDayListActivity.kt`
- `loadReports()`: if not configured, show the not-configured message and skip the query.
- `fetchReportsForMonth()`: keep the date-range query; after mapping, filter with `StoreLocationHelper.matches(this, data["storeLocation"] as? String)` (a legacy document has no `storeLocation`, so it is admitted only on a primary device). Then, if more than one report shares the same `date`, keep the one whose `storeLocation` is non-empty (the per-store report) and drop the legacy one. Existing `documentId` pass-through to the report screen stays as is.

### 16. `app/src/main/java/com/techcity/techcitysuite/AddFinancingAccountActivity.kt`
- Around line 320: for new accounts, read `storeLocation` from `StoreLocationHelper.getStoreLocation(this)` instead of `settings?.storeLocation`. `createdBy` and edit-mode behaviour are unchanged.

---

## Files Explicitly Unchanged

- `AccountReceivableActivity.kt`, `InHousePaymentActivity.kt`, `FinancingAccountListActivity.kt`, `FinancingAccountDetailActivity.kt` — shared modules, no filtering.
- `DeviceTransactionActivity.kt`, `AccessoryTransactionActivity.kt` — already stamp `userLocation` and already block when unset.
- `ManufacturerDetailActivity.kt`, `BarcodeScannerActivity.kt` — reached only via the guarded detail screen.
- Model classes (`DeviceTransaction`, `AccessoryTransaction`, `ServiceTransaction`, `Expense`, `InventoryItem`, `FinancingAccount`, `AppSettings`) — no new fields.
- `activity_program_settings.xml`, `activity_end_of_day_report.xml` — no layout changes.

---

## Implementation Order

1. `Constants.kt` — add the new preference key.
2. `StoreLocationHelper.kt` — create the helper (everything else depends on it).
3. `ProgramSettingsActivity.kt` — capture `isPrimary`, validate dropdown selection, confirmation dialog.
4. Transaction lists: `DeviceTransactionListActivity.kt`, `AccessoryTransactionListActivity.kt`, `ServiceTransactionListActivity.kt`; then `TransactionDetailsActivity.kt` save guard.
5. `LedgerViewActivity.kt`.
6. `MenuActivity.kt` notification filter.
7. `ExpenseListActivity.kt`.
8. `EndOfDayReportActivity.kt`, then `EndOfDayListActivity.kt`.
9. `dialog_new_reconciliation.xml`, `PhoneInventoryListActivity.kt`, `ReconciliationDetailActivity.kt`.
10. `AddFinancingAccountActivity.kt`.
11. Build with `./gradlew assembleDebug`; fix compile errors only.

---

## Dependencies / Libraries

None. Uses existing Firestore, coroutines, Material components.

---

## Migration and Data Considerations

- **No Firestore migration.** No existing document is modified or deleted.
- **No new indexes.** All new conditions are applied client-side to already date/month/status-scoped results.
- **`daily_summaries` gains a second ID scheme.** New reports use `<yyyy-MM-dd>_<storeLocationName>`; old date-only documents remain and are readable only on primary devices. Both can coexist for the same date; the list prefers the per-store one.
- **`accessory_locations` must have exactly one document with `isPrimary: true`** for the legacy rule to apply. If none is flagged, no device sees legacy records; if several are flagged, each of those stores sees them.
- **Every device must re-save Program Settings once after the update** so the `store_location_is_primary` preference is written. Until then the flag defaults to false and legacy records are hidden even on the primary store's devices.
- **Store location names must not contain `/`** (Firestore document ID rule) because the name becomes part of the End of Day document ID.

---

## Risks and Things to Watch

- **End of Day correctness.** The three fetch functions are the single choke point; every summary is derived from their output. Verify after implementation that no other code path in `EndOfDayReportActivity` queries transactions (grep for `collection(` in that file: only the three fetches plus `daily_summaries` reads/writes should remain).
- **Existing report check fallback.** On a primary device the fallback to the legacy id must only run when the per-store document does not exist, otherwise a freshly saved per-store report could be shadowed by the legacy one.
- **Reconciliation list filter has a third case** ("All" on a primary device) that the generic helper does not cover; keep the extra condition local to the two reconciliation screens so the helper stays strict for transactions.
- **ViewBinding id cap on the End of Day layout** (see memory note): the store location is shown by reusing `titleText`; do not add ids to that layout.
- **Service transaction save guard** must restore the progress bar and Save button exactly like the existing user guard, or the screen is left disabled.
- **Program Settings offline path.** When the dropdown fails to load, the map is empty; the save must not blank out the previously saved location or its primary flag.
- **Case sensitivity.** Transaction and expense matching is exact and case-sensitive by spec; only the reconciliation item selection against inventory `location` stays case-insensitive.
- **Notification listener** still downloads the entire `device_transactions` collection (existing behaviour); this feature only filters what is shown, it does not narrow the listener.
