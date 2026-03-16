# Technical Plan: Financed Amount Field & Month-Year Search Fix

## Context

The Financing Accounts feature needs a new optional "Financed Amount" field to track how much was financed per account. Additionally, the search filter has a bug where typing a month name (e.g., "Dec") only matches entries in the current year, failing to find entries from previous years like "Dec 31, 2025". A new "Month Year" search pattern is also needed.

**Spec:** `_specs/financed-amount-field-and-search-fix.md`

---

## Files to Modify (7 files)

| # | File | Changes |
|---|------|---------|
| 1 | `app/.../FinancingAccount.kt` | Add `financedAmount: Double? = null` property |
| 2 | `app/.../res/values/strings.xml` | Add 3 string resources |
| 3 | `app/.../res/layout/activity_add_financing_account.xml` | Add Financed Amount input field after Downpayment |
| 4 | `app/.../AddFinancingAccountActivity.kt` | Currency watcher, edit pre-fill, save logic |
| 5 | `app/.../res/layout/item_financing_account.xml` | Add 4th "Financed" column to financial summary row |
| 6 | `app/.../res/layout/activity_financing_account_list.xml` | Add bottom summary bar, update constraints |
| 7 | `app/.../FinancingAccountListActivity.kt` | Data loading, adapter, edit intent, summary, search fix |

**Files NOT modified:** `FinancingAccountDetailActivity.kt`, `activity_financing_account_detail.xml` (financed amount not shown in detail view per spec)

---

## Implementation Steps

### Step 1: Model — `FinancingAccount.kt`

Add `financedAmount` after `downpayment` (line 14):

```kotlin
    val downpayment: Double? = null,
    val financedAmount: Double? = null,   // <-- NEW
    val createdAt: Timestamp? = null,
```

Backward compatible — Firestore documents without this field deserialize as `null`.

---

### Step 2: String Resources — `strings.xml`

Add after existing financing labels:

```xml
<string name="label_financed_amount">Financed Amount (Optional)</string>
<string name="label_total_financed">Total Financed</string>
<string name="label_entries">Entries</string>
```

---

### Step 3: Form Layout — `activity_add_financing_account.xml`

Add TextInputLayout after the Downpayment field (after line 233), before the Save button. Clone the Downpayment field pattern exactly:

- ID: `financedAmountInputLayout` / `financedAmountEditText`
- Hint: `@string/label_financed_amount`
- inputType: `numberDecimal`
- Style: `OutlinedBox`, `14sp`, `12dp` margin top

---

### Step 4: Form Logic — `AddFinancingAccountActivity.kt`

**4a. `setupCurrencyFormatting()` (line 154):** Add third watcher call:
```kotlin
addCurrencyTextWatcher(binding.financedAmountEditText)
```

**4b. `checkEditMode()` (after line 243):** Pre-fill from intent extras:
```kotlin
if (intent.getBooleanExtra("has_financed_amount", false)) {
    val financedAmount = intent.getDoubleExtra("financed_amount", 0.0)
    binding.financedAmountEditText.setText(currencyFormatter.format(financedAmount))
}
```

**4c. `saveAccount()` (after line 310):** Parse the field:
```kotlin
val financedAmountText = binding.financedAmountEditText.text.toString().trim().replace(",", "")
val financedAmount = if (financedAmountText.isNotEmpty()) financedAmountText.toDoubleOrNull() else null
```

**4d. `saveAccount()` data map (after line 327):** Add to map:
```kotlin
"financedAmount" to financedAmount,
```

**4e. `saveAccount()` edit mode (after line 340):** Handle deletion:
```kotlin
if (financedAmount == null) data["financedAmount"] = FieldValue.delete()
```

---

### Step 5: Card Layout — `item_financing_account.xml`

Add 4th column inside `financialSummaryRow`, after the Downpayment column (after line 265):

```xml
<!-- Financed Amount column -->
<LinearLayout
    android:id="@+id/financedAmountColumn"
    android:layout_width="0dp"
    android:layout_height="wrap_content"
    android:layout_weight="1"
    android:orientation="vertical"
    android:gravity="end">

    <TextView
        android:id="@+id/financedAmountLabel"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Financed"
        android:textSize="10sp"
        android:textColor="@color/gray" />

    <TextView
        android:id="@+id/financedAmountValue"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="13sp"
        android:textStyle="bold"
        android:textColor="@color/black" />
</LinearLayout>
```

Column order: **Monthly | Term | Downpayment | Financed** — all `layout_weight="1"`.

---

### Step 6: List Layout — `activity_financing_account_list.xml`

**6a. Add bottom summary bar** between RecyclerView section (after line 238) and FAB section:

```xml
<LinearLayout
    android:id="@+id/bottomSummaryBar"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="12dp"
    android:background="@color/white"
    android:elevation="8dp"
    app:layout_constraintBottom_toBottomOf="parent"
    app:layout_constraintStart_toStartOf="parent"
    app:layout_constraintEnd_toEndOf="parent">

    <!-- Left: Total Financed label + amount (techcity_blue) -->
    <!-- Right: Entries label + count (techcity_blue, gravity end) -->
</LinearLayout>
```

