# Accessories Management — Firestore Schema Reference

> **Audience:** Claude assisting on a **separate mobile app** that connects to the same Firebase project (`tech-city-phone-information`) and reads/writes accessory data.
>
> **Scope:** Only the collections introduced by the Accessories Management feature in the web app (`Tech City Inventory System`). Existing phone-related collections (`phones`, `inventory`, `suppliers`, `procurements`, `price_configurations`, etc.) are **shared** with the phone module — they are documented at the bottom only where the accessory module reuses them.
>
> **Last updated:** 2026-05-28
> **Web app source of truth:** `src/services/accessoryService.js`, `src/services/accessoryInventoryService.js`, `src/services/accessoryLocationService.js`, `src/services/accessoryImageService.js`, `src/services/appConfigService.js`

---

## Critical Rules

1. **The database is live production data.** Never run destructive operations, scripts, or seed routines against it from a debug build.
2. **The web app is the canonical schema owner.** When the mobile app adds new fields, mirror them in the corresponding web service to keep the two clients in sync.
3. **Quantity mutations MUST go through transactions.** Any field that lives on `accessory_inventory` (`onHand`, `onDisplay`, `reserved`, `defective`, `sold`) must be updated inside a Firestore transaction using `FieldValue.increment()` — never read-modify-write from the client. The web app enforces an audit trail in the same transaction (see `accessory_inventory_adjustments`); the mobile app must do the same when adjusting stock.
4. **Sales decrement `onHand` and increment `sold` atomically.** Use the same transaction pattern as `recordAccessorySale` in `accessoryInventoryService.js`. Do NOT use `adjustAccessoryInventory` to record a sale — `sold` is intentionally not adjustable through that path.
5. **Security rules are currently fully open** (`allow read, write: if true`) because the mobile app does not use Firebase Auth. Treat any user-identifying field (`userId`, `userEmail`) as caller-supplied metadata, not as authenticated identity.

---

## Collection Map

| Collection | Purpose | Doc ID strategy |
|---|---|---|
| `accessory_products` | Catalog of accessory SKUs (one doc per product) | Doc ID = `internalSku` (e.g. `ACC-CHG-0001`) |
| `accessory_categories` | Lookup table for category codes used inside the SKU pattern | Firestore auto-ID |
| `accessory_locations` | Physical stores / branches | Firestore auto-ID |
| `accessory_pricing` | Current dealer/retail price per SKU (denormalized for fast catalog reads) | Doc ID = SKU |
| `accessory_inventory` | Per-store inventory counters for each SKU | Doc ID = `{sku}__{locationId}` (composite) |
| `accessory_inventory_adjustments` | Append-only audit log of every quantity change | Firestore auto-ID |
| `accessory_procurements` | Purchase orders raised against suppliers | Firestore auto-ID |
| `accessory_ledger` | Supplier debit/credit entries (purchase + payment) | Firestore auto-ID |
| `app_config` | Single-doc app settings (e.g. settings password) | Doc IDs are well-known strings, e.g. `settings_password` |

**Shared with the phone module (read by accessory features):**

| Collection | How accessories use it |
|---|---|
| `suppliers` | Accessory procurements reference suppliers by ID; supplier docs are shared with phone procurements |
| `users` | Email/UID stored on audit entries comes from this collection in the web app; mobile app may not have access |

**Firebase Storage:**

| Path | Contents |
|---|---|
| `accessory_images/{internalSku}/primary.png` | Single primary product image. Fixed `.png` filename so upload-overwrite avoids orphan files; content-type is taken from the `File` blob, not the extension. |

---

## 1. `accessory_products`

One document per SKU. The Internal SKU is the document ID and is immutable.

**Doc ID:** `internalSku` — e.g. `ACC-CHG-0001`, `ACC-EAR-0042`. Pattern is `ACC-{categoryCode}-{4-digit-sequence}` but the system does NOT enforce the pattern — it only enforces uniqueness.

**Example document:**

