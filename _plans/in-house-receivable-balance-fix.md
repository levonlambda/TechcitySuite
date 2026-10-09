# Technical Plan: In-House Receivable Balance Fix

## Context

The Accounts Receivable list shows the day-one balance for In-House installment cards because it reads `inHouseInstallment.remainingBalance`, a field that is written only at transaction creation and never updated when payments are recorded. The In-House Payment screen gets the correct figure by recomputing **(Final Price − Downpayment + Interest Amount) − Σ payments**. This fix makes the receivable list compute the balance the same way. No Firestore writes change.

**Spec:** `_specs/in-house-receivable-balance-fix.md`

---

## Files to Modify (1 file)

| # | File | Changes |
|---|------|---------|
| 1 | `app/src/main/java/com/techcity/techcitysuite/AccountReceivableActivity.kt` | Add one private helper that computes the In-House remaining balance; use it in the `"In-House"` branch of `parseDeviceReceivable()` and `parseAccessoryReceivable()` |

**Files NOT modified:**
- `InHousePaymentActivity.kt` — already correct; it is the reference formula.
- `DeviceTransactionActivity.kt`, `AccessoryTransactionActivity.kt` — creation writes unchanged.
- `DeviceTransactionListActivity.kt`, `AccessoryTransactionListActivity.kt` — out of scope (same stale field, noted in spec).
- `item_account_receivable.xml`, `activity_account_receivable.xml` — no layout changes.
- `InHouseInstallmentDetails.kt` — no model changes.

**Files to create:** none.

---

## Implementation Steps

### Step 1: Add a private helper — `AccountReceivableActivity.kt`

Add a new private function in **Part 2 (data loading)**, placed immediately before `parseDeviceReceivable()` (currently line 346) so both parse functions sit next to it.

```kotlin
    /**
     * Remaining balance for an In-House installment, derived the same way
     * InHousePaymentActivity computes it:
     *   (Final Price - Downpayment + Interest Amount) - sum of payments[].amount
     * Stored balance fields are not used: remainingBalance is never updated after
     * creation, and balance is stored pre-interest for device transactions until
     * the first payment. Result is floored at 0.
     */
    private fun computeInHouseRemainingBalance(
        data: Map<String, Any>,
        paymentData: Map<*, *>
    ): Double {
        val finalPrice = (data["finalPrice"] as? Number)?.toDouble() ?: 0.0
        val downpayment = (paymentData["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
        val interestAmount = (paymentData["interestAmount"] as? Number)?.toDouble() ?: 0.0

        val paymentsArray = paymentData["payments"] as? List<*>
        val totalPaid = paymentsArray?.sumOf { payment ->
            val paymentMap = payment as? Map<*, *>
            (paymentMap?.get("amount") as? Number)?.toDouble() ?: 0.0
        } ?: 0.0

        val remaining = (finalPrice - downpayment) + interestAmount - totalPaid
        return if (remaining < 0.01) 0.0 else remaining
    }
```

Notes:
- `finalPrice` comes from the **top-level** transaction document (`data`), not the nested installment map, matching how `ReceivableItem.finalPrice` is already read for non-Credit-Card rows.
- `downpaymentAmount`, `interestAmount`, and `payments` come from the nested `inHouseInstallment` map (`paymentData`).
- Missing or non-numeric values fall back to `0.0` (spec edge cases 2, 3, 4).
- The `< 0.01` floor implements spec rule 7 (tolerance) and edge case 5 (never negative).
- The payments sum deliberately mirrors the existing `totalPayments` sum in both parse functions rather than reusing it, because `balance` is computed **before** `totalPayments` in each function today. Keeping the existing statement order avoids reordering unrelated lines.

### Step 2: Use the helper in `parseDeviceReceivable()` — `AccountReceivableActivity.kt`

Change only the `"In-House"` branch of the `balance` `when` (currently line 367):

```kotlin
            val balance = when (transactionType) {
                "In-House" -> computeInHouseRemainingBalance(data, paymentData)
                "Credit Card" -> (paymentData["netReceivable"] as? Number)?.toDouble() ?: 0.0
                else -> (paymentData["balance"] as? Number)?.toDouble() ?: 0.0
            }
```

