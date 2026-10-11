# Technical Plan: Stock Transfer Between Branches

Implementation plan for `_specs/stock-transfer.md` (all open questions resolved through round 3).

## Approach Summary

- Three new Activities mirror the existing Phone Inventory trio: **list** (`PhoneInventoryListActivity` pattern), **new transfer** (draft builder with scan), and **detail** (`ReconciliationDetailActivity` pattern with a real-time listener and a Scan/Confirm FAB).
- All Firestore mutations that touch more than one document (Create, Confirm, Complete with discrepancies, Delete, per-item verification) live in one new `object StockTransferRepository` as `suspend` functions built on `db.runTransaction { }`. Activities stay thin and the four multi-document operations share one audit-entry helper. This follows the existing no-DI singleton style (`StoreLocationHelper`, `AppSettingsManager`).
- Phones in a transfer are stored as a **snapshot map** on the transfer document (`phones`, keyed by inventory doc ID), so the detail screen renders from the transfer document alone (no `whereIn` batching) and per-item verification is a single dotted-path update inside a transaction.
- Scanner reuse: `BarcodeScannerActivity` is launched one-shot with its existing extras. No scanner changes.
- The only existing flows modified are: Program Settings (toggle), Menu (card), `Constants.kt` (additive), `AndroidManifest.xml` (registrations), `strings.xml`, and the **Device Transaction sale guard** (block `Reserved`).

---

## Files to Create

### 1. `app/src/main/java/com/techcity/techcitysuite/StockTransferModels.kt`
Plain Kotlin data classes with no-arg constructors (Firestore-mappable), one file:
- `StockTransfer` — header fields from the spec Data Model: `id`, `reference`, `status`, `hasDiscrepancies`, `fromLocation`, `fromLocationId`, `toLocation`, `toLocationId`, `date`, `createdTime`, `createdAt: Timestamp?`, `createdBy`, `completedAt: Timestamp?`, `completedBy`, `phoneCount`, `phoneVerifiedCount`, `accessoryUnitCount`, `accessoryReceivedCount`, `missingPhoneCount`, `missingAccessoryUnitCount`, `phones: Map<String, TransferPhoneItem>`, `accessories: Map<String, TransferAccessoryItem>`, `phoneInventoryIds: List<String>`.
- `TransferPhoneItem` — `inventoryId`, `manufacturer`, `model`, `ram`, `storage`, `color`, `imei1`, `imei2`, `serialNumber`, `originalStatus`, `originalLocation`, `verified`, `verifiedBy`, `verifiedAt: Timestamp?`, `scannedType`, `scannedValue`, `missing`.
- `TransferAccessoryItem` — `sku`, `barcode`, `manufacturer`, `model`, `category`, `quantity: Int`, `receivedQuantity: Int`, `missingQuantity: Int`, `originOnHand: Int`, `originOnDisplay: Int` (flattened `originBuckets`), `lastReceivedBy`, `lastReceivedAt: Timestamp?`.
- Parsing helpers: `StockTransfer.fromDocument(doc)` and `toMap()` for the two item types (manual map parsing like the reconciliation code, not `toObject`, because the nested maps carry Timestamps and Ints that need null-safe handling).

### 2. `app/src/main/java/com/techcity/techcitysuite/StockTransferRepository.kt`
`object StockTransferRepository` holding every Firestore write for the feature. Each function takes `db: FirebaseFirestore` plus a small context (user name, auth email/uid, store location name/id) and runs on `Dispatchers.IO`.

