# Feature Spec: Salmon Financing Company Integration

## Description

Add **Salmon** as a new financing company throughout the app, mirroring the existing Home Credit and Skyro integrations. This touches two areas:

1. **Service Transactions** — a new "SLM" filter toggle button (orange) in the transaction list, and a new "Salmon Payment" option in the New Transaction flow that records a payment tagged as `Salmon Payment`, following the same behavior as Home Credit Payment and Skyro Payment.
2. **Financing Accounts** — a new "Salmon" filter toggle button in the account list, and a "Salmon" option in the Financing Company dropdown when adding/editing a financing account.

The brand color for Salmon is **Orange**.

---

## Feature 1: SLM Filter Toggle Button (Service Transaction List)

### User Story

**As a store staff**, I want a dedicated "SLM" filter button in the Service Transactions screen, so I can quickly view only Salmon payment transactions for the selected date.

### UI/UX Flow

1. On the Service Transactions list screen, a new **"SLM"** toggle button appears in the filter button row, positioned **immediately after the "HC" button** and before the "Misc" button.
2. The button uses an **orange** background color.
3. Tapping "SLM" filters the list to show only transactions tagged as `Salmon Payment` for the currently selected date.
4. The selected/unselected visual behavior matches the other filter buttons (active = full opacity, inactive = dimmed at 50% opacity).
5. The summary header (Total Amount label) updates to indicate the Salmon filter is active (e.g., "Total Amount (SLM)"), and the totals/counts reflect only Salmon transactions.

### Notes

- The filter button row currently holds: All, In, Out, Load, Skyro, HC, Misc. Adding an 8th button means all buttons become narrower (they share width equally). This is acceptable per the existing layout pattern, but see Open Questions regarding crowding.

---

## Feature 2: Salmon Payment Option (New Transaction Flow)

### User Story

**As a store staff**, I want a "Salmon Payment" option when creating a new transaction, so I can record a customer's Salmon installment payment the same way I record Home Credit and Skyro payments.

### UI/UX Flow

1. From the Service Transactions list, tapping **New Transaction** opens the **Select Transaction Type** screen.
2. A new **"Salmon Payment"** card is added, positioned **immediately after the "Home Credit Payment" card** and before the "Misc Payment" card.
3. The card follows the same visual layout as the Skyro and Home Credit cards: a logo/icon on the left, a bold title "Salmon Payment" in the Salmon brand color (orange), and a subtitle (e.g., "Process Salmon installments").
4. Tapping the card opens the **same payment entry screen** used by Home Credit Payment and Skyro Payment, with the transaction type passed as **`Salmon Payment`**.
5. The payment entry screen behaves identically to the existing financing payments (amount entry, payment method / source of funds selection, fee handling, save to Firestore), but every reference, label, ledger description, and stored `transactionType` is tagged as **`Salmon Payment`**.
6. On save, the transaction is stored in the `service_transactions` collection with `transactionType = "Salmon Payment"` and appears in the list, filterable via the new SLM button.

### Business Rules

- The stored transaction type string is exactly **`Salmon Payment`** (consistent with `Home Credit Payment` and `Skyro Payment`).
- Ledger entries generated for a Salmon Payment use "Salmon Payment" in their credit/debit descriptions, mirroring how Home Credit / Skyro descriptions are produced.
- Source label on the detail screen for a Salmon Payment is "Payment Method:" (same as the other financing payments).
- The transaction-type badge/accent color for "Salmon Payment" is orange wherever transaction types are color-coded (transaction list cards and transaction details screen).

### Open Questions (fee behavior)

The existing financing payments handle fees differently:
- **Skyro Payment** uses a fixed fee.
- **Home Credit Payment** computes the fee based on the selected payment method.

The request says Salmon should "follow the same activity" as both, but their fee logic differs. **What is Salmon's fee rule?** Options:
- (a) Fixed fee like Skyro (what amount?) 18 Pesos


This must be answered before implementation. See Open Questions section.

---

## Feature 3: Salmon Filter Toggle Button (Financing Account List)

### User Story

**As a store staff**, I want a "Salmon" filter button in the Financing Accounts screen, so I can view only Salmon financing accounts.

### UI/UX Flow

