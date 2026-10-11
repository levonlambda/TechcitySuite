# Feature Spec: Stock Transfer Between Branches

## Description

Add a **Stock Transfer** module that lets one branch send phones (and tablets/laptops) and accessories to another branch, and lets the receiving branch verify what arrived before the inventory records are moved.

The module is a new **Stock Transfer** button on the main menu, shown or hidden by a new **Stock Transfer** toggle in Program Settings (same pattern as Phone Inventory). It opens a list screen modelled on the Phone Inventory (reconciliation) list: a header, a list of transfer cards, and a **+** floating button at the bottom right. Creating a transfer is password-gated with the same settings password used by Phone Inventory.

A transfer has two halves:

1. **Sending (origin branch).** Staff scan the items leaving the store: phone IMEIs / serial numbers one by one, and accessory product barcodes with a quantity. They pick the destination branch and tap **Create**. The transfer is saved as **pending**. At that moment each phone's inventory status becomes **Reserved** and the accessory quantities are **moved out of the origin's `onHand` / `onDisplay` into the origin's `reserved` bucket** (in transit, still counted as the origin's stock until received).
2. **Receiving (destination branch).** When the goods arrive, staff at the destination open the transfer, scan each phone and each accessory barcode (entering the quantity received) to verify them. Once everything is verified, the **+ Scan** button becomes **Confirm**. Confirming marks the transfer **completed** and moves the inventory: each phone's inventory record now belongs to the destination as **On-Hand**, and accessory quantities are **added to the destination's `onHand` counters**.

Phones are identified by IMEI or serial number (unique per unit). Accessories have no per-unit identity, so they are tracked by SKU and quantity. A strict rule applies when scanning: a barcode that belongs to a phone box is rejected with a message asking the user to scan the IMEI or serial number instead; only accessories may be added by product barcode.

A pending transfer can be **deleted** (password-gated), which puts everything back the way it was at the origin. A completed transfer can never be deleted. If something is lost in transit, the destination can **Complete with discrepancies** (password-gated), which completes the verified items and returns the missing ones to the origin's records.

This feature touches the shared `inventory` (phones) and `accessory_inventory` collections, which are also written by the web app. See **Cross-App Coordination**.

---

## User Stories / Use Cases

1. **As origin-branch staff**, I want to scan the phones and accessories I am sending to another branch and record where they are going, so there is a clear list of what left my store.
2. **As origin-branch staff**, when I scan a phone box's product barcode by mistake, I want the app to tell me to scan the IMEI or serial number instead, so every phone in a transfer is identified by its unique number.
3. **As origin-branch staff**, when I scan an accessory barcode, I want to enter how many units I am sending, so I don't have to scan the same barcode 20 times.
4. **As destination-branch staff**, I want to open the incoming transfer and scan each item as I unpack it, so I can confirm exactly what arrived.
5. **As destination-branch staff**, when I scan an accessory barcode, I want to enter the quantity I received for that SKU, so verifying a box of 50 cables takes seconds.
6. **As destination-branch staff**, once every item is verified, I want a single **Confirm** action that moves the stock into my branch's inventory, so my phone inventory and accessory counters are correct.
7. **As destination-branch staff**, when an item never arrives, I want to complete the transfer anyway and have the missing items clearly recorded, so a lost item is identified immediately instead of leaving the transfer stuck.
8. **As the owner**, I want every device to be able to see all transfers, but only the receiving branch's device to be able to verify and confirm them.
9. **As the owner**, I want a pending transfer to be deletable (password-gated) if it was created by mistake, with the stock put back at the origin, and I never want a completed transfer to be deletable.
10. **As the owner**, I want a stock transfer to never allow a phone to be sold twice or an accessory count to go negative, even while goods are in transit between branches.
11. **As the owner**, I want the Stock Transfer button to be hidden on devices that don't need it (toggle in Program Settings).

---

## UI/UX Flow

### A. Program Settings — Stock Transfer toggle

1. In **Program Settings → Feature Settings**, add a new switch **Stock Transfer** with helper text "Show Stock Transfer in main menu". Default: **off**.
2. Saved with the other feature flags in SharedPreferences (`TechCitySettings`).

### B. Main menu — Stock Transfer button

1. A new **Stock Transfer** card/button is added to the menu grid (same card style as Phone Inventory), with a short subtitle such as "Transfer stock between branches".
2. The card is visible only when the toggle is on (re-checked on resume, like the other feature cards).
3. Tapping it opens the **Stock Transfer List** screen.

### C. Stock Transfer List screen (modelled on Phone Inventory list)

