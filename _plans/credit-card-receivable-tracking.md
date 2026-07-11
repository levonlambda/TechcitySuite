# Technical Plan: Credit Card Receivable Tracking

## Context

Approved spec: `_specs/credit-card-receivable-tracking.md`. Credit card payments (bank reimburses ~1 week later, minus a 3.5% fee) are currently recorded as instantly-settled cash flow with no way to track which ones the bank has actually deposited. This plan tags every credit-card payment at sale time — both full Cash-Transaction payments and down payments on Home Credit / Skyro / Salmon / In-House transactions (per answered spec Q1) — with a nested `creditCardReceivable` block, surfaces unpaid ones in Accounts Receivable under the In-House filter group with a gray "Credit Card" badge showing the net amount (gross × 0.965), and supports manual multi-select mark-as-paid. End-of-Day reporting is untouched (spec Q2). Fee hard-coded at 3.5% (Q3), snapshotted per transaction.

## Files to Create (1)

### `app/src/main/java/com/techcity/techcitysuite/CreditCardReceivableDetails.kt`
Follows the one-payment-details-class-per-file convention (`CashPaymentDetails.kt`, etc.). Shared by both device and accessory models (like `AccountDetails` already is):

```kotlin
package com.techcity.techcitysuite

import com.google.firebase.Timestamp

data class CreditCardReceivableDetails(
    val amountCharged: Double = 0.0,
    val feePercent: Double = 0.0,
    val netReceivable: Double = 0.0,
    val isPaid: Boolean = false,
    val paidDate: String? = null,
    val paidBy: String? = null,
    val paidTimestamp: Timestamp? = null
) {
    constructor() : this(0.0)   // Firestore no-arg requirement
}
```

## Files to Modify (6)

### 1. `Constants.kt`
One constant next to the subsidy constants (~line 92):
```kotlin
const val CREDIT_CARD_FEE_PERCENT = 3.5
```
No shared rounding helper is added (none exists in the codebase; adding one would be scope creep) — rounding is a one-line inline expression at the two write sites.

### 2. `DeviceTransaction.kt` (~line 66) and 3. `AccessoryTransaction.kt` (~line 39)
Add one optional field to each data class, after the existing payment blocks:
```kotlin
val creditCardReceivable: CreditCardReceivableDetails? = null,
```
Defaulted null → legacy docs and existing `toObject()` calls remain fully compatible. Nothing else changes in either model.

### 4. `DeviceTransactionActivity.kt` — single insertion at line ~1622 (between the closing `}` of `when (transactionType)` and the STEP-4 save code)

**One post-`when` block instead of edits inside each of the 5 branches** — a pure insertion; every existing branch stays byte-identical (lowest-risk diff). Re-reads the same UI inputs the branches read, with the identical parse expression (verified: `binding.hcDownPaymentInput.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0`):

```kotlin
// Credit Card receivable tagging: when the money was charged to a credit card,
// record the pending bank reimbursement (gross less fee) for Accounts Receivable.
val ccChargeSource = when (transactionType) {
    AppConstants.TRANSACTION_TYPE_CASH -> binding.paymentSourceDropdown.text.toString()
    AppConstants.TRANSACTION_TYPE_HOME_CREDIT,
    AppConstants.TRANSACTION_TYPE_SKYRO,
    AppConstants.TRANSACTION_TYPE_SALMON -> binding.hcDownPaymentSourceDropdown.text.toString()
    AppConstants.TRANSACTION_TYPE_IN_HOUSE -> binding.ihDownPaymentSourceDropdown.text.toString()
    else -> ""
}
val ccChargeAmount = when (transactionType) {
    AppConstants.TRANSACTION_TYPE_CASH -> finalPrice
    AppConstants.TRANSACTION_TYPE_HOME_CREDIT,
    AppConstants.TRANSACTION_TYPE_SKYRO,
    AppConstants.TRANSACTION_TYPE_SALMON ->
        binding.hcDownPaymentInput.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0
    AppConstants.TRANSACTION_TYPE_IN_HOUSE ->
        binding.ihDownPaymentInput.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0
    else -> 0.0
}
if (ccChargeSource == AppConstants.PAYMENT_SOURCE_CREDIT_CARD && ccChargeAmount > 0) {
    val ccNetReceivable =
        Math.round(ccChargeAmount * (1 - AppConstants.CREDIT_CARD_FEE_PERCENT / 100.0) * 100) / 100.0
    transactionData["creditCardReceivable"] = hashMapOf<String, Any?>(
        "amountCharged" to ccChargeAmount,
        "feePercent" to AppConstants.CREDIT_CARD_FEE_PERCENT,
        "netReceivable" to ccNetReceivable,
        "isPaid" to false,
        "paidDate" to null,
        "paidBy" to null,
        "paidTimestamp" to null
    )
}
```
The `> 0` guard implements the zero-amount rule; when it fails the key is never written (doc shape stays legacy-identical). Inner map must be `hashMapOf<String, Any?>` to hold nulls.

