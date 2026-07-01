# Technical Plan: Accessory Search Dropdown by Manufacturer or Model

Implementation plan for `_specs/accessory-search-dropdown.md`. Reflects the resolved open questions:

- **When to search:** the search runs **only when the user presses the magnifying-glass (Search) button** — not on each keystroke and not on screen open.
- **Minimum characters:** require **at least 3 characters** in the field before searching.
- **Row layout (mobile-friendly):** each result is a **two-line** row — **Manufacturer** on the first line; **Model** + **Price** on a second, indented line below it (so it fits a phone width).
- **No price on file:** show **₱0.00** for that row.
- **Field hint:** unchanged (no wording update).

This extends the existing barcode lookup ([accessory-barcode-scan-lookup](../_specs/accessory-barcode-scan-lookup.md)). The **Scan** button path (`barcodeScannerLauncher` → `lookupAccessoryByBarcode`) is **left completely unchanged**. Only the **Search** button path is changed to the new manufacturer/model/barcode search that shows a dropdown.

---

## Files to Create

### 1. `app/src/main/res/layout/item_accessory_search_result.xml`
- New row layout for the search results dropdown, inflated by the results adapter.
- Vertical `LinearLayout` (`match_parent` width, `wrap_content` height, small vertical padding, e.g. `8dp`/`10dp`):
  - **Line 1** — `TextView` `@+id/resultManufacturer`: manufacturer name, bold, `textColor` black, `textSize` ~15sp.
  - **Line 2** — `TextView` `@+id/resultModelPrice`: model + price, `textSize` ~13sp, `textColor` a muted gray, with a start indent (e.g. `android:layout_marginStart="16dp"` or leading padding) so it reads as a "tabbed" sub-line under the manufacturer.
- No View Binding is required for this file (the adapter inflates it and uses `findViewById`), so it does not add to any activity's ViewBinding view-id count.

---

## Files to Modify

### 2. `app/src/main/java/com/techcity/techcitysuite/AccessoryTransactionActivity.kt`

All changes are inside **Part 4B: BARCODE SCAN AND LOOKUP** plus a small set of new imports. Nothing in the scan path, price/discount listeners, down-payment listeners, validation, or save logic changes.

**a. Imports (Part 1)**
- Add: `android.widget.ListPopupWindow`, `android.widget.BaseAdapter`, `android.view.LayoutInflater`, `android.view.ViewGroup`, `android.widget.TextView`.
- (Coroutines `async`/`awaitAll` come from the already-imported `kotlinx.coroutines.*`.)

**b. Repoint the Search button (`setupBarcodeButton`, Part 4B)**
- The `binding.searchButton.setOnClickListener { searchByBarcode() }` call is changed to call the new `searchAccessories()` (rename or repoint — keep it a single small method).
- The `binding.barcodeButton` (scan) click listener and the `binding.barcodeInput` TextWatcher (which already clears `scannedBarcode`/`scannedSku` on manual edits, guarded by `isAutoFillingFromScan`) are **unchanged**.

**c. New method `searchAccessories()` (replaces the body of the old `searchByBarcode()`)**
- Read `binding.barcodeInput.text` → trimmed `searchTerm`.
- If empty → `showMessage("Please enter a barcode, manufacturer, or model", true)` and return.
- **If `searchTerm.length < 3`** → `showMessage("Enter at least 3 characters to search", true)` and return.
- Otherwise call `runAccessorySearch(searchTerm)`.

