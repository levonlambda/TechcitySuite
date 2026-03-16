# Feature Spec: Financing User Accounts

## Description

A new module to track customer accounts for purchases made through financing partners (Home Credit, Skyro, Samsung Finance). This provides a centralized place to look up customer financing details separate from the existing Account Receivable / transaction screens.

## User Stories

1. **As a store staff**, I want to see a list of all customers who purchased via financing so I can quickly look up their account details when they visit or call.
2. **As a store staff**, I want to add a new financing user account after a customer completes a financing purchase.
3. **As a store staff**, I want to search for a customer by name, account number, contact number, or financing company so I can quickly find their record.

## UI/UX Flow

### Menu Card (MenuActivity)

- A new menu card is added to the main menu between the "Account Receivable" card and the bottom of the list (i.e., as the last card in the ScrollView).
- **Icon:** A person/account icon (e.g., `ic_person` or `ic_account_circle` — use a built-in Android drawable or create a custom drawable consistent with the existing card icons).
- **Title:** "Financing Accounts"
- **Subtitle:** "Track customer financing accounts"
- **Tint color:** `#00796B` (teal dark — distinct from existing cards)

### Financing Account List Screen (FinancingAccountListActivity)

- **Header:** Blue header bar matching existing list screens (e.g., Account Receivable), with:
  - Title: "Financing Accounts"
  - Back button (left arrow) to return to MenuActivity  
- **Search bar:** Below the header, a `TextInputEditText` with a search icon. Searches across: name, account number, contact number, and financing company. Search is real-time as the user types (filter-as-you-type on the local list).
- **Filter chips:** Below the search bar, horizontal scrollable chips to filter by financing company:
  - "All" (default, selected)
  - "Home Credit"
  - "Skyro"
  - "Samsung Finance"
- **List (RecyclerView):** Each item shows:
  - Financing company (as a colored badge/chip at the top-right corner of the item)
  - Customer name (bold, primary text)
  - Account number
  - Contact number
  - Device purchased (if available, shown in smaller text)
  - Date of purchase (formatted as "MMM dd, yyyy")
- **Empty state:** When no accounts exist or no results match the search/filter, show centered text: "No financing accounts found"
- **FAB (Floating Action Button):** Bottom-right "+" button. Opens the Add Financing Account screen. Make sure to give padding to not be blocked by the phones navigation button.
- **Item click:** Tapping a list item opens a detail/edit screen (Phase 2 — not part of this spec). For now, tapping does nothing or shows a toast "Detail view coming soon."

### Add Financing Account Screen (AddFinancingAccountActivity)

- **Header:** Blue header bar with:
  - Title: "Add Financing Account"
  - Back button (left arrow) to return to the list
- **Form fields** (scrollable if needed):

  | Field | Type | Required | Default | Notes |
  |---|---|---|---|---|
  | Date of Purchase | TextInputEditText (date picker) | Yes | Current date (GMT+8) | Tapping opens a DatePickerDialog |
  | Financing Company | Dropdown (Spinner or ExposedDropdownMenu) | Yes | — | Options: "Home Credit", "Skyro", "Samsung Finance" |
  | Customer Name | TextInputEditText | Yes | — | Text input |
  | Account Number | TextInputEditText | Yes | — | Text input |  
  | Contact Number | TextInputEditText | Yes | — | Phone input type |
  | Device Purchased | TextInputEditText | No | — | Text input |
  | Monthly Payment | TextInputEditText | No | — | Number/decimal input type |
  | Term | TextInputEditText | Yes | — | Text input |
  | Downpayment | TextInputEditText | No | — | Number/decimal input type |

- **Save button:** Full-width Material button at the bottom labeled "Save Account"
  - Validates all required fields are filled
  - Shows error messages on empty required fields (inline `TextInputLayout` errors)
  - On success: saves to Firestore, shows a success Toast ("Account saved successfully"), and finishes the activity (returns to list)
  - On failure: shows an error Toast with the error message

## Data Model

### Firestore Collection: `financing_accounts`

Each document represents one customer financing account:

```
{
  "financingCompany": "Home Credit" | "Skyro" | "Samsung Finance",
  "customerName": "Juan Dela Cruz",
  "accountNumber": "HC-123456789",
  "purchaseDate": "2026-02-14",
  "contactNumber": "09171234567",
  "devicePurchased": "Samsung Galaxy S25" | null,
  "monthlyPayment": 1500.00 | null,
  "term": "12 months" | null,
  "downpayment": 3000.00 | null,
  "createdAt": <Firestore Server Timestamp>,
  "createdBy": "<user from AppSettingsManager>",
  "storeLocation": "<store_location from AppSettingsManager>"
}
```

### Kotlin Data Class: `FinancingAccount`

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

## Business Rules

1. **Financing companies** are a fixed list: "Home Credit", "Skyro", "Samsung Finance". These are hardcoded (not fetched from Firebase) since they rarely change.
2. **Required field validation** must happen client-side before saving. All required fields must be non-empty.
3. **Date of purchase** defaults to the current date in GMT+8 timezone, matching the app's existing timestamp convention.
4. **Search** is case-insensitive and matches partial strings across name, account number, contact number, and financing company.
5. **Filter + search** work together: if "Skyro" chip is selected and user types "Juan", only Skyro accounts matching "Juan" are shown.
6. **List ordering:** Accounts are loaded ordered by `createdAt` descending (newest first).
7. **No feature flag** for this module — it is always visible in the menu once implemented.
8. **`createdBy` and `storeLocation`** are populated automatically from `AppSettingsManager` at save time — not shown in the form.

## Edge Cases and Error Handling

1. **No internet:** Firestore operations will fail. Show a Toast with the error message. The user can retry via the refresh button on the list.
2. **Duplicate account numbers:** No uniqueness constraint enforced — different financing companies may reuse account numbers. Users are responsible for avoiding duplicates.
3. **Empty list:** Show the "No financing accounts found" empty state.
4. **Search with no results:** Show the same empty state text.
5. **Form validation errors:** Show inline errors on each required `TextInputLayout` that is empty when "Save Account" is tapped.
6. **Back navigation during save:** If the user presses back while saving (coroutine in-flight), the coroutine is cancelled in `onDestroy()` — no crash, but the save may or may not have completed on Firestore.

## Open Questions

1. Should there be an edit/delete capability for existing accounts? (Deferred to Phase 2 — not in this spec.) Yes but lets implement it later.
2. Should there be a way to link a financing account to an existing device transaction? (Deferred to Phase 2.) No for now. we may add it as a feature later.
3. Should the financing company list be configurable from ProgramSettingsActivity in the future? (Deferred — hardcoded for now.) No.