### 5. `AccessoryTransactionActivity.kt` — same insertion at line ~1442 (after the `when` closes, before the doc is used in `runTransaction`)
Identical block **except** the `when` keys use this file's raw string literals (it does not use `AppConstants.TRANSACTION_TYPE_*`): `"Cash Transaction"` → `paymentSourceDropdown` / `finalPrice`; `"Home Credit Transaction"`, `"Skyro Transaction"`, `"Salmon Transaction"` → `hcDownPaymentSourceDropdown` / `hcDownPaymentInput`; `"In-House Installment"` → `ihDownPaymentSourceDropdown` / `ihDownPaymentInput`.

### 6. `AccountReceivableActivity.kt` — read side

**(i) Selection-key collision fix.** One HC/Skyro/Salmon/In-House doc with a credit-card down payment yields TWO AR rows sharing the same doc id; today selection is keyed on `item.id`, so checking one would check both, sums would double-count, and mark-as-paid could hit the wrong block. Add a computed property to `ReceivableItem` (after line 87):
```kotlin
val selectionKey: String get() = "$source|$id|$transactionType"
```
`selectedItems` now stores selection keys. Complete enumeration of sites switching from `id` to `selectionKey`:

| Line | Function | Change |
|---|---|---|
| 611–612 | `handleItemSelection` (hasInHouseSelected) | `find { it.selectionKey == selectedId }` |
| 616–617 | `handleItemSelection` (hasOtherSelected) | same |
| 639 | `handleItemSelection` | `selectedItems.add(item.selectionKey)` |
| 642 | `handleItemSelection` | `selectedItems.remove(item.selectionKey)` |
| 652 | `updateSelectionUI` sum | `.filter { selectedItems.contains(it.selectionKey) }` |
| 660–661 | `updateSelectionUI` (hasInHouseSelected) | find by `selectionKey` |
| 698–699 | `handleActionButtonClick` | find by `selectionKey` |
| 713–714 | `openInHousePaymentActivity` | find by `selectionKey`; **line 717 `putExtra("documentId", selectedItem.id)` stays as-is** (raw doc id) |
| 735, 740 | `showMarkAsPaidConfirmation` | `contains(it.selectionKey)` |
| 779–780 | `markSelectedAsPaid` loop | `for (selectedKey in ...)` + find by `selectionKey` |
| 800 | `markSelectedAsPaid` update | **`.document(itemId)` → `.document(item.id)`** — critical |
| 974 | adapter checkbox state | `contains(item.selectionKey)` |
| 985 | adapter card highlight | `contains(item.selectionKey)` |

No change: line 211 `selectedItems.clear()`, count/`.size` usages, adapter constructor param type.

