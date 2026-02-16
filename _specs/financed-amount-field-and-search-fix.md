# Financed Amount Field & Month-Year Search Fix

## Feature Description

Two changes to the Financing Accounts feature:

1. **New "Financed Amount" field** — An optional numeric field added to the financing account form, card item, list summary, and Firebase document.
2. **Month-year search filter fix** — Fix the search filter so that typing the first 3 letters of a month (e.g., "Dec") matches entries in that month across all loaded years (not just the current year), and support a "Mon YYYY" pattern (e.g., "Dec 2025") to filter to a specific month-year. The existing 2-year-prior loading limit still applies.

---

## User Stories / Use Cases

### Financed Amount Field

- **As a user**, I want to record the total financed amount for a financing account so I can track how much was financed through the financing company.
- **As a user**, I want the financed amount field to be optional so I can skip it when the information is not available.
- **As a user**, I want to see the financed amount on each card entry in the list so I can quickly reference it.
- **As a user**, I want to see a total financed amount at the bottom of the list that sums up all visible card entries so I can see the overall financed total at a glance.
- **As a user**, I want the total financed amount to adjust when I apply company filters or search filters so the total always reflects what I see.

### Month-Year Search Fix

- **As a user**, I want to type "Dec" in the search field and see all entries from December across all loaded years (not just the current year).
- **As a user**, I want to type "Dec 2025" and see only entries from December 2025.
- **As a user**, I want the 2-year-prior limit to still apply (e.g., in 2026, "Dec 2021" returns no results since data is only loaded from 2024 onward).

---

## UI/UX Flow

### 1. Add/Edit Financing Account Form (`AddFinancingAccountActivity`)

- A new **"Financed Amount"** text input field is added to the form.
- **Position:** After the "Downpayment" fields 
- **Input type:** `numberDecimal` — same as Monthly Payment and Downpayment.
- **Formatting:** Same currency formatting behavior as Monthly Payment and Downpayment (comma-separated thousands, 2 decimal places on focus loss, duplicate-dot prevention while typing).
- **Hint text:** "Financed Amount"
- **Optional:** No validation error if left empty.
- **Edit mode:** Pre-fills the value if present in the existing document; left empty if the field does not exist (backward compatible).

### 2. Card Item in List (`item_financing_account.xml`)

- The **financial summary row** (Row 4) currently shows 3 columns: Monthly | Term | Downpayment.
- **Add a 4th column:** "Financed" — placed after downpayment. The new order will be: **Monthly | Term | Downpayment | Financed** — all 4 in a single horizontal line using `layout_weight`.
- **Label:** "Financed" (10sp, gray, matching existing labels).
- **Value:** Formatted as `₱X,XXX.XX` (13sp, bold, black). If null/absent, show "—" in gray (same pattern as the other optional fields).
- **Visibility:** The financial summary row is shown if **any** of the 4 fields have data. The `hasFinancialData` check in the adapter must include the new financed amount field.
- **Spacing adjustments:** All 4 columns use equal `layout_weight="1"` to fit in a single line. Text sizes remain the same (10sp labels, 13sp values). Reduce horizontal margins/padding if necessary to prevent wrapping.

### 3. Total Financed Amount Summary at Bottom of List (`activity_financing_account_list.xml`)

