# Technical Plan: End of Day Report Clipboard Export

Spec: `_specs/eod-report-clipboard-export.md`

## Summary

Add a long-press handler to each entry in the End of Day Reports list. On long-press, read six figures already present in the saved `daily_summaries` document, format them as one tab-separated line, place it on the clipboard, and show a toast. All work is confined to a single Kotlin file. No layout, manifest, Gradle, or Firestore changes.

---

## Files to Be Created

None.

## Files to Be Modified

### 1. `app/src/main/java/com/techcity/techcitysuite/EndOfDayListActivity.kt`

This is the only file that changes. Four localized edits:

**(a) Extend `EODReportSummary` (Part 1, properties)**

Add six `Double` fields to the existing data class, defaulting to `0.0`, plus one `Boolean` flag:

- `deviceCashCollected` — column 1
- `accessoryCashCollected` — column 2
- `homeCreditBalance` — column 3
- `skyroBalance` — column 4
- `creditCardTotal` — column 5
- `inHouseSalmonBalance` — column 6
- `hasBreakdownData` — `true` when the document contains a `salesSummary` or `cashFlowSummary` map; drives the "no breakdown data" toast.

Defaults keep the existing constructor call sites compiling without change.

**(b) Populate the new fields in `fetchReportsForMonth()` (Part 4, data loading)**

Inside the existing `mapNotNull { doc -> ... }` block, after the current `grandTotals` extraction and before the `EODReportSummary(...)` constructor call, extract from the same `data` map (already fetched; no additional Firestore reads):

- `cashFlowSummary.devices` and `cashFlowSummary.accessories` maps → read `cash`, `gcash`, `paymaya`, `bankTransfer`, `others`, `creditCard`, each as `(x as? Number)?.toDouble() ?: 0.0`.
  - Column 1 = device `cash + gcash + paymaya + bankTransfer + others`
  - Column 2 = accessory `cash + gcash + paymaya + bankTransfer + others`
  - Column 5 = device `creditCard` + accessory `creditCard`
- `salesSummary.devices` and `salesSummary.accessories` maps → read the `balance` value of the nested `homeCredit`, `skyro`, `salmon`, `inHouse` maps, each defaulting to `0.0`.
  - Column 3 = device HC balance + accessory HC balance
  - Column 4 = device Skyro balance + accessory Skyro balance
  - Column 6 = device In-House balance + accessory In-House balance + device Salmon balance + accessory Salmon balance
- `hasBreakdownData` = (`salesSummary` map is non-null) OR (`cashFlowSummary` map is non-null).

Use the `salesSummary` balances (not the `cashFlowSummary` receivables) as the primary source for columns 3, 4, 6, matching the spec's Data Model section. Both carry identical totals; this is a single-source choice to keep the code simple.

Pass the seven new values into the `EODReportSummary(...)` constructor.

**(c) Add the long-press handler in `EODReportAdapter.onBindViewHolder()` (Part 5, adapter)**

Directly after the existing `root.setOnClickListener { onItemClick(item) }`, add `root.setOnLongClickListener { ... }` that:

1. Calls `copyReportFiguresToClipboard(item)` (new helper, see (d)).
2. Returns `true` so the gesture is consumed and the normal click does not also fire (the item card root is already `clickable="true"` / `focusable="true"` in `item_end_of_day_report.xml`, so the long-press is delivered without any layout change).

Haptic feedback: `setOnLongClickListener` on a clickable view triggers the platform's default long-press haptic automatically; no explicit haptic call is needed.

**(d) Add helpers in Part 6 (utility methods)**

- `buildClipboardLine(item: EODReportSummary): String`
  - Formats the six values in order 1 → 6 using `String.format(Locale.US, "%.2f", value)` to guarantee a period decimal separator and no grouping regardless of device locale.
  - Joins with a single `\t`. No header, no trailing newline.