```json
{
  "internalSku": "ACC-CHG-0001",
  "barcode": "8801234567890",
  "manufacturer": "Anker",
  "model": "PowerPort III 65W",
  "category": "Charger",
  "categoryCode": "CHG",
  "description": "65W GaN III USB-C fast charger, single port",
  "photoUrl": "https://firebasestorage.googleapis.com/v0/b/tech-city-phone-information.appspot.com/o/accessory_images%2FACC-CHG-0001%2Fprimary.png?alt=media&token=...",
  "active": true,
  "dateCreated": "<Firestore Timestamp>",
  "lastUpdated": "<Firestore Timestamp>"
}
```

**Field reference:**

| Field | Type | Required | Notes |
|---|---|---|---|
| `internalSku` | string | yes | Mirrors doc ID; redundant but stored for query/filter ergonomics. |
| `barcode` | string | optional | Globally unique across `accessory_products` if present. Used by POS lookup (`getProductByBarcode`). |
| `manufacturer` | string | yes | Free text. Used as the top filter in the Add-Items cascade. |
| `model` | string | yes | Free text. |
| `category` | string | yes | Human-readable name (e.g. `"Charger"`). Must match the `name` of an `accessory_categories` doc. |
| `categoryCode` | string | yes | Three-letter code (e.g. `"CHG"`). Must match the `code` of an `accessory_categories` doc. |
| `description` | string | optional | Free text. |
| `photoUrl` | string | optional | Download URL written by `uploadAccessoryImage` after upload to Storage. Empty string if no image. |
| `active` | boolean | yes | Soft-delete flag. `false` hides the SKU from the catalog/picker. Inventory is preserved. |
| `dateCreated` | Timestamp | auto | Set on create. |
| `lastUpdated` | Timestamp | auto | Refreshed on every write (including image uploads). |

**Uniqueness constraints (enforced in service layer, NOT by rules):**

- `internalSku` (doc ID) — Firestore guarantees uniqueness.
- `barcode` — pre-checked against `where('barcode', '==', value)` before write.

---

## 2. `accessory_categories`

Lookup table for category names and SKU-pattern codes. Seeded on first load with six defaults (Printer/PRT, Earphones/EAR, Charger/CHG, Cable/CBL, Power Bank/PBK, Misc/MSC) — see `seedDefaultCategoriesIfEmpty` for the race-safe seed pattern (module-level promise guard).

**Doc ID:** Firestore auto-ID.

**Example document:**

```json
{
  "name": "Charger",
  "code": "CHG",
  "active": true,
  "sortOrder": 30,
  "dateCreated": "<Firestore Timestamp>",
  "lastUpdated": "<Firestore Timestamp>"
}
```

**Field reference:**

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes | Human-readable. Used as `category` on product docs. |
| `code` | string | yes | Uppercase 3-letter code. **Immutable** once created — products embed it in their `categoryCode`. The web app strips this field from update payloads. |
| `active` | boolean | yes | Soft-delete flag. |
| `sortOrder` | number | yes | Lower = earlier in dropdowns. Defaults: 10/20/30/40/50/60 for seeded, 100 for user-created. |
| `dateCreated` | Timestamp | auto | |
| `lastUpdated` | Timestamp | auto | |

**Deletion:** does NOT cascade to products. Callers should warn the user via `countProductsInCategory(name)` before deleting.

---

## 3. `accessory_locations`

Physical stores / branches. Inventory is tracked per `(sku, locationId)` pair, so every store has its own counters.

Seeded on first load with one default location named `"Main Branch"` (`isPrimary: true`).

**Doc ID:** Firestore auto-ID.

**Example document:**

```json
{
  "name": "Main Branch",
  "address": "123 Market Street, Tech City",
  "active": true,
  "isPrimary": true,
  "sortOrder": 10,
  "dateCreated": "<Firestore Timestamp>",
  "lastUpdated": "<Firestore Timestamp>"
}
```

**Field reference:**

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes | Unique (case-sensitive exact match). Denormalized onto `accessory_inventory` docs as `locationName`. |
| `address` | string | optional | Free text. |
| `active` | boolean | yes | |
| `isPrimary` | boolean | yes | Exactly one is expected to be `true` (the default destination). `getPrimaryLocation()` returns this one or falls back to the first active location. |
| `sortOrder` | number | yes | |
| `dateCreated` / `lastUpdated` | Timestamp | auto | |