**(ii) New queries.** Append a 5th query in `loadDeviceReceivables()` (before `return receivablesList`, ~line 320) and mirrored in `loadAccessoryReceivables()` (~line 471):
```kotlin
val creditCardQuery = db.collection(COLLECTION_DEVICE_TRANSACTIONS)
    .whereEqualTo("creditCardReceivable.isPaid", false)
    .orderBy("timestamp", Query.Direction.DESCENDING)
    .get().await()
for (doc in creditCardQuery.documents) {
    val data = doc.data ?: continue
    val ccReceivable = data["creditCardReceivable"] as? Map<*, *> ?: continue
    val item = parseDeviceReceivable(doc.id, data, "Credit Card", ccReceivable)
    if (item != null) receivablesList.add(item)
}
```
Legacy docs lack the field and never match an equality filter — excluded for free, no crash path.

**(iii) Parse functions — two tweaks each, no new functions.**
- `parseDeviceReceivable` (324–394): balance `when` (344–347) gains `"Credit Card" -> (paymentData["netReceivable"] as? Number)?.toDouble() ?: 0.0`; `finalPrice` (line 380) becomes `amountCharged` when type is "Credit Card", else unchanged.
- `parseAccessoryReceivable` (474–526): same two tweaks (balance at 481–484, finalPrice at 512).
- All other fields degrade correctly for CC rows: downpayment 0 → row hidden (adapter line 926), brandZeroSubsidy 0 → hidden (947), payments/customer are In-House-only (892, 955).

**(iv) `applyFilters()` (550–552):** IN_HOUSE branch → `it.transactionType == "In-House" || it.transactionType == "Credit Card"`. ALL branch unchanged. `updateSummary()` unchanged (spec Q5 accepts mixed In-House totals; label stays "In-House Receivable").

**(v) `handleItemSelection()` — no change.** "Credit Card" ≠ "In-House", so CC rows join the freely-multi-selectable group and keep the green "Mark as Paid" button, per spec.

**(vi) `markSelectedAsPaid()` (771–832):** replace the `fieldPath` string `when` + `update(fieldPath, true)` with an update-map `when`. Before the loop:
```kotlin
val prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE)
val paidBy = prefs.getString(AppConstants.KEY_USER, "") ?: ""
val paidDate = SimpleDateFormat("M/d/yyyy", Locale.US).format(Date())
```
In the loop, existing types map to their current single-boolean update (`mapOf("homeCreditPayment.isBalancePaid" to true)` etc. — behavior identical); new branch:
```kotlin
"Credit Card" -> mapOf(
    "creditCardReceivable.isPaid" to true,
    "creditCardReceivable.paidDate" to paidDate,
    "creditCardReceivable.paidBy" to paidBy,
    "creditCardReceivable.paidTimestamp" to FieldValue.serverTimestamp()
)
```
then `db.collection(collection).document(item.id).update(updates).await()`. Dot-notation map keys update only the nested fields — siblings untouched. (`M/d/yyyy` + prefs `KEY_USER` match the existing conventions, e.g. `InHousePaymentActivity` line 541.)

**(vii) Imports:** add `com.google.firebase.firestore.FieldValue` and `java.text.SimpleDateFormat`.

**(viii) Badge — no change needed.** Adapter line 913 already has `else -> item.transactionType to R.color.gray`, which renders a gray "Credit Card" badge automatically (`gray = #757575`).

## Files NOT touched (by design)

- `EndOfDayReportActivity.kt` and all EOD code — spec Q2: reporting stays exactly as-is (credit card remains an immediate cash-flow bucket).
- `InHousePaymentActivity.kt` — CC settlement never routes there.
- All layouts — the existing `item_account_receivable.xml` renders CC rows correctly via auto-hiding rows; no ViewBinding regeneration.
- `TransactionProcessor.kt` / `LedgerManager` — device/accessory sales never touch them.

## Dependencies / Libraries

None. Uses existing Firebase, coroutines, and binding infrastructure.

## Migration / Data Considerations