Follows the same pattern as `AccountReceivableActivity`'s `bottomActionBar`.

**6b. Update constraints** — RecyclerView, ProgressBar, emptyMessage all change bottom constraint from `parent`/`addButton` to `@id/bottomSummaryBar`.

**6c. FAB stays unchanged** — already constrained to `parent` bottom with `marginBottom="48dp"`, floats above the bar.

---

### Step 7: List Activity — `FinancingAccountListActivity.kt`

**7a. Import** (after line 28): Add `import java.text.NumberFormat`

**7b. Data loading** (line 266, inside FinancingAccount constructor): Add:
```kotlin
financedAmount = doc.getDouble("financedAmount"),
```

**7c. Adapter `onBindViewHolder`** (lines 615-654):
- Add `val hasFinanced = account.financedAmount != null && account.financedAmount > 0` after line 618
- Update line 619: `val hasFinancialData = hasMonthly || hasTerm || hasDownpayment || hasFinanced`
- Add financed amount rendering block after downpayment block (after line 650), same pattern:
  - Has value: `"₱${String.format("%,.2f", account.financedAmount)}"` in black
  - No value: `"—"` in gray

**7d. Edit intent extras** (`openEditMode`, after line 568): Add:
```kotlin
intent.putExtra("has_financed_amount", account.financedAmount != null)
if (account.financedAmount != null) {
    intent.putExtra("financed_amount", account.financedAmount)
}
```

**7e. Summary update** in `applyFilters()` (after line 344): Add:
```kotlin
val totalFinanced = filteredAccounts.sumOf { it.financedAmount ?: 0.0 }
binding.totalFinancedAmount.text = formatCurrency(totalFinanced)
binding.entryCount.text = filteredAccounts.size.toString()
```

**7f. Add `formatCurrency` helper** (after line 701, before closing brace):
```kotlin
private fun formatCurrency(amount: Double): String {
    val format = NumberFormat.getCurrencyInstance(Locale("en", "PH"))
    return format.format(amount)
}
```
Reuses the same implementation from `AccountReceivableActivity.kt:795-798`.

**7g. Fix `matchesDateSearch`** (replace lines 353-373):

```kotlin
private fun matchesDateSearch(purchaseDate: String, searchText: String): Boolean {
    if (purchaseDate.isEmpty()) return false

    // 1. Check for "month year" pattern (e.g., "dec 2025")
    val searchParts = searchText.split(" ")
    if (searchParts.size == 2) {
        val matchedMonth = monthNames[searchParts[0]]
        val matchedYear = if (searchParts[1].length == 4) searchParts[1].toIntOrNull() else null
        if (matchedMonth != null && matchedYear != null) {
            val currentYear = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila")).get(Calendar.YEAR)
            if (matchedYear < currentYear - 2) return false
            val parts = purchaseDate.split("-")
            if (parts.size == 3) {
                return parts[0].toIntOrNull() == matchedYear && parts[1].toIntOrNull() == matchedMonth
            }
            return false
        }
    }

    // 2. Month name only — match across ALL loaded years (bug fix)
    val matchedMonth = monthNames[searchText]
    if (matchedMonth != null) {
        val parts = purchaseDate.split("-")
        if (parts.size == 3) {
            return parts[1].toIntOrNull() == matchedMonth
        }
        return false
    }

    // 3. Fallback: match against formatted display date
    val displayDate = formatDisplayDate(purchaseDate).lowercase(Locale.getDefault())
    return displayDate.contains(searchText)
}
```

**Bug fix:** Removed the `year == currentYear` restriction from month-only search (was on old line 365). Now matches across all loaded years. Added "month year" pattern as priority check.

---

## Key Patterns Reused

| Pattern | Source | Reused In |
|---------|--------|-----------|
| `addCurrencyTextWatcher()` | `AddFinancingAccountActivity.kt:159` | New financed amount field |
| `has_*` / value intent extra pattern | `openEditMode()` lines 561-568 | Financed amount edit extras |
| `FieldValue.delete()` for nulls | `saveAccount()` lines 337-341 | Financed amount in edit mode |
| `formatCurrency()` | `AccountReceivableActivity.kt:795-798` | New helper in list activity |
| Bottom bar layout | `activity_account_receivable.xml:304-358` | New summary bar |

---

## Verification

1. **Build:** `./gradlew assembleDebug` — confirms View Binding generation and no compilation errors
2. **Add new account:** Fill in financed amount, save, verify it appears on the card and is stored in Firebase
3. **Edit existing account:** Verify financed amount pre-fills correctly; clear it and save to verify `FieldValue.delete()` works
4. **Old entries:** Verify entries created before this change display "—" for the financed column and don't crash
5. **Total summary bar:** Verify the total and entry count update when toggling company filters and when typing search text
6. **Search "dec":** Should show December entries from all loaded years (2024, 2025, 2026)
7. **Search "dec 2025":** Should show only December 2025 entries
8. **Search "dec 2021":** Should show no results (beyond 2-year limit)
9. **Detail screen:** Verify financed amount is NOT shown when tapping a card
10. **Card layout:** Verify all 4 financial columns fit in one line on a standard device