**d. New method `runAccessorySearch(searchTerm: String)` (new sub-section in Part 4B)**
- Show `binding.progressBar`; disable `binding.searchButton` and `binding.barcodeButton` (mirror the existing `lookupAccessoryByBarcode` guard pattern).
- Launch in the Activity `scope`. On `Dispatchers.IO`:
  1. Query `db.collection("accessory_products").get().await()`.
  2. For each document:
     - Skip if `active == false` (`getBoolean("active") ?: true`).
     - Read `manufacturer`, `model`, `barcode` (default `""`), and `sku` = `internalSku` ?: `document.id`.
     - **Match** if `searchTerm` is a case-insensitive substring of **manufacturer OR model OR barcode**. *(Note: unlike the current barcode-only lookup, products with an empty `barcode` are NOT skipped — they can still match on manufacturer/model.)*
     - Collect matches into a list of a new `AccessorySearchResult` (sku, barcode, manufacturer, model, displayName = `"$manufacturer $model".trim()`, retailPrice = null for now).
  3. If matches are non-empty, fetch each match's price concurrently: `accessory_pricing/{sku}` → `getDouble("retailPrice")` (may be null). Use `matches.map { async { ... } }.awaitAll()` to fill `retailPrice`.
- Back on `Dispatchers.Main`: hide progress, re-enable both buttons, then:
  - **0 matches** → `showMessage("No matching accessories found. Please enter details manually.", true)` (leave the form untouched).
  - **≥1 match** → call `showSearchResultsDropdown(results)`.
- Wrap in try/catch; on failure show `"Could not search accessories, check connection"` and leave the form untouched.