1. **Two new Firestore composite indexes (manual — no `firestore.indexes.json` exists in this repo).** Firebase console → Firestore → Indexes → Composite:
   - `device_transactions`: `creditCardReceivable.isPaid` Ascending + `timestamp` Descending (collection scope)
   - `accessory_transactions`: `creditCardReceivable.isPaid` Ascending + `timestamp` Descending (collection scope)
   Create these **before/immediately after deploying** — until they exist, the CC query throws inside the loader's shared try/catch and the whole AR screen shows the error state. (Shortcut: run the app once, Logcat's `FirebaseFirestoreException` contains click-to-create index URLs.)
2. No backfill: pre-feature credit-card transactions have no block and never appear in AR (per spec).
3. Hard-deleting a transaction (existing swipe-delete flow) removes the whole doc, so its CC receivable disappears automatically — no extra handling needed. There is no void flow in the codebase today (STATUS_VOID is defined but never set), so the spec's void edge case requires no code.

## Implementation Order

1. `CreditCardReceivableDetails.kt` (new) + model fields in `DeviceTransaction.kt` / `AccessoryTransaction.kt` + constant in `Constants.kt` — compiles standalone, zero behavior change.
2. Write-side blocks in `DeviceTransactionActivity.kt` then `AccessoryTransactionActivity.kt` — tagging starts; nothing reads it yet, independently safe.
3. Create both Firestore composite indexes.
4. `AccountReceivableActivity.kt`, in this order: (a) `selectionKey` + all key sites in one pass, (b) parse tweaks, (c) two query loops, (d) `applyFilters` branch, (e) `markSelectedAsPaid` restructure + imports.
5. Build + verification checklist.

## Risks

- **Missed selection-key site** → the exact double-select/double-count bug the key fixes. The table in 6(i) is the complete verified list.
- **Line 800** is the sneakiest edit: forgetting `.document(item.id)` would update against a composite key string.
- **Missing index** breaks the entire AR screen (shared try/catch), not just CC rows — hence step 3 before step 4 ships.
- **Accessory raw strings**: using `AppConstants.TRANSACTION_TYPE_*` in the accessory block would silently never match; must mirror that file's literals.
- Rounding `Math.round(x * 100) / 100.0` is stored on the doc, so list and totals sum the same rounded values (spec rounding rule).

## Verification

Build: `./gradlew assembleDebug` (no layout changes, so no ViewBinding regeneration concerns).

Manual scenarios:
1. **Cash CC device sale** (price 10,000, source Credit Card) → doc gains `creditCardReceivable {amountCharged: 10000.0, feePercent: 3.5, netReceivable: 9650.0, isPaid: false, paid* null}`; AR shows the row under All and In-House filters, gray "Credit Card" badge, Final Price ₱10,000, Balance ₱9,650, no downpayment/BZ/customer rows; In-House total includes 9,650.
2. **HC sale with 2,000 CC down payment → two rows from one doc**: an HC row (unchanged) + a CC row (Final Price ₱2,000, Balance ₱1,930). Checking one must NOT check the other; selected totals count only the checked row; selecting both sums both.
3. **Selection rules**: CC + HC + Skyro multi-select freely; In-House still exclusive-alone; button stays green "Mark as Paid" for CC selections.
4. **Mark as paid**: select CC row from #2 → confirm dialog shows net total → doc gets `isPaid: true`, `paidDate` (M/d/yyyy today), `paidBy` (settings user), `paidTimestamp` (server); sibling `homeCreditPayment` untouched; CC row gone on reload, HC row remains.
5. **Negative cases**: GCash cash sale → no `creditCardReceivable` key at all; financed sale with 0 down payment → no block; legacy docs → never appear, no crash; existing HC/Skyro/Salmon/In-House rows/totals identical to pre-change.
6. **Accessory parity**: accessory cash CC sale and accessory HC + CC down payment behave like #1/#2 with the accessory source badge.
7. **EOD unchanged**: run End-of-Day before/after — Credit Card bucket still counts gross immediately, identical totals.
8. **In-House flow intact**: In-House row → "Add Payment" still opens `InHousePaymentActivity` with the correct raw `documentId`; partial payments work.
9. **Delete cascade**: swipe-delete a CC-tagged transaction → its AR row disappears.