- `copyReportFiguresToClipboard(item: EODReportSummary)`
  - If `!item.hasBreakdownData`: show toast "This report has no breakdown data to copy" and return without touching the clipboard.
  - Otherwise, wrap in `try/catch`:
    - Obtain `ClipboardManager` via `getSystemService(Context.CLIPBOARD_SERVICE)` and call `setPrimaryClip(ClipData.newPlainText("EOD figures", line))`. This mirrors the existing pattern in `FinancingAccountDetailActivity`.
    - Show toast `Copied EOD figures for <formatDisplayDate(item.date)>` using the existing `showMessage(..., false)` helper and the existing `formatDisplayDate()` formatter.
  - On exception: `showMessage("Unable to copy: ${e.message}", true)`.

New imports required at the top of the file: `android.content.ClipData`, `android.content.ClipboardManager`, `android.content.Context`.

---

## Implementation Order

1. Add the three imports.
2. Extend `EODReportSummary` with the new defaulted fields.
3. Add extraction logic in `fetchReportsForMonth()` and pass values to the constructor.
4. Add `buildClipboardLine()` and `copyReportFiguresToClipboard()` helpers in Part 6.
5. Wire `setOnLongClickListener` in `onBindViewHolder()`.
6. Build with `./gradlew assembleDebug` and verify compilation.
7. Manual verification on device (see Testing).

## Dependencies or Libraries Needed

None. `ClipboardManager` and `ClipData` are part of the Android framework and are already used elsewhere in the app.

## Migration or Data Considerations

- No Firestore schema changes. No new fields are written anywhere.
- The list query already fetches full `daily_summaries` documents; the new fields are parsed from the same payload. Firestore read count is unchanged.
- Legacy documents missing `cashFlowSummary` or `salesSummary` sub-maps (or missing the `salmon` or `others` keys) default every missing value to `0.0`. A document with neither map copies nothing and shows the "no breakdown data" toast.

## Testing / Verification

Manual, on a device or emulator with existing reports:

1. **Tap still opens the report.** Short-tap an entry; the report screen opens as before.
2. **Long-press copies, does not open.** Long-press an entry; a toast with the report's date appears, the report screen does not open.
3. **Paste into a spreadsheet.** Paste into Excel (or Google Sheets / any TSV-aware target) with the cursor on one cell; six cells fill left to right.
4. **Cross-check values.** Open the same report and confirm:
   - Column 1 = device Cash + GCash + PayMaya + Bank Transfer + Others in the device cash-flow section.
   - Column 2 = the same for accessories.
   - Column 3 / 4 = device + accessory Home Credit / Skyro receivable.
   - Column 5 = device + accessory Credit Card.
   - Column 6 = device + accessory In-House receivable + device + accessory Salmon receivable.
5. **Credit card example.** On a day containing a financed sale with a credit-card downpayment, confirm the downpayment is in column 5 and the full balance is in column 3 / 4 / 6, and none of it is in column 1 / 2.
6. **Zero-activity day.** A report with no device/accessory sales copies six `0.00` values.
7. **Locale check.** With the device set to a comma-decimal locale (e.g. German), the pasted values still use a period.

## Risks and Things to Watch Out For

- **Long-press must return `true`.** Returning `false` lets the click fire afterwards and opens the report, violating the spec.
- **Do not reuse `formatCurrency()`** for the clipboard line; it emits a currency symbol and thousands separators, which Excel would treat as text.
- **Scope discipline.** `EndOfDayReportActivity`, `DailySummaryCalculator`, the item layout, and the `daily_summaries` write path are not touched. Only the list activity changes.
- **`hasBreakdownData` vs. zero values.** A valid zero-activity day has both maps present with zero amounts and must still copy `0.00` values; only a document with neither map triggers the "no breakdown data" toast.
- **Deduplicated legacy/per-store reports.** The existing dedupe logic runs before the `mapNotNull`, so the long-pressed entry already refers to the correct per-store document; no extra handling is needed.
- **Android 13+ clipboard overlay.** The system shows its own "Copied" preview in addition to the toast. This is expected and acceptable per the spec.
