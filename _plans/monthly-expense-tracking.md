# Technical Plan: Monthly Expense Tracking

Spec: `_specs/monthly-expense-tracking.md` (all open questions answered: edit/delete allowed with confirmation dialog + password; no future-month navigation; toggle disabled by default; description-only entries, no categories; not connected to End of Day).

## Reference Pattern

The primary template is **`EndOfDayListActivity`** (`app/src/main/java/com/techcity/techcitysuite/EndOfDayListActivity.kt`) and its layout **`activity_end_of_day_list.xml`** — it already implements exactly the month-traversal UI the spec asks for: `prevMonthButton` / `monthLabel` / `nextMonthButton` header, `currentYear`/`currentMonth` state initialized from `Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila"))`, `navigateMonth(delta)`, a RecyclerView with an inner adapter using item ViewBinding, progress bar, and empty-state layout.

Password verification reuses the existing **`dialog_password.xml`** + **`AppSettingsManager.verifyPassword(password)`** pattern (see `MenuActivity.showPasswordDialog()`, `MenuActivity.kt:212-295`).

---

## Files to be Created

### 1. `app/src/main/java/com/techcity/techcitysuite/Expense.kt`
Kotlin data class for one expense entry:
- `documentId: String` (Firestore doc id, `""` before save)
- `description: String`
- `amount: Double`
- `date: String` — `yyyy-MM-dd` in GMT+8
- `monthKey: String` — `yyyy-MM` in GMT+8
- `createdBy: String` — from `AppConstants.KEY_USER` SharedPreferences (consistent with other records)
- `timestamp` — Firestore server timestamp (written via `FieldValue.serverTimestamp()` in the save map; field typed as `com.google.firebase.Timestamp?` on reads)

### 2. `app/src/main/java/com/techcity/techcitysuite/ExpenseListActivity.kt`
The Expenses screen, structured like `EndOfDayListActivity` (same PART section comments, per-Activity `CoroutineScope(Dispatchers.Main + Job())` cancelled in `onDestroy()`):

- **Month state & navigation**: `currentYear`/`currentMonth` initialized to Asia/Manila now; `prevMonthButton` → `navigateMonth(-1)`; `nextMonthButton` → `navigateMonth(+1)`, **disabled (and alpha-dimmed) when the displayed month is the current month** — no future navigation. Past months unbounded.
- **Load**: query `expenses` collection with `whereEqualTo("monthKey", key)` and sort by `timestamp` descending **client-side** (avoids needing a Firestore composite index for equality + orderBy on different fields). Runs on `Dispatchers.IO` with `.await()`.
- **Total**: `totalLabel` at top = sum of loaded entries' amounts, formatted with `NumberFormat.getCurrencyInstance(Locale("en", "PH"))` (same as `EndOfDayListActivity.formatCurrency`). Shows ₱0.00 for empty months.
- **Empty state**: "No expenses recorded for <Month Year>" layout, same show/hide logic as EOD list.
- **Add flow**: `addButton` opens the add/edit dialog (`dialog_add_expense.xml`) in "add" mode. Validation: description non-blank after trim; amount parses to Double and > 0; inline error text, save blocked otherwise. Save writes a new document to `expenses` with date/monthKey computed from **now in Asia/Manila** (always current month, regardless of displayed month). On success: dismiss, snap the view back to the current month, reload. On failure: error message shown, dialog stays open, nothing added.
- **Edit flow**: tapping an entry shows a small options dialog (Edit / Delete / Cancel).
  - *Edit*: opens the same add/edit dialog pre-filled. On Save (after validation), show the password dialog (reuse `dialog_password.xml` + `AppSettingsManager.verifyPassword`, replicating `MenuActivity.showPasswordDialog()` locally as other activities do); on correct password, update `description` and `amount` only — `date`/`monthKey` unchanged, so an edit never moves an entry to another month. Reload the displayed month.
  - *Delete*: confirmation `AlertDialog` ("Delete this expense?" with description + amount shown), then the password dialog; on correct password, delete the document and reload.
- **Inner adapter** `ExpenseAdapter` (RecyclerView, `ItemExpenseBinding`), matching the `EODReportAdapter` structure; binds description, formatted amount, and display date (e.g. "July 5, 2026" via `SimpleDateFormat`).

### 3. `app/src/main/res/layout/activity_expense_list.xml`
Modeled directly on `activity_end_of_day_list.xml`:
- Blue header (`techcity_blue`) with back button and title "Expenses"
- Month navigation row: `prevMonthButton` (ImageButton, left arrow) / `monthLabel` (e.g. "July 2026") / `nextMonthButton` (right arrow)
- **Total row**: "Total Expenses" label + `totalLabel` amount, prominent, in/below the header
- `progressBar`, `expensesRecyclerView`, `emptyStateLayout` (+ `emptyMessage`)
- `addButton` (same add-button style/placement as the EOD list screen)

### 4. `app/src/main/res/layout/dialog_add_expense.xml`
- Dialog title ("Add Expense" / "Edit Expense" set from code)
- `TextInputLayout` + `TextInputEditText` for **Description**
- `TextInputLayout` + `TextInputEditText` for **Amount** (`inputType="numberDecimal"`)
- Inline `errorMessage` TextView, `progressBar`, and Save/Cancel buttons (same structure as `dialog_password.xml`)

