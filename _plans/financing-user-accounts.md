# Technical Plan: Financing User Accounts

## Context

The shop needs a centralized module to track customer accounts for purchases made through financing partners (Home Credit, Skyro, Samsung Finance). Currently, financing details are scattered across device/accessory transaction records. This new module provides a dedicated list + add screen accessible from the main menu.

Spec: `_specs/financing-user-accounts.md`

---

## Files to Create

| # | File | Purpose |
|---|------|---------|
| 1 | `app/src/main/java/com/techcity/techcitysuite/FinancingAccount.kt` | Data class for Firestore mapping |
| 2 | `app/src/main/res/drawable/ic_financing.xml` | Vector icon for the menu card (person/account icon) |
| 3 | `app/src/main/res/layout/activity_financing_account_list.xml` | List screen layout (header, search, filter chips, RecyclerView, FAB) |
| 4 | `app/src/main/res/layout/item_financing_account.xml` | RecyclerView item layout (card with name, account #, contact, device, date, badge) |
| 5 | `app/src/main/res/layout/activity_add_financing_account.xml` | Add form layout (ScrollView with TextInputLayouts + save button) |
| 6 | `app/src/main/java/com/techcity/techcitysuite/FinancingAccountListActivity.kt` | List activity with search, filter chips, RecyclerView adapter, FAB navigation |
| 7 | `app/src/main/java/com/techcity/techcitysuite/AddFinancingAccountActivity.kt` | Form activity with validation, DatePicker, dropdown, Firestore save |

## Files to Modify

| # | File | Changes |
|---|------|---------|
| 8 | `app/src/main/java/com/techcity/techcitysuite/Constants.kt` | Add `COLLECTION_FINANCING_ACCOUNTS = "financing_accounts"` |
| 9 | `app/src/main/res/values/strings.xml` | Add string resources for financing accounts screens |
| 10 | `app/src/main/res/values/colors.xml` | Add `financing_teal` color (`#00796B`) and `financing_teal_light` (`#E0F2F1`) |
| 11 | `app/src/main/res/layout/activity_menu.xml` | Add new MaterialCardView after accountReceivableCard (line 508), before closing `</LinearLayout>` (line 510) |
| 12 | `app/src/main/java/com/techcity/techcitysuite/MenuActivity.kt` | Add click handler for `financingAccountsButton` in `setupMenuButtons()` (after line 179) |
| 13 | `app/src/main/AndroidManifest.xml` | Register `FinancingAccountListActivity` and `AddFinancingAccountActivity` |

---

## Implementation Order

### Step 1: Data class + Constants

**FinancingAccount.kt** — Kotlin data class with no-arg constructor for Firestore:
```kotlin
data class FinancingAccount(
    val id: String = "",
    val financingCompany: String = "",
    val customerName: String = "",
    val accountNumber: String = "",
    val purchaseDate: String = "",
    val contactNumber: String = "",
    val devicePurchased: String? = null,
    val monthlyPayment: Double? = null,
    val term: String? = null,
    val downpayment: Double? = null,
    val createdAt: Timestamp? = null,
    val createdBy: String = "",
    val storeLocation: String = ""
)
```

**Constants.kt** — Add after `COLLECTION_INVENTORY_ARCHIVES` (line 46):
```kotlin
const val COLLECTION_FINANCING_ACCOUNTS = "financing_accounts"
```

### Step 2: Resources (colors, strings, drawable, layouts)

**colors.xml** — Add two new colors:
- `financing_teal` = `#00796B`
- `financing_teal_light` = `#E0F2F1`

**strings.xml** — Add strings for:
- Menu card title/subtitle/content description
- List screen title, empty state, search hint
- Add screen title, field labels/hints, save button text, validation messages, success/error toasts

**ic_financing.xml** — New vector drawable (24dp). Use a person-with-card or account-balance style icon in `#000000` fill (tinted at usage site), matching existing `ic_*.xml` pattern.

**activity_financing_account_list.xml** — ConstraintLayout structure following `activity_phone_inventory_list.xml` pattern:
- Blue header LinearLayout with back arrow ImageButton + title TextView
- Search TextInputLayout below header with search icon
- HorizontalScrollView with filter chips (All, Home Credit, Skyro, Samsung Finance) as MaterialButtons
- RecyclerView (initially GONE)
- ProgressBar (centered, initially GONE)
- Empty state TextView (centered, initially GONE)
- FloatingActionButton bottom-right, margin-end 24dp, margin-bottom 48dp (nav bar padding per spec)

**item_financing_account.xml** — MaterialCardView following `item_account_receivable.xml` pattern:
- Row 1: Customer name (bold, 16sp) + financing company badge (top-right, colored by company)
- Row 2: Account number
- Row 3: Contact number + Date of purchase (right-aligned)
- Row 4 (conditional): Device purchased (smaller text, gray, GONE if null)

**activity_add_financing_account.xml** — ConstraintLayout with:
- Blue header with back arrow + title
- ScrollView with vertical LinearLayout containing TextInputLayout fields in spec order:
  1. Date of Purchase (click opens DatePickerDialog, not editable via keyboard)
  2. Financing Company (ExposedDropdownMenu / AutoCompleteTextView in TextInputLayout)
  3. Customer Name
  4. Account Number
  5. Contact Number (inputType: phone)
  6. Device Purchased
  7. Monthly Payment (inputType: numberDecimal)
  8. Term
  9. Downpayment (inputType: numberDecimal)
- Full-width MaterialButton "Save Account" at bottom of ScrollView content

### Step 3: Menu integration

**activity_menu.xml** — Insert new card after line 508 (closing tag of accountReceivableCard), before the closing `</LinearLayout>` on line 510. Follow the exact same MaterialCardView pattern (90dp height, 12dp corner radius, 4dp elevation). IDs: `financingAccountsCard`, `financingAccountsButton`, `financingAccountsIcon`, `financingAccountsTitle`. Icon tint: `@color/financing_teal`.

**MenuActivity.kt** — Add after line 179 in `setupMenuButtons()`:
```kotlin
binding.financingAccountsButton.setOnClickListener {
    val intent = Intent(this, FinancingAccountListActivity::class.java)
    startActivity(intent)
}
```

### Step 4: List Activity (FinancingAccountListActivity.kt)

Following the patterns from `AccountReceivableActivity.kt` and `PhoneInventoryListActivity.kt`:

- **Properties**: ViewBinding, FirebaseFirestore, CoroutineScope, `allAccounts` + `filteredAccounts` lists, adapter, current filter enum
- **Filter enum**: `ALL`, `HOME_CREDIT`, `SKYRO`, `SAMSUNG_FINANCE`
- **onCreate**: Init binding, Firebase, setup back button, search listener (TextWatcher with `afterTextChanged` for real-time filtering), filter chip click handlers, FAB click → `Intent(this, AddFinancingAccountActivity::class.java)`, call `loadAccounts()`
- **onResume**: Reload accounts (so newly added accounts appear on return)
- **loadAccounts()**: Coroutine with `Dispatchers.IO`, query `financing_accounts` ordered by `createdAt` descending, map documents to `FinancingAccount` (set `id` from `document.id`), update `allAccounts`, call `applyFilters()`
- **applyFilters()**: Filter `allAccounts` by current chip selection + search text (case-insensitive partial match on customerName, accountNumber, contactNumber, devicePurchased, and purchaseDate). Financing company is NOT searched via text — the filter buttons handle that. Date search uses a `matchesDateSearch()` helper with month-name matching (e.g., "feb" → February of current year only) and display-date partial matching for specific dates. A `monthNames` map provides month name lookups. Update `filteredAccounts`, notify adapter, toggle empty state visibility
- **Search bar**: White background (`boxBackgroundColor`), hint text includes "name, account #, contact, device, date"
- **Inner Adapter**: RecyclerView.Adapter with ViewHolder using `ItemFinancingAccountBinding`. Bind: name, account number, contact, date (formatted "MMM dd, yyyy"), device (visibility toggle), financing company badge (colored background per company). Item click → Toast "Detail view coming soon."
- **onDestroy**: Cancel coroutine scope

### Step 5: Add Activity (AddFinancingAccountActivity.kt)

- **Properties**: ViewBinding, FirebaseFirestore, CoroutineScope, selected date string
- **onCreate**: Init binding, Firebase, setup back button, setup date picker (default to current date GMT+8), setup financing company dropdown (ArrayAdapter with ["Home Credit", "Skyro", "Samsung Finance"]), setup save button click
- **Date picker**: On date field click, show `DatePickerDialog` (using `DatePickerSpinnerStyle` theme from existing app). Pre-populate with current date in GMT+8. On selection, format as "yyyy-MM-dd" for storage and "MMM dd, yyyy" for display.
- **Financing company dropdown**: Material ExposedDropdownMenu pattern — `TextInputLayout` with `style="@style/Widget.Material3.TextInputLayout.OutlinedBox.ExposedDropdownMenu"` and inner `AutoCompleteTextView`. Set adapter with the 3 companies.
- **saveAccount()**: Validate required fields (financingCompany, customerName, accountNumber, purchaseDate, contactNumber, term). Show inline `TextInputLayout.error` for empty required fields. If valid:
  - Build a HashMap with all fields + `createdAt` (FieldValue.serverTimestamp()), `createdBy` (from `AppSettingsManager.getCurrentSettings()?.user`), `storeLocation` (from `AppSettingsManager.getCurrentSettings()?.storeLocation`)
  - Save to Firestore `financing_accounts` collection via `db.collection(...).add(data).await()`
  - On success: Toast "Account saved successfully", `finish()`
  - On failure: Toast with error message
- **onDestroy**: Cancel coroutine scope

### Step 6: Manifest registration

**AndroidManifest.xml** — Add after existing activity registrations:
```xml
<activity
    android:name=".FinancingAccountListActivity"
    android:exported="false"
    android:label="Financing Accounts" />
<activity
    android:name=".AddFinancingAccountActivity"
    android:exported="false"
    android:label="Add Financing Account" />
```

---

## Dependencies

No new libraries required. All needed dependencies (Firestore, Material, Coroutines) are already in the project.

## Risks / Watch Out For

- **Badge colors per financing company**: Need to pick distinct colors — use existing red for Home Credit, `skyro_light_blue` for Skyro, and `financing_teal` for Samsung Finance
- **FAB bottom margin**: Spec explicitly calls for padding so it's not blocked by phone navigation buttons — use 48dp margin-bottom (matching PhoneInventoryListActivity pattern)
- **AppSettingsManager may return null**: Guard `getCurrentSettings()` calls with fallback empty strings for `createdBy`/`storeLocation`

## Verification

1. Build the project: `./gradlew assembleDebug` — must compile without errors
2. Manually verify on device/emulator:
   - New "Financing Accounts" card appears on the menu below Account Receivable
   - Tapping it opens the list screen (empty initially)
   - FAB opens the add form
   - Date defaults to today, financing company dropdown shows 3 options
   - Required field validation shows inline errors
   - Saving creates a document in `financing_accounts` Firestore collection
   - Returning to list shows the newly added account
   - Search filters in real-time across name/account#/contact/company
   - Filter chips narrow results by financing company
