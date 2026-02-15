# Technical Plan: Financing Account Management

## Context

This plan implements 4 features from `_specs/financing-account-management.md` for the Financing Account module: swipe-left to delete (password-protected), swipe-right to edit, tap to view detail with copy-to-clipboard, and default loading with a 2-year Firestore filter. These were deferred as "Phase 2" in the original financing-user-accounts spec.

---

## Files to Create

| # | File | Purpose |
|---|------|---------|
| 1 | `app/src/main/res/drawable/ic_edit.xml` | Edit/pencil vector icon (24dp) for swipe-right background |
| 2 | `app/src/main/res/drawable/ic_content_copy.xml` | Copy vector icon (24dp) for detail screen clipboard buttons |
| 3 | `app/src/main/res/layout/activity_financing_account_detail.xml` | Layout for the detail/copy-to-clipboard screen |
| 4 | `app/src/main/java/com/techcity/techcitysuite/FinancingAccountDetailActivity.kt` | Detail activity with copy-to-clipboard |
| 5 | `app/src/main/res/layout/dialog_delete_account.xml` | Custom styled layout for the delete confirmation dialog |

## Files to Modify

| # | File | Summary of Changes |
|---|------|--------------------|
| 5 | `app/src/main/AndroidManifest.xml` | Register `FinancingAccountDetailActivity` |
| 6 | `app/src/main/res/values/strings.xml` | Add ~12 new string resources |
| 7 | `app/src/main/java/com/techcity/techcitysuite/FinancingAccountListActivity.kt` | Add ItemTouchHelper swipe actions, password/delete dialogs, edit trigger, detail navigation, 2-year Firestore filter on default load |
| 8 | `app/src/main/java/com/techcity/techcitysuite/AddFinancingAccountActivity.kt` | Add edit mode support (pre-fill fields, update vs add, preserve original metadata) |
| 9 | `app/src/main/java/com/techcity/techcitysuite/Constants.kt` | Add `KEY_FINANCING_ACCOUNTS_ENABLED` SharedPreferences key |
| 10 | `app/src/main/java/com/techcity/techcitysuite/ProgramSettingsActivity.kt` | Add Financing Accounts toggle (default on) to Feature Settings — load/save preference |
| 11 | `app/src/main/res/layout/activity_program_settings.xml` | Add Financing Accounts SwitchMaterial toggle in Feature Settings card |
| 12 | `app/src/main/java/com/techcity/techcitysuite/MenuActivity.kt` | Add Financing Accounts card visibility toggle in `updateFeatureVisibility()` |
| 13 | `app/src/main/java/com/techcity/techcitysuite/AddFinancingAccountActivity.kt` | Programmatically style date picker dialog buttons (`techcity_blue_dark` background, white text) after `show()` |

---

## Implementation Order

### Step 1: Resources (drawables + strings)

**1a. Create `app/src/main/res/drawable/ic_edit.xml`**
- Material Design pencil/edit vector icon (24dp, white fill)

**1b. Create `app/src/main/res/drawable/ic_content_copy.xml`**
- Material Design content_copy vector icon (24dp, white fill)

**1c. Add string resources to `app/src/main/res/values/strings.xml`**

| String Name | Value |
|---|---|
| `edit_financing_account` | Edit Financing Account |
| `update_account` | Update Account |
| `account_updated_successfully` | Account updated successfully |
| `account_deleted_successfully` | Account deleted successfully |
| `delete_account_title` | Delete Account? |
| `confirm_delete` | Yes, Delete |
| `account_details` | Account Details |
| `account_number_copied` | Account number copied |
| `customer_name_copied` | Customer name copied |
| `contact_number_copied` | Contact number copied |
| `password_required_to_delete` | Password required to delete accounts |

---

### Step 2: Feature 3 — Tap to View Detail (new activity, no dependencies on other features)

**2a. Create `app/src/main/res/layout/activity_financing_account_detail.xml`**
- ConstraintLayout root, `#F5F5F5` background
- Blue header with back button + title "Account Details" (same pattern as other activities)
- Body: ScrollView → MaterialCardView (12dp corners, 2dp elevation) with 5 rows:
  - **Financing Company**: label + value (text color matches company brand — no badge, no copy button)
  - **Customer Name**: label + value + copy ImageButton (tinted `techcity_blue`)
  - **Account Number**: label + value + copy ImageButton
  - **Contact Number**: label + value + copy ImageButton
  - **Monthly Payment**: label + value (formatted as `₱X,XXX.XX` in green, or "—" in gray if null) + copy ImageButton
- Rows separated by thin dividers

**2b. Create `app/src/main/java/com/techcity/techcitysuite/FinancingAccountDetailActivity.kt`**
- View Binding with `ActivityFinancingAccountDetailBinding`
- Receives 5 intent extras: `financing_company`, `customer_name`, `account_number`, `contact_number`, `monthly_payment` (Double, with `has_monthly_payment` boolean to distinguish null from 0.0)
- Back button calls `finish()`
- Populates text values and financing company text color (brand color, no badge)
- Monthly payment formatted as `₱X,XXX.XX` (green) or "—" (gray) when null
- 4 copy button click listeners using `ClipboardManager`:
  ```kotlin
  val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  clipboard.setPrimaryClip(ClipData.newPlainText("label", value))
  Toast.makeText(this, "Customer name copied", Toast.LENGTH_SHORT).show()
  ```