### 5. `app/src/main/res/layout/item_expense.xml`
Card row for one expense: description (primary text), date recorded (secondary), amount (right-aligned, bold).

---

## Files to be Modified

### 1. `app/src/main/java/com/techcity/techcitysuite/Constants.kt`
- Add to Feature Settings block: `const val KEY_EXPENSES_ENABLED = "expenses_enabled"`
- Add to collection names block: `const val COLLECTION_EXPENSES = "expenses"`

### 2. `app/src/main/res/layout/activity_program_settings.xml`
- Add an **Expenses** row (label + `SwitchMaterial` with id `expensesSwitch`) inside the Feature Settings card, after the existing switches (`accountReceivableSwitch` … `deviceTransactionNotificationsSwitch`), copying the existing row structure exactly.

### 3. `app/src/main/java/com/techcity/techcitysuite/ProgramSettingsActivity.kt`
- Companion object: add `private const val KEY_EXPENSES_ENABLED = "expenses_enabled"`
- `loadSettings()`: `binding.expensesSwitch.isChecked = prefs.getBoolean(KEY_EXPENSES_ENABLED, false)` — **default false** per spec
- `saveSettings()`: `editor.putBoolean(KEY_EXPENSES_ENABLED, binding.expensesSwitch.isChecked)`

### 4. `app/src/main/res/layout/activity_menu.xml`
- Add an **Expenses** menu card (`expensesCard` containing `expensesButton`), copying the structure of the existing `endOfDayCard` (`activity_menu.xml:394`) / `financingAccountsCard` block.

### 5. `app/src/main/java/com/techcity/techcitysuite/MenuActivity.kt`
- `updateFeatureVisibility()`: show/hide `binding.expensesCard` from `prefs.getBoolean(AppConstants.KEY_EXPENSES_ENABLED, false)` — default false
- `setupMenuButtons()`: `expensesButton` → `startActivity(Intent(this, ExpenseListActivity::class.java))`

### 6. `app/src/main/AndroidManifest.xml`
- Register `<activity android:name="com.techcity.techcitysuite.ExpenseListActivity" ... />` following the attributes used by `EndOfDayListActivity`'s entry.

---

## Implementation Order

1. `Constants.kt` — new preference key + collection constant
2. `Expense.kt` — data model
3. Layouts: `activity_expense_list.xml`, `item_expense.xml`, `dialog_add_expense.xml`
4. `ExpenseListActivity.kt` — month navigation + load/total, then add flow, then edit/delete (options dialog → confirmation → password)
5. `AndroidManifest.xml` — register the activity
6. Menu integration: `activity_menu.xml` + `MenuActivity.kt`
7. Settings integration: `activity_program_settings.xml` + `ProgramSettingsActivity.kt`
8. Build with `./gradlew assembleDebug` and verify

## Dependencies / Libraries

None. Everything uses libraries already in the project (Firestore + coroutines `.await()`, Material components, RecyclerView, View Binding).

## Migration / Data Considerations

- New Firestore collection `expenses`; no existing collections, documents, or model classes are touched.
- No Firestore **composite index** is required because the month query is a single `whereEqualTo("monthKey", …)` with client-side sorting. (If server-side `orderBy("timestamp")` were added later, a composite index `monthKey ASC, timestamp DESC` would need to be created in the Firebase console.)
- Firestore security rules may need an entry for the `expenses` collection depending on how rules are currently written (manual step in Firebase console, outside this codebase).
- Toggling the feature off hides only the menu card; data is never deleted.

## Risks / Things to Watch Out For

- **Timezone correctness at month boundaries**: `date`/`monthKey` must be computed from `Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila"))` (as `EndOfDayListActivity.initializeCurrentMonth()` does), not device-default timezone, or entries near midnight could land in the wrong month.
- **Next-button disable logic**: compare displayed `(year, month)` against Asia/Manila "now" each time the label updates; re-enable when navigating back. This deliberately differs from `EndOfDayListActivity` (which allows future months) — per the spec answer, do **not** change the EOD screen.
- **Add-while-viewing-past-month**: after a successful save the view must snap back to the current month so the user sees the entry land; the displayed total must never include a failed save.
- **Password dialog reuse**: `dialog_password.xml` is inflated with local `findViewById` in each activity that uses it (no shared helper). Replicate that pattern inside `ExpenseListActivity`; do not refactor the existing copies.
- **Amount parsing**: use `String.toDoubleOrNull()` on the trimmed input; reject null, ≤ 0, and values that would format oddly (round/validate to 2 decimals before saving).
- **ViewBinding**: new ids in `activity_menu.xml` and `activity_program_settings.xml` are additive and safe. (The known ~254-id ViewBinding cap concern applies to `activity_end_of_day_report.xml`, which this feature does not touch.)
- **Scope discipline**: `MenuActivity`, `ProgramSettingsActivity`, `Constants.kt`, and the two layouts get only the additive lines listed above — no reordering or reformatting of existing switches/cards.