1. **Header** (TechCity blue): title "Stock Transfer", subtitle "Branch stock transfer records".
2. **List of transfer cards**, newest first, **no filter chips**. Each card shows:
   - Date created and a **status badge**: `Pending` (in transit) or `Completed`. A completed transfer that had missing items also shows a small **Discrepancies** badge.
   - **From → To** branch names.
   - A **direction hint** for this device: "Outgoing" when this device's store location is the origin, "Incoming" when it is the destination, nothing when it is neither.
   - Phone count and verified count (e.g. "Phones 12 / 12 verified"), accessory unit count and received count (e.g. "Accessories 80 / 65 received").
   - "Created at h:mm a by <user>"; when completed, "Received by <user>".
3. **Visibility rule:** **all transfers are listed on every device**, regardless of the device's store location. (This is deliberately simpler than the one-store rule used by other modules.)
4. **+ floating button** (bottom right). Tapping it:
   - If the device has no store location configured: show the existing "Please configure Store Location in Settings first" message and stop.
   - Otherwise show the existing **password dialog** (`dialog_password`, verified with the settings password). On success, open the **New Stock Transfer** screen.
5. **Tapping a card** opens the **Stock Transfer Detail** screen. No date-based password gate (a transfer may legitimately take days to arrive). Completed transfers open read-only.
6. **Long-press a card** → password dialog → **Delete Transfer** confirmation (shows from/to, item counts, and "This will return the items to <origin>. This action cannot be undone."). Allowed only while the transfer is **pending**; long-pressing a completed transfer shows "Completed transfers cannot be deleted". Deleting reverts the origin's records (Business Rules §On Delete) and removes the document.
7. If no store location is configured, the list still shows all transfers (read-only), but creating, verifying, confirming, and deleting are blocked with the configure-first message.

### D. New Stock Transfer screen (origin, before Create)

A full screen (not a dialog), because the item list can be long.

1. **Header:** "New Stock Transfer", "From: <this device's store location>", running counts ("3 phones · 45 accessory units").
2. **Body:** the draft item list in two sections:
   - **Phones** — one row per unit: manufacturer, model, RAM/storage, color, IMEI 1 (and IMEI 2 / serial when present), current status (On-Hand / On-Display). Rows use the same look as the reconciliation phone card.
   - **Accessories** — one row per SKU: manufacturer + model, SKU, barcode, **quantity** to transfer, and "available at origin" (onHand / onDisplay) for reference.
   - Empty state: "Tap + Scan to add phones or accessories".
3. **Bottom-right buttons:** **Create** (to the left) and **+ Scan** (extended FAB, same look as the reconciliation Scan FAB). Create is disabled while the list is empty.
4. **+ Scan** opens the existing `BarcodeScannerActivity` with the **Take Photo** capture button (photo-capture mode, as used today). Default scanner mode (IMEI prioritised, product codes not filtered) so a phone box showing both an IMEI barcode and an EAN returns the IMEI. The scanner stays **one-shot**: it returns one value and closes; the user taps + Scan again for the next item.
5. **After a scan returns a value, the app classifies it** (exact match, in this order):
   1. **Phone unit** — the value equals `imei1`, `imei2`, or `serialNumber` of a document in `inventory`. Eligibility checks (Business Rules §Phone eligibility) run; if eligible, the phone is added to the Phones section with a short confirmation. If it is already in the draft: "Already in this transfer".
   2. **Phone box barcode** — the value equals the `barcode` field of an `inventory` document (the product/box barcode, not the IMEI). Rejected with a dialog: "This barcode belongs to a phone (<manufacturer model>). Please scan the IMEI or Serial Number instead." Nothing is added.
   3. **Accessory** — the value equals `barcode` of an active `accessory_products` document. A **Quantity dialog** opens (see D.6). On confirm, the SKU is added to the Accessories section. If the SKU is already in the draft, the dialog is pre-filled with the current quantity and the entered value **replaces** it.
   4. **Not found** — "Barcode not found. Phones must be scanned by IMEI or Serial Number; accessories must exist in the accessory catalog." (A phone box barcode that is not stored on any inventory record also lands here; accepted.)