- No Firestore access, no coroutine scope needed

**2c. Register in `AndroidManifest.xml`**
- Add `<activity android:name=".FinancingAccountDetailActivity" android:exported="false" />`

**2d. Modify card click handler in `FinancingAccountListActivity.kt`**
- Replace Toast in `onBindViewHolder` click listener with Intent to `FinancingAccountDetailActivity` passing 5 fields + `has_monthly_payment` boolean

---

### Step 3: Feature 2 — Edit Mode in AddFinancingAccountActivity

**Modify `AddFinancingAccountActivity.kt`:**

- Add properties: `isEditMode`, `editDocumentId`, `originalCreatedBy`, `originalStoreLocation`
- Add `checkEditMode()` method called from `onCreate()` **after** `setupCurrencyFormatting()`:
  - Reads intent extras: `edit_mode` (boolean), `document_id`, all field values
  - Updates header: `binding.titleText.text = getString(R.string.edit_financing_account)`
  - Updates button: `binding.saveButton.text = getString(R.string.update_account)`
  - Pre-fills all fields including date picker state (`selectedDateStorage`)
  - **Currency fields**: Format with `DecimalFormat("#,##0.00")` before `setText()` — e.g., `1500.0` → `"1,500.00"`
  - **Dropdown**: Use `binding.financingCompanyDropdown.setText(text, false)` — the `false` prevents dropdown filter from activating
  - Uses `has_monthly_payment`/`has_downpayment` boolean extras to distinguish null from 0.0

- Modify `saveAccount()`:
  - In edit mode: remove `createdAt` from data map (preserve original), use `originalCreatedBy`/`originalStoreLocation`, call `.document(editDocumentId).update()` instead of `.add()`
  - Handle null optional fields with `FieldValue.delete()` when clearing values in edit mode
  - Show "Account updated successfully" toast on success

---

### Step 4: Features 1 & 2 — Swipe Actions in FinancingAccountListActivity

**Add to `FinancingAccountListActivity.kt`:**

**4a. `setupSwipeActions()` method** — called from `onCreate()` after `setupRecyclerView()`:
- `ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)`
- `onMove()` returns `false` (no drag)
- `onSwiped()`: LEFT → `showPasswordDialogForDelete(account, position)`, RIGHT → `openEditMode(account, position)`
- `onChildDraw()`: draws colored background + icon during swipe
  - Left (dX < 0): **red** background + white delete icon (right-aligned) — uses `android.R.drawable.ic_menu_delete`
  - Right (dX > 0): **teal** background + white edit icon (left-aligned) — uses `R.drawable.ic_edit`
- Pattern follows `DeviceTransactionListActivity` lines 148-273

**4b. `showPasswordDialogForDelete(account, position)` method:**
- Inflates `R.layout.dialog_password` (reuses existing layout)
- Updates subtitle to "Password required to delete accounts"
- Cancel/dismiss → `adapter?.notifyItemChanged(position)` to reset swipe
- Submit → `AppSettingsManager.verifyPassword(password)` in coroutine
- On correct → dismiss, call `showDeleteConfirmationDialog()`
- On incorrect → show error, clear field
- Pattern follows `DeviceTransactionListActivity` lines 294-387

**4c. `showDeleteConfirmationDialog(account, position)` method:**
- Inflates custom layout `R.layout.dialog_delete_account` (red warning icon, red title, gray subtitle, styled label/value rows, Cancel/Delete buttons)
- Populates 4 value TextViews: financing company, customer name, account number, contact number
- Cancel button → `dialog.dismiss()` + `adapter?.notifyItemChanged(position)`
- Delete button → `dialog.dismiss()` + `deleteAccountFromFirestore(account.id, position)`
- `setOnCancelListener` → `adapter?.notifyItemChanged(position)` (back button)
- Pattern follows `dialog_password.xml` and `dialog_transaction_confirmation.xml` styling

**4d. `deleteAccountFromFirestore(documentId, position)` method:**
- `db.collection(COLLECTION_FINANCING_ACCOUNTS).document(documentId).delete().await()` in `Dispatchers.IO`
- On success: remove from `allAccounts` and `filteredAccounts`, notify adapter, update empty state, Toast "Account deleted successfully"
- On failure: `adapter?.notifyItemChanged(position)`, error Toast

**4e. `openEditMode(account, position)` method:**
- `adapter?.notifyItemChanged(position)` to reset swipe visual
- Launch Intent to `AddFinancingAccountActivity` with extras:
  - `edit_mode` = true, `document_id`, all field values
  - `has_monthly_payment`/`has_downpayment` booleans to distinguish null from 0.0
  - `created_by`, `store_location` to preserve original metadata

---

### Step 5: Feature 4 — Default Loading with 2-Year Filter

**Modify `FinancingAccountListActivity.kt`:**