**Deletion:** does NOT cascade. Callers should check `countInventoryAtLocation(locationId)` (counts docs with any non-zero quantity bucket) before deleting.

---

## 4. `accessory_pricing`

Current dealer & retail price per SKU. Denormalized from procurements for fast catalog reads — the web app's procurement-save flow writes here automatically (`setAccessoryPricing` for each line) so the catalog always reflects the most recent purchase.

**Doc ID:** SKU (matches `accessory_products` doc ID).

**Example document:**

```json
{
  "dealersPrice": 850.00,
  "retailPrice": 1250.00,
  "lastUpdated": "<Firestore Timestamp>"
}
```

**Field reference:**

| Field | Type | Required | Notes |
|---|---|---|---|
| `dealersPrice` | number | yes | Cost from supplier (in PHP). |
| `retailPrice` | number | yes | Selling price (in PHP). |
| `lastUpdated` | Timestamp | auto | |

**Notes:**

- Written with `setDoc(..., { merge: true })` — partial updates are safe.
- Margin is computed at read time: `((retail − dealer) / dealer) × 100`.
- Procurement save uses `Promise.all` to push prices for every line; failures are logged but do NOT roll back the procurement.

---

## 5. `accessory_inventory`

Per-store quantity counters. The most-touched collection in the system.

**Doc ID:** `{sku}__{locationId}` (composite — see `composeInventoryId`). Both halves are also stored as fields so `where('sku', '==', ...)` and `where('locationId', '==', ...)` queries work without scanning IDs.

**Example document (`ACC-CHG-0001__abc123MainBranchId`):**

```json
{
  "sku": "ACC-CHG-0001",
  "locationId": "abc123MainBranchId",
  "locationName": "Main Branch",
  "onHand": 24,
  "onDisplay": 2,
  "reserved": 0,
  "defective": 1,
  "sold": 87,
  "lastUpdated": "<Firestore Timestamp>"
}
```

**Field reference:**

| Field | Type | Required | Notes |
|---|---|---|---|
| `sku` | string | yes | Matches `accessory_products` doc ID. |
| `locationId` | string | yes | Matches `accessory_locations` doc ID. |
| `locationName` | string | yes | Denormalized from the location doc — refreshed on every write. |
| `onHand` | number | yes | Sellable stock in the back room / storage. Decremented by sales. |
| `onDisplay` | number | yes | Units on the shop-floor display. NOT sellable directly — must be moved to `onHand` first via an adjustment. |
| `reserved` | number | yes | Held against a pending sale / hold. Not currently mutated by any automated flow — manual only. |
| `defective` | number | yes | Damaged stock; pulled from `onHand` via an adjustment. |
| `sold` | number | yes | Lifetime sold count. Monotonically non-decreasing under normal operation. Only `recordAccessorySale` writes here. |
| `lastUpdated` | Timestamp | yes | |

**Derived field (computed client-side, not stored):**

- **Available** = `onHand − reserved` (clamped at 0). Displayed as a green pill in the web UI.

**Invariants enforced by the service layer:**

- No field is allowed to go negative. `adjustAccessoryInventory` reads the doc inside its transaction and throws before writing if any delta would drop a counter below zero.
- The audit entry is written in the **same** transaction as the inventory update — they can never drift.

**Lifecycle:**

- Documents are created lazily on first non-zero write (either via procurement receive or manual adjustment).
- Documents are never deleted by the web app — even a zeroed-out doc is kept so the audit trail remains anchored.

---

## 6. `accessory_inventory_adjustments`

Append-only audit log. One document per quantity adjustment, written **inside the same transaction** as the corresponding inventory mutation.

**Doc ID:** Firestore auto-ID.

**Example document (manual adjustment from the Inventory Entry form):**

