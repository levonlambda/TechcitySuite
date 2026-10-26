# Feature Spec: End of Day Report Clipboard Export

## Description

The store owner maintains an Excel spreadsheet with one row per business day and six numeric columns summarizing that day's device and accessory sales. Today these six figures are read off the End of Day report screen and typed into Excel by hand.

This feature adds a **press-and-hold (long-press) gesture** on an entry in the **End of Day Reports list**. Long-pressing an entry copies that day's six figures to the Android clipboard as a single **tab-separated line**, so that pasting into Excel with the cursor on the first column fills six cells **left to right** in the order 1 → 6.

A normal tap on an entry continues to open the report exactly as it does today. No new screens are added.

> Context: Each entry in the End of Day Reports list corresponds to a saved document in the `daily_summaries` collection, generated for one date and one store location. That document already stores the per-type sales breakdown (count / amount / downpayment / balance for Cash, Home Credit, Skyro, Salmon, In-House) and the cash-flow breakdown by payment source (Cash, GCash, PayMaya, Bank Transfer, Credit Card, Others) separately for devices and accessories.

---

## Feature 1: Long-Press to Copy Daily Figures

### User Story

**As the store owner**, I want to press and hold an End of Day report entry and have that day's six spreadsheet figures copied to the clipboard, so I can switch to Excel and paste them into the row for that date without retyping.

### UI/UX Flow

1. Open **End of Day Reports** from the menu. The monthly list of generated reports appears as today.
2. **Press and hold** any report entry (the card showing the date, counts, and total revenue).
3. The device gives a short haptic tick (standard long-press feedback) and the six figures for that date are placed on the clipboard.
4. A short toast confirms the action, e.g. `Copied EOD figures for October 25, 2026`.
5. The long-press is consumed: the report screen does **not** open. A normal tap still opens the report as before.
6. The user switches to Excel (desktop via a clipboard-sync tool, or Excel on the phone), selects the first cell of the row, and pastes. Excel fills six adjacent cells left to right with columns 1 to 6.

### Clipboard Payload

- Exactly six numeric values on **one line**, separated by a single **tab** character, in the order 1 → 6 defined below.
- Values are plain numbers: two decimal places, a period as the decimal separator, **no currency symbol and no thousands separators** (e.g. `125000.00`), so Excel stores them as numbers rather than text.
- No header row, no labels, no trailing newline.
- Example (values illustrative, `⇥` marks a tab character):

  `125000.00 ⇥ 8450.00 ⇥ 64000.00 ⇥ 31500.00 ⇥ 18000.00 ⇥ 22400.00`

### The Six Columns

All figures are for the single date and store location of the long-pressed report. "Paid by credit card" means the payment source (for Cash Transactions) or the downpayment source (for financed transactions) is **Credit Card**.

| # | Excel column | Definition |
|---|---|---|
| 1 | Device cash collected | Sum of **device** Cash Transaction amounts **plus** the sum of downpayments from **device** Home Credit, Skyro, Salmon, and In-House Installment transactions, **excluding any amount paid by credit card** |
| 2 | Accessory cash collected | Sum of **accessory** Cash Transaction amounts **plus** the sum of downpayments from **accessory** Home Credit, Skyro, Salmon, and In-House Installment transactions, **excluding any amount paid by credit card** |
| 3 | Home Credit balance | Sum of balances of **device and accessory** Home Credit transactions |
| 4 | Skyro balance | Sum of balances of **device and accessory** Skyro transactions |
| 5 | Credit card total | Sum of **all device and accessory** amounts paid by credit card that day: full Cash Transaction amounts where the payment source is Credit Card, plus downpayments where the downpayment source is Credit Card |
| 6 | In-House + Salmon balance | Sum of balances of **device and accessory** In-House Installment transactions **plus** the sum of balances of **device and accessory** Salmon transactions (Salmon volume is too small to warrant its own column, so it is grouped with In-House) |

Put differently, every peso collected at the counter that day lands in exactly one of columns 1, 2, or 5: credit card money goes to column 5, everything else goes to column 1 (devices) or column 2 (accessories). Columns 3, 4, and 6 are the amounts still owed by the financing companies or the customer.

**Worked example:** a device sold for 10,000 on Home Credit with a 1,000 downpayment paid by credit card leaves a 9,000 balance. The 1,000 downpayment goes to column 5 (credit card), the 9,000 balance goes to column 3 (Home Credit), and nothing from this sale goes to column 1. Had the downpayment been paid in cash or GCash, the 1,000 would go to column 1 instead and column 5 would be unaffected.

### Business Rules