6. **Quantity dialog (sending):** title "Transfer Quantity", the accessory name/SKU, the origin's current stock (onHand and onDisplay), a numeric field (default 1), Cancel / Add. Validation: whole number ≥ 1 and ≤ (onHand + onDisplay) at the origin. If the origin has no inventory record or zero stock for that SKU, the dialog explains this and the item cannot be added.
7. **Removing a draft item:** swipe left on a row removes it from the draft after a short confirmation.
8. **Create** opens the **Create Transfer dialog**, styled like `dialog_new_reconciliation` (centered icon, blue title, grey subtitle, outlined exposed-dropdown field, error text, progress bar, and a text **Cancel** button beside a filled blue **Create** button):
   - Title "Create Stock Transfer"; subtitle "Select the branch to transfer the stock to".
   - **Transfer To** dropdown: active store locations from `accessory_locations`, **excluding** this device's own store location. No default selection; Create is disabled until the list has loaded and a value is chosen.
   - A read-only summary line: "From <origin> · N phones · M accessory units".
   - **Create** validates, shows the progress bar, saves the transfer (see Business Rules §On Create), then closes the screen and returns to the list with "Stock transfer created for <destination>".
   - **Cancel** closes the dialog and keeps the draft.
9. **Leaving the screen** (back button) with a non-empty draft asks "Discard this transfer? Scanned items will be lost." The draft is never saved to Firestore until Create; no saved-draft mechanism (a draft does not survive the app being killed).

### E. Stock Transfer Detail screen (all devices)

Modelled on the reconciliation detail screen.

1. **Header:** back button, date created, **status badge**, "From <origin> → To <destination>", item counts, "Created by <user> at h:mm a".
2. **Summary card:** Phones — qty / verified; Accessories — units expected / units received; an overall progress line. On a completed-with-discrepancies transfer, a **Missing items** line and the missing rows highlighted in red.
3. **Item list** in two sections (Phones, Accessories), each row with a verification state:
   - Phone row: model details + identifiers; **Not Verified** / **Verified by <user>** (checkbox style, as in the reconciliation phone card). Tapping an unverified phone row on the destination device offers **manual verification** (see E.6).
   - Accessory row: name + SKU + barcode; **received / expected** (e.g. "35 / 50") and a green check when received equals expected.
4. **Scan FAB (bottom right):**
   - On the **destination** device (its store location equals `toLocation`) while the transfer is **pending**: **+ Scan**. When every phone is verified and every accessory's received quantity equals its expected quantity, the FAB changes to **Confirm** (different icon/label; same position).
   - On any other device: no FAB. The screen is a read-only progress view.
   - On **completed** transfers: no FAB, read-only.
5. **Receiving scan flow** (+ Scan on destination): opens the scanner (one-shot) with the **auto-match list** set to all expected identifiers (IMEI1 / IMEI2 / serials of listed phones plus the barcodes of listed accessories), the same way the reconciliation screens do. After a value returns:
   - Matches a listed **phone** → marked verified (user, time, which identifier was scanned). If already verified: "Already verified by <user>".
   - Matches a listed **accessory** barcode → **Quantity dialog (receiving)**: title "Received Quantity", shows expected and already-received, numeric field pre-filled with the **remaining** quantity, Cancel / Confirm. Validation: whole number ≥ 1 and ≤ remaining. The received count is increased by the entered amount (cumulative).
   - Matches a phone or accessory **not** in this transfer → "Item is not part of this transfer".
   - Phone box barcode → same "scan the IMEI instead" rejection as when sending.
6. **Manual verification** (destination, pending, unreadable label): tapping an unverified phone row → password dialog → confirmation dialog listing the phone's details (same as the reconciliation manufacturer screen) → the phone is marked verified with scanned type "Manual".
7. **Confirm** (destination, all verified): a confirmation dialog in the reconciliation-dialog style, "Confirm receipt of N phones and M accessory units from <origin>? This will move the stock to <destination>." Cancel / Confirm. **No password** is asked at this step. On Confirm the completion rules run (Business Rules §On Confirm); on success the header badge becomes **Completed** and the FAB disappears.
8. **Complete with discrepancies** (destination, pending, **not** fully verified): available from an overflow/menu action on the detail screen (not the FAB, to keep it deliberate). Flow: password dialog → a dialog listing every unverified phone and every accessory with a shortfall ("Cable X: 45 of 50 received, 5 missing") with the warning "Missing items will be returned to <origin>'s records. This cannot be undone." → Cancel / Complete. On Complete the discrepancy rules run (Business Rules §On Complete with discrepancies). The transfer becomes **Completed** with a **Discrepancies** badge.
9. The detail screen listens to the transfer document in real time so two staff members scanning on two devices see each other's progress (same approach as reconciliations).

---

## Data Model

### New collection: `stock_transfers`

One document per transfer. Firestore auto-ID.