```json
{
  "sku": "ACC-CHG-0001",
  "locationId": "abc123MainBranchId",
  "locationName": "Main Branch",
  "userId": "uid-of-logged-in-web-user-or-null",
  "userEmail": "levon.kc1@gmail.com",
  "timestamp": "<Firestore Timestamp>",
  "before": {
    "onHand": 24,
    "onDisplay": 2,
    "reserved": 0,
    "defective": 0
  },
  "after": {
    "onHand": 23,
    "onDisplay": 2,
    "reserved": 0,
    "defective": 1
  },
  "delta": {
    "onHand": -1,
    "defective": 1
  },
  "reason": "Unit dropped during unboxing — moved to defective for return.",
  "source": "manual-entry"
}
```

**Field reference:**

| Field | Type | Required | Notes |
|---|---|---|---|
| `sku` | string | yes | |
| `locationId` | string | yes | |
| `locationName` | string | yes | Denormalized snapshot at write time. |
| `userId` | string \| null | yes | Caller-supplied. `null` if anonymous. |
| `userEmail` | string \| null | yes | Caller-supplied. |
| `timestamp` | Timestamp | yes | Server time at write. |
| `before` | object | yes | Full snapshot of `{onHand, onDisplay, reserved, defective}` immediately before the change. Used by the audit view to render diffs. |
| `after` | object | yes | Snapshot immediately after. |
| `delta` | object | yes | Only fields that changed. Positive = added, negative = subtracted. |
| `reason` | string | yes | Required by the service — empty/whitespace-only reasons are rejected with `"A reason is required for inventory adjustments"`. |
| `source` | string | yes | Where the change originated. Known values: `"manual-entry"` (default, Inventory Entry form), `"inventory-list-edit"` (inline edit on the Inventory list). The mobile app should set this to a stable identifier like `"mobile-pos"` or `"mobile-stock-take"`. |

**What is NOT logged here:**

- Procurement **receive** (`receiveAccessoryStock`) does not currently write an adjustments entry — the procurement document's `receivedBreakdown` IS the audit trail for receives.
- Sales (`recordAccessorySale`) do not write adjustments either — the running `sold` counter is the audit trail for sales. If finer per-sale provenance is needed, add a separate `accessory_sales` collection rather than overloading this one.

---

## 7. `accessory_procurements`

Purchase orders raised against a supplier. Created in a batch write together with a `purchase` ledger entry. Receiving stock updates this doc with delivery metadata and the per-bucket breakdown.

**Doc ID:** Firestore auto-ID.

**Example document (created, not yet received, unpaid):**

```json
{
  "reference": "APROC-1716885600000-042",
  "supplierId": "9f8e7d6c5b4a",
  "supplierName": "ABC Electronics Trading",
  "purchaseDate": "2026-05-20",
  "items": [
    {
      "internalSku": "ACC-CHG-0001",
      "manufacturer": "Anker",
      "model": "PowerPort III 65W",
      "category": "Charger",
      "quantity": 20,
      "dealersPrice": 850,
      "retailPrice": 1250,
      "totalPrice": 17000
    },
    {
      "internalSku": "ACC-CBL-0005",
      "manufacturer": "Anker",
      "model": "PowerLine III USB-C 1m",
      "category": "Cable",
      "quantity": 50,
      "dealersPrice": 180,
      "retailPrice": 280,
      "totalPrice": 9000
    }
  ],
  "grandTotal": 26000,
  "totalQuantity": 70,
  "bankName": "",
  "bankAccount": "",
  "accountPayable": "",
  "isPaid": false,
  "datePaid": "",
  "paymentReference": "",
  "isReceived": false,
  "dateCreated": "<Firestore Timestamp>",
  "lastUpdated": "<Firestore Timestamp>"
}
```

**Example after receiving (allocated 18 to stock, 1 to display, 1 defective on the cable; everything to stock on the charger):**

```json
{
  "...": "everything above",
  "isReceived": true,
  "dateDelivered": "2026-05-24",
  "deliveryReference": "DR-2026-0124",
  "deliveryLocationId": "abc123MainBranchId",
  "deliveryLocationName": "Main Branch",
  "receivedBreakdown": [
    { "internalSku": "ACC-CHG-0001", "onHand": 20, "onDisplay": 0, "defective": 0 },
    { "internalSku": "ACC-CBL-0005", "onHand": 48, "onDisplay": 1, "defective": 1 }
  ],
  "lastUpdated": "<Firestore Timestamp>"
}
```

