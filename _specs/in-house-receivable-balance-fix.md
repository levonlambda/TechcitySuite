# Feature Spec: In-House Receivable Balance Fix

## Feature Description

In the **Accounts Receivable** module, the card for an **In-House** installment shows the wrong **Balance**. The card displays the balance as it was **when the transaction was created** (the original financed amount including interest), not the **remaining balance** after the customer's partial payments have been deducted.

Example from the reported case (device transaction #3, Infinix Smart 20):

| Figure | Receivable card (wrong) | In-House Payment screen (correct) |
|---|---|---|
| Original balance (price − DP + interest) | ₱7,736.94 | ₱7,736.94 |
| Payments made | ₱645.00 | ₱645.00 |
| **Balance shown** | **₱7,736.94** | **₱7,091.94** |

The card correctly shows ₱645.00 under **Payments**, yet still shows ₱7,736.94 under **Balance**. Opening the same entry in the In-House Payment screen shows the correct **Remaining Balance** of ₱7,091.94.

### Root cause (as found in the current app)

The In-House installment record in Firestore keeps two balance-related fields that have drifted apart:

- A **"remaining balance"** field that is written **once**, when the transaction is created, and never touched again.
- A **"balance"** field that the In-House Payment screen **overwrites with the new remaining balance every time a payment is recorded** (alongside appending the payment to the `payments` list).

The Accounts Receivable list reads the first field (the one that is never updated), so it always shows the day-one balance. The In-House Payment screen does not trust either stored field; it recomputes **Original Balance − Total Paid** from the `payments` list, which is why it shows the right number.

Note that the `balance` field cannot simply be read instead: for device transactions it is stored **before interest** at creation and only becomes correct after the first payment. The fix therefore derives the balance from the payment history, exactly as the In-House Payment screen does.

---

## User Stories / Use Cases

1. **As the owner / cashier**, when I look at the In-House section of Accounts Receivable, I want each card's **Balance** to show what the customer **still owes today**, so I can tell at a glance who has paid down their account and who hasn't.
2. **As the owner**, I want the **In-House Receivable** total at the top of the screen (and the **Total Receivable** when "All" is selected) to sum the **remaining** balances, so the figure reflects money actually still collectible.
3. **As the cashier**, when I select an In-House entry and tap **Add Payment**, I want the **selected amount** shown at the bottom of the screen to match the remaining balance, so it agrees with what the payment screen will show next.
4. **As the owner**, I want an In-House account whose payments already cover the full balance to no longer show as an unpaid receivable, so fully paid accounts don't inflate the receivable total.
5. **As the owner**, I want the card's **Balance** to equal **Original Balance − Payments** exactly as shown on the In-House Payment screen, so the two screens never disagree.

---

## UI/UX Flow

### 1. Accounts Receivable list — In-House card (`item_account_receivable.xml`)

No layout changes. The card keeps the same five figures:

- **Final Price** — unchanged.
- **DP** with payment-source badge — unchanged.
- **Payments** — total of all recorded payments (already correct today).
- **Balance** — **changed**: now shows the **remaining balance**, i.e. the original balance (Final Price − Downpayment + Interest Amount) less the total of all payments. Shown in red as today. Must never show a negative value; if payments meet or exceed the original balance it shows ₱0.00 (and in practice such an entry should not appear in the list; see Business Rules).

The same applies to In-House entries that originate from **accessory transactions** (the receivable list merges device and accessory in-house accounts).

### 2. Summary header

- **In-House Receivable** (when the In-House filter is active) and **Total Receivable** (when "All" is active) are sums of each row's Balance, so they automatically reflect remaining balances once the card value is fixed. No separate layout change.

### 3. Selection footer and Add Payment

- When an In-House entry is ticked, the **selected amount** and **Selected Total** in the footer use the row's Balance, so they will now show the remaining balance.
- Tapping **Add Payment** opens the existing In-House Payment screen as today. That screen already computes the correct remaining balance; no change there.
- After returning from the payment screen the list already reloads; the newly reduced balance must be visible on the card immediately.

### 4. Other screens

The In-House Payment screen (`activity_in_house_payment.xml` — Transaction Details / Balance Summary cards shown in the second screenshot) is already correct and is the **reference** for what the card must show. It is not modified by this fix.

---

## Data Model

### Firestore — no schema change required

Collections involved: `device_transactions` and `accessory_transactions`, nested object `inHouseInstallment`.

Relevant existing fields inside `inHouseInstallment`:

| Field | Written when | Meaning today |
|---|---|---|
| `interestAmount` | Creation | Interest in pesos |
| `downpaymentAmount` | Creation | Downpayment |
| `balance` | Creation, **and updated on every payment** | Current remaining balance (kept current by the payment screen) |
| `remainingBalance` | Creation only | Day-one balance; **stale** after the first payment |
| `totalAmountDue` | Creation | Day-one balance including interest (device transactions) |
| `originalBalance` | Set on first payment (backfill) | Original balance including interest |
| `payments[]` | Appended on every payment | `{ date, amount, remainingAfter, source, timestamp? }` |
| `isBalancePaid` | Creation (false), set true on final payment | Fully paid flag |

Top-level `finalPrice` on the transaction document is also used.

### Source of truth for the receivable card

The card's **Balance** is derived the same way the In-House Payment screen derives **Remaining Balance**:

> Remaining Balance = (Final Price − Downpayment Amount + Interest Amount) − Σ payments[].amount, floored at 0.

Reasons for computing rather than reading a stored field:

- `remainingBalance` is never updated after creation (the reported bug).
- `balance` is stored **pre-interest** for device transactions until the first payment overwrites it, so it is wrong for every new device account.
- The formula is already what the payment screen uses, so the two screens are guaranteed to agree, including on older documents where `originalBalance` was never backfilled.

**No Firestore writes are added or changed by this fix.** Transaction creation and payment recording stay exactly as they are.

### Model classes

No new models. The in-memory receivable row (`ReceivableItem` inside `AccountReceivableActivity`) already carries `finalPrice`, `downpayment`, `totalPayments`, and `balance`; only the way `balance` is populated changes. The implementation reads `interestAmount` from the installment data during parsing to compute the balance.

---

## Business Rules and Logic

1. **Balance definition (In-House only).** For both device and accessory in-house receivables:
   `Balance = (Final Price − Downpayment + Interest Amount) − Total Payments`, never below zero.
2. **Payments definition.** `Total Payments = sum of amount over every entry in payments[]`. Unchanged from today.
3. **Consistency rule.** The card's Balance must equal the **Remaining Balance** shown on the In-House Payment screen for the same document, to the centavo.
4. **Non-In-House rows are untouched.** Home Credit, Skyro, Salmon and Credit Card rows keep their current balance sources (`balance` / `netReceivable`). This fix must not change their displayed values or totals.
5. **Totals follow rows.** Header totals and the selection footer continue to sum row balances; no separate calculation.
6. **Fully paid accounts.** An In-House document is listed only while `isBalancePaid` is false (unchanged). If such a document nonetheless computes to a zero balance (e.g. payments recorded externally), it shows ₱0.00 rather than a negative number.
7. **Rounding.** Currency comparisons use a small tolerance (as the payment screen already does with ±0.01) so floating-point residue does not produce "₱0.01" balances or negative displays.
8. **No change to write paths.** Creating transactions and recording payments are not modified. Stored `balance` / `remainingBalance` fields are left as they are.

---

## Edge Cases and Error Handling

1. **No payments yet.** `payments[]` missing or empty → Balance = original balance (same number as today). Card looks identical to current behaviour for brand-new accounts.
2. **Missing `interestAmount`** (older or hand-edited document) → treat as 0. The balance then equals Final Price − Downpayment − Payments.
3. **Missing `downpaymentAmount`** → treat as 0.
4. **Payment entries with a missing or non-numeric `amount`** → that entry contributes 0; the rest are still summed (matches current Payments column behaviour).
5. **Payments exceed original balance** (over-payment or data entry error) → Balance shows ₱0.00, never negative. The account is not auto-marked paid by the list screen; marking paid remains the payment screen's responsibility.
6. **Accessory in-house records** whose stored `remainingBalance` excludes interest → unaffected, because the stored field is no longer the source of the displayed value.
7. **Document with `isBalancePaid = true`** → excluded from the list as today, regardless of computed balance.
8. **Parse failure** on a single document → that row is skipped (existing behaviour) and must not block the rest of the list from loading.
9. **Returning from Add Payment** → list reloads and the reduced balance is visible; if the final payment was made, the entry disappears from the list.

---

## Open Questions

None. Decided: the card computes Balance from Original Balance − Payments (option 2). No stored field is changed and no backfill is needed.

**Out of scope, noted for later:** the Device Transaction List and Accessory Transaction List in-house cards and their balance summaries read the same stale `remainingBalance` field and show the same wrong number. They are not touched by this task.
