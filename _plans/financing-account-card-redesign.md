# Technical Plan: Financing Account Card Redesign

## Context

The `item_financing_account.xml` card layout has poor readability — labels and values share the same small font size and gray color, rows are cramped (2-4dp margins), and useful financial data (term, downpayment) is not displayed. The spec at `_specs/financing-account-card-redesign.md` defines a redesign with label-above-value patterns, better spacing, color contrast, and a financial summary row.

## Files to Modify

1. **`app/src/main/res/layout/item_financing_account.xml`** — Complete layout rewrite
2. **`app/src/main/java/com/techcity/techcitysuite/FinancingAccountListActivity.kt`** — Update `onBindViewHolder` (lines 300-348)

## Files NOT Changed

- `FinancingAccount.kt` — model already has all fields (`term`, `downpayment`)
- `colors.xml` — all colors exist (`cash_dark_green`, `gray`, `black`, `light_gray`)
- `rounded_badge_small.xml` — reused as-is
- Firestore queries, search/filter logic, other activities/layouts

## Implementation Steps

### Step 1: Rewrite `item_financing_account.xml`

Replace the entire layout with the new structure:

| Row | Content | Key Styling |
|-----|---------|-------------|
| **Row 1** | `customerNameText` + `financingCompanyBadge` | Name: 16sp bold black. Badge: **11sp** (was 10sp), padding 10dp/3dp (was 8dp/2dp) |
| **Row 2** | Account Number (label-above-value, left) + Date (label-above-value, right) | Acct label: "Account Number" 11sp gray. Acct value: **14sp bold black**. Date label: "Date" 11sp gray. Date value: 13sp black. Date column `wrap_content`, pushed right. marginTop=8dp |
| **Row 3** | Device (left col, conditional) + Phone (right col) | Device: `weight=1`, `visibility="gone"` by default. Phone: `wrap_content`, flush right. Labels: 11sp gray. Values: 13sp black. Row has `gravity="end"` to keep Phone right when Device is hidden. marginTop=6dp |
| **Divider** | 1dp `@color/light_gray` line | Conditional — `visibility="gone"` by default |
| **Row 4** | Monthly / Term / Downpayment (3-column) | Labels: 10sp gray. Values: 13sp bold. Monthly=left-aligned `cash_dark_green`, Term=center-aligned black, Downpayment=right-aligned black. Each column `weight=1`. Conditional |

Card padding increased from 12dp to 14dp.

**Column alignment detail (Row 2 ↔ Row 3):**
- Phone column is `wrap_content` (sizes to phone number text width), flush to right card edge
- Date column is `wrap_content` with `minimumWidth` set **programmatically** in `onBindViewHolder` to match the Phone column's text width (using `Paint.measureText`). This ensures the Date value starts at the same x-position as the Phone value, and the "Date" label left-aligns with the Date value

**View IDs removed:** `accountNumberText`, `contactNumberText`, `monthlyPaymentText`, `devicePurchasedRow`, `devicePurchasedText`

**View IDs added:** `accountNumberLabel`, `accountNumberValue`, `contactInfoRow`, `contactNumberColumn`, `contactNumberLabel`, `contactNumberValue`, `deviceColumn`, `devicePurchasedLabel`, `devicePurchasedValue`, `financialDivider`, `financialSummaryRow`, `monthlyPaymentColumn`, `monthlyPaymentLabel`, `monthlyPaymentValue`, `termColumn`, `termLabel`, `termValue`, `downpaymentColumn`, `downpaymentLabel`, `downpaymentValue`, `purchaseDateColumn`, `purchaseDateLabel`

**View IDs retained:** `customerNameText`, `financingCompanyBadge`, `purchaseDateText`

### Step 2: Update adapter `onBindViewHolder`

Replace `onBindViewHolder` method (lines 300-348) in the inner `FinancingAccountAdapter` class. Key changes:

1. **Renamed view bindings** — `accountNumberText` → `accountNumberValue`, `contactNumberText` → `contactNumberValue`
2. **Device column** — Show/hide `deviceColumn` (was `devicePurchasedRow`), bind to `devicePurchasedValue` (was `devicePurchasedText`). Now left column in Row 3.
3. **Financial summary visibility** — Check if any of `monthlyPayment > 0`, `term` not blank, or `downpayment > 0` exist. If yes, show `financialDivider` + `financialSummaryRow`. If no, hide both.
4. **Monthly payment** — Bind to `monthlyPaymentValue` with `cash_dark_green` color. Show "—" in gray if null/zero.
5. **Term** — NEW binding. `termValue` = `account.term`. Show "—" in gray if blank.
6. **Downpayment** — NEW binding. `downpaymentValue` = formatted currency. Show "—" in gray if null/zero.
7. **Purchase date** — Same logic, same ID, moved to right side of Account Number row (Row 2).
8. **Date column width alignment** — NEW logic. Use `Paint.measureText` on the phone number value and "Phone" label to calculate the Phone column's text width. Set `purchaseDateColumn.minimumWidth` to the larger of the two, ensuring the Date column starts at the same x-position as the Phone column.
9. **Badge + customer name + click** — No logic changes (badge tinting, name binding, toast click all unchanged).

### Step 3: Build and verify

Run `./gradlew assembleDebug` to confirm compilation succeeds with regenerated View Binding.

## Verification

1. Build: `./gradlew assembleDebug` — should compile without errors
2. Visual check on device/emulator:
   - Account with all fields populated → all 4 rows visible, financial summary shows values
   - Account with no financial data (monthlyPayment=null, term=null, downpayment=null) → divider and row 4 hidden
   - Account with partial financial data → row 4 visible, missing fields show "—"
   - Account with no device → device column hidden, phone column stays flush right
   - Badge is larger and more readable
   - Date label+value on right side of Row 2, left edge aligned with Phone label+value in Row 3
   - Financial summary spans full width: Monthly (left), Term (center), Downpayment (right)