Header fields:
- `reference` (string) — human-readable reference, e.g. `ST-<timestamp>-<3 digits>` (same style as accessory procurement references).
- `status` (string) — `pending` or `completed`.
- `hasDiscrepancies` (boolean) — true when completed via "Complete with discrepancies".
- `fromLocation` (string) — origin store name (device's configured store location).
- `fromLocationId` (string) — origin `accessory_locations` document ID (needed for accessory counter keys).
- `toLocation` (string), `toLocationId` (string) — destination name and ID. `toLocation` is what makes a device the "destination device".
- `date` (string, `M/d/yyyy`), `createdTime` (string, `h:mm a`), `createdAt` (Timestamp), `createdBy` (string, user from settings).
- `completedAt` (Timestamp), `completedBy` (string) — set on confirm / complete with discrepancies.
- Summary counters for list cards: `phoneCount`, `phoneVerifiedCount`, `accessoryUnitCount`, `accessoryReceivedCount`, `missingPhoneCount`, `missingAccessoryUnitCount`.

Item fields (maps keyed by ID so single-item updates can be made inside a transaction, like `verifiedItems` on reconciliations):
- `phones` (map, key = `inventory` document ID) → `{ manufacturer, model, ram, storage, color, imei1, imei2, serialNumber, originalStatus, originalLocation, verified (bool), verifiedBy, verifiedAt, scannedType, scannedValue, missing (bool) }`. `originalStatus` / `originalLocation` are snapshots taken at Create so a delete or a discrepancy can restore them exactly. `scannedType` is `IMEI1`, `IMEI2`, `Serial`, or `Manual`.
- `accessories` (map, key = SKU) → `{ sku, barcode, manufacturer, model, category, quantity, receivedQuantity, missingQuantity, originBuckets { onHand, onDisplay }, lastReceivedBy, lastReceivedAt }`. `originBuckets` records how many units were taken from each origin bucket so a delete or a discrepancy can put them back exactly.
- `phoneInventoryIds` (array of strings) — convenience list of the phone document IDs.

### Existing collection: `inventory` (phones) — status changes, one new field

- On **Create**: each listed phone's `status` is set to **`Reserved`** (the existing web-app value, exact spelling confirmed from a live record: capital R) and a new field `transferId` (the `stock_transfers` document ID) is stamped. `location` is unchanged until confirm. The web app also sets `Reserved` on its own for other reasons; those records have no `transferId`, which is how the two cases are told apart.
- On **Confirm**: `status` → `On-Hand`, `location` → destination store name, `lastUpdated` → today (`M/d/yyyy`), `transferId` cleared.
- On **Delete** (pending) and for **missing** phones on a discrepancy completion: `status` → `originalStatus`, `transferId` cleared, `location` unchanged (still the origin).

### Existing collection: `accessory_inventory` — counters moved

- Document IDs `{sku}__{fromLocationId}` (origin) and `{sku}__{toLocationId}` (destination).
- On **Create**: origin `onHand` is decremented first, then `onDisplay` for whatever `onHand` could not cover, and the origin's **`reserved` is incremented by the full quantity** (the units are in transit but still the origin's stock). The split is recorded in `originBuckets`.
- On **Confirm**: origin `reserved` is decremented by `quantity`; destination `onHand` is incremented by the received quantity. If the destination document does not exist it is **created** with `sku`, `locationId`, `locationName`, all buckets (`onHand`, `onDisplay`, `reserved`, `defective`, `sold`) initialised to zero before the increment, and `lastUpdated`.
- On **Delete** (pending): origin `reserved` is decremented by `quantity` and the origin buckets are incremented back exactly per `originBuckets`.
- On **Complete with discrepancies**: origin `reserved` is decremented by `quantity`; destination `onHand` is incremented by `receivedQuantity`; the origin gets `missingQuantity` back (into `onHand`).
- If the origin counter document no longer exists at completion (deleted in the web app), the `reserved` release is skipped and only the returned units are written into a lazily created document.
- All counter changes use Firestore transactions with atomic increments, never read-modify-write.

### Existing collection: `accessory_inventory_adjustments` — audit entries (adopted)

One entry per SKU per affected location, written in the same transaction as the counter change (Create, Confirm, Complete with discrepancies, Delete), with `before` / `after` / `delta`, a `reason` such as "Stock transfer ST-… to TC-Kapatagan" / "… from Main Branch" / "… deleted" / "… missing units returned", `source` = `mobile-stock-transfer`, `userEmail` = the signed-in account email, `userId` = the signed-in Firebase UID, `locationName` = the affected store's name.

### Existing collection: `accessory_locations` — read only

Source of the destination dropdown (`name`, `active`, document ID).

### Existing collection: `accessory_products` — read only

Barcode → SKU lookup (`barcode`, `internalSku`, `manufacturer`, `model`, `category`, `active`).

### SharedPreferences (`TechCitySettings`)