**Scan classification (reads only, outside transactions):**
- `classifyScan(db, value, originLocation, draftPhoneIds, draftSkus): ScanResult` — sealed result: `PhoneFound(InventoryItem)`, `PhoneBoxBarcode(manufacturer, model)`, `AccessoryFound(sku, barcode, manufacturer, model, category)`, `NotFound`, `Ineligible(message)`.
  - Order: `inventory` equality queries on `imei1`, then `imei2`, then `serialNumber` (each `.limit(5)`; first document passing eligibility wins; if matches exist but none pass, return `Ineligible` with that item's reason). Then `inventory` equality on `barcode` → `PhoneBoxBarcode`. Then `accessory_products` equality on `barcode` where `active != false` → `AccessoryFound`. Else `NotFound`.
  - Eligibility (spec Rule 10): `isArchived != true`; `status` in {On-Hand, On-Display}; `location.equals(origin, ignoreCase = true)` after trim; manufacturer not "Techcity" (ignore case); `imei1`/`imei2` != `PLACEHOLDER_IMEI`; id not in `draftPhoneIds`.
- `readOriginStock(db, sku, fromLocationId): Pair<Int, Int>?` — `onHand`/`onDisplay` of `accessory_inventory/{sku}__{fromLocationId}`, or null when the doc is missing.

**Create (`createTransfer(...)`: returns the new document ID):**
1. Pre-create `transferRef = db.collection(COLLECTION_STOCK_TRANSFERS).document()`; build `reference = "ST-" + currentTimeMillis + "-" + 3 random digits`.
2. `runTransaction`: **reads first** — `tx.get` every phone `inventory` doc, then every origin `accessory_inventory` doc. Re-check phone eligibility (status still On-Hand/On-Display, location still origin, `transferId` absent); throw `IllegalStateException("<model> <imei> is no longer available (<reason>)")` on failure. For each SKU compute `takeFromOnHand = min(qty, onHand)`, `takeFromOnDisplay = qty - takeFromOnHand`; throw if `takeFromOnDisplay > onDisplay`.
3. **Writes** — `tx.set(transferRef, transferMap)` (status `pending`, counters, phone snapshots with `originalStatus`/`originalLocation`, accessory items with the computed origin split); per phone `tx.update(invRef, "status" to Reserved, "transferId" to transferRef.id)`; per SKU `tx.update(originInvRef, "onHand" to increment(-a), "onDisplay" to increment(-b), "reserved" to increment(a + b), "lastUpdated" to serverTimestamp())` plus one audit entry (see helper). *(Amendment 1: the `reserved` increment holds the in-transit units as the origin's stock.)*

**Receiving (per-item, destination):**
- `verifyPhone(db, transferId, inventoryId, verifiedBy, scannedType, scannedValue)` — `runTransaction`: read transfer; throw if status != pending or the phone is already verified; `tx.update` with `FieldPath.of("phones", inventoryId, "verified")` = true, `verifiedBy`, `verifiedAt` = `Timestamp.now()`, `scannedType`, `scannedValue`, and `phoneVerifiedCount` = `increment(1)`.
- `receiveAccessory(db, transferId, sku, amount, receivedBy)` — `runTransaction`: read transfer; throw if `receivedQuantity + amount > quantity`; update `FieldPath.of("accessories", sku, "receivedQuantity")` = `increment(amount)`, `lastReceivedBy`, `lastReceivedAt`, and `accessoryReceivedCount` = `increment(amount)`.
  - `FieldPath.of(...)` is required because SKUs contain hyphens (`ACC-CHG-0001`), which are illegal in dotted string paths.

**Confirm (`confirmTransfer(...)`):**
`runTransaction`: read transfer (throw unless `pending` and fully verified); read every phone `inventory` doc (throw unless `status == Reserved && transferId == this id`); read every destination `accessory_inventory/{sku}__{toLocationId}` doc and (Amendment 1) every origin `accessory_inventory/{sku}__{fromLocationId}` doc. Writes: per phone `status` = On-Hand, `location` = `toLocation`, `lastUpdated` = today `M/d/yyyy`, `transferId` = `FieldValue.delete()`; per SKU either `tx.set` a new destination doc (`sku`, `locationId`, `locationName`, all five buckets 0, then `onHand` = qty, `lastUpdated`) or `tx.update` `onHand` = `increment(qty)`; audit entry per SKU; per SKU origin `reserved` = `increment(-qty)` via `returnToOrigin(..., reservedDelta = -qty)` with its own audit entry (reason "Stock transfer ST-… received at X"); transfer `status` = completed, `hasDiscrepancies` = false, `completedAt`, `completedBy`.

**Complete with discrepancies (`completeWithDiscrepancies(...)`):**
Same read set plus the **origin** `accessory_inventory` docs. Verified phones as Confirm; unverified phones → `status` = `originalStatus`, `transferId` deleted, `location` untouched, `phones.{id}.missing` = true. Per SKU: destination `onHand += receivedQuantity` (if > 0); origin `reserved -= quantity` and `onHand += missingQuantity` in **one** `returnToOrigin` write (Amendment 1), `accessories.{sku}.missingQuantity` set when > 0, one audit entry per affected location. Transfer `status` = completed, `hasDiscrepancies` = true, `missingPhoneCount`, `missingAccessoryUnitCount`, `completedAt`, `completedBy`. Phones whose inventory doc is no longer `Reserved` with this `transferId` are skipped and reported in the result (spec Edge Case 15).

**Delete (`deleteTransfer(...)`):**
`runTransaction`: read transfer (throw unless pending), phone docs, origin inventory docs. Writes: phones still `Reserved` with this `transferId` → `status` = `originalStatus`, `transferId` deleted; per SKU origin `onHand += originOnHand`, `onDisplay += originOnDisplay`, `reserved -= quantity` (Amendment 1) in one write plus audit entry; `tx.delete(transferRef)`.

**Audit helper (private):**
`addAuditEntry(tx, db, sku, locationId, locationName, beforeSnapshot, deltaOnHand, deltaOnDisplay, deltaReserved = 0, reason)` → `tx.set(db.collection(COLLECTION_ACCESSORY_INVENTORY_ADJUSTMENTS).document(), map)` with `before {onHand, onDisplay, reserved, defective}` from the snapshot read in the same transaction (zeros when the doc did not exist), `after` = before + delta (including `reserved`, Amendment 1), `delta` containing only changed buckets, `userId` = `AuthManager.currentUser()?.uid`, `userEmail` = `AuthManager.currentEmail()`, `timestamp` = `Timestamp.now()`, `source` = `"mobile-stock-transfer"`, `reason` strings per spec ("Stock transfer ST-… to X", "… from X", "… deleted", "… missing units returned").

**Write budget per transaction (spec size cap 150 phones / 100 SKUs):**
- Create / Confirm / Delete: 1 + 150 + 100 counter + 100 audit = **351** writes (< 500).
- Complete with discrepancies, worst case every SKU both partly received and partly missing: 1 + 150 + 200 counter + 200 audit = **551** writes (> 500). See Risks — the plan lowers the **accessory SKU cap to 80** (1 + 150 + 160 + 160 = 471) unless you prefer to keep 100 and accept that a worst-case discrepancy completion fails with a Firestore error.
- **After Amendment 1 (SKU cap 80):** Create **311**, Delete **311** (the `reserved` change rides on the existing origin write), Confirm **471** (origin release adds 80 counter + 80 audit writes), Complete with discrepancies **471** (origin release merged into the existing origin write). All < 500.

### 3. `app/src/main/java/com/techcity/techcitysuite/StockTransferListActivity.kt`
Copy of the `PhoneInventoryListActivity` structure:
- `onCreate`: binding, RecyclerView, FAB → store-location check (`StoreLocationHelper.isConfigured`) → `showPasswordDialog()` (same `dialog_password` code as Phone Inventory) → `startActivity(NewStockTransferActivity)`.
- `onResume`: `loadTransfers()` — `stock_transfers` ordered by `createdAt` desc, **no location filter**, parsed via `StockTransfer.fromDocument`.
- Adapter binding `item_stock_transfer.xml`: date, status badge (`Pending` blue / `Completed` green via `badge_background` tint), optional `Discrepancies` badge (red), "From → To", direction hint computed against `StoreLocationHelper.getStoreLocation(this)`, phone and accessory progress lines, created/received line.
- Click → `StockTransferDetailActivity` with extra `TRANSFER_ID`.
- Long-press → if status != pending: toast "Completed transfers cannot be deleted"; else password dialog → confirmation `AlertDialog` → `StockTransferRepository.deleteTransfer` → reload. Delete requires `StoreLocationHelper.isConfigured`.

### 4. `app/src/main/res/layout/activity_stock_transfer_list.xml`
Copy of `activity_phone_inventory_list.xml` with the title/subtitle text changed and ids `transferRecyclerView`, `emptyStateLayout`, `emptyMessage`, `progressBar`, `addButton`.

### 5. `app/src/main/res/layout/item_stock_transfer.xml`
MaterialCardView modelled on `item_reconciliation.xml`: header row (`transferDate`, `discrepancyBadge`, `statusBadge`), route row (`routeText` "TC-Main → TC-Kapatagan"), `directionText`, divider, `phonesProgressText`, `accessoriesProgressText`, `createdInfo`.

### 6. `app/src/main/java/com/techcity/techcitysuite/NewStockTransferActivity.kt`
Draft builder (origin side).
- State: `draftPhones: MutableList<TransferPhoneItem>` (with `originalStatus`), `draftAccessories: MutableMap<String, TransferAccessoryItem>` plus a per-SKU "available at origin" pair for display.
- Header text from `StoreLocationHelper.getStoreLocation`; counts refreshed on every change.
- One RecyclerView with three view types: section header (`item_transfer_section_header.xml`), phone row, accessory row. Adapter rebuilds a flat list `[Phones header, phones..., Accessories header, accessories...]` (headers omitted when their section is empty).
- `scanFab` → `BarcodeScannerActivity` via `registerForActivityResult` with no extras (default IMEI priority, one-shot). On result: size-cap check, then `classifyScan` in a coroutine with the progress bar visible; handle each `ScanResult`:
  - `PhoneFound` → add row, toast "Added <model>".
  - `PhoneBoxBarcode` → `AlertDialog` with the spec message.
  - `AccessoryFound` → `readOriginStock`; if null or both zero → dialog "No stock recorded at <origin>…"; else `showQuantityDialog(mode = SEND, max = onHand + onDisplay, prefill = existing qty or 1)`; on OK put/replace in `draftAccessories`.
  - `NotFound` / `Ineligible` → toast with the message.
- Swipe-left removal via `ItemTouchHelper` (same pattern as `AccessoryTransactionListActivity`): only phone/accessory rows are swipeable (`getSwipeDirs` returns 0 for headers); on swipe show a confirm dialog; cancel → `notifyItemChanged`.
- `createButton` enabled only when the draft is non-empty → `showCreateTransferDialog()`: inflate `dialog_create_stock_transfer.xml`, load `StoreLocationHelper.loadActiveLocations(db)`, exclude the device's own store name, keep a `name → id` map; empty list → error text "No other branch available", submit disabled. Submit → progress → `StockTransferRepository.createTransfer` → toast → `finish()`. Errors stay in the dialog's `errorMessage` and re-enable the form (same handling as the reconciliation dialog).
- `onBackPressedDispatcher` callback: non-empty draft → "Discard this transfer?" dialog.

### 7. `app/src/main/res/layout/activity_new_stock_transfer.xml`
ConstraintLayout: blue header (`backButton`, `titleText`, `fromLocationLabel`, `countsLabel`), `progressBar`, `emptyStateLayout`/`emptyMessage`, `draftRecyclerView` (bottom padding 160dp like the reconciliation detail), and two bottom-right buttons: `createButton` (`MaterialButton`, outlined, "Create", constrained `end_toStartOf="@id/scanFab"`, margin 12dp) and `scanFab` (`ExtendedFloatingActionButton`, "Scan", `ic_add`, same margins as `activity_reconciliation_detail.xml`).

### 8. `app/src/main/res/layout/item_transfer_section_header.xml`
Single bold TextView (`sectionTitle`, e.g. "PHONES (3)") styled like the "OVERALL SUMMARY" caption.

### 9. `app/src/main/res/layout/item_transfer_phone.xml`
Trimmed copy of `item_reconciliation_phone.xml`: `cardView`, `manufacturerText`, `statusBadge`, `modelText`, `ramStorageText`, `colorText`, `imei1Row/imei1Text`, `imei2Row/imei2Text`, `serialRow/serialText`, footer with `notVerifiedText`, `verifiedLayout/verifiedByText`, and a new `missingText` ("Missing", red, gone by default). Used by both the draft screen (footer hidden) and the detail screen.

### 10. `app/src/main/res/layout/item_transfer_accessory.xml`
MaterialCardView: `nameText` (manufacturer + model), `skuText`, `barcodeText`, `quantityText` (draft: "Qty: 10 · Available: 24 / 2"; detail: "Received 35 / 50"), `receivedCheck` (ic_check_circle, green, gone unless complete), `missingText` (red, gone by default).

### 11. `app/src/main/res/layout/dialog_create_stock_transfer.xml`
Copy of `dialog_new_reconciliation.xml`: same header block (icon `ic_arrow_forward`, title "Create Stock Transfer", subtitle "Select the branch to transfer the stock to"), one exposed-dropdown `TextInputLayout` (`locationInputLayout` / `locationInput`, hint "Transfer To"), a `summaryText` TextView, `errorMessage`, `progressBar`, `buttonsLayout` with text `cancelButton` and filled `submitButton` ("Create"). The status-filter dropdown is omitted.

### 12. `app/src/main/res/layout/dialog_transfer_quantity.xml`
Same visual language: icon, `titleText`, `itemNameText`, `stockInfoText` ("Available at origin: onHand 24, onDisplay 2" or "Expected 50 · Received 35 · Remaining 15"), numeric `TextInputLayout` (`quantityInput`, `inputType=number`), `errorMessage`, `buttonsLayout` (`cancelButton`, `submitButton`). Shared by the sending and receiving flows; the Activity sets the strings and the max.

### 13. `app/src/main/java/com/techcity/techcitysuite/StockTransferDetailActivity.kt`
Modelled on `ReconciliationDetailActivity`:
- Reads `TRANSFER_ID`; attaches a snapshot listener to the transfer doc; on each snapshot re-parses `StockTransfer`, re-renders header, summary card, and the three-view-type list (same adapter class as the draft screen, reused via a shared `TransferItemsAdapter` placed in its own file if cleaner — see file 14). Deleted document → toast "This transfer was deleted" → `finish()`.
- Role: `isDestinationDevice = StoreLocationHelper.getStoreLocation(this) == transfer.toLocation` (exact match; both values are canonical names) and `StoreLocationHelper.isConfigured`.
- FAB logic (`updateFab()`): pending + destination → if fully verified show "Confirm" (`ic_check` icon) else "Scan" (`ic_add`); otherwise hide. Click routes to `openScanner()` or `showConfirmDialog()`.
- `openScanner()` → `BarcodeScannerActivity` with `EXTRA_AUTO_MATCH_ENABLED = true` and `EXTRA_VALID_IDENTIFIERS` = all phone IMEI1/IMEI2/serials plus accessory barcodes (same as reconciliation).
- Scan result handling: match against the transfer's own `phones` (imei1/imei2/serialNumber) → `verifyPhone`; match against `accessories[*].barcode` → quantity dialog (mode RECEIVE, max = remaining, prefill = remaining) → `receiveAccessory`; otherwise one `inventory` `barcode` equality read to distinguish "scan the IMEI instead" from "Item is not part of this transfer".
- Manual verification: tapping an unverified phone row on the destination device while pending → password dialog (copy of `showManualVerificationPasswordDialog` in `ManufacturerDetailActivity`) → details confirmation → `verifyPhone(scannedType = "Manual", scannedValue = "Manual verification")`.
- Header kebab (`ic_kebab_menu`) → `PopupMenu` with one item "Complete with discrepancies", shown only when pending + destination + not fully verified → password dialog → discrepancy summary dialog (lists unverified phones and short SKUs) → `completeWithDiscrepancies`.
- `showConfirmDialog()` (no password) → `confirmTransfer` → toast; the listener updates the UI.

### 14. `app/src/main/java/com/techcity/techcitysuite/TransferItemsAdapter.kt`
Shared RecyclerView adapter for the draft and detail screens: builds the flat row list from `phones` and `accessories`, three view types, callbacks `onPhoneClick`, `onPhoneSwiped`, `onAccessorySwiped` (swipe callbacks only wired by the draft screen), and a `mode` flag (DRAFT / DETAIL) controlling which footer views are shown.

### 15. `app/src/main/res/layout/activity_stock_transfer_detail.xml`
Copy of `activity_reconciliation_detail.xml` with: header (`backButton`, `dateLabel`, `statusBadge`, `routeLabel`, `itemCountLabel`, `createdByLabel`, `kebabButton`), summary card rows (`summaryPhonesText`, `summaryAccessoriesText`, `summaryMissingText` gone by default), `progressBar`, `emptyStateLayout`, `itemsRecyclerView`, and `scanFab` (ExtendedFloatingActionButton).

---

## Files to Modify

### 16. `app/src/main/java/com/techcity/techcitysuite/Constants.kt`
Additive only:
- `KEY_STOCK_TRANSFER_ENABLED = "stock_transfer_enabled"`.
- `COLLECTION_STOCK_TRANSFERS = "stock_transfers"`, `COLLECTION_ACCESSORY_INVENTORY_ADJUSTMENTS = "accessory_inventory_adjustments"`.
- `INVENTORY_STATUS_RESERVED = "Reserved"`.
- `TRANSFER_STATUS_PENDING = "pending"`, `TRANSFER_STATUS_COMPLETED = "completed"`, `TRANSFER_MAX_PHONES = 150`, `TRANSFER_MAX_ACCESSORY_SKUS = 80` (see Risks), `TRANSFER_AUDIT_SOURCE = "mobile-stock-transfer"`, `TRANSFER_EXCLUDED_MANUFACTURERS = listOf("Techcity")`.

### 17. `app/src/main/res/layout/activity_program_settings.xml`
After the Expenses toggle block (before the Feature Settings card closes): add a divider `View` and a **Stock Transfer** toggle row identical in structure to the Expenses row, switch id `stockTransferSwitch`, texts "Stock Transfer" / "Show Stock Transfer in main menu", `checked="false"`.

### 18. `app/src/main/java/com/techcity/techcitysuite/ProgramSettingsActivity.kt`
- Companion: add `KEY_STOCK_TRANSFER_ENABLED = "stock_transfer_enabled"` (mirrors the existing local-duplicate pattern).
- `loadSettings()`: `binding.stockTransferSwitch.isChecked = prefs.getBoolean(KEY_STOCK_TRANSFER_ENABLED, false)`.
- `saveSettings()`: `editor.putBoolean(KEY_STOCK_TRANSFER_ENABLED, binding.stockTransferSwitch.isChecked)`.

### 19. `app/src/main/res/layout/activity_menu.xml`
Insert a **Stock Transfer** card directly after the Phone Inventory card (`inventoryCard`), copying its structure: `stockTransferCard` / `stockTransferButton` / `stockTransferIcon` (`ic_arrow_forward`, tint `teal_700`) / `stockTransferTitle` (`@string/stock_transfer`) / subtitle (`@string/stock_transfer_subtitle`).

### 20. `app/src/main/res/values/strings.xml`
Add `stock_transfer` ("Stock Transfer"), `stock_transfer_subtitle` ("Transfer stock between branches"), `stock_transfer_icon` ("Stock Transfer Icon").

### 21. `app/src/main/java/com/techcity/techcitysuite/MenuActivity.kt`
- `updateFeatureVisibility()`: show/hide `binding.stockTransferCard` from `KEY_STOCK_TRANSFER_ENABLED` (default false).
- `setupMenuButtons()`: `stockTransferButton` → `StockTransferListActivity`.

### 22. `app/src/main/AndroidManifest.xml`
Register `StockTransferListActivity` ("Stock Transfer"), `NewStockTransferActivity` ("New Stock Transfer"), `StockTransferDetailActivity` ("Stock Transfer Details"), all `exported="false"`, placed after `ManufacturerDetailActivity`.

### 23. `app/src/main/java/com/techcity/techcitysuite/DeviceTransactionActivity.kt`
Sale guard extension (spec Rule 18, Option 2), two places:
- **Pre-validation** (the "Block selling a device that is already marked Sold" block, ~line 1187): after the Sold check add a `Reserved` check (`status.equals(INVENTORY_STATUS_RESERVED, ignoreCase = true)`, placeholder exempt). Message: if `foundInventoryItem.transferId` is non-blank → "This device is reserved for a stock transfer"; else → "This device is reserved. Change its status in the inventory system before selling." `InventoryItem` has no `transferId` property; read it from the document data that populates `foundInventoryItem` (add a private `foundTransferId: String` captured during the search, or add an optional `transferId` field to `InventoryItem` — adding the field is the smaller change and is additive; the Firestore mapper ignores unknown fields so existing reads are unaffected).
- **Inside the save transaction** (~line 1692): after the "already been sold" race check add the same `Reserved` check throwing `IllegalStateException("This device is reserved and cannot be sold")`.

### 24. `app/src/main/java/com/techcity/techcitysuite/InventoryItem.kt`
Additive: `val transferId: String = ""` (only if the approach in file 23 is taken; otherwise unchanged).

---

## Files Explicitly Unchanged

- `BarcodeScannerActivity.kt` and `activity_barcode_scanner.xml` — reused as-is.
- `PhoneInventoryListActivity.kt`, `ReconciliationDetailActivity.kt`, `ManufacturerDetailActivity.kt` — used as templates only; reconciliation queries already exclude `Reserved` via `whereIn("status", On-Hand, On-Display)`.
- `AccessoryTransactionActivity.kt` / `AccessoryTransactionListActivity.kt` — sales and their reversal are untouched; in-transit accessory stock sits in the origin's `reserved` bucket (Amendment 1), which sales never read.
- `StoreLocationHelper.kt`, `AppSettingsManager.kt`, `AuthManager.kt` — read only.
- Firestore security rules.

---

## Implementation Order

1. `Constants.kt`, `StockTransferModels.kt`, `strings.xml`.
2. Program Settings toggle (layout + activity) and Menu card (layout + activity) with the card temporarily opening nothing; `AndroidManifest.xml` entries.
3. `StockTransferRepository.kt`: `classifyScan`, `readOriginStock`, audit helper, `createTransfer`.
4. Layouts: list, item card, new-transfer screen, section header, phone row, accessory row, both dialogs. `TransferItemsAdapter.kt`.
5. `StockTransferListActivity.kt` (list + password + navigation; delete stubbed).
6. `NewStockTransferActivity.kt` (scan → classify → draft → create dialog → create). First end-to-end test: create a transfer, check the `stock_transfers` doc, phone status `Reserved` + `transferId`, origin counters, audit entries.
7. `StockTransferRepository.kt`: `verifyPhone`, `receiveAccessory`, `confirmTransfer`, `deleteTransfer`, `completeWithDiscrepancies`.
8. `StockTransferDetailActivity.kt` + layout (listener, role, scan/verify/receive, manual verify, Confirm, kebab discrepancy flow). Wire delete into the list.
9. `DeviceTransactionActivity.kt` sale guard (+ `InventoryItem.transferId`).
10. `./gradlew assembleDebug`; manual test matrix (below).

**Manual test matrix:** create with 2 phones + 2 SKUs (one SKU needing onDisplay spill-over); verify Sold / Reserved / other-store / Techcity / box-barcode / unknown scans are rejected with the right message; receive on a device set to the destination store (and confirm a third-store device sees read-only); partial receive then Complete with discrepancies; delete a pending transfer; attempt delete on a completed one; try selling a reserved phone; create with a destination counter doc that does not exist yet. **Amendment 1:** after Create check the origin doc shows `onHand`/`onDisplay` down and `reserved` up by the same total; after Confirm, Delete and Complete with discrepancies check the origin `reserved` is back to its pre-transfer value and each audit entry's `delta.reserved` matches.

---

## Dependencies / Libraries

None new. Firestore (`runTransaction`, `FieldValue.increment/delete/serverTimestamp`, `FieldPath.of`), Material components (`ExtendedFloatingActionButton`, `MaterialCardView`, exposed dropdown), `ItemTouchHelper`, coroutines, and ViewBinding are all already in use.

---

## Migration and Data Considerations

- **New collection** `stock_transfers`; nothing to migrate.
- **`inventory` documents** gain `transferId` only while reserved by a transfer; it is removed with `FieldValue.delete()` on Confirm / Delete / discrepancy, so completed records look exactly like before except for the status/location change.
- **`accessory_inventory`** destination docs may be created by the mobile app for the first time (lazy creation with all five buckets at 0, `sku`, `locationId`, `locationName`, `lastUpdated`), matching the web app's document shape.
- **`accessory_inventory_adjustments`** entries from the mobile app use `source = "mobile-stock-transfer"` and the signed-in Firebase email/UID.
- **Behaviour change:** phones with `status = Reserved` set by the web app can no longer be sold from the app (spec round-3 decision). Communicate to staff.
- **Amendment 1 rollout:** transfers created by the first build never added to the origin's `reserved`; completing one on the amended build would push `reserved` negative. No compatibility flag is written — the owner will ensure **no pending transfer exists** when the amended build is installed.
- Web app follow-ups (spec Cross-App Coordination): respect `transferId`, display the new audit source, optionally view `stock_transfers`.

---

## Risks and Things to Watch

1. **Firestore 500-write limit on Complete with discrepancies.** With the spec's 100-SKU cap the worst case is 551 writes. This plan sets `TRANSFER_MAX_ACCESSORY_SKUS = 80` (worst case 471). If you want to keep 100, say so and the cap stays, with the accepted risk that an extreme discrepancy completion errors out and must be retried after resolving some SKUs by scanning.
2. **Reads before writes.** Every repository transaction must perform all `tx.get` calls before the first `tx.set/update/delete`; the plan's step lists are ordered that way. Firestore also retries transactions on contention; all logic inside the lambda must be idempotent (no side effects outside the transaction).
3. **Field paths with hyphens.** All nested updates into `accessories.<sku>.*` must use `FieldPath.of(...)`, never string concatenation.
4. **Exact-name matching for roles.** `toLocation` vs the device's `store_location` is compared exactly (both from `accessory_locations.name`). Phone `location` matching stays case-insensitive (web-app data).
5. **`transferId` cleared with `FieldValue.delete()`** rather than `""` so inventory docs do not accumulate an empty field; the sale guard treats both absent and blank as "not a transfer".
6. **Verified count drift.** `phoneVerifiedCount` / `accessoryReceivedCount` are maintained by increments inside the same transaction as the item update; the detail screen computes "fully verified" from the item maps, not from the counters, so the counters are display-only.
7. **ViewBinding id budget.** New layouts are small; no file approaches the limit noted for the End of Day layout.
8. **Scanner one-shot on large transfers** is accepted by the owner; the size cap message is shown before launching the scanner.
9. **Device sale guard placement.** The pre-validation block references `foundInventoryItem!!`; keep the new check adjacent to the Sold check and preserve the placeholder exemption. Do not touch any other validation in that method.
10. **Scope discipline.** Only the files listed above change. No refactors of the reconciliation activities, the accessory sale code, or the scanner.

---

## Amendment 1 — In-transit accessories held in the origin's `reserved` bucket

Approved after the first build (spec Resolved Questions round 4). Only `StockTransferRepository.kt` changes; no UI, model, constant or manifest changes.

- `createTransfer`: the origin counter update also does `reserved = increment(takeFromOnHand + takeFromOnDisplay)`; the audit entry carries `deltaReserved`.
- `confirmTransfer`: additionally reads the origin counter docs and, after the destination writes, calls `returnToOrigin(..., onHandDelta = 0, onDisplayDelta = 0, reservedDelta = -quantity)` per SKU.
- `completeWithDiscrepancies`: `returnToOrigin` is now called for every SKU with `reservedDelta = -quantity` and `onHandDelta = missing`; the `missingQuantity` update stays conditional on `missing > 0`.
- `deleteTransfer`: `returnToOrigin` gains `reservedDelta = -quantity`; the "nothing to return" short-circuit is removed because the release always applies.
- `returnToOrigin`: new `reservedDelta` parameter; when the origin doc exists the update includes `reserved = increment(reservedDelta)`; when it is missing the release is skipped (no negative counters) and the lazy-create path runs only if there are units to return.
- `addAuditEntry`: new `deltaReserved: Long = 0` parameter folded into `after` and `delta`.
- Rollout condition: no pending transfers at install time (see Migration).
