# Technical Plan: Accessory Inventory Counter on Sale & Delete

Implementation plan for `_specs/accessory-inventory-counter-sync.md`. Resolved open questions:

- Store Location selector = **dropdown** (mirror the existing inventory-status dropdown).
- **No edit flow** exists → only create (sale) and delete (undo) sync inventory.
- "Inventory not updated" warning = **Toast**.

Core decisions baked in: qty implicit = 1; decrement **`onDisplay` first, then `onHand`**, increment `sold`; sale never blocked; reversal is **marker-driven** (delete reverses only what the doc says was done). See `[[accessory-inventory-semantics]]` memory.

---

## Files to Modify

### 1. `app/src/main/java/com/techcity/techcitysuite/Constants.kt` (`AppConstants`)
Additive only:
- Add `const val KEY_STORE_LOCATION_ID = "store_location_id"`.
- Add collection names used by the new code: `const val COLLECTION_ACCESSORY_TRANSACTIONS = "accessory_transactions"`, `COLLECTION_ACCESSORY_PRODUCTS = "accessory_products"`, `COLLECTION_ACCESSORY_PRICING = "accessory_pricing"`, `COLLECTION_ACCESSORY_INVENTORY = "accessory_inventory"`, `COLLECTION_ACCESSORY_LOCATIONS = "accessory_locations"`.
  - *(The existing accessory code hard-codes these strings. To avoid churn and respect scope rules, new code may either use these constants or hard-code the same literals — but do NOT refactor the already-working hard-coded references in existing methods.)*

### 2. `app/src/main/res/layout/activity_program_settings.xml`
- Convert the Store Location field from a free-text `TextInputEditText` (`storeLocationInput`) into a **dropdown**, mirroring the inventory-status dropdown:
  - Change the `TextInputLayout` (`storeLocationInputLayout`) to the exposed-dropdown-menu style.
  - Replace the `TextInputEditText` with a `MaterialAutoCompleteTextView`, **keeping the same id `storeLocationInput`** (so existing binding references keep working; `setText(..)`/`text` still apply).
  - Make it non-editable (selection only), like `inventoryStatusDropdown`.
- No other settings rows change.

### 3. `app/src/main/java/com/techcity/techcitysuite/ProgramSettingsActivity.kt`
The Activity currently has no Firestore/coroutines — add them for the location list.
- **Imports/props:** add `FirebaseFirestore`, a `CoroutineScope(Dispatchers.Main + Job())`, and a local `const val KEY_STORE_LOCATION_ID = "store_location_id"` (mirrors the existing local-duplicate pattern for `KEY_STORE_LOCATION`).
- **Cancel scope** in `onDestroy()`.
- **New `setupStoreLocationDropdown()`** (called from `onCreate`, before/with `loadSettings`):
  - Launch a coroutine to read `accessory_locations` (active locations), build a list of `name` values and a `name → documentId` map (keep an `id → name` map too for pre-selection).
  - Set an `ArrayAdapter` of names on `binding.storeLocationInput` (same pattern as `setupInventoryStatusDropdown`).
  - After loading, pre-select the saved location: read `KEY_STORE_LOCATION` (name) and set it via `setText(name, false)` if it exists in the list.
  - **Offline/empty fallback:** if the read fails or returns nothing, leave whatever name was loaded from prefs in the field and don't block; the adapter is just empty.
- **`loadSettings()`:** keep setting the field text from `KEY_STORE_LOCATION` (works for both edit-text→dropdown). (The dropdown population/pre-select is handled by `setupStoreLocationDropdown()`; ordering must ensure the saved name shows even before the async list returns.)
- **`saveSettings()`:**
  - Keep saving `KEY_STORE_LOCATION` = the selected/visible name.
  - Resolve the selected name to its id via the `name → id` map; save `KEY_STORE_LOCATION_ID` = that id (empty string if unresolved).

### 4. `app/src/main/java/com/techcity/techcitysuite/AccessoryTransactionActivity.kt`
Make the sale atomically write the transaction doc **and** decrement inventory, stamping the reversal marker.
- **In `saveTransaction()`:** also read `val locationId = prefs.getString(AppConstants.KEY_STORE_LOCATION_ID, "") ?: ""` and pass it into the save function.
- **Refactor `saveTransactionToFirebase(...)`** (currently ends with `db.collection("accessory_transactions").add(transactionData)`):
  - Pre-create the doc ref: `val newRef = db.collection("accessory_transactions").document()`.
  - Determine eligibility: `val canTouchInventory = scannedSku.isNotEmpty() && locationId.isNotEmpty()`.
  - Run `db.runTransaction { tx -> ... }`:
    1. **Reads first.** If `canTouchInventory`, `tx.get()` the inventory doc `accessory_inventory/{scannedSku}__{locationId}`.
    2. Decide bucket from the snapshot: if exists and `onDisplay >= 1` → bucket = `onDisplay`; else if `onHand >= 1` → bucket = `onHand`; else → none.
    3. Add marker fields to `transactionData` before writing:
       - bucket chosen → `inventoryUpdated=true`, `inventorySku=scannedSku`, `inventoryLocationId=locationId`, `inventoryBucket=<bucket>`, `inventoryQuantity=1`.
       - otherwise → `inventoryUpdated=false` (and empty/absent other markers).
    4. **Writes.** `tx.set(newRef, transactionData)`. If a bucket was chosen, `tx.update(invRef, mapOf(bucket to FieldValue.increment(-1), "sold" to FieldValue.increment(1), "lastUpdated" to FieldValue.serverTimestamp()))`.
    5. Return an outcome enum/boolean: `INVENTORY_UPDATED` vs `INVENTORY_SKIPPED` (so the UI can warn).
  - Return `Result<Pair<String, InventoryOutcome>>` (docId + outcome), or keep `Result<String>` and surface the outcome via a property — pick one; the calling code in `saveTransaction()` needs it to choose the Toast.