- A **bottom bar** is added between the RecyclerView and the FAB button.
- **Layout:** A full-width horizontal bar (similar to `AccountReceivableActivity`'s bottom action bar) containing:
  - **Left side:** Label "Total Financed" (small text, muted) with the summed amount below it (bold, larger text).
  - **Right side:** Entry count label "Entries" with the count value below it.
- **The FAB (+) button remains** in its current position, floating above the bottom bar.
- **The RecyclerView** bottom constraint changes from `parent` to the top of this new bottom bar so the list doesn't overlap the summary.
- **Value:** Sum of `financedAmount` from all currently visible (filtered) card entries. Entries with null/absent financed amount contribute `0` to the total.
- **Updates:** Recalculated whenever filters change (company filter, search filter).
- **Style:** White background, slight elevation (matching Account Receivable's bottom bar styling). Text uses `@color/techcity_blue` for the amount and `@color/gray` for labels.

### 4. Account Detail Screen (`FinancingAccountDetailActivity`)

- **No changes.** The financed amount field is NOT displayed in the detail view.
- The detail screen continues to show only: Financing Company, Customer Name, Account Number, Contact Number, Monthly Payment.

### 5. Search Filter — Month-Year Fix

**Current behavior (buggy):**
- Typing a month name (e.g., "dec") only matches entries in the **current year** (2026). So "Dec" does not find an entry dated "Dec 31, 2025".
- Partial date text (e.g., "Dec 2") falls through to the display-date string match, which works but inconsistently with the month-only behavior.

**New behavior:**
- **Month name only** (e.g., "dec", "december"): Matches entries in that month across **all loaded years** (not just current year). Since data loading already limits to the last 2 years, no additional year restriction is needed for this case.
- **Month + Year pattern** (e.g., "dec 2025", "december 2025"): Matches entries in that specific month AND year. The 2-year-prior loading limit still applies inherently (the data was never loaded), but as an explicit safeguard: if the specified year is more than 2 years before the current year, return no matches.
- **Other text:** Falls through to the existing display-date string match (e.g., typing "Dec 31" still matches "Dec 31, 2025" via string contains).

---

## Data Model

### Firebase Document Changes

**Collection:** `financing_accounts` (referenced via `AppConstants.COLLECTION_FINANCING_ACCOUNTS`)

**New field:**
| Field | Type | Required | Default |
|---|---|---|---|
| `financedAmount` | `number` (Double) | No | `null` (absent) |

- Existing documents that do not have this field will be treated as `financedAmount = null`.
- When saving a new account with an empty financed amount field, the field is not written to the document (same pattern as `monthlyPayment` and `downpayment`).
- When editing an existing account and the financed amount field is cleared, `FieldValue.delete()` is used to remove it (same pattern as other optional fields).

### Model Class Changes

**File:** `FinancingAccount.kt`

Add new property:
```kotlin
val financedAmount: Double? = null
```

---

## Business Rules and Logic

1. **Financed Amount formatting:** Uses the same `DecimalFormat("#,##0.00")` formatter and `addCurrencyTextWatcher` function as Monthly Payment and Downpayment.
2. **Card visibility logic:** The financial summary row (divider + row) is shown if `hasMonthly || hasFinanced || hasTerm || hasDownpayment`. The new `hasFinanced` check follows the same pattern: `account.financedAmount != null && account.financedAmount > 0`.
3. **Total financed amount calculation:** `filteredAccounts.sumOf { it.financedAmount ?: 0.0 }`. Recalculated in `applyFilters()` after the filtered list is updated.
4. **Month search (3-letter abbreviation):** When the search text exactly matches a month abbreviation or full name from the `monthNames` map, match against **all years** in the loaded data (remove the `year == currentYear` restriction).
5. **Month + Year search:** When the search text matches the pattern `<month_name> <4-digit_year>` (e.g., "dec 2025"), extract the month and year, then filter to entries where `purchaseDate` has that exact month and year. If the year is more than 2 years before the current year, return false (no match).
6. **Backward compatibility:** The `FinancingAccount` model already uses `null` defaults for optional fields. Firestore's `toObject()` will simply leave `financedAmount` as `null` for old documents.
7. **Detail screen:** No changes — financed amount is not passed via Intent and not displayed.

---

## Edge Cases and Error Handling

1. **Old documents without `financedAmount`:** Handled by Kotlin's nullable default (`val financedAmount: Double? = null`). Card shows "—" for the financed column; total calculation treats it as 0.
2. **User enters 0 in financed amount:** Treated the same as other currency fields — stored as `0.0` in Firestore. Displayed as "₱0.00" on the card (following the same `> 0` visibility check — if we want 0 to show as "—", keep the existing `> 0` pattern; if we want to show "₱0.00" explicitly, adjust the check to `!= null`). **Decision: Follow existing pattern — `null` shows "—", any non-null value (including 0) shows the formatted amount.**
3. **Search "dec" with no December entries:** Empty list shown (existing behavior for no matches).
4. **Search "dec 2021" when current year is 2026:** The data for 2021 was never loaded (2-year limit loads from 2024). The explicit year check also returns false since 2021 < (2026 - 2). Result: no matches.
5. **Search "dec 20" (partial year):** Does not match the `<month> <year>` pattern (year must be exactly 4 digits). Falls through to display-date string matching, which will match "Dec 20" in dates like "Dec 20, 2025".
6. **Total financed amount with all entries having null financed amount:** Shows "₱0.00" (sum of zeros).
7. **Four columns in card on narrow screens:** Using equal `layout_weight="1"` ensures even distribution. Text sizes (10sp labels, 13sp values) are small enough to fit. The currency prefix "₱" and formatting keep values concise.

---

## Open Questions

None — all requirements are clear from the user's description.
