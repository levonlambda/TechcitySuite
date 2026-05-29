# Technical Plan: Accessory Barcode Scan Lookup

Implementation plan for `_specs/accessory-barcode-scan-lookup.md`. Reflects the resolved open questions:

- **Accessory Name auto-fill** uses the human-readable model (`manufacturer + model`), **not** the raw SKU.
- The scanner is launched in **product-code mode** (do NOT prioritize IMEI).
- The saved `accessory_transactions` document is extended to also store the scanned **barcode** and the **SKU**.

---

## Files to Create

### 1. `app/src/main/res/drawable/rounded_button_background_orange.xml`
- New shape drawable mirroring `rounded_button_background_blue.xml` (rectangle, 8dp corners) but using the accessory theme color `#FF9800` as the solid fill.
- Purpose: give the new scan button a background consistent with the orange accessory screen theme (the device screen's button is blue).
- *(Alternative if we want zero new files: reuse `rounded_button_background_blue`. Recommendation: create the orange variant for visual consistency with the rest of the Accessory Transaction screen.)*

---

## Files to Modify

### 2. `app/src/main/res/layout/activity_accessory_transaction.xml`
**Change: wrap the Accessory Name field in a horizontal row and add a scan button beside it.**

- Currently the `accessoryNameInputLayout` (`TextInputLayout`) is a direct child of the vertical `LinearLayout` with `layout_width="match_parent"`.
- Wrap it in a horizontal `LinearLayout` (mirroring lines 76–125 of `activity_device_transaction.xml`):
  - Horizontal `LinearLayout`, `layout_width="match_parent"`, `android:gravity="center_vertical"`.
  - The existing `accessoryNameInputLayout` changes from `match_parent` to `layout_width="0dp"` + `android:layout_weight="1"` (this "shortens" the field).
  - Add a new `ImageButton` after it:
    - `android:id="@+id/barcodeButton"`
    - `48dp` × `48dp`, `android:layout_marginStart="8dp"`
    - `android:src="@drawable/ic_barcode_scanner"`
    - `android:background="@drawable/rounded_button_background_orange"` (new drawable above)
    - `android:contentDescription="Scan Barcode"`, `android:padding="12dp"`, `app:tint="@color/white"`
- Keep the "Accessory Name" label `TextView` (lines 67–74) above the row as-is.
- **No other rows in the layout change** (Price field and all transaction-type cards untouched).

### 3. `app/src/main/java/com/techcity/techcitysuite/AccessoryTransactionActivity.kt`
**Changes:**

**a. Imports / properties (Part 1)**
- Add imports: `android.app.Activity`, `android.content.Intent`, `androidx.activity.result.contract.ActivityResultContracts`.
- Add a barcode result launcher property mirroring `DeviceTransactionActivity` (lines 78–94):
  - `registerForActivityResult(ActivityResultContracts.StartActivityForResult())`
  - On `RESULT_OK`, read `BarcodeScannerActivity.RESULT_BARCODE_VALUE`; if non-empty, call a new `lookupAccessoryByBarcode(barcodeValue)`.
- Add two nullable properties to hold the matched lookup result so they can be written into the saved document later:
  - `private var scannedBarcode: String = ""`
  - `private var scannedSku: String = ""`
  - (Reset both to empty whenever the user manually edits the Accessory Name — see step d — so stale SKU/barcode are never saved against a hand-typed item.)

**b. `onCreate` (Part 2)**
- Add a call to a new `setupBarcodeButton()` alongside the existing setup calls (after `setupButtonListeners()` is fine).

**c. New method `setupBarcodeButton()` (new sub-section, e.g. Part 4B)**
- Set `binding.barcodeButton.setOnClickListener` to build an `Intent(this, BarcodeScannerActivity::class.java)` and put product-code-mode extras before launching:
  - `putExtra(BarcodeScannerActivity.EXTRA_PRIORITIZE_IMEI, false)`
  - `putExtra(BarcodeScannerActivity.EXTRA_FILTER_PRODUCT_CODES, false)`
  - Launch via the barcode launcher.

**d. New method `lookupAccessoryByBarcode(barcode: String)` (new sub-section)**
- Launch in `scope` (existing `CoroutineScope(Dispatchers.Main + Job())`).
- Show the existing `binding.progressBar` while looking up; hide it when done.
- On `Dispatchers.IO`:
  1. Query `db.collection("accessory_products").whereEqualTo("barcode", barcode).limit(1).get().await()`.
  2. If empty → return a "not found" outcome.
  3. Read the product doc: `internalSku` (or doc ID), `manufacturer`, `model`, `active`.
  4. If `active == false` → return an "inactive" outcome.
  5. Read pricing: `db.collection("accessory_pricing").document(sku).get().await()`; read `retailPrice` (may be missing).
- Back on `Dispatchers.Main`, apply the outcome:
  - **Found:**
    - Set `binding.accessoryNameInput.setText("$manufacturer $model".trim())` — the existing `accessoryNameInput` TextWatcher updates `accessoryName` and re-validates automatically.
    - If `retailPrice` present: `binding.priceInput.setText(...)` formatted to feed the existing price TextWatcher (plain number string, e.g. `String.format("%.2f", retailPrice)`), which recomputes `price`, discount caps, and all transaction amounts and re-validates.
    - If `retailPrice` missing: leave Price empty and show a Toast that the price must be entered manually.
    - Store `scannedBarcode = barcode` and `scannedSku = sku`.
    - Toast: "Product found and filled in".
  - **Not found / inactive / pricing error:** clear `scannedBarcode`/`scannedSku`, leave fields untouched, Toast the appropriate message.
- Wrap Firestore calls in try/catch; on failure show "Could not look up barcode, check connection" and leave fields untouched (use the same `showMessage(..., true)` helper).
- In `setupAccessoryNameListener()` (Part 4), when the user manually types in the name field, reset `scannedBarcode`/`scannedSku` to empty (so a hand-edited name doesn't carry a mismatched SKU/barcode into the saved doc). *Guard against this firing during the programmatic `setText` from a successful lookup* — set the properties **after** calling `setText`, or use a short-lived flag like the existing `isUpdating*` pattern, so the auto-filled SKU/barcode survive.

**e. `saveTransactionToFirebase(...)` (Part 9)**
- In the `transactionData` HashMap (around the "Accessory Details" block, line ~913), add two fields:
  - `"barcode" to scannedBarcode` (empty string if the item was entered manually)
  - `"sku" to scannedSku` (empty string if manual)
- No other changes to the save logic, payment branches, or collection name.

---

## Implementation Order

1. Create `rounded_button_background_orange.xml`.
2. Modify `activity_accessory_transaction.xml` — wrap the name field, add `barcodeButton`.
3. In `AccessoryTransactionActivity.kt`:
   a. Add imports + launcher property + `scannedBarcode`/`scannedSku` properties.
   b. Add `setupBarcodeButton()` and wire it in `onCreate`.
   c. Add `lookupAccessoryByBarcode()`.
   d. Add the SKU/barcode reset in the Accessory Name TextWatcher (guarded against the programmatic auto-fill).
   e. Add `barcode` + `sku` to the saved transaction map.
4. Build and smoke-test on device (scan a known catalog barcode, an unknown one, and an item with no pricing doc).

---

## Dependencies / Libraries

None new. Reuses:
- `BarcodeScannerActivity` (already registered, already used by `DeviceTransactionActivity`).
- CameraX / ML Kit / ZXing (already in the project).
- Firebase Firestore + Coroutines `.await()` (already used in this Activity).

`BarcodeScannerActivity` is already declared in the manifest (used by Device Transaction) — no manifest change needed.

---

## Migration / Data Considerations

- **Read-only against the accessories catalog** (`accessory_products`, `accessory_pricing`). No writes to catalog, pricing, or inventory. No transactions/`FieldValue.increment` needed (per the schema's transaction rules, which apply only to quantity mutations — not relevant here).
- **New fields on `accessory_transactions`** (`barcode`, `sku`): additive only. Existing documents simply won't have them; existing readers that use known field names are unaffected. Manually entered (non-scanned) transactions will store empty strings for both.
- The composite inventory doc ID pattern (`{sku}__{locationId}`) and inventory counters are **not** touched.

---

## Risks / Things to Watch

1. **TextWatcher feedback loop / stale SKU.** Programmatically `setText`-ing the name triggers the existing Accessory Name TextWatcher. Ensure the SKU/barcode reset logic does not wipe the values we just set on a successful scan (set properties after `setText`, or use a guard flag mirroring the existing `isUpdating*`/`isCorrectingValue` pattern).
2. **Price formatting.** `priceInput`'s watcher parses with comma stripping and rejects negatives. Feed it a clean numeric string (e.g. `"1250.00"`), not a pre-formatted `₱1,250.00`, so parsing and the downstream calculations behave exactly as for manual entry.
3. **Scanner returns a non-product barcode.** Even in product-code mode the scanner can return any detected value; the Firestore `whereEqualTo("barcode", …)` simply returns empty for non-matches, which is the handled "not found" path — no special casing needed.
4. **`retailPrice` numeric type.** Firestore may return the price as `Long` or `Double`; read it defensively (e.g. `getDouble("retailPrice")`) and null-check for the "missing price" path.
5. **Coroutine cancellation.** Use the Activity's existing `scope` so the lookup is cancelled in `onDestroy()` (already wired).
6. **Button color drawable.** If the new orange drawable is skipped, the button will look blue against the orange screen — cosmetic only, but the plan recommends the orange variant.

---

## Scope Boundaries (per CLAUDE.md)

- Only the Accessory Name row of the layout, the listed methods in `AccessoryTransactionActivity.kt`, and the one new drawable are touched.
- No refactoring of existing price/discount/down-payment listeners, no formatting changes to untouched code, no changes to other activities, and no git operations.