1. On the Financing Accounts list screen, a new **"Salmon"** filter button appears in the filter button row, positioned **immediately after the "Home Credit" button**.
2. Tapping "Salmon" filters the loaded accounts (client-side) to show only those whose `financingCompany` equals `Salmon`.
3. The active/inactive opacity behavior matches the existing All / Home Credit / Skyro / Samsung Finance buttons.
4. The bottom summary bar (Total Financed and Entry Count) reflects the filtered Salmon accounts.

### Notes

- The filter row currently holds: All, Home Credit, Skyro, Samsung Finance. Adding a 5th button narrows all buttons. See Open Questions regarding crowding.

---

## Feature 4: Salmon Option in Financing Company Dropdown (Add/Edit Financing Account)

### User Story

**As a store staff**, I want "Salmon" as a selectable option in the Financing Company dropdown when adding or editing a financing account, so I can record Salmon-financed purchases.

### UI/UX Flow

1. On the Add/Edit Financing Account screen, opening the **Financing Company** dropdown now shows **Salmon** as an option, in addition to Home Credit, Skyro, and Samsung Finance.
2. Suggested ordering: Home Credit, Skyro, **Salmon**, Samsung Finance (Salmon placed after Home Credit/Skyro to match the financing payment ordering) — final order is an open question.
3. Selecting "Salmon" and saving stores `financingCompany = "Salmon"` on the account document (no schema change — same `financing_accounts` collection and `FinancingAccount` model).
4. In edit mode, an account already stored as "Salmon" pre-selects correctly in the dropdown.

### Business Rules

- The financing company badge color for "Salmon" account cards (and the Financing Account Detail screen) is **orange**, consistent with the rest of the feature. Currently badge colors are: Home Credit = red, Skyro = light blue, Samsung Finance = teal; unmatched companies fall back to gray. Salmon must be added to this color mapping so its badge is orange, not the gray fallback.

---

## Data Model

No schema changes.

- **`service_transactions`** — Salmon payments reuse the existing document shape; only the `transactionType` value `"Salmon Payment"` is new.
- **`financing_accounts`** — Salmon accounts reuse the existing `FinancingAccount` model; only the `financingCompany` value `"Salmon"` is new.

No new model classes or collections are introduced.

## Business Rules (Summary)

1. New transaction type string: **`Salmon Payment`** (exact).
2. New financing company string: **`Salmon`** (exact).
3. Salmon brand color is **orange** everywhere it is color-coded: SLM filter button, Salmon Payment card title, transaction-type accent/badge, and financing account badge.
4. Salmon payment processing reuses the existing financing-payment entry screen and ledger-generation logic; only labels/tags differ — except fee logic, which is an open question.
5. Button placement: SLM after HC (service list); Salmon Payment card after Home Credit Payment card (new transaction); Salmon filter after Home Credit (financing list); Salmon option after Home Credit/Skyro in the dropdown.

## Edge Cases and Error Handling

- Existing transactions and accounts with other types/companies are unaffected; their filters and badges continue to behave as before.
- A "Salmon Payment" transaction must not accidentally match the HC or Skyro filters, and vice versa (exact-string matching, as currently implemented).
- If no Salmon transactions/accounts exist for the active date/filter, the standard empty state is shown.
- Saving/loading errors reuse the existing Toast-based error handling — no new error paths.

## Open Questions

1. **Salmon fee rule** (critical for Feature 2): fixed fee (like Skyro), payment-method-based (like Home Credit), or none/manual? If fixed, what amount? If method-based, what schedule and what payment-method options? - fixed fee 18 pesos
2. **Salmon logo asset**: Skyro and Home Credit cards use brand logo drawables (`ic_skyro_logo`, `ic_home_credit_logo`). Is there a Salmon logo to supply, or should we use a generic orange icon/placeholder for the Salmon Payment card? I will supply the logo later use a place holder for now
3. **Filter row crowding**: Service list grows to 8 filter buttons and the financing list to 5. Is shrinking all buttons equally acceptable, or should the rows scroll horizontally / restyle? - shirnk all button. if possible make all buton fit but if it doesnt fit allow scroll horizontally
4. **Dropdown ordering**: Confirm the desired order of companies in the Financing Company dropdown. - order doesnt matter sort it alphabetically A first Z last
5. **Payment method options for Salmon** (only relevant if fee is method-based): which payment methods/sources apply? - Same Payment Model for Home Credit and SKyro