**Example after marking paid:**

```json
{
  "...": "everything above",
  "isPaid": true,
  "datePaid": "2026-05-26",
  "paymentReference": "APAY-1717057800000-318",
  "bankName": "BPI",
  "bankAccount": "1234-5678-90",
  "accountPayable": "Tech City Inc."
}
```

**Field reference:**

| Field | Type | Required | Notes |
|---|---|---|---|
| `reference` | string | yes | Generated by `generateAccessoryProcurementReference()` → `APROC-{timestamp}-{3-digit-random}`. Used as the human-readable reference everywhere. |
| `supplierId` | string | yes | References `suppliers` doc ID (shared with phone module). |
| `supplierName` | string | yes | Denormalized at create/update time. |
| `purchaseDate` | string `YYYY-MM-DD` | yes | Used for year filtering and ledger entry date. |
| `items` | array | yes | See line-item shape below. |
| `grandTotal` | number | yes | Sum of `items[].totalPrice`. Mirrored to the purchase ledger entry's `amountDue`. |
| `totalQuantity` | number | yes | Sum of `items[].quantity`. |
| `bankName` / `bankAccount` / `accountPayable` | string | optional | Payment metadata. Populated when payment is recorded. |
| `isPaid` | boolean | yes | |
| `datePaid` | string `YYYY-MM-DD` | optional | Empty if unpaid. |
| `paymentReference` | string | optional | Generated by `generateAccessoryPaymentReference()` → `APAY-{timestamp}-{random}`. |
| `isReceived` | boolean | yes | Set to `true` by `receiveAccessoryStock`. |
| `dateDelivered` | string `YYYY-MM-DD` | optional | Set on receive. |
| `deliveryReference` | string | optional | Set on receive. Caller-supplied (e.g. carrier waybill). |
| `deliveryLocationId` | string | optional | Destination chosen at receive time, not at create time. |
| `deliveryLocationName` | string | optional | Denormalized. |
| `receivedBreakdown` | array | optional | Per-SKU bucket allocation actually received. Shape: `[{ internalSku, onHand, onDisplay, defective }]`. The view-only stock-receiving screen reads this to reproduce the split; falls back to "all onHand" for legacy procurements. |
| `dateCreated` / `lastUpdated` | Timestamp | auto | |

**Line-item shape (`items[i]`):**

