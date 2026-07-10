# Feature Spec: Credit Card Receivable Tracking

## Description

When a customer pays by **Credit Card** in a Device Transaction or Accessory Transaction, the bank reimburses the store roughly a week later and deducts a **3.5% transaction fee**. Today these payments are recorded as if the money was received immediately, and there is no way to track which credit card payments have actually been deposited by the bank.

This feature **tags** credit-card payments at sale time and surfaces them in the **Accounts Receivable** section under the **In-House** group, showing the **net receivable (amount less 3.5%)**. The store owner manually marks each entry as paid when reconciling with the digital bank's transaction history.

**The transaction flow does not change.** Cashiers create device/accessory transactions exactly as they do today — selecting "Credit Card" as the payment source is all that is required. No new screens, dropdowns, or steps are added to the selling flow. The feature only adds background tagging plus visibility and settlement in Accounts Receivable.

> Context: "Credit Card" already exists as a payment source option (`Cash Transaction` → payment source dropdown) in both `DeviceTransactionActivity` and `AccessoryTransactionActivity`, and is already bucketed in End-of-Day reports. What is missing is receivable tracking and manual settlement.

---

## Feature 1: Tag Credit Card Payments at Sale Time

### User Story

**As a store owner**, I want every device/accessory sale paid via Credit Card to be automatically tagged as a pending bank reimbursement, so none of them slip through unreconciled.

### UI/UX Flow

1. Cashier creates a Device or Accessory transaction as usual: transaction type **Cash Transaction**, payment source **Credit Card**.
2. Nothing new appears on screen; the save flow is unchanged.
3. Behind the scenes, the saved transaction document carries an additional credit-card reimbursement block recording: the amount charged to the card, the fee percentage (3.5%), the computed net receivable (amount × 0.965), and an unpaid flag.

### Business Rules

- Tagging applies **only** when the payment source is exactly **`Credit Card`**.
- Applies to both `device_transactions` and `accessory_transactions`.
- The gross amount is the amount actually charged to the card (for a Cash Transaction this is the full amount paid).
- Net receivable = gross amount − (gross amount × 3.5%), rounded to 2 decimal places.
- The reimbursement block is created at save time with `isPaid = false`.
- Existing transactions saved before this feature are not retroactively tagged.

---

## Feature 2: Credit Card Entries in Accounts Receivable (In-House Group)

### User Story

**As a store owner**, I want unreimbursed credit card payments to appear in the Accounts Receivable screen under the In-House group, showing the net amount I expect from the bank, so I can see at a glance how much money is still in transit.

### UI/UX Flow

1. Open **Accounts Receivable**. Credit card entries appear in the list alongside existing receivables.
2. Credit card entries are included in the **All** filter and in the **In-House** filter group.
3. Each entry displays, following the existing receivable card layout:
   - Item name / details (device model or accessory name)
   - Date sold
   - Amount charged to the card (gross)
   - **Balance = net receivable (gross less 3.5%)** — this is the figure counted in receivable totals
   - A distinct **"Credit Card"** badge so entries are visually distinguishable from In-House Installment entries within the same group
4. Entries disappear from the list once marked as paid (same behavior as other receivable types).

### Business Rules

- Only transactions with an **unpaid** credit-card reimbursement block appear.
- The receivable balance shown and totaled is always the **net** amount (less 3.5%), since that is what the bank will actually deposit.
- Voided or returned transactions must not appear as receivables.

---

## Feature 3: Manually Mark Credit Card Entries as Paid

### User Story

**As a store owner**, when I see the bank deposit in my digital bank's transaction history, I want to select the matching credit card entries and mark them as paid, so my receivables stay accurate.

### UI/UX Flow

1. In Accounts Receivable, the owner selects one or more **Credit Card** entries (multi-select supported, same as Home Credit / Skyro / Salmon entries).
2. Taps **Mark as Paid** and confirms in the existing confirmation dialog (showing count and total net amount).
3. Selected entries are flagged paid (with paid date/user recorded) and removed from the list.