- **Keep** `loadAccounts()` call in `onCreate()` — accounts load automatically on open
- **Keep** `loadAccounts()` call in `onResume()` — reloads to stay in sync after edit/add/delete

- **Modify `setupSearch()` → `afterTextChanged()`**:
  - Simply calls `applyFilters()` on every text change (client-side filtering on cached data, same as original behavior)

- **Modify `loadAccounts()`**: add 2-year filter to Firestore query:
  ```kotlin
  val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila"))
  calendar.add(Calendar.YEAR, -2)
  val twoYearsAgo = com.google.firebase.Timestamp(calendar.time)

  db.collection(AppConstants.COLLECTION_FINANCING_ACCOUNTS)
      .whereGreaterThan("createdAt", twoYearsAgo)
      .orderBy("createdAt", Query.Direction.DESCENDING)
      .get().await()
  ```

- **`updateEmptyState()`**: keep existing behavior — shows "No financing accounts found" when list is empty

---

### Step 6: Feature 5 — Financing Accounts Toggle in Program Settings

**6a. Add constant to `Constants.kt`:**
- `KEY_FINANCING_ACCOUNTS_ENABLED = "financing_accounts_enabled"`

**6b. Add toggle to `activity_program_settings.xml`:**
- New "Financing Accounts" SwitchMaterial toggle in Feature Settings card (between Phone Inventory and Device Transaction Notifications)
- Title: "Financing Accounts", subtitle: "Show Financing Accounts in main menu"
- Default checked: `true`

**6c. Modify `ProgramSettingsActivity.kt`:**
- Add `KEY_FINANCING_ACCOUNTS_ENABLED` companion const
- `loadSettings()`: load toggle state with default `true`
- `saveSettings()`: save toggle state

**6d. Modify `MenuActivity.kt` → `updateFeatureVisibility()`:**
- Read `KEY_FINANCING_ACCOUNTS_ENABLED` preference (default `true`)
- Show/hide `financingAccountsCard` based on the value

---

## Key Patterns Being Reused

| Pattern | Source File | Reused In |
|---------|------------|-----------|
| ItemTouchHelper + onChildDraw | `DeviceTransactionListActivity.kt:148-273` | `FinancingAccountListActivity.kt` |
| Password dialog (inflate + verify) | `DeviceTransactionListActivity.kt:294-387` + `dialog_password.xml` | `FinancingAccountListActivity.kt` |
| Delete confirmation dialog | `DeviceTransactionListActivity.kt:392-420` | `FinancingAccountListActivity.kt` |
| Firestore delete | `DeviceTransactionListActivity.kt:425-459` | `FinancingAccountListActivity.kt` |
| Intent extras for data passing | `InHousePaymentActivity.kt`, `EndOfDayReportActivity.kt` | Edit mode + detail navigation |
| Currency formatting | `AddFinancingAccountActivity.kt:118-152` | Edit mode pre-fill |
| Badge color mapping | `FinancingAccountListActivity.kt:372-380` | `FinancingAccountDetailActivity.kt` |

---

## Implementation Risks & Mitigations

1. **`checkEditMode()` ordering** — Must be called AFTER `setupCurrencyFormatting()` in `onCreate()` so the TextWatcher's `isFormatting` guard is already set up when we `setText()`.
2. **`AutoCompleteTextView.setText(text, false)`** — The `false` param is critical to prevent dropdown filter from opening.
3. **Null Doubles in Intent** — Use separate `has_*` boolean extras since `getDoubleExtra` defaults to 0.0.
4. **Firestore `.update()` with null values** — Use `FieldValue.delete()` for fields that should be cleared, or conditionally include optional fields.
5. **Firestore composite index** — The query with `.whereGreaterThan("createdAt", ...)` + `.orderBy("createdAt", ...)` uses the same field, so no composite index is needed.

---

## Verification

1. **Swipe Left to Delete**: Swipe a card left → see red background with delete icon → password dialog appears → enter correct password → confirmation dialog shows account details → tap "Yes, Delete" → entry removed, toast shown. Also test: wrong password, cancel at each stage.
2. **Swipe Right to Edit**: Swipe a card right → see teal background with edit icon → AddFinancingAccountActivity opens with "Edit Financing Account" title, all fields pre-filled (including formatted currency), button says "Update Account" → modify a field → tap Update → toast shown, returns to list, changes reflected.
3. **Tap to View Detail**: Tap a card → FinancingAccountDetailActivity opens with "Account Details" title → fields displayed with correct badge color → tap copy button for each field → toast confirms copy → paste elsewhere to verify clipboard content → back button returns to list.
4. **Default Loading with 2-Year Filter**: Open Financing Accounts list → spinner appears, accounts from last 2 years load automatically → search field filters results client-side → filter buttons work on loaded results → returning from edit/add/delete refreshes the list via `onResume()`.
5. **Financing Accounts Toggle**: Open Program Settings → Financing Accounts toggle is ON by default → turn OFF → save → return to menu → Financing Accounts card is hidden → go back to settings → turn ON → save → card reappears.
5. **Build**: `./gradlew assembleDebug` succeeds with no errors.
