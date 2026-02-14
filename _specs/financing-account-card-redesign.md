# Feature Spec: Financing Account Card Redesign

## Description

Redesign the `item_financing_account.xml` card layout to be cleaner, more professional, and easier to read. The current card has poor visual hierarchy — labels and values use similar font sizes and colors, rows are cramped, and important data blends together. The redesign introduces clear label/value contrast, better spacing, and a structured layout that lets staff quickly scan account information.

## Current Problems

1. **No visual hierarchy** — Labels ("Acct #:", "Phone #:", "Device:") and values use nearly the same font size (12-13sp) and the same gray color, making everything look flat.
2. **Cramped spacing** — Rows have only 2-4dp top margin, making the card feel dense and hard to scan.
3. **Values don't stand out** — Account number, contact number, and device are all `@color/gray`, the same as their labels. Nothing pops.
4. **Monthly payment is hidden** — It's on the same row as contact number with `visibility="gone"`, and even when visible it's easy to miss.
5. **Purchase date has no label** — Right-aligned on row 2 with no context, easy to overlook.
6. **Financing company badge is tiny** — 10sp text with minimal padding makes it hard to read at a glance.
7. **Financial info (monthly, downpayment, term) not shown** — The card doesn't surface monthly payment, term, or downpayment — all useful at-a-glance info.

## Redesigned Layout

### Visual Structure

```
┌──────────────────────────────────────────────────────┐
│  Customer Name                    [Home Credit]      │  Row 1: Name + Badge
│                                                      │
│  Acct #                                              │
│  HC-123456789                                        │  Row 2: Account Number
│                                                      │
│  Phone #                Device                       │
│  09171234567            Samsung Galaxy S25            │  Row 3: Contact + Device
│                                                      │
│  ─────────────────── (thin divider) ───────────────  │
│                                                      │
│  Monthly         Term         Downpayment            │
│  ₱1,500.00       12 months    ₱3,000.00             │  Row 4: Financial summary
│                                                      │
│                                  Feb 14, 2026        │  Row 5: Purchase date
└──────────────────────────────────────────────────────┘
```

### Row-by-Row Specification

#### Row 1: Customer Name + Financing Company Badge
- **Customer name**: `16sp`, bold, `@color/black` — primary identifier, unchanged from current.
- **Financing company badge**: Increase text to `11sp`, increase padding to `paddingStart/End="10dp"` and `paddingTop/Bottom="3dp"` for better readability. Keep the colored background (`rounded_badge_small`) and white text.
- Layout: name takes remaining space (`weight=1`), badge is `wrap_content` on the right.

#### Row 2: Account Number (label-above-value)
- **Label**: "Account Number" — `11sp`, `@color/gray` — small, muted label above the value.
- **Value**: Account number — `14sp`, `medium/bold` weight, `@color/black` — larger and darker than the label so it pops.
- Layout: Vertical stack (label on top, value below). Full width. `marginTop="8dp"` from Row 1 for breathing room.

#### Row 3: Contact Number + Device Purchased (two-column, label-above-value)
- **Left column** (Contact Number):
  - Label: "Phone" — `11sp`, `@color/gray`
  - Value: phone number — `13sp`, `@color/black`
- **Right column** (Device Purchased) — only visible if device is not null/blank:
  - Label: "Device" — `11sp`, `@color/gray`
  - Value: device name — `13sp`, `@color/black`, `ellipsize="end"`, `maxLines="1"`
- Layout: Horizontal, each column `weight=1`. `marginTop="6dp"` from Row 2.
- If no device, the contact number column still takes `weight=1` (left-aligned) and the right column is `gone`.

#### Divider (between info and financial summary)
- A thin horizontal line: `1dp` height, `@color/light_gray` background.
- `marginTop="10dp"`, `marginBottom="2dp"`.
- Only visible when at least one financial field (monthly payment, term, or downpayment) has data. If all three are empty/null, hide the divider and Row 4 entirely.