- **In the `onSuccess` UI branch:** if outcome was `INVENTORY_SKIPPED` **and** `scannedSku` was non-empty, show a warning Toast ("Sale recorded, but inventory could not be updated (out of stock or not configured)") in addition to / instead of the success message. Free-typed items (empty sku) show the normal success only.
- Note: server timestamps and `FieldValue.increment` inside a `runTransaction` are supported. Reads must precede writes inside the transaction.

### 5. `app/src/main/java/com/techcity/techcitysuite/AccessoryTransactionListActivity.kt`
Make delete reverse the recorded inventory change atomically.
- **Refactor `deleteTransactionFromFirebase(documentId, position)`** (currently `db.collection(...).document(documentId).delete()`):
  - Replace the plain delete with `db.runTransaction { tx -> ... }`:
    1. **Read** the transaction doc: `val txSnap = tx.get(txRef)`.
    2. Read marker: `inventoryUpdated`, `inventorySku`, `inventoryLocationId`, `inventoryBucket`, `inventoryQuantity` (default 1).
    3. If `inventoryUpdated == true` and sku/locationId/bucket are present, read the inventory doc `tx.get(invRef)` where `invRef = accessory_inventory/{sku}__{locationId}`.
    4. **Writes:** if reversing, `tx.update(invRef, mapOf(bucket to FieldValue.increment(+qty), "sold" to FieldValue.increment(-qty), "lastUpdated" to FieldValue.serverTimestamp()))`. Then `tx.delete(txRef)`.
    5. If no marker → just `tx.delete(txRef)`.
  - Keep the existing post-success UI updates (remove from adapter/list, update summary, empty-state, Toast) and the existing error handling (restore item, error Toast). The reversal + delete now succeed or fail together (Edge Case 6).
  - All marker reads come from the freshly-read doc inside the transaction, so **no change to the `AccessoryTransaction` data model is required**.

---

## Implementation Order

1. `Constants.kt` — add `KEY_STORE_LOCATION_ID` + accessory collection constants.
2. Settings: layout dropdown conversion (#2), then `ProgramSettingsActivity` load/save + location fetch (#3). Verify a location can be picked and both name + id persist.
3. Sale path: `AccessoryTransactionActivity` atomic save + marker + warning (#4).
4. Delete path: `AccessoryTransactionListActivity` atomic reverse + delete (#5).
5. Build, then test on non-production data (see Risks): catalog sale with display stock, with only on-hand stock, with zero stock (warning), free-typed sale (no change), and delete of each (reversal correctness + no-marker no-op).

---

## Dependencies / Libraries

None new. Uses Firebase Firestore transactions + `FieldValue.increment()` (already in the project) and Kotlin coroutines (`.await()`, already used). `ProgramSettingsActivity` gains a coroutine scope + Firestore instance (new to that file only).

---

## Migration / Data Considerations

- **New SharedPreferences key** `store_location_id`: empty until the user re-saves Settings after selecting a location. Until then, sales fall into the "inventory skipped + warning" path (safe).
- **New transaction fields** (`inventoryUpdated`, `inventorySku`, `inventoryLocationId`, `inventoryBucket`, `inventoryQuantity`): additive. Pre-existing transactions lack them → delete treats them as "no reversal" (no accidental counter inflation).
- **No inventory docs are created** by a sale; a missing doc = skip + warning (matches schema's lazy-creation rule — only procurement/adjustment create inventory docs).
- **Cross-app:** the web app must mirror (a) `onDisplay`-first sale logic and (b) `sold` being reversible — otherwise the two clients corrupt each other's counters. Action item outside this codebase (documented in the spec + `[[accessory-inventory-semantics]]`).

---

## Risks / Things to Watch

1. **Production data.** This writes live inventory counters (schema Critical Rule 1). Test against a non-production project/dataset first.
2. **Read-before-write in Firestore transactions.** Both the sale and delete transactions must perform all `tx.get()` calls before any `tx.set`/`tx.update`/`tx.delete`. The transaction lambda can re-run; keep it side-effect-free except for the tx ops.
3. **Atomicity of doc + counter.** Use one `runTransaction` for create+decrement and one for reverse+delete so a sale can't exist without its decrement and a delete can't drop the doc without restoring the counter.
4. **Bucket selection correctness.** Only decrement a bucket that is `>= 1`; never allow negatives (Edge Case 4). The reversal must target the **same** bucket recorded in the marker, not re-derived.
5. **Location id vs name drift.** The sale uses `KEY_STORE_LOCATION_ID`; the dropdown must keep name↔id consistent. If the saved location was deleted from `accessory_locations`, sales skip + warn (acceptable).
6. **Settings async ordering.** Ensure the saved location name still displays if the `accessory_locations` fetch is slow/offline; don't clear the field while loading. Cancel the scope in `onDestroy()`.
7. **`scannedSku` lifetime.** Confirm `scannedSku` is still populated at save time for catalog items and empty for free-typed (it already is, from the barcode feature).

---

## Scope Boundaries (per CLAUDE.md)

- Touch only: `Constants.kt` (additive), the Store Location field in `activity_program_settings.xml`, and the listed methods in `ProgramSettingsActivity`, `AccessoryTransactionActivity`, and `AccessoryTransactionListActivity`.
- No refactoring of unrelated settings rows, unrelated transaction logic, payment branches, or other activities. No new audit (`accessory_inventory_adjustments`) writes. No git operations.
