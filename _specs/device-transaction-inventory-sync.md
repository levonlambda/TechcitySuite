# Feature Spec: Device Transaction Inventory Sync (Mark Sold & Revert on Delete)

## Description

When a device transaction is created, the app should update the matching device record in the `inventory` collection to reflect that it was sold — setting its `status` to **Sold**, its `location` to the store's location, and its `lastUpdated` to today's date. When a device transaction is deleted, the app should **revert** that same inventory record back to exactly what it was before the sale (restoring the original `status`, `location`, and `lastUpdated`).

To make the revert reliable, the device transaction document stores the inventory record's **original** `status`, `location`, and `lastUpdated` at the moment of sale, so the delete flow can restore them precisely before removing the transaction document.

### Current state (important)

`DeviceTransactionActivity` is currently a **testing version**: when a sale is saved it already writes the transaction document **and already stamps** `inventoryDocumentId`, `originalStatus`, `originalLastUpdated`, `newStatus` (= `Sold`), and `newLastUpdated` (date format `M/d/yyyy`, e.g. `9/29/2025`) onto that document — **but it does NOT actually update the `inventory` document**, and it does **not** capture the original `location`. This feature:
1. Activates the real inventory update on save.
2. Adds capture of the original `location`.
3. Adds the revert-on-delete behavior.

---

## User Stories / Use Cases

1. **As store staff**, when I complete a device sale, I want the device's inventory record to be marked **Sold** automatically, so the inventory list reflects reality without manual editing.
2. **As store staff**, when I sell a device, I want its inventory `location` updated to my store and its `lastUpdated` set to today, so records show where and when it was sold.
3. **As a store owner**, when I delete a device transaction that was created by mistake, I want the device's inventory record restored to exactly its previous state (status, location, last-updated), so a deletion doesn't leave a device wrongly marked Sold.

---

## UI/UX Flow

### A. Creating a device transaction (mark Sold)

1. Staff searches for a device by IMEI/serial (existing flow), the matching `inventory` record is found, and the transaction form is completed and saved (existing flow).
2. On save, in addition to writing the `device_transactions` document, the app updates the matching `inventory` document:
   - `status` → **Sold**
   - `location` → the store location string from Program Settings (`store_location`)
   - `lastUpdated` → today's date as a string in `M/d/yyyy` format (e.g. `9/29/2025`)
3. The transaction document also records the inventory record's **original** values (see Data Model) so the change can be reverted later.
4. The existing success message is shown and the screen returns as it does today.

### B. Deleting a device transaction (revert)

1. Deletion happens through the existing delete flow in `DeviceTransactionListActivity` (swipe-left → password for past dates → confirm).
2. Before/while removing the transaction document, the app uses the original values stored on the transaction to **revert** the matching `inventory` document:
   - `status` → original status
   - `location` → original location
   - `lastUpdated` → original lastUpdated
3. The transaction document is then deleted. The user sees the existing success message.

---

## Data Model

No new collections.

### `inventory` collection (existing)
This is the Firestore collection named **`inventory`** — the same one the device search reads from. Each document is a single device record (manufacturer, model, IMEIs, serial number, status, etc.). In the app's code these documents are represented by the `InventoryItem` data class, but no change to that class is required.

The fields this feature changes on sale and restores on delete:
- `status` (string) — one of `On-Display`, `On-Hand`, `Sold`. Set to `Sold` on sale.
- `location` (string) — set to the store location on sale.
- `lastUpdated` (string, `M/d/yyyy`) — set to today on sale.

**Which document is updated:** the device was located by IMEI/serial during the search step, and its inventory **document ID** is already captured on the transaction as `inventoryDocumentId`. The update/revert targets that document directly (equivalent to "the inventory document whose `imei1`/`imei2`/`serialNumber` matches the transaction", but more reliable than re-querying).

### `device_transactions` collection (existing)
This is the Firestore collection where each device sale is recorded (one document per sale). Fields already stored today:
- `inventoryDocumentId` (string) — the matching `inventory` document's ID.
- `originalStatus` (string) — inventory `status` before the sale.
- `originalLastUpdated` (string) — inventory `lastUpdated` before the sale.
- `newStatus` (string) — `Sold`.
- `newLastUpdated` (string) — sale date in `M/d/yyyy`.

New field added by this feature:
- `originalLocation` (string) — inventory `location` before the sale (needed to revert `location`).

All are additive; transactions created before this feature simply lack `originalLocation` (handled in Edge Cases).

---

## Business Rules and Logic

1. **One inventory record per sale.** A device transaction corresponds to exactly one `inventory` document (`inventoryDocumentId`).
2. **Mark-sold values:** on sale, `status = Sold`, `location = <store location from settings>`, `lastUpdated = <today, M/d/yyyy>`.
3. **Revert values come from the transaction.** Delete restores `status`, `location`, `lastUpdated` from the `original*` fields stamped at sale time — never inferred.
4. **Atomicity.** The transaction-document write and the inventory update should be consistent with each other (a sale should not be recorded without its inventory update, and vice versa). Likewise, the revert and the transaction deletion should be consistent.
5. **Date format.** `lastUpdated` and `newLastUpdated` use `M/d/yyyy` (e.g. `9/29/2025`) — matching the existing `dateSold` formatting.
6. **Status values** are restricted to `On-Display`, `On-Hand`, `Sold`.

---

## Edge Cases and Error Handling

1. **Inventory document not found / already deleted** at sale time — the sale should fail with a clear error (marking a non-existent inventory record Sold is not meaningful), OR proceed without the inventory update with a warning. (See Open Questions — needs a decision; default: fail with error so the data stays consistent.)
2. **Device already marked `Sold`.** The "already sold" guard currently exists in code but is **commented out for testing**. With this feature live, decide whether to block re-selling a Sold device (see Open Questions).
3. **Inventory document missing/changed at delete time.** If the inventory record no longer exists, delete the transaction anyway (nothing to revert) and inform the user. If it exists, revert from the stored originals.
4. **Transaction created before this feature (no `originalLocation`).** On delete, revert `status` and `lastUpdated` from the stored originals; for `location`, leave it unchanged — do not crash. (See Open Questions.)
5. **No network / Firestore failure** on save — surface an error; the sale + inventory update should not partially apply.
6. **No network / Firestore failure** on delete — surface an error; the revert + deletion should not partially apply.
7. **Store location not configured** — the existing save flow already blocks saving when Store Location is empty, so `location` will always be set on sale.

---

## Out of Scope

- Changing how devices are searched or matched (IMEI/serial search is unchanged).
- The accessory inventory feature (separate; uses different collections and bucket logic).
- Multi-quantity device sales (each device transaction is a single unique device).
- Editing a device transaction and re-syncing inventory (only create and delete are covered).

---

## Open Questions

1. **Sale when the inventory record is missing.** If `inventoryDocumentId` points to a record that no longer exists at save time, should the sale **fail with an error** (recommended, keeps data consistent) or **save anyway with a warning** (like the accessory feature)? - it should fail, because the device transaction requires a valid IMEI or serial number
2. **Re-selling an already-Sold device.** Should the app block creating a transaction for a device whose inventory `status` is already `Sold` (re-enable the currently-commented guard), or allow it? - yes block it. items marked as sold cannot be used in a new device transaction.
3. **Legacy transactions without `originalLocation`.** On delete of an older transaction, should `location` be left as-is (recommended) or cleared to empty? -should be left as-is
4. **Atomicity mechanism.** Acceptable to use a Firestore transaction (single atomic update of inventory + write/delete of the transaction doc), consistent with how the accessory inventory feature was implemented? yes use a firestore transaction