#### Row 4: Financial Summary (three-column, label-above-value)
- This row is **conditionally visible** — shown only when at least one of monthlyPayment, term, or downpayment has data.
- **Left column** (Monthly Payment):
  - Label: "Monthly" — `10sp`, `@color/gray`
  - Value: formatted amount (e.g., "₱1,500.00") — `13sp`, bold, `@color/cash_dark_green`
  - If null/zero, show "—" in `@color/gray`
- **Center column** (Term):
  - Label: "Term" — `10sp`, `@color/gray`
  - Value: term string (e.g., "12 months") — `13sp`, bold, `@color/black`
  - Gravity: center
  - If null/blank, show "—" in `@color/gray`
- **Right column** (Downpayment):
  - Label: "Downpayment" — `10sp`, `@color/gray`
  - Value: formatted amount (e.g., "₱3,000.00") — `13sp`, bold, `@color/black`
  - Gravity: end (right-aligned)
  - If null/zero, show "—" in `@color/gray`
- Layout: Horizontal, each column `weight=1`. `marginTop="8dp"` from divider.

#### Row 5: Purchase Date (right-aligned footer)
- **Value**: Formatted date (e.g., "Feb 14, 2026") — `11sp`, `@color/gray`, right-aligned (`gravity="end"`).
- `marginTop="8dp"` from previous row.
- This serves as a subtle timestamp footer, consistent with how `item_device_transaction.xml` places date/time at the bottom.
- Change the position to be at the bottom left side instead of the bottom right.
- Add a new field by:<username> positioned at the bottom right. This makes the format consistent with the other card entries in other activity such as item_device_transaction.xml


### Card Container
- Keep `MaterialCardView` with existing properties: `cardCornerRadius="8dp"`, `cardElevation="2dp"`, `strokeColor="@color/light_gray"`, `strokeWidth="1dp"`.
- Increase inner padding from `12dp` to `14dp` for a slightly more spacious feel.

## Data Binding Changes (Adapter)

The adapter in `FinancingAccountListActivity.kt` needs updates to bind the new layout:

1. **Account number** — bind to new `accountNumberValue` (was `accountNumberText`).
2. **Contact number** — bind to new `contactNumberValue` (was `contactNumberText`).
3. **Device purchased** — bind to new `devicePurchasedValue` (was `devicePurchasedText`). Show/hide the right column (`deviceColumn`) instead of `devicePurchasedRow`.
4. **Monthly payment** — bind to new `monthlyPaymentValue` (was `monthlyPaymentText`). Show "—" if null/zero instead of hiding.
5. **Term** — new field, bind `account.term` to `termValue`. Show "—" if null/blank.
6. **Downpayment** — new field, bind `account.downpayment` to `downpaymentValue`. Format as currency. Show "—" if null/zero.
7. **Financial row + divider visibility** — show `financialDivider` and `financialSummaryRow` only when at least one of monthlyPayment, term, or downpayment has data.
8. **Purchase date** — bind to new `purchaseDateText` (same id, moved to bottom row).
9. **Customer name and badge** — no changes to binding logic, only badge padding increases in XML.

## Files to Modify

1. `app/src/main/res/layout/item_financing_account.xml` — Complete layout rewrite per the spec above.
2. `app/src/main/java/com/techcity/techcitysuite/FinancingAccountListActivity.kt` — Update `onBindViewHolder` in the inner adapter class to bind the new/renamed views and handle financial row visibility logic.

## What NOT to Change

- No changes to the `FinancingAccount` data model.
- No changes to Firestore queries or data loading.
- No changes to search/filter logic.
- No changes to other layouts or activities.
- No new colors, drawables, or resources needed (uses existing palette).
- No changes to the card's click behavior.

## Edge Cases

1. **All financial fields empty** — Divider and Row 4 are hidden. Card shows only rows 1-3 and the date footer.
2. **Only some financial fields present** — Row 4 is visible; missing fields show "—" as placeholder.
3. **Very long device name** — Ellipsized with `maxLines="1"` in the right column.
4. **Very long customer name** — Already handled with `ellipsize="end"` and `maxLines="1"`.
5. **Missing purchase date** — If empty string, the date footer shows the raw empty string (same behavior as current).

## Open Questions

None — this is a purely visual/layout change with no new functionality.