The `"Credit Card"` and `else` branches are untouched (spec rule 4).

### Step 3: Use the helper in `parseAccessoryReceivable()` — `AccountReceivableActivity.kt`

Same one-line change in the `"In-House"` branch (currently line 524):

```kotlin
            val balance = when (transactionType) {
                "In-House" -> computeInHouseRemainingBalance(data, paymentData)
                "Credit Card" -> (paymentData["netReceivable"] as? Number)?.toDouble() ?: 0.0
                else -> (paymentData["balance"] as? Number)?.toDouble() ?: 0.0
            }
```

### Step 4: Nothing else changes

- `updateSummary()`, `updateSelectionUI()`, the Mark-as-Paid confirmation text, and the adapter's `balanceText` binding all read `item.balance`, so header totals, the selection footer, and the card pick up the corrected value automatically (spec UI/UX sections 1–3).
- `inHousePaymentLauncher` already calls `loadReceivables()` on return, so the reduced balance shows immediately after a payment (spec edge case 9).

---

## Implementation Order

1. Step 1 (helper) → Step 2 (device branch) → Step 3 (accessory branch).
2. Build with `./gradlew assembleDebug`.

---

## Dependencies / Libraries

None. Pure Kotlin change inside one Activity.

---

## Migration / Data Considerations

- **No Firestore schema or write changes.** `balance`, `remainingBalance`, `originalBalance`, `totalAmountDue` are left exactly as they are.
- **No backfill needed.** Because the balance is derived from `finalPrice`, `downpaymentAmount`, `interestAmount`, and `payments[]`, every existing document (with or without past payments, with or without `originalBalance`) displays correctly immediately.
- **Older documents missing `interestAmount`** will show Final Price − Downpayment − Payments, which matches what the In-House Payment screen shows for the same document.

---

## Risks / Things to Watch

1. **Formula parity with the payment screen.** `InHousePaymentActivity` computes `originalBalance = (finalPrice - downpaymentAmount) + interestAmount` and `remainingBalance = originalBalance - totalPaid`, floored at 0. The helper must use the same three inputs from the same fields (`finalPrice` top-level, `downpaymentAmount` / `interestAmount` / `payments` nested). Do not substitute `totalAmountDue` or `originalBalance`, which are not reliably present or consistent across device vs accessory documents.
2. **Accessory interest base differs at creation.** Accessory transactions compute interest on `price` (before discount) at creation; the stored `interestAmount` reflects that. The helper reads the stored value, so it stays consistent with the payment screen regardless of how it was originally computed.
3. **Zero-balance rows.** A document with `isBalancePaid = false` but payments that already cover the balance will show ₱0.00 and remain listed (spec rule 6). The list does not auto-mark it paid; that remains the payment screen's job.
4. **Scope discipline.** The only edits are the new helper and the two `"In-House"` branch lines. The existing `totalPayments` blocks, comments, and the other `when` branches must not be touched or re-formatted.
5. **Device/Accessory Transaction List screens** still read the stale `remainingBalance` and will continue to show the old number. Explicitly out of scope per spec.

---

## Verification

1. `./gradlew assembleDebug` succeeds.
2. Open **Accounts Receivable → In-House** filter. For the reported entry (Infinix Smart 20, DP ₱1,000, interest ₱437.94, payments ₱645.00) the card Balance must read **₱7,091.94**, matching the In-House Payment screen's Remaining Balance.
3. In-House Receivable total and Total Receivable (All filter) decrease by the sum of recorded payments across all listed in-house accounts.
4. Tick the entry: the footer selected amount shows ₱7,091.94; tap **Add Payment**, record a payment, return: the card and totals reflect the new remaining balance without a manual refresh.
5. An in-house account with **no payments** shows the same balance as before the change.
6. An **accessory** in-house account shows Original Balance − Payments and matches its payment screen.
7. Home Credit, Skyro, Salmon, and Credit Card rows show unchanged balances and totals.
