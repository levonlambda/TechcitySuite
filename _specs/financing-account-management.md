# Feature Spec: Financing Account Management (Swipe Actions, Detail View, 2-Year Filter)

## Description

Enhancements to the Financing Account module adding four capabilities: swipe-left to delete (password-protected), swipe-right to edit, tap to view detail with copy-to-clipboard, and default loading with a 2-year Firestore filter to reduce reads.

---

## Feature 1: Swipe Left to Delete (Password-Protected)

### User Story

**As a store staff**, I want to swipe a financing account card to the left to delete it, protected by password verification, so that only authorized users can remove account entries.

### UI/UX Flow

1. User swipes a card entry **to the left** in the RecyclerView.
2. A **password dialog** appears (identical to the one used in `MenuActivity` for the kebab/settings button). It uses `AppSettingsManager.verifyPassword()` for verification.
3. **If password is incorrect:** Show "Incorrect password" error in the dialog. User can retry or cancel.
4. **If password is correct:** Dismiss the password dialog. Show a **confirmation dialog** with the account details:
   - Title: "Delete Account?"
   - Body displays:
     - Financing Company
     - Customer Name
     - Account Number     
     - Contact Number
   - Two buttons: **"Yes, Delete"** and **"Cancel"**
5. **If user taps "Yes, Delete":** Delete the document from Firestore (`financing_accounts` collection using the document ID), remove it from the local list, show a Toast "Account deleted successfully", and update the RecyclerView.
6. **If user taps "Cancel":** Dismiss the dialog. The card returns to its original position (swipe resets).

### Edge Cases

- If Firestore delete fails (no internet, etc.), show an error Toast and keep the entry in the list.
- The swipe action should show a visual indicator (red background with a trash/delete icon) while swiping.
- If the user cancels the password dialog, the card swipe resets to its original position.

---

## Feature 2: Swipe Right to Edit

### User Story

**As a store staff**, I want to swipe a financing account card to the right to edit its details, so I can correct or update customer information.

### UI/UX Flow

1. User swipes a card entry **to the right** in the RecyclerView.
2. The swipe action should show a visual indicator (blue/teal background with an edit icon) while swiping.
3. `AddFinancingAccountActivity` opens with all fields **pre-filled** with the selected account's data:
   - Date of Purchase: Set the date picker to the account's `purchaseDate` (displayed in "MMM dd, yyyy" format)
   - Financing Company: Pre-selected in the dropdown
   - Customer Name: Pre-filled
   - Account Number: Pre-filled
   - Contact Number: Pre-filled
   - Device Purchased: Pre-filled (if available)
   - Monthly Payment: Pre-filled with formatted number (e.g., "1,500.00" — must display correctly with the existing currency formatting, not raw double like "1500.0")
   - Term: Pre-filled
   - Downpayment: Pre-filled with formatted number (e.g., "3,000.00" — same currency formatting consideration)
4. The header title changes to **"Edit Financing Account"** (instead of "Add Financing Account").
5. The save button text changes to **"Update Account"** (instead of "Save Account").
6. Clicking **"Update Account"** performs a Firestore **update** (not add) on the existing document using its ID.
7. On success: show Toast "Account updated successfully", finish the activity (return to list).
8. On failure: show error Toast, keep the form open.

### Data Passing

- The account data is passed to `AddFinancingAccountActivity` via Intent extras.
- An extra `"edit_mode"` boolean flag (or similar) distinguishes add vs. edit mode.
- The document `id` is passed so the activity knows which document to update.

### Currency Display Note

- When pre-filling `monthlyPayment` and `downpayment`, the values must be formatted using `DecimalFormat("#,##0.00")` before setting the text (e.g., `1500.0` should display as `"1,500.00"`, not `"1500.0"`).
- The existing `addCurrencyTextWatcher` in `AddFinancingAccountActivity` handles formatting on focus loss and typing, so the initial value just needs to be formatted correctly when set.

---

## Feature 3: Tap Card to View Detail (with Copy-to-Clipboard)

### User Story

**As a store staff**, I want to tap a financing account card to view its key details and easily copy fields to the clipboard, so I can quickly paste customer information when processing loan payments on financing company portals.