**e. New method `showSearchResultsDropdown(results: List<AccessorySearchResult>)`**
- Build a `ListPopupWindow(this)`:
  - `anchorView = binding.barcodeInput`
  - `width = ListPopupWindow.MATCH_PARENT` (or the anchor's measured width), `height = ListPopupWindow.WRAP_CONTENT`, `isModal = true`.
  - `setAdapter(...)` with a new lightweight adapter (see step f).
  - `setOnItemClickListener { _, _, position, _ -> applySearchSelection(results[position]); popup.dismiss() }`
  - `popup.show()`.
- Keep a reference so it can be dismissed in `onDestroy()` (optional but tidy) to avoid a window leak if the Activity finishes while open.

**f. Results adapter (private inner `BaseAdapter`, or a small `ArrayAdapter` override)**
- Inflates `item_accessory_search_result.xml`.
- `resultManufacturer.text` = `manufacturer` (fallback to `displayName` if manufacturer is blank).
- `resultModelPrice.text` = `"$model  •  ₱${String.format("%,.2f", retailPrice ?: 0.0)}"` — i.e. model then price, with **₱0.00 shown when `retailPrice` is null** (per resolved Q4). (Separator character is cosmetic.)

**g. New method `applySearchSelection(result: AccessorySearchResult)`**
- Mirror the **Found** branch of `lookupAccessoryByBarcode`:
  - Set `isAutoFillingFromScan = true`, then `binding.barcodeInput.setText(result.barcode)` and `binding.accessoryNameInput.setText(result.displayName)`, then `isAutoFillingFromScan = false` (so the barcode TextWatcher does not wipe the SKU we are about to set).
  - Set `scannedBarcode = result.barcode` and `scannedSku = result.sku`.
  - Feed the price to the existing price watcher: `binding.priceInput.setText(String.format(Locale.US, "%.2f", result.retailPrice ?: 0.0))` — this re-runs all price/discount/amount calculations and re-validates the form exactly like a manual price entry.
  - `showMessage("Product selected and filled in", false)`.
- Note: because the barcode auto-fill sets `result.barcode` (which may be empty for a name-only match), an empty barcode is fine — the saved transaction stores `sku` for the inventory decrement regardless.

**h. New data class (Part 4B, alongside `MatchedProduct` / `BarcodeLookupResult`)**
```
private data class AccessorySearchResult(
    val sku: String,
    val barcode: String,
    val manufacturer: String,
    val model: String,
    val displayName: String,
    val retailPrice: Double?
)
```

**No changes** to `saveTransactionToFirebase(...)` — it already writes `barcode` (`scannedBarcode`) and `sku` (`scannedSku`) and drives the inventory decrement from `scannedSku`. A dropdown selection populates those exactly like a scan does.

---

## Implementation Order

1. Create `item_accessory_search_result.xml` (two-line row).
2. In `AccessoryTransactionActivity.kt`:
   a. Add imports.
   b. Repoint `searchButton` to `searchAccessories()`; add the min-3-char guard.
   c. Add `runAccessorySearch()` (query active products, match manufacturer/model/barcode, fetch prices).
   d. Add `showSearchResultsDropdown()` + the results adapter.
   e. Add `applySearchSelection()` (auto-fill + store SKU/barcode, guarded by `isAutoFillingFromScan`).
   f. Add the `AccessorySearchResult` data class.
3. Build and smoke-test on device:
   - Type a brand (≥3 chars) → dropdown lists active matches as `Manufacturer` / `Model  •  ₱Price`.
   - Select a row → name + price fill; Save enables; saved doc has `barcode` + `sku`; inventory decrements.
   - Type <3 chars → guard toast, no query.
   - Type text with no matches → "no matching accessories" toast.
   - A product with no pricing doc → row shows ₱0.00.
   - Scan button still works unchanged.

---

## Dependencies / Libraries

None new. Reuses:
- Firebase Firestore + Coroutines `.await()` (already used in this Activity).
- `ListPopupWindow` (Android framework).
- The existing `accessory_products` / `accessory_pricing` catalog collections.

---

## Migration / Data Considerations

- **Read-only** against `accessory_products` and `accessory_pricing`. No writes to catalog, pricing, or inventory while searching.
- No new fields and no schema changes. The saved `accessory_transactions` document is unchanged from the barcode-lookup feature (still writes `barcode` + `sku`).
- **Read volume:** the search fetches all `accessory_products` once per button press, then one `accessory_pricing/{sku}` read per match (run concurrently). With a 3-char minimum and manual button trigger this stays modest; see Risks for the optional cap.

---

## Risks / Things to Watch

1. **TextWatcher feedback loop / stale SKU.** `applySearchSelection` sets the barcode and name fields programmatically. The barcode field's TextWatcher clears `scannedBarcode`/`scannedSku` on edits — it must be guarded by `isAutoFillingFromScan` (already the established pattern) so the selected SKU survives. Set `scannedBarcode`/`scannedSku` **after** the guarded `setText` calls.
2. **Price formatting.** Feed `priceInput` a clean numeric string (e.g. `"1250.00"`), never a pre-formatted `₱1,250.00`, so the existing price watcher parses it and recalculates correctly. `retailPrice` may come back as `Long` or `Double` from Firestore — read with `getDouble("retailPrice")` and treat null as "no price → 0.00".
3. **Products without a barcode.** The new match loop must **not** skip products with an empty `barcode` (the current barcode-only lookup does). Name-only matches are the whole point; they simply save an empty `barcode` and rely on `sku`.
4. **Result count / read cost.** A very broad 3-char term could match many products and trigger many pricing reads. Optional safeguard: cap the results (e.g. first 25) before fetching prices and note the cap in a toast if truncated. Kept out of the default flow per the resolved "keep it simple" decision, but easy to add if lists get long.
5. **ListPopupWindow inside a ScrollView.** Anchoring to `binding.barcodeInput` works, but verify the popup positions correctly on a real device (the screen is a `ScrollView`). If positioning is awkward, fall back to an `AlertDialog` with the same adapter — same rows, same selection handler.
6. **Window leak.** Dismiss the `ListPopupWindow` in `onDestroy()` (or keep it non-retained) so it doesn't leak if the Activity finishes while the dropdown is open.
7. **Coroutine cancellation.** Use the Activity's existing `scope` (cancelled in `onDestroy()`), as the scan lookup already does.

---

## Scope Boundaries (per CLAUDE.md)

- Only the **Search** button path in `AccessoryTransactionActivity.kt` (Part 4B) and one new row-layout XML are added.
- The **Scan** button path, price/discount/down-payment listeners, validation, and save logic are **untouched**.
- No refactoring of existing code, no formatting changes to untouched lines, no changes to other activities, no manifest changes, and no git operations.