| Field | Type | Notes |
|---|---|---|
| `internalSku` | string | Matches `accessory_products` doc ID. |
| `manufacturer`, `model`, `category` | string | Snapshot of the product at procurement time (so historical line items don't change if the catalog is renamed). |
| `quantity` | number | Ordered quantity. The receive flow enforces `onHand + onDisplay + defective === quantity` per line — partial receives are blocked at the UI layer. |
| `dealersPrice` | number | Per-unit cost. Must be > 0 to save (UI validation). |
| `retailPrice` | number | Per-unit selling price. Must be > 0 to save. Used to sync `accessory_pricing` on save. |
| `totalPrice` | number | `quantity × dealersPrice`. |

**Strict receive validation:** the web app blocks save when any line's `onHand + onDisplay + defective ≠ quantity`. If a supplier under- or over-delivers, the user is expected to edit the procurement first to adjust `quantity`, then receive. **Mobile clients should adopt the same rule** to keep procurement data honest.

---

## 8. `accessory_ledger`

Supplier-by-supplier debit (`purchase`) and credit (`payment`) entries. Always written in the same transaction/batch as the matching procurement operation, so the ledger and procurement state never drift.

**Doc ID:** Firestore auto-ID.

**Example — purchase entry (debit, created with the procurement):**

```json
{
  "supplierId": "9f8e7d6c5b4a",
  "supplierName": "ABC Electronics Trading",
  "procurementId": "Rj2k8sL1nMxYqWp7v",
  "entryType": "purchase",
  "reference": "APROC-1716885600000-042",
  "purchaseDate": "2026-05-20",
  "entryDate": "2026-05-20",
  "amountDue": 26000,
  "amountPaid": 0,
  "description": "Accessory purchase - 2 item(s)",
  "sortOrder": 1,
  "isDeleted": false,
  "dateCreated": "<Firestore Timestamp>",
  "lastUpdated": "<Firestore Timestamp>"
}
```

**Example — payment entry (credit, created when marked paid):**

```json
{
  "supplierId": "9f8e7d6c5b4a",
  "supplierName": "ABC Electronics Trading",
  "procurementId": "Rj2k8sL1nMxYqWp7v",
  "entryType": "payment",
  "reference": "APAY-1717057800000-318",
  "purchaseDate": "2026-05-20",
  "entryDate": "2026-05-26",
  "amountDue": 0,
  "amountPaid": 26000,
  "description": "Payment for APROC-1716885600000-042",
  "sortOrder": 2,
  "isDeleted": false,
  "dateCreated": "<Firestore Timestamp>",
  "lastUpdated": "<Firestore Timestamp>"
}
```

**Example — soft-deleted purchase entry (after deleting the parent procurement):**

```json
{
  "...": "everything above",
  "description": "Accessory purchase - 2 item(s) - DELETED (Original Due: 26000, Paid: 0)",
  "amountDue": 0,
  "amountPaid": 0,
  "isDeleted": true,
  "deletedDate": "<Firestore Timestamp>"
}
```

**Field reference:**

| Field | Type | Required | Notes |
|---|---|---|---|
| `supplierId` | string | yes | |
| `supplierName` | string | yes | Denormalized. Refreshed on procurement update. |
| `procurementId` | string \| null | yes | `null` for standalone manual entries (none today, but the read code already handles it). |
| `entryType` | string | yes | `"purchase"` or `"payment"`. |
| `reference` | string | yes | Procurement reference (for purchase) or payment reference (for payment). |
| `purchaseDate` | string `YYYY-MM-DD` | yes | The parent procurement's date — kept on both entry types so they group correctly. |
| `entryDate` | string `YYYY-MM-DD` | yes | Date the entry itself takes effect (purchase date for purchases, payment date for payments). |
| `amountDue` | number | yes | Set on purchase entries; `0` on payment entries. |
| `amountPaid` | number | yes | Set on payment entries; `0` on purchase entries. |
| `description` | string | yes | Human-readable. Includes `" (EDITED)"` suffix if the parent procurement's `grandTotal` has been edited, and `" - DELETED (...)"` if the parent procurement has been deleted. |
| `sortOrder` | number | yes | `1` for purchase, `2` for payment — used to order entries within a procurement group. |
| `isDeleted` | boolean | yes | Soft-delete flag set when the parent procurement is deleted. Read code filters or annotates these. |
| `deletedDate` | Timestamp | optional | Set when `isDeleted` flips to `true`. |
| `dateCreated` / `lastUpdated` | Timestamp | auto | |

**Running balance:** computed client-side by `getAccessoryLedgerBySupplier`. Algorithm:

1. Group entries by `procurementId`.
2. Within each group, sort by `sortOrder` (purchase before payment).
3. Sort groups chronologically by the purchase entry's `dateCreated`.
4. Append standalone entries (no procurementId) at the end.
5. Walk the list, adding `amountDue` for non-deleted purchases and subtracting `amountPaid` for non-deleted payments. Clamp at 0. Write the running total back to each entry as `runningBalance` (not stored).

---

## 9. `app_config`

Single-document settings. Today only used for the inventory-adjustment password gate.

**Doc ID:** `settings_password` (well-known string).

**Example:**

```json
{
  "password": "your-plain-text-password-here"
}
```

**Notes:**

- **Plain text comparison.** `verifySettingsPassword` does `String(input) === stored`. This is acceptable here because the doc itself is only readable by clients that already have full read/write access (rules are fully open) — the gate exists to prevent casual misclicks on the inventory adjustment forms, not to defend against a determined attacker.
- The mobile app should reuse this same password for any equivalent destructive flow (manual stock adjustments) to keep operator experience consistent.

---

## Common Mobile-App Operations (Recipes)

These recipes show the exact transaction shape the web app uses. Mirror them in the mobile client.

### Record a sale (POS)

```js
// Atomic: onHand -= qty, sold += qty. Rejects if onHand would go negative.
await runTransaction(db, async (tx) => {
  const invRef = doc(db, 'accessory_inventory', `${sku}__${locationId}`);
  const snap = await tx.get(invRef);
  if (!snap.exists()) throw new Error('No inventory at this location');
  const current = snap.data();
  if ((current.onHand || 0) < qty) throw new Error('Insufficient stock');
  tx.update(invRef, {
    onHand: increment(-qty),
    sold: increment(qty),
    lastUpdated: Timestamp.now()
  });
});
```

Do NOT write to `accessory_inventory_adjustments` for sales — `sold` is the audit trail. If you need per-sale records, add a separate `accessory_sales` collection.

### Manual stock adjustment

Use the same transaction shape as `adjustAccessoryInventory` in `accessoryInventoryService.js`:

1. Read the inventory doc AND the location doc inside the transaction.
2. Compute the new bucket values; throw if any would go negative.
3. Either `tx.set(...)` (if doc doesn't exist) or `tx.update(...)` with `increment(delta)` per bucket.
4. **In the same transaction**, `tx.set(...)` a new doc in `accessory_inventory_adjustments` with `before` / `after` / `delta` / `reason` / `userId` / `userEmail` / `source`. The `reason` field is required — reject empty strings.

The web app additionally gates the UI behind the `app_config/settings_password` check, but that's a UI policy, not a schema requirement.

### Look up a product by barcode

```js
const q = query(
  collection(db, 'accessory_products'),
  where('barcode', '==', scannedBarcode)
);
const snap = await getDocs(q);
const product = snap.empty ? null : { id: snap.docs[0].id, ...snap.docs[0].data() };
```

### Read inventory for a location (full store snapshot)

```js
const q = query(
  collection(db, 'accessory_inventory'),
  where('locationId', '==', currentLocationId)
);
const snap = await getDocs(q);
```

The doc IDs follow `{sku}__{locationId}` so they can be reconstructed without a query if both halves are known.

---

## Shared collections (read-only context for accessories)

### `suppliers`

Created and maintained by the phone module. Accessory procurements reference these by `id`. Mobile app should treat as read-only when working on accessory flows.

Relevant fields the accessory module reads:

| Field | Notes |
|---|---|
| `supplierName` | Denormalized onto every accessory procurement and ledger entry on write. |
| `id` (doc ID) | Used as `supplierId`. |

### `users`

Optional. The web app populates `userId` / `userEmail` on audit entries from this collection via `AuthContext`. The mobile app doesn't use Firebase Auth and should supply whatever identity makes sense for its operator model (e.g. the device's logged-in cashier name) as a plain string.

---

## Anti-patterns to avoid

- **Don't write to `accessory_inventory` outside a transaction.** Race conditions between two POS terminals will silently corrupt counters.
- **Don't bypass the audit log for manual adjustments.** Every operator-driven quantity change must produce an `accessory_inventory_adjustments` entry in the same transaction.
- **Don't mutate `sold` from `adjustAccessoryInventory`.** It is deliberately excluded from `QUANTITY_FIELDS`. Use `recordAccessorySale` (or an equivalent transaction) so `onHand` is decremented in lockstep.
- **Don't store images outside `accessory_images/{sku}/primary.png`.** The fixed filename is what makes upload-replace orphan-free; using timestamped filenames will leak storage.
- **Don't rename a category's `code` or a product's `internalSku`.** Both are referenced by other documents; the service layer strips these from update payloads on purpose.
- **Don't delete `accessory_inventory` docs.** Zero them out instead — the audit log assumes the doc continues to exist.
- **Don't delete `accessory_ledger` entries.** Soft-delete via `isDeleted: true` so the running balance can still be reproduced and the description still tells the user what happened.