### UI/UX Flow

1. User **taps** a card entry in the RecyclerView.
2. A new activity opens: **`FinancingAccountDetailActivity`**.
3. The screen displays the following fields in a clean, read-only layout:
   - **Financing Company** — displayed as text (not editable, no copy button) use the assigned colors of the financing company as shown in the card entry.
   - **Customer Name** — displayed as text with a **copy icon/button** to the right
   - **Account Number** — displayed as text with a **copy icon/button** to the right
   - **Contact Number** — displayed as text with a **copy icon/button** to the right
4. Tapping a copy button copies **only that field's value** to the clipboard and shows a short Toast: "Copied to clipboard" (or more specific like "Account number copied").
5. A **back button** (left arrow) in the header allows the user to return to the financing account list.

### Layout Design

- **Header:** Blue header bar matching existing screens, with:
  - Back button (left arrow)
  - Title: "Account Details"
- **Body:** Vertically stacked rows, each row being:
  - Label (small, gray text, e.g., "Customer Name")
  - Value (larger, bold text) + copy icon button aligned to the right (for Customer Name, Account Number, Contact Number)
  - Financing Company row has label + value only (no copy button)
- The copy icon should use a standard clipboard/copy drawable (e.g., `ic_content_copy` or a custom vector).

### Business Logic

- The copy-to-clipboard uses Android's `ClipboardManager`.
- The purpose is to streamline the workflow of copying account details when making payments on financing company apps/websites.

---

## Feature 4: Default Loading with 2-Year Filter

### User Story

**As a store owner**, I want the financing account list to automatically load recent accounts (last 2 years) when opened, so that I can immediately see and search through active accounts without extra steps, while keeping Firestore read costs manageable by excluding very old records.

### UI/UX Flow

1. When `FinancingAccountListActivity` opens, accounts from the **last 2 years** are loaded from Firestore automatically and displayed in the RecyclerView.
2. The empty state message shows **"No financing accounts found"** if no accounts exist within the 2-year window.
3. The **search field** filters the loaded accounts **client-side** (same as current behavior) — no additional Firestore reads when typing.
4. The **filter buttons** (All, Home Credit, Skyro, Samsung Finance) still work — they filter the already-loaded results client-side.
5. `onResume()` reloads accounts to stay in sync after edit, add, or delete operations.

### Query Approach

- On open, load accounts from Firestore **limited to the last 2 years** (based on `createdAt` timestamp). The query uses `.whereGreaterThan("createdAt", twoYearsAgo)` combined with `.orderBy("createdAt", Query.Direction.DESCENDING)` to limit the result set.
- Search filtering is applied **client-side** on the loaded list (same as current behavior).
- This reduces Firestore reads compared to loading the entire collection, while still showing all relevant active accounts immediately.

### Edge Cases

- If no accounts exist within the 2-year window, the empty state message "No financing accounts found" is shown.
- The loading spinner appears during the Firestore fetch and disappears once data is loaded.
- If the user swipes to delete or swipes to edit and returns, `onResume()` reloads the list to stay in sync.

---

## Data Model

No changes to the existing `FinancingAccount` data class or Firestore schema. The existing document ID (`id` field) is used for update and delete operations.

## Business Rules

1. **Password for delete** uses the same `AppSettingsManager.verifyPassword()` function used by the settings kebab menu in `MenuActivity`.
2. **Delete** removes the Firestore document permanently — there is no soft delete or trash.
3. **Edit** updates the existing document in-place (same document ID) — `createdAt`, `createdBy`, and `storeLocation` are preserved from the original document (not overwritten).
4. **Copy-to-clipboard** only copies the text value, not the label.
5. **2-year filter** limits the Firestore query to accounts created within the last 2 years, reducing read costs while showing all relevant active accounts by default.
6. **Swipe gestures** use Android's `ItemTouchHelper` attached to the RecyclerView.

## Open Questions

None — all four features are well-defined. The Firestore query in Feature 4 loads accounts created within the last 2 years (via `createdAt` timestamp filter) by default on open, keeping reads manageable as the dataset grows.