- The long-press copies figures for the **report entry pressed**, i.e. the date and store location of that saved report, not for "today".
- Figures are taken from the **saved End of Day summary document** that the list entry represents, so the clipboard values always match what the report screen for that date shows. They are not recomputed live from transactions.
- Column 1 is equivalent to the device cash-flow buckets Cash + GCash + PayMaya + Bank Transfer + Others (i.e. all device collections except Credit Card). Column 2 is the same for accessories. Column 5 is the device Credit Card bucket plus the accessory Credit Card bucket. Columns 3 and 4 are the device plus accessory balance (receivable) figures for Home Credit and Skyro respectively. Column 6 is the device plus accessory In-House balance plus the device plus accessory Salmon balance.
- A credit-card downpayment never reduces a balance column: the downpayment is counted in column 5 and the remaining balance is still counted in full in column 3, 4, or 6.
- The In-House balance follows the existing End of Day convention: it is the total amount due **including interest** (falling back to the plain balance for older records), consistent with the In-House receivable shown in the report.
- Only transactions with status **completed** are included, exactly as in the saved summary (voided and returned transactions are already excluded at report-generation time).
- Service transactions (cash in/out, loading, financing payments, misc) are **not** included in any column.
- Salmon **downpayments** contribute to columns 1, 2, or 5 depending on source; Salmon **balances** contribute to column 6 alongside In-House balances.
- Brand Zero subsidy amounts are not included in any column.
- Rounding: each column is rounded to two decimal places only when formatted for the clipboard.
- The gesture does not modify any data in Firestore and does not regenerate the report.

### Visual / Interaction Details

- No change to the entry card layout and **no on-screen hint** for the gesture; it stays undiscoverable to avoid clutter.
- The copy happens **immediately** on long-press. There is no preview dialog or confirmation step.
- Toast text includes the human-readable date so the user can confirm they pressed the right row.
- On Android 13 and above the system also shows its own "copied" preview overlay; the toast remains for older devices and for confirming the date.

---

## Data Model

No new Firestore collections and no new fields. The feature **reads** the following existing parts of a `daily_summaries` document (already fetched by the list screen's monthly query):

- `salesSummary.devices.homeCredit.balance` + `salesSummary.accessories.homeCredit.balance` → column 3
- `salesSummary.devices.skyro.balance` + `salesSummary.accessories.skyro.balance` → column 4
- `salesSummary.devices.inHouse.balance` + `salesSummary.accessories.inHouse.balance` + `salesSummary.devices.salmon.balance` + `salesSummary.accessories.salmon.balance` → column 6
- `cashFlowSummary.devices.{cash,gcash,paymaya,bankTransfer,others}` → column 1
- `cashFlowSummary.accessories.{cash,gcash,paymaya,bankTransfer,others}` → column 2
- `cashFlowSummary.devices.creditCard` + `cashFlowSummary.accessories.creditCard` → column 5

(The receivable fields `hcReceivable`, `skyroReceivable`, `salmonReceivable`, `inHouseReceivable` inside `cashFlowSummary` hold the same balance totals and may be used interchangeably with the `salesSummary` balances.)

The in-memory list item for each report (currently holding date, counts, and total revenue) is extended to carry the six computed figures so the long-press needs no extra network round-trip.

## Edge Cases and Error Handling

- **Legacy summary documents** generated before `cashFlowSummary` / `salesSummary` sub-maps existed (or missing a sub-field such as `salmon` or `others`): any missing value is treated as 0 and the copy still succeeds. If the document has **neither** a sales summary nor a cash-flow summary, show a toast such as "This report has no breakdown data to copy" and copy nothing.
- **Zero-activity day**: a report with no device or accessory sales copies six `0.00` values; this is valid and intentional.
- **Long-press vs. tap**: the long-press must return "handled" so the subsequent tap/click is not also fired and the report screen does not open.
- **Clipboard unavailable** (rare, e.g. restricted work profile): catch the failure and show an error toast; never crash.
- **Store location not configured**: the list already shows an empty state in this case, so there is nothing to long-press.
- **Duplicate legacy / per-store reports** for the same date: the list already de-duplicates to the per-store report, so the copied figures come from that one.
- **Negative or oddly formatted values**: formatted with the same two-decimal rule; no special handling.
- **Locale**: number formatting must always use a period decimal separator and no grouping regardless of device locale, otherwise Excel may treat the values as text.

## Resolved Decisions

The following questions were raised during spec review and answered by the owner. The spec body above already reflects these answers.

1. **Credit card and balance columns**: a credit-card downpayment goes to column 5 and the remaining balance still goes in full to column 3, 4, or 6. Example: a 10,000 device on Home Credit with a 1,000 credit-card downpayment puts 1,000 in column 5 and 9,000 in column 3.
2. **In-House balance basis**: use the total amount due **including interest**, matching the End of Day report.
3. **Salmon balances**: added to **column 6** together with In-House balances, because Salmon volume is too small to justify its own column.
4. **Data source**: figures come from the **saved End of Day summary document**, not a live recalculation. Transactions are very rarely changed after a report is generated, so this is acceptable.
5. **Number format**: two decimals, period decimal separator, no thousands separators, no currency symbol.
6. **Discoverability**: no on-screen hint for the long-press gesture.
7. **Confirmation**: copy immediately on long-press with a toast; no preview dialog.

## Open Questions

None at this time.