- New: `stock_transfer_enabled` (boolean, default false).
- Existing, read: `store_location`, `store_location_id`, `user`.

### Model classes

New Kotlin data classes for the transfer header, transfer phone item, and transfer accessory item (plain data holders, Firestore-mappable). `InventoryItem` is unchanged (it already has `barcode`, `status`, `location`). A new inventory status constant for `Reserved` is added beside the existing On-Hand / On-Display / Sold constants.

---

## Business Rules and Logic

### Access and visibility

1. **Feature flag.** The menu button appears only when `stock_transfer_enabled` is true.
2. **Store location required** for creating, verifying, confirming, completing with discrepancies, and deleting. Viewing never requires it.
3. **Origin is always the creating device's store location.** It is not selectable.
4. **Destination** must be an active store location other than the origin.
5. **Everyone can see every transfer.** The list and detail screens are not filtered by store location.
6. **Only the destination device can act on a pending transfer.** "Destination device" means the device's configured store location equals the transfer's `toLocation`. Verifying, manual verification, Confirm, and Complete with discrepancies are available only there. All other devices see read-only progress.
7. **Password gates.** Creating, deleting, manual verification, and Complete with discrepancies all require the settings password. The normal **Confirm** (fully verified receipt) does **not**.

### Scanning and classification (sending)

8. **Classification order** is phone identifier → phone box barcode → accessory barcode → not found, with exact (case-sensitive, trimmed) matching.
9. **Phones are only ever added by IMEI or serial number.** A value matching an inventory record's `barcode` field is rejected with the "scan the IMEI or Serial Number instead" message. A phone box barcode that is not stored on any inventory record gets the generic "not found" message (accepted).
10. **Phone eligibility** (checked at scan time against the live `inventory` document):
    - exists in `inventory` and is not archived;
    - `status` is `On-Hand` or `On-Display` (not `Sold`, not `Reserved`, nothing else);
    - `location` equals this device's store location (case-insensitive, matching the existing reconciliation rule for web-app-written values);
    - manufacturer is **not "Techcity"** (same exclusion as reconciliations);
    - not the placeholder IMEI; and
    - not already in this draft.
    Each failure has its own message, e.g. "This phone is marked Sold", "This phone is already reserved (transfer <reference>)", "This phone belongs to <location>, not <origin>", "Techcity units are excluded from stock transfers".