### Business Rules

- Credit card entries use the **simple mark-as-paid flow** (like Home Credit / Skyro / Salmon) — they do **not** open the In-House partial-payment screen, because the bank always deposits the full net amount in one transfer.
- Marking as paid is full settlement only — no partial payments.
- Paid date, paid-by user, and timestamp are recorded on the transaction document.
- Mixing selection of Credit Card entries with other types follows the existing selection rules (In-House Installment entries must still be handled alone via their payment screen).

---

## Data Model

No new Firestore collections. Both `device_transactions` and `accessory_transactions` documents gain a new **optional nested block** (present only when payment source is Credit Card), conceptually:

- `creditCardReceivable`
  - `amountCharged` — gross amount charged to the card
  - `feePercent` — fee applied at sale time (3.5)
  - `netReceivable` — amountCharged less fee, the amount expected from the bank
  - `isPaid` — false at sale time; true after manual reconciliation
  - `paidDate`, `paidBy`, `paidTimestamp` — set when marked as paid

Storing `feePercent` and `netReceivable` at sale time snapshots the fee, so a future fee change never alters historical receivables.

Model classes `DeviceTransaction` and `AccessoryTransaction` gain a matching optional field. All existing fields and the existing payment blocks are untouched, so old documents remain fully compatible.

## Business Rules (Summary)

1. Trigger: payment source exactly **`Credit Card`** on a device or accessory transaction.
2. Net receivable = gross × (1 − 0.035); the 3.5% fee is fixed and snapshotted per transaction.
3. Credit card receivables are listed under the **In-House** filter group (and All), with their own badge.
4. Settlement is manual, full-amount only, via the existing mark-as-paid flow.
5. The selling flow, screens, and steps remain exactly as they are today.

## Edge Cases and Error Handling

- **Voided / returned transactions**: excluded from Accounts Receivable; if a tagged transaction is voided after creation, its receivable must not remain outstanding.
- **Zero/invalid amounts**: a credit-card payment of zero should not produce a receivable entry.
- **Legacy transactions**: pre-feature credit card transactions have no reimbursement block and simply never appear in AR (no crashes on missing fields).
- **Feature flag**: Accounts Receivable visibility is already controlled by the `account_receivable_enabled` setting; tagging still happens at sale time regardless, so nothing is lost if the section is enabled later.
- **Rounding**: net receivable rounded to 2 decimals; totals sum the rounded per-entry values so list and summary always agree.
- **Concurrent mark-as-paid**: if two users mark the same entry, the second update is a no-op (already true) — same behavior as existing types.

## Open Questions

1. **Down payments by credit card**: Home Credit / Skyro / Salmon / In-House transactions also allow "Credit Card" as the *down payment* source. Should those down payments also generate a credit card receivable (net of 3.5%)? The request mentions tagging "payments using credit card" generally, but the simplest first version covers only full Cash-Transaction credit card payments. - yes those downpayment should generate credit card receivable net 3.5%
2. **End-of-Day report**: today a credit card payment is counted as immediate cash-flow in the "Credit Card" bucket, not as a receivable. Since "the flow of the program should remain the same," this spec leaves End-of-Day unchanged. Should a later iteration move unreimbursed credit card amounts into the EOD receivables block instead? - nothing should change as far as end of day report is concern. leave the current reporting as is
3. **Fee configurability**: is a hard-coded 3.5% acceptable, or should the fee percentage be editable in app settings (it is snapshotted per transaction either way)? - for now hard code it
4. **Badge color**: what accent color should the "Credit Card" badge use in the AR list? (Existing: Home Credit = red, Skyro = light blue, Salmon = orange, In-House = its current color.) - lets try gray
5. **In-House group vs. own filter**: the request says to place these under the In-House group. Confirm you do not want a separate "Credit Card" filter button instead (or in addition) — grouping under In-House means the In-House totals will mix installments and card reimbursements. - yes for now just place it in the in-house group.
