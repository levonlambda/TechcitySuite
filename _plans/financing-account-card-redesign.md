# Technical Plan: Financing Account Card Redesign

## Context

The `item_financing_account.xml` card layout has poor readability — labels and values share the same small font size and gray color, rows are cramped (2-4dp margins), and useful financial data (term, downpayment) is not displayed. The spec at `_specs/financing-account-card-redesign.md` defines a redesign with label-above-value patterns, better spacing, color contrast, a financial summary row, and a footer with date + username.

## Files to Modify

1. **`app/src/main/res/layout/item_financing_account.xml`** — Complete layout rewrite
2. **`app/src/main/java/com/techcity/techcitysuite/FinancingAccountListActivity.kt`** — Update `onBindViewHolder` (lines 300-348)

## Files NOT Changed

- `FinancingAccount.kt` — model already has all fields (`term`, `downpayment`, `createdBy`)
- `colors.xml` — all colors exist (`cash_dark_green`, `gray`, `black`, `light_gray`)
- `rounded_badge_small.xml` — reused as-is
- Firestore queries, search/filter logic, other activities/layouts

## Implementation Steps

### Step 1: Rewrite `item_financing_account.xml`

Replace the entire layout with the new structure:

| Row | Content | Key Styling |
|-----|---------|-------------|
| **Row 1** | `customerNameText` + `financingCompanyBadge` | Name: 16sp bold black. Badge: **11sp** (was 10sp), padding 10dp/3dp (was 8dp/2dp) |
| **Row 2** | Account Number (label-above-value) | Label: "Account Number" 11sp gray. Value: **14sp bold black**. marginTop=8dp |
| **Row 3** | Phone (left col) + Device (right col, conditional) | Labels: 11sp gray. Values: 13sp black. Two-column with weight=1. marginTop=6dp |
| **Divider** | 1dp `@color/light_gray` line | Conditional — `visibility="gone"` by default |
| **Row 4** | Monthly / Term / Downpayment (3-column) | Labels: 10sp gray. Values: 13sp bold. Monthly=`cash_dark_green`, others=black. Conditional |
| **Row 5** | `purchaseDateText` (left) + `userText` (right) | Both 11sp gray. Spacer view between. marginTop=8dp |

Card padding increased from 12dp to 14dp.

**View IDs removed:** `accountNumberText`, `contactNumberText`, `monthlyPaymentText`, `devicePurchasedRow`, `devicePurchasedText`

**View IDs added:** `accountNumberLabel`, `accountNumberValue`, `contactInfoRow`, `contactNumberColumn`, `contactNumberLabel`, `contactNumberValue`, `deviceColumn`, `devicePurchasedLabel`, `devicePurchasedValue`, `financialDivider`, `financialSummaryRow`, `monthlyPaymentColumn`, `monthlyPaymentLabel`, `monthlyPaymentValue`, `termColumn`, `termLabel`, `termValue`, `downpaymentColumn`, `downpaymentLabel`, `downpaymentValue`, `footerRow`, `userText`

**View IDs retained:** `customerNameText`, `financingCompanyBadge`, `purchaseDateText`

### Step 2: Update adapter `onBindViewHolder`

Replace `onBindViewHolder` method (lines 300-348) in the inner `FinancingAccountAdapter` class. Key changes:

1. **Renamed view bindings** — `accountNumberText` → `accountNumberValue`, `contactNumberText` → `contactNumberValue`
2. **Device column** — Show/hide `deviceColumn` (was `devicePurchasedRow`), bind to `devicePurchasedValue` (was `devicePurchasedText`)
3. **Financial summary visibility** — Check if any of `monthlyPayment > 0`, `term` not blank, or `downpayment > 0` exist. If yes, show `financialDivider` + `financialSummaryRow`. If no, hide both.
4. **Monthly payment** — Bind to `monthlyPaymentValue` with `cash_dark_green` color. Show "—" in gray if null/zero.
5. **Term** — NEW binding. `termValue` = `account.term`. Show "—" in gray if blank.
6. **Downpayment** — NEW binding. `downpaymentValue` = formatted currency. Show "—" in gray if null/zero.
7. **User text** — NEW binding. `userText` = `"by ${account.createdBy}"` (pattern from `DeviceTransactionListActivity` line 1351)
8. **Purchase date** — Same logic, same ID, just moved to footer position in XML.
9. **Badge + customer name + click** — No logic changes (badge tinting, name binding, toast click all unchanged).

### Step 3: Build and verify

Run `./gradlew assembleDebug` to confirm compilation succeeds with regenerated View Binding.

## Verification

1. Build: `./gradlew assembleDebug` — should compile without errors
2. Visual check on device/emulator:
   - Account with all fields populated → all 5 rows visible, financial summary shows values
   - Account with no financial data (monthlyPayment=null, term=null, downpayment=null) → divider and row 4 hidden
   - Account with partial financial data → row 4 visible, missing fields show "—"
   - Account with no device → device column hidden, phone column takes left space
   - Badge is larger and more readable
   - "by username" shows in bottom-right footer
   - Purchase date shows in bottom-left footer