11. **Accessory eligibility:** active catalog product; origin stock (`onHand + onDisplay`) ≥ 1.
12. **Accessory quantity** must be a whole number from 1 to the origin's `onHand + onDisplay` at the time of adding. The same check is repeated inside the Create transaction, because stock may have been sold between scanning and creating.
13. **Duplicate accessory scan** replaces the quantity (dialog pre-filled with the current value). Duplicate phone scan is rejected.
14. **Draft is local only.** Nothing is written to Firestore until Create succeeds.
15. **Size cap.** A transfer may hold at most **150 phones and 100 accessory SKUs**; the + Scan button tells the user to split the shipment beyond that. (Keeps the Create / Confirm transactions within Firestore's 500-write limit.)

### On Create (origin)

16. **One atomic operation** (Firestore transaction) that:
    - re-reads every phone and verifies eligibility again (status still On-Hand/On-Display, location still origin) — if any phone fails, the whole create fails with a message naming the item;
    - re-reads every origin accessory counter and verifies quantity ≤ `onHand + onDisplay` — if any fails, the whole create fails naming the SKU;
    - creates the `stock_transfers` document with `status = pending`;
    - sets each phone's `status = Reserved` and `transferId`;
    - **moves the origin accessory counters** (`onHand` first, then `onDisplay`) **into the origin's `reserved` bucket**, records the split in `originBuckets`, and writes one audit entry per SKU.
17. **Why the origin is adjusted at Create:** the goods physically leave the sellable stock at that moment. While pending, the in-transit quantity is held in the origin's `reserved` bucket (so the web app shows it as reserved stock of the sending branch) and mirrored on the transfer document.
18. **Reserved phones cannot be sold.** The device-sale flow's existing "already Sold" guard is extended to block **every** phone whose `status` is `Reserved`, whatever the reason. The message names the cause: "This device is reserved for stock transfer <reference>" when `transferId` is set, otherwise "This device is reserved. Change its status in the inventory system before selling." (This is a behaviour change for phones the web app reserved: today they can still be sold from the app.) Reconciliations already select only On-Hand / On-Display, so reserved phones are automatically excluded from the origin's counts.

### Receiving (destination)

19. **Verification is per item and transactional**, like reconciliation verification: the item entry in the `phones` / `accessories` map and the summary counters are updated together, so two devices scanning at once cannot lose an update.
20. A phone can be verified once (by scan or manually). An accessory's received quantity is cumulative and can never exceed its expected quantity.
21. **Completion condition for Confirm:** every phone `verified = true` and every accessory `receivedQuantity == quantity`. Only then does the FAB become **Confirm**.
22. **Complete with discrepancies** is available whenever the transfer is pending and at least one item is unverified or short. Every unverified phone is treated as **missing**; every accessory shortfall (`quantity − receivedQuantity`) is treated as **missing units**.

### On Confirm (destination, fully verified)

23. **One atomic operation** (Firestore transaction) that:
    - re-reads the transfer and checks it is still `pending` and fully verified (another device may have completed or deleted it);
    - for every phone: re-reads the `inventory` document, checks it is still `Reserved` with this `transferId`, then sets `status = On-Hand`, `location = toLocation`, `lastUpdated = today`, clears `transferId`;
    - for every accessory: decrements the origin `reserved` by `quantity`, increments the destination `onHand` by `quantity` (creating the destination counter document if missing), and writes one audit entry per SKU per location;
    - sets `status = completed`, `hasDiscrepancies = false`, `completedAt`, `completedBy`.
24. Phones always arrive as **On-Hand** regardless of their original status.
25. **Received units never touch `sold`** at either location.

### On Complete with discrepancies (destination, password-gated)

26. **One atomic operation** that:
    - re-reads the transfer and checks it is still `pending`;
    - **verified phones:** same as Confirm (On-Hand at destination);
    - **missing phones:** `status = originalStatus`, `transferId` cleared, `location` stays the origin; `missing = true` on the transfer item;
    - **accessories:** origin `reserved` −= `quantity`; destination `onHand` += `receivedQuantity`; origin `onHand` += `missingQuantity` (returned to the back room regardless of which bucket it came from); `missingQuantity` stored on the item; one audit entry per affected SKU and location (the origin's release and return are one write and one audit entry);
    - sets `status = completed`, `hasDiscrepancies = true`, `completedAt`, `completedBy`, and the missing counters.
27. Returning missing items to the origin's records is a bookkeeping choice: the item is "unaccounted for" and the origin is where it was last known to be. The Discrepancies badge and the missing rows are the signal for the owner to investigate.

### On Delete (pending only, password-gated)

28. **One atomic operation** that re-reads the transfer, checks it is still `pending`, restores every phone (`status = originalStatus`, `transferId` cleared — only if the phone is still `Reserved` with this `transferId`), **releases the origin `reserved` units and adds back the origin accessory counters** exactly per `originBuckets` with one audit entry per SKU, and **deletes** the `stock_transfers` document.
29. Deleting is allowed from any device (everyone can see the transfer) but always requires the password.
30. A completed transfer (with or without discrepancies) cannot be deleted. Reversing a completed transfer is done by creating a new transfer in the opposite direction.

### General

31. All timestamps use the app's GMT+8 convention; display strings follow the existing `M/d/yyyy` and `h:mm a` formats.
32. The `user` from Program Settings is stamped as `createdBy` / `verifiedBy` / `completedBy`; "Unknown" when blank (as elsewhere).

---

## Edge Cases and Error Handling

1. **No store location configured** — list is read-only; + Scan / Create / Confirm / Delete are blocked with the configure-first message.
2. **Only one active store location exists** — the destination dropdown is empty; the Create dialog shows "No other branch available" and cannot proceed.
3. **Location list fails to load (offline)** — the Create dialog shows an error and keeps Create disabled; the draft is kept.
4. **Scanner cancelled / returns empty** — nothing changes.
5. **Scanned value matches more than one phone** (duplicate IMEI data) — use the first match that passes eligibility; if none pass, show the eligibility message.
6. **Phone exists but `location` differs only by case/spacing from the origin name** — treated as a match (case-insensitive, trimmed), consistent with reconciliation.
7. **Phone sold or moved between scanning and Create** — the Create transaction re-checks and fails with the offending item named; the draft stays so the user can remove that row and retry.
8. **Accessory sold between scanning and Create** so the quantity now exceeds stock — Create fails naming the SKU; the user adjusts the quantity.
9. **Accessory with no origin inventory document** — cannot be added ("No stock recorded at <origin> for this item").
10. **Destination accessory document missing at confirm** — created inside the confirm transaction with zeroed buckets, then incremented.
11. **Received quantity entered larger than remaining** — rejected by the dialog; the remaining amount is shown.
12. **Phone verified, then the transfer is deleted** — the delete resets the phone's status; the transfer document is gone (hard delete, like reconciliations).
13. **Two devices confirm simultaneously** — the transaction's status re-check makes the second one fail with "This transfer was already completed".
14. **Transfer completed or deleted while another device has the detail open** — the real-time listener updates the badge, or shows "This transfer was deleted" and returns to the list.
15. **Phone's inventory document deleted or changed by the web app while reserved** (no longer `Reserved` / different `transferId`) — Confirm fails naming the phone and leaves the transfer pending; the owner resolves it in the web app or completes with discrepancies (that phone is then treated as missing and its inventory record is left untouched).
16. **Size cap reached** — + Scan is refused with "Maximum 150 phones / 100 accessory items per transfer. Please create another transfer for the rest."
17. **Phone list display** uses the snapshot data stored on the transfer document (no extra `inventory` reads, and the record stays stable even if the phone is later edited).
18. **Network failure during Create / Confirm / Complete with discrepancies / Delete** — the transaction fails as a whole; an error is shown; nothing is partially applied.
19. **Device whose store location changes** between creating and receiving — "destination device" is evaluated from the current setting each time the detail screen opens.
20. **Destination store deactivated while a transfer is pending** — the transfer still completes (names and IDs are stored on the document); the location simply no longer appears in dropdowns.
21. **A "Reserved" phone that was reserved by the web app for another reason** (no `transferId`) — not eligible for a transfer ("This phone is reserved"), not sellable from the app (Rule 18), and never touched by Confirm / Delete because those only act on phones carrying this transfer's `transferId`.
22. **Accessory received quantity equals expected but a phone is missing** — Confirm is not offered; Complete with discrepancies handles it.

---

## Out of Scope

- Editing a pending transfer's item list after Create (delete and re-create instead).
- Transfers of `reserved` or `defective` accessory stock (only `onHand` / `onDisplay` can be sent; the origin's `reserved` bucket is used solely to hold in-transit units).
- Continuous scanning (scanner stays one-shot).
- Notifications to the destination branch.
- List filter chips.
- Transfer history reporting / End of Day integration.
- Changes to the web app (listed under Cross-App Coordination as action items).

---

## Cross-App Coordination (important)

The web app ("Tech City Inventory System") is the canonical schema owner for `inventory` and `accessory_inventory`. This feature introduces:

1. **Phones set to `Reserved` by the mobile app**, with a new `transferId` field on `inventory` documents. The web app should not let a user change a phone's status or location while `transferId` is set, and should show the transfer reference in the phone's detail.
2. **A new collection `stock_transfers`.** The web app does not need to write it, but it should at least be able to view it.
3. **Accessory counters moved by the mobile app**, each with an `accessory_inventory_adjustments` entry whose `source` is `mobile-stock-transfer`. The web app's audit view should display this source.
4. The accessory bucket semantics already diverge from the schema doc (sales take `onDisplay` first). This feature's "transfer takes `onHand` first, arrives as `onHand`" rule should be mirrored if the web app ever gains its own transfer screen.
5. **In-transit accessory units sit in the origin's `reserved` bucket** while a transfer is pending (amendment, round 4). The web app has no per-unit marker to tell them apart from units it reserved itself; the owner has accepted this.

---

## Resolved Questions (round 1)

1. **Visibility:** every device sees every transfer; only the device whose store location is the destination can verify and confirm.
2. **In-transit phone status:** use the existing **Reserved** status, not a new `In-Transit` value.
3. **Origin accessory decrement:** at **Create**.
4. **Buckets:** origin takes from `onHand` first then `onDisplay`; destination receives into `onHand`.
5. **Phone box barcode detection:** most phone records carry the `barcode` field; a box barcode not in the database just gets the generic "not found" message.
6. **Discrepancies:** option (b), a password-gated **Complete with discrepancies**.
7. **Cancel → Delete:** no cancelled status. A pending transfer can be deleted (password), reverting phones to their original status and returning accessory units to the origin. Completed transfers cannot be deleted.
8. **Re-validation at Create / Confirm:** keep.
9. **Scanner:** one-shot.
10. **Size cap:** 150 phones / 100 SKUs accepted.
11. **Arrival status:** always On-Hand.
12. **Techcity manufacturer:** excluded from transfers.
13. **Notifications:** none.
14. **Manual verification at receiving:** yes, password-gated like reconciliation.
15. **List:** single newest-first list, no filters.

---

## Explanations Requested

### Audit entries (`accessory_inventory_adjustments`)

The web app keeps a **history log** collection called `accessory_inventory_adjustments`. Every time someone changes an accessory quantity in the web app (for example moving 5 chargers from the back room to the display), the web app writes one log entry **in the same transaction** as the counter change. The entry records the SKU, the location, the counts **before** and **after**, the **delta**, a **reason**, who did it, and a `source` tag. The web app has an audit screen that shows this history per product.

The schema reference says any app that changes counters (other than recording a sale) must write the same log entry, so the history stays complete. Sales are the one exception: the `sold` counter is their trail.

**Example.** Transfer `ST-1001` sends 10 Anker chargers from Main Branch to TC-Kapatagan.
- At Create, the origin's counter goes `onHand 24 → 14`. A log entry is written: SKU `ACC-CHG-0001`, Main Branch, before `onHand 24`, after `onHand 14`, delta `−10`, reason "Stock transfer ST-1001 to TC-Kapatagan", source `mobile-stock-transfer`.
- At Confirm, the destination's counter goes `onHand 0 → 10`. A second entry: TC-Kapatagan, before `0`, after `10`, delta `+10`, reason "Stock transfer ST-1001 from Main Branch".

**Without** these entries, the web app's history for that charger just shows the count dropping by 10 at Main Branch and rising by 10 at Kapatagan with no explanation. **With** them, the owner can see exactly why. The cost is a couple of extra writes per SKU per transfer. Adopted (round 2).

### Draft loss

Before **Create** is tapped, the New Stock Transfer screen's list exists only in the app's memory on that phone. Nothing is in Firestore yet.

**Example.** Staff scan 20 phones and 6 accessory barcodes, then accidentally press the Back button (or take a call and Android closes the app to free memory). Those 26 scans are gone and they start over. The spec adds a "Discard this transfer?" prompt on Back so an accidental tap does not lose the work. It does not protect against the app being killed by Android. Accepted (round 2).

### Accessory re-scan semantics and removing rows

**Re-scan.** Staff scan a charger's barcode and enter **10**. Later in the same draft they scan the same charger barcode again and enter **5**. Two possible meanings:
- **Replace:** the charger line now says 5 (they were correcting the number).
- **Add:** the charger line now says 15 (they found another box of 5).

The spec chose **replace**, with the quantity dialog pre-filled with the current value (10) so staff can see what is there before changing it. Confirmed (round 2).

**Removing rows.** Staff scan the wrong phone, or an accessory they decide not to send. They need a way to take it off the list before Create. The spec uses swipe-left on the row, with a short confirmation. Confirmed (round 2).

### Confirm password

"Confirm" meant the **receiving** step: the button the destination device taps once everything is verified, which moves the stock. Creation is already password-gated as you requested. Decision (round 2): no password on the receiving Confirm.

---

## Resolved Questions (round 2)

1. **`Reserved` value:** confirmed from a live `inventory` record: `status: "Reserved"` (capital R). The same record shows `barcode` populated, `location: "TC-Kapatagan"`, and `lastUpdated` in `M/d/yyyy`, all matching the spec's assumptions. The web app sets `Reserved` on its own for other reasons; transfer-reserved phones are distinguished by the `transferId` field.
2. **Password on receiving Confirm:** no.
3. **Draft loss:** the discard prompt is enough.
4. **Re-scan = replace; swipe-left to remove:** yes to both.
5. **Audit entries:** yes, written for every transfer counter change.
6. **Missing accessory units:** returned to the origin's `onHand`.

---

## Resolved Questions (round 3)

1. **Selling a `Reserved` phone:** **every** `Reserved` phone is blocked from sale in the Device Transaction flow, not only transfer-reserved ones. A phone reserved in the web app must have its status changed there before it can be sold from the app. The block message distinguishes the two cases (Rule 18).

---

## Resolved Questions (round 4 — amendment after the first build)

1. **In-transit accessories were invisible to the web app.** The first build removed units from the origin's `onHand` / `onDisplay` at Create and added them to the destination's `onHand` at Confirm; nothing touched `reserved`, so the web app showed no reserved stock for a pending transfer. Decision: at Create the same units are **added to the origin's `reserved` bucket**, and every completion path (Confirm, Complete with discrepancies, Delete) **subtracts `quantity` from the origin's `reserved`** in the same transaction as its other counter writes. While pending, reserved units count as the **origin** branch's stock.
2. **Rollout:** no compatibility flag is written. The owner will ensure **no pending transfer exists** when the new build is installed, since a transfer created by the old build never added to `reserved` and completing it on the new build would drive the origin's `reserved` negative.
3. **Web-app reserved logic:** no coordination needed beyond item 5 of Cross-App Coordination; the owner has confirmed the web app's own reserved handling does not conflict.

---

## Open Questions

None. The spec is ready for the technical plan. 
