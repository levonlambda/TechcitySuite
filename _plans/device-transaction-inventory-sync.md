# Technical Plan: Device Transaction Inventory Sync (Mark Sold & Revert on Delete)

Implementation plan for `_specs/device-transaction-inventory-sync.md`. Resolved open questions:

- **Missing inventory record at save → fail** the sale with an error (a device transaction requires a valid inventory record).
- **Re-selling an already-`Sold` device → block it** (re-enable the currently-commented guard).
- **Legacy transactions without `originalLocation` → leave `location` as-is** on revert.
- **Use a Firestore transaction** for atomicity (consistent with the accessory inventory feature).

Core behavior: on sale, set the matching `inventory` doc's `status = Sold`, `location = <store location>`, `lastUpdated = <today M/d/yyyy>`, atomically with creating the `device_transactions` doc, after stamping the originals onto that doc. On delete, revert those three fields from the stored originals, atomically with deleting the doc.

---

## Files to Modify

### 1. `app/src/main/java/com/techcity/techcitysuite/DeviceTransactionActivity.kt`

**a. Re-enable the "already Sold" guard (in `saveTransaction()`).**
- The guard is currently commented out (around the `foundInventoryItem!!.status.equals("Sold", ...)` block). Re-enable it so a device whose inventory `status` is already `Sold` cannot be used in a new transaction:
  - If `foundInventoryItem!!.status.equals(AppConstants.INVENTORY_STATUS_SOLD, ignoreCase = true)` → `showMessage("This device has already been sold", true)` and `return`.

**b. Capture the original `location` (in `saveTransactionToFirebase(...)`).**
- In the `transactionData` map (which already stamps `originalStatus`, `originalLastUpdated`, `newStatus`, `newLastUpdated`), add:
  - `"originalLocation" to inventoryItem.location`
- (Optional, for symmetry/readability) `"newLocation" to userLocation` — not required by the revert, include only if desired. The spec doesn't require it; default is to omit.

**c. Replace the plain create with an atomic transaction (end of `saveTransactionToFirebase`).**
- Currently ends with `db.collection(AppConstants.COLLECTION_DEVICE_TRANSACTIONS).add(transactionData).await()` and `Result.success(docRef.id)`.
- Replace with:
  - `val newRef = db.collection(AppConstants.COLLECTION_DEVICE_TRANSACTIONS).document()`
  - `val invRef = db.collection(AppConstants.COLLECTION_INVENTORY).document(inventoryItem.id)`
  - `db.runTransaction { tx -> ... }.await()`:
    1. **Read** `val invSnap = tx.get(invRef)`.
    2. If `!invSnap.exists()` → `throw IllegalStateException("Device is no longer in inventory")` (fails the sale → `Result.failure`).
    3. Defense-in-depth: if `invSnap.getString("status").equals(SOLD, ignoreCase=true)` → `throw IllegalStateException("This device has already been sold")` (covers the race where it was sold between search and save).
    4. **Write** `tx.set(newRef, transactionData)`.
    5. **Write** `tx.update(invRef, mapOf("status" to AppConstants.INVENTORY_STATUS_SOLD, "location" to userLocation, "lastUpdated" to dateSoldString))`.
  - `Result.success(newRef.id)` (keep the existing `Result<String>` return type and the existing `result.isSuccess` handling in `saveTransaction()` — failures already surface via `showMessage("Error: ...")`).
- Reads precede writes inside the transaction (required by Firestore). `dateSoldString` is the existing `M/d/yyyy` value already computed in this method.
- Remove the "TESTING MODE … does NOT update inventory" comment block since it no longer applies.

> No new imports expected: `FieldValue`, `db`, coroutines, and `.await()` are already used in this file. The inventory `lastUpdated`/`location`/`status` are plain strings (no `FieldValue.increment` needed).

### 2. `app/src/main/java/com/techcity/techcitysuite/DeviceTransactionListActivity.kt`

