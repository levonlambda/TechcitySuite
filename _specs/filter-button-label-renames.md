# Feature Spec: Filter Button Label Renames

## Description

Cosmetic, label-only renames of filter toggle buttons on four screens. No behavior, filtering logic, stored data, or navigation changes — only the visible text on the buttons.

1. **Device Transaction list, Accessory Transaction list, and Accounts Receivable screens**: rename the **"Skyro"** filter button to **"SKY"** and the **"In-House"** filter button to **"TC"**.
2. **Financing Accounts screen**: rename the **"Home Credit"** filter button to **"HC"**.

> Context: on the three transaction/receivable screens the Home Credit button is already abbreviated as "HC"; this change brings Skyro and In-House to a similar compact style. On the Financing Accounts screen the buttons currently use full names ("Home Credit", "Salmon", "Skyro", "Samsung Finance"); only Home Credit is abbreviated by this request.

---

## Feature 1: "SKY" and "TC" Filter Buttons (Device Transactions, Accessory Transactions, Accounts Receivable)

### User Story

**As a store staff**, I want shorter filter button labels ("SKY", "TC") on the transaction list and receivable screens, so the crowded filter rows are easier to read and tap.

### UI/UX Flow

1. On the **Device Transactions** list screen, the filter row currently reads: All, In, Out*, HC, Skyro, Salmon, In-House (per current layout). The "Skyro" button now reads **"SKY"** and the "In-House" button now reads **"TC"**.
2. On the **Accessory Transactions** list screen, the same two buttons in its filter row are renamed identically: "Skyro" → **"SKY"**, "In-House" → **"TC"**.
3. On the **Accounts Receivable** screen, the filter row (All, HC, Skyro, Salmon, In-House) becomes: All, HC, **SKY**, Salmon, **TC**.
4. Button position, size, color, selected/dimmed alpha behavior, and tap behavior are all unchanged — each button filters exactly the same set of transactions as before.

*(Exact button sets per screen may differ slightly; the rename applies to whichever button currently reads "Skyro" or "In-House" on each of the three screens.)*

### Business Rules

- **Label text only.** The underlying filter values, stored `transactionType` strings (`"Skyro Transaction"`, `"In-House Installment"`), Firestore data, and filter enum/logic are untouched.
- Everything else that displays "Skyro" or "In-House" as full words — transaction type badges on list cards and receivable cards, dropdown options in the New Transaction flow, summary labels (e.g. "In-House Receivable", "Skyro Receivable"), End-of-Day report rows, and dialogs — is **not** part of this change and keeps its current wording.
- The "Skyro" filter button on the **Service Transactions** screen is **not** renamed (it was not requested); it stays "Skyro".

---

## Feature 2: "HC" Filter Button (Financing Accounts)

### User Story

**As a store staff**, I want the "Home Credit" filter button on the Financing Accounts screen abbreviated to "HC", consistent with the transaction list screens.

### UI/UX Flow

1. On the **Financing Accounts** list screen, the filter row currently reads: All, Home Credit, Salmon, Skyro, Samsung Finance.
2. The "Home Credit" button now reads **"HC"**. All other buttons keep their current labels.
3. Filtering behavior, button order, colors, and the bottom summary bar are unchanged.

### Business Rules

- Label text only; the stored `financingCompany` value `"Home Credit"`, the company dropdown in Add/Edit Financing Account, and the account card badges are untouched.
- Note: this button's label comes from a shared string resource; the rename must affect **only** the Financing Accounts filter button, not any other screen that may reuse the same resource.

---

## Data Model

No data model changes of any kind. No Firestore documents, collections, model classes, or stored strings are affected.

## Business Rules (Summary)

1. Renames are visual button labels only: "Skyro" → **SKY** and "In-House" → **TC** on the Device Transaction list, Accessory Transaction list, and Accounts Receivable screens; "Home Credit" → **HC** on the Financing Accounts screen.
2. No filtering logic, stored values, badge texts, dropdown options, summary labels, or other screens change.
3. Service Transactions screen and End-of-Day report keep their current labels.

## Edge Cases and Error Handling

- **Shared string resource** (Financing Accounts): the Home Credit button label must be changed in a way that does not accidentally rename other UI that reuses the same string.
- **Button width/appearance**: shorter labels may render narrower text inside the same button widths; no layout adjustments are expected or desired.
- No error paths — this is a static label change with no runtime behavior.

## Open Questions

1. **"TC" meaning**: assumed to be the TechCity in-house brand abbreviation — confirm "TC" (not "TCI" or "IH") is the desired label. - yes TC is the desired label
2. **Consistency elsewhere**: the Accounts Receivable summary label still says "In-House Receivable" / "Skyro Receivable" when those filters are active, and list-card badges still say "Skyro" / "In-House" in full. Should these stay full-word (assumed yes, per "buttons only")? yes they should stay full words
3. **Service Transactions screen**: confirmed out of scope? Its filter row has a "Skyro" button that will now be inconsistent with the other screens. - I forgot about this also update the Service transaction and rename SKYRO button to SKY