**Replace the plain delete in `deleteTransactionFromFirebase(documentId, position)` with an atomic revert-then-delete.**
- Currently the `withContext(Dispatchers.IO) { db.collection(DEVICE_TRANSACTIONS).document(documentId).delete().await() }`.
- Replace the IO block with a `db.runTransaction { tx -> ... }.await()`:
  1. `val txRef = db.collection(AppConstants.COLLECTION_DEVICE_TRANSACTIONS).document(documentId)`
  2. **Read** `val snap = tx.get(txRef)`.
  3. Read markers from `snap`: `inventoryDocumentId` (string), `originalStatus` (string), `originalLastUpdated` (string), and `originalLocation` via `snap.getString("originalLocation")` plus presence check `snap.contains("originalLocation")`.
  4. If `inventoryDocumentId` is non-empty:
     - `val invRef = db.collection(AppConstants.COLLECTION_INVENTORY).document(inventoryDocumentId)`
     - **Read** `val invSnap = tx.get(invRef)`.
     - If `invSnap.exists()`, build a revert map:
       - `"status" to originalStatus`
       - `"lastUpdated" to originalLastUpdated`
       - **only if `snap.contains("originalLocation")`** add `"location" to (originalLocation ?: "")` (legacy docs without the field → leave `location` untouched).
       - `tx.update(invRef, revertMap)`.
     - If the inventory doc doesn't exist → skip the revert (nothing to restore).
  5. **Write** `tx.delete(txRef)`.
- Keep the existing success UI updates (remove from adapter/list, update summary, empty-state, success Toast) and the existing error handling (error Toast). The revert + delete now succeed or fail together.
- All `tx.get(...)` calls happen before `tx.update`/`tx.delete` (Firestore ordering).
- The delete reads the marker fields directly from the transaction snapshot inside the transaction, so **no change to the `DeviceTransaction` data class or the list's deserialization is required.**

### 3. (Optional) `app/src/main/java/com/techcity/techcitysuite/DeviceTransaction.kt`
- Not required for the feature to work (delete reads from the live snapshot). If desired for model completeness, add `val originalLocation: String = ""`. Recommendation: **skip** to keep the change minimal, unless the field is needed elsewhere for display.

---

## Implementation Order

1. `DeviceTransactionActivity` — (a) re-enable the Sold guard, (b) add `originalLocation` to `transactionData`, (c) convert the save to an atomic `runTransaction` (create doc + update inventory; fail if inventory missing / already Sold).
2. `DeviceTransactionListActivity` — convert delete to an atomic `runTransaction` (revert `status`/`location`/`lastUpdated` from the stored originals, honoring legacy-no-`originalLocation`, then delete).
3. Build, then test on non-production data: sell a device (inventory becomes Sold + correct location + today's date), try to re-sell it (blocked), delete the sale (inventory reverts to prior status/location/lastUpdated), delete a legacy transaction (status/lastUpdated revert, location untouched), and sale against a deleted inventory doc (fails cleanly).

---

## Dependencies / Libraries

None new. Uses Firestore transactions and coroutines `.await()` already present in both files. `AppConstants.COLLECTION_INVENTORY` and `AppConstants.INVENTORY_STATUS_SOLD` already exist.

---

## Migration / Data Considerations

- **New `device_transactions` field** `originalLocation`: additive. Transactions created before this feature lack it; the delete revert leaves their inventory `location` unchanged (per the resolved Open Question).
- **No inventory documents are created** — sales only update an existing inventory doc, and fail if it's missing.
- **`newStatus`/`newLastUpdated`/`originalStatus`/`originalLastUpdated`** are already written today; this feature now actually applies them to the inventory doc and reverts them.
- `lastUpdated` format stays `M/d/yyyy` (e.g. `9/29/2025`), matching `dateSold`.

---

## Risks / Things to Watch

1. **Production data.** This now mutates live `inventory` documents. Test against a non-production dataset first.
2. **Read-before-write in transactions.** Both the save and delete transactions must perform all `tx.get(...)` before any `tx.set`/`tx.update`/`tx.delete`. Keep the lambdas side-effect-free except for the tx ops (they may re-run on contention).
3. **Failing the sale on missing inventory.** Per the resolved decision, a missing inventory doc throws inside the transaction → the whole sale rolls back (no orphan `device_transactions` doc). Confirm the surfaced error message is clear.
4. **Already-Sold race.** The UI guard plus the in-transaction status check together prevent double-selling even if two clients race.
5. **Legacy `location` revert.** Use `snap.contains("originalLocation")` (not just null/empty) to distinguish "field absent" (leave location as-is) from "field present but empty".
6. **Re-read before editing.** When implementing, re-read the exact `saveTransactionToFirebase` tail and `deleteTransactionFromFirebase` body immediately before editing to anchor the edits precisely.

---

## Scope Boundaries (per CLAUDE.md)

- Touch only: the save path in `DeviceTransactionActivity` (Sold guard, `originalLocation`, atomic transaction) and the delete path in `DeviceTransactionListActivity` (atomic revert + delete). Optional single-field addition to `DeviceTransaction.kt`.
- No changes to the IMEI/serial search, the transaction-type cards, pricing logic, list UI, or other activities. No git operations.
