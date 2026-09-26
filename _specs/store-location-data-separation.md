# Feature Spec: Store Location Data Separation

## Description

Make each installed copy of the app behave as a **separate instance per store location**. The instance is determined by the **Store Location** chosen in **Program Settings → User Settings → Store Location** (the dropdown populated from the `accessory_locations` collection). Devices configured with different store locations keep their operational records separate: each device only sees, creates, and reports on records that belong to its own store location.

Records continue to be stored in the **same Firestore collections** as today. Separation is achieved by **tagging every record with its store location at creation time** and **filtering every list, view, and report by the device's configured store location**. There is no per-store collection and no data migration of collections.

**Scoped (filtered) by store location:**
- Phone Inventory (reconciliation records)
- Device Transactions
- Accessory Transactions
- Service Transactions
- Ledgers
- End of Day (report generation, saved reports, and report list)
- Expenses

**Shared across all store locations (NOT filtered, unchanged):**
- Account Receivable
- Financing Accounts

Today none of the list screens filter by location. Transactions already stamp the store location name onto each document (`userLocation` on device/accessory/service transactions; `storeLocation` on expenses), but the End of Day report, its saved `daily_summaries` documents, and inventory reconciliations carry no store location at all. This feature closes those gaps and turns the stored location **name** into the visibility boundary. No new location ID fields are introduced; the existing name fields are the only key.

**Legacy records:** records that predate location stamping (no location field, or an empty one), legacy "All" reconciliations, and End of Day reports saved under the old date-only document ID are treated as belonging to the **primary store location**: the `accessory_locations` document whose `isPrimary` flag is true (the original single store). Devices configured with that store location see them; other devices do not. Nothing legacy is modified or deleted.

---

## Feature 1: Store Location as the Instance Key

### User Story

**As a store owner with more than one branch**, I want each device to be locked to one store location so that staff at one branch only see and affect that branch's records, while the whole business still shares one Firestore database.

### UI/UX Flow

1. Open **Program Settings → User Settings → Store Location**.
2. The Store Location field is a **dropdown of active store locations** (from `accessory_locations`, as today). The field must **only accept a value picked from the dropdown**; free-typed text is no longer accepted, so a saved location is always a canonical name from the collection.
3. When the user changes the Store Location to a different value and taps **Save**, a **confirmation dialog** explains that the device will switch to showing only records for the newly selected store, and that existing records are not moved. Confirming saves; cancelling keeps the previous value.
4. After saving, every location-scoped screen uses the new store location the next time it is opened or resumed.
5. If no Store Location is configured, every location-scoped screen (listed in the Description) shows a clear message ("Please configure Store Location in Settings first") and shows **no records**. Creating any location-scoped record is blocked with the same message. Device and accessory transactions already block this way; the rule is extended to service transactions, expenses, reconciliations, and End of Day generation.

### Business Rules

- The device's store location is the value saved in Program Settings (SharedPreferences `store_location` name). This is the single source of truth for the instance on that device. The existing `store_location_id` preference continues to be saved for the accessory inventory stock keys, but it is not used for visibility.
- **Matching key:** a record belongs to a store location when the record's stored **location name** equals the device's configured store location name (exact, case-sensitive match, since both come from the same canonical dropdown list). This keeps all existing records (which carry only the name) visible without migration.
- **Primary store location:** the active `accessory_locations` document with `isPrimary` set to true. When Program Settings is saved, the app records alongside the chosen store location whether that location is the primary one. This "is primary" status is what enables the legacy rule on that device.
- **Legacy rule:** a record whose location field is missing or empty belongs to the primary store location. Only devices configured with the primary store location see such records. If no location is flagged primary, no device sees legacy records; if more than one is flagged, each of them is treated as primary.
- Changing the store location never moves, re-tags, or deletes any record. Records keep the store location they were created under.
- Store location names are canonical values from `accessory_locations`. Renaming a location is **not supported**; a renamed location would orphan its records.

---

## Feature 2: Phone Inventory — Location-Scoped Reconciliations

### User Story

**As branch staff**, when I create an inventory reconciliation I want to pick the store location from a dropdown, and I want only devices assigned to that store location to be able to see and open that reconciliation.

### UI/UX Flow

1. Open **Phone Inventory**. The list shows **only reconciliation records whose location equals the device's store location**. Reconciliations created for other locations are not listed and cannot be opened.
2. Tap the **add** button. The existing **password gate** stays: only a user who enters the settings password can create a reconciliation. The **New Reconciliation** dialog opens.
3. The **Location** field is now a **dropdown of active store locations** (same source and labels as Program Settings). It is **pre-selected to the device's configured store location**, and the user may pick any other listed store location. Free text and the previous "All" option are removed; the helper text "Enter 'All' to include all locations" is replaced with a hint such as "Store location for this reconciliation".
4. The **Inventory Status** dropdown (All / On-Display / On-Hand) is unchanged.
5. Tap **Create**. The reconciliation is created for the selected location:
   - Only inventory items whose `location` matches the selected store location (and match the status filter, excluding the "Techcity" manufacturer, as today) are included.
   - The reconciliation document stores the selected store location name in the existing `location` field.
6. If the selected location is different from the device's own store location, the reconciliation is created but will **not appear** on this device's list (it belongs to the selected location). A short message informs the user of this after creation.
7. Opening a reconciliation (tap, or password-gated tap for past dates) and the reconciliation detail, manufacturer detail, and barcode verification screens behave as today, but if a reconciliation's location does not match the device's store location (e.g. reached via a stale screen), the app shows "This reconciliation belongs to another store location" and returns to the list.
8. Long-press delete remains available only for reconciliations visible on this device.

### Business Rules

- Reconciliation visibility: the reconciliation's stored location name equals the device's store location name.
- Creating a reconciliation (for any store location) requires the settings password, as today.
- The Location dropdown lists the same active store locations as the Program Settings dropdown.
- If no inventory items match the selected location and status, creation fails with "No inventory items found matching criteria" (unchanged).
- Inventory item `location` matching during item selection stays **case-insensitive**, as today, because inventory items are created by the web app and may differ in casing.
- Existing reconciliations with location "All" are **kept** in Firestore and are never deleted by this feature. They are treated as legacy records and are visible only on devices configured with the primary store location.

---

## Feature 3: Device, Accessory, and Service Transaction Lists

### User Story

**As branch staff**, I want the Device Transactions, Accessory Transactions, and Service Transactions screens to show only transactions made at my store location.

### UI/UX Flow

1. Open any of the three transaction list screens. The list for the selected date shows **only transactions whose stored store location equals the device's store location**.
2. Date navigation, type filter chips, summary totals, drag-to-reorder, and swipe-to-delete all operate on this filtered set only.
3. Creating a transaction (device sale, accessory sale, service transaction) stamps the device's store location name on the document in the existing `userLocation` field, as today. Service transactions now **block saving** when the store location is not configured, matching device and accessory transactions.
4. The real-time device transaction notification (shown from the main menu when a new device transaction is created) is shown **only for transactions from the device's own store location**.

### Business Rules

- Transaction visibility: the transaction's stored `userLocation` equals the device's store location name, in addition to the existing date and status conditions. On a primary-store device, transactions with a missing or empty `userLocation` are also included (legacy rule).
- Summary totals shown on the list screens are computed from the filtered set only.
- Deleting a transaction (and its inventory rollback) is unchanged, but only same-store transactions are reachable from the list.
- The device sale flow's inventory search is **unchanged**: any inventory item can still be found and sold from any store, and the sale still updates that inventory item's `location` to the selling store (existing behaviour). Sales are attributed to the store where they were rung up.

---

## Feature 4: Ledger View

### User Story

**As branch staff**, I want the Cash / GCash / PayMaya / Others ledgers and the All Credits view to show only entries generated by my store location's transactions.

### UI/UX Flow

1. Open **Ledger**. For the selected date, the ledger entries are built only from device, accessory, and service transactions whose store location equals the device's store location.
2. Ledger balances, All Credits, transaction type filters, drag-to-reorder, and swipe-to-delete operate on this filtered set only.

### Business Rules

- All three underlying transaction loads (service, device, accessory) apply the store location condition, including the legacy rule on a primary-store device.
- The display sequence numbers (#001, #002, …) are assigned after filtering, so they are **per store location per day**.
- Deleting a ledger entry deletes the underlying transaction, as today; only same-store entries are reachable.

---

## Feature 5: End of Day — Per-Store Reports

This is the most sensitive part of the feature. An End of Day report must reflect **exactly one store location's** transactions, and reports from different stores for the same date must **never overwrite or mix with** each other.

### User Story

**As a branch manager**, when I generate an End of Day report I want the transaction details, transaction summary, cash flow, ledger summary, receivables created, and grand totals to be computed **only from transactions made at my store location**, and I want my saved report to be independent of other stores' reports for the same day.

### UI/UX Flow

1. Open **End of Day** from the menu. The monthly report list shows **only saved reports for the device's store location**. On a primary-store device the list also includes legacy reports (saved under the old date-only document ID with no store location).
2. Tap **add** / pick a date, or open an existing report from the list. The report screen shows the date and the **store location name** in the header area so the user can see which store the report is for.
3. Tap **Generate**. The app loads the day's completed device, accessory, and service transactions **restricted to the device's store location**, then builds every section of the report from that set only:
   - Transaction Detail (per-transaction listing and counts)
   - Transaction Summary: device sales, accessory sales, service summary (Cash In / Cash Out / Mobile Loading / Skyro / Salmon / Home Credit payments / Misc), cash flow by payment source, ledger summary, receivables created (Home Credit / Skyro / Salmon / In-House), Brand Zero subsidy, revenue breakdown, and grand totals.
4. If no transactions exist for that store on that date, the screen shows "No transactions found for <date>" and Save is disabled.
5. Tap **Save**. The confirmation says the report for **this date and this store location** will be saved and will overwrite any existing report for the same date **and store**. Saving never touches another store's report for the same date.
6. On opening a date, the "existing report" check looks up the report for **this date and this store location** only; another store's report for the same date is not loaded and not shown. On a primary-store device, if no per-store report exists for the date but a legacy date-only report does, the legacy report is loaded and shown.

### Business Rules

- Every transaction fetch used by End of Day (device, accessory, service) applies **date + status + store location** (with the legacy rule on a primary-store device). No section of the report may use an unfiltered fetch.
- The saved report document stores the **store location name** and is keyed so that one date can have one report **per store location**. The document ID incorporates both the date and the store location name, replacing the current date-only ID.
- The End of Day list query is filtered by the device's store location in addition to the month range.
- `generatedBy` remains the configured user name; `storeLocation` (name) is added to the saved document.
- End of Day generation and saving are blocked when the device has no store location configured.
- The transaction ID lists stored on the report (device / accessory / service) contain only that store's transaction IDs.
- **Legacy reports** (date-only document ID, no `storeLocation`) are kept, never modified or deleted by this feature, and are visible and openable only on primary-store devices. Saving always writes to the per-store document ID, so a legacy document is never overwritten. If both a legacy and a per-store report exist for the same date, the list shows the per-store one.

---

## Feature 6: Expenses

### User Story

**As branch staff**, I want the Expenses screen to show only my store's expenses and a monthly total for my store only.

### UI/UX Flow

1. Open **Expenses**. The month view lists only expenses whose store location equals the device's store location, and the **Total Expenses** figure sums only those entries.
2. Adding an expense stamps the device's store location name (already the case) and is **blocked** when no store location is configured.
3. Edit and delete (password-gated) remain available only for expenses visible on this device.

### Business Rules

- Expense visibility: the expense's stored `storeLocation` equals the device's store location name, in addition to the month condition. On a primary-store device, expenses with a missing or empty `storeLocation` are also included (legacy rule).
- The month total is per store location.

---

## Feature 7: Shared Modules (No Filtering)

### User Story

**As the business owner**, I want Account Receivable and Financing Accounts to remain visible from every store so that any branch can collect a payment or look up a financing account regardless of where it was created.

### Business Rules

- **Account Receivable** continues to list all unpaid Home Credit / Skyro / Salmon / In-House / Credit Card receivables from all store locations. Marking as paid and recording In-House installment payments work from any device, unchanged. Payments collected at a different store than the original sale are still recorded on the sale document with no collecting-store tag (accepted for now).
- **Financing Accounts** continues to list all accounts from all store locations. One correction is included: when a **new** financing account is created, its `storeLocation` is taken from the **Program Settings** store location (the same value every other module uses) instead of the Firebase-backed app settings, which the app never writes and which therefore always produced an empty value. Editing an existing account keeps its original `storeLocation`, as today.
- No filtering is introduced in these two modules.

---

## Data Model

No new collections and no new location ID fields. The existing **location name** fields are the only key:

- **`device_transactions`**, **`accessory_transactions`**, **`service_transactions`**
  - `userLocation` (existing) — store location name; now the visibility key. No new fields.

- **`expenses`**
  - `storeLocation` (existing) — the visibility key. `storeLocationId` continues to be written as today but is not used for visibility. No new fields.

- **`inventory_reconciliations`**
  - `location` (existing) — now always a canonical store location name chosen from the dropdown (no more "All" or free text for new records); the visibility key. No new fields.

- **`daily_summaries`** (End of Day)
  - Document ID changes from the date alone to a compound ID of **date + store location name**, so each store has its own report per date. Existing date-only documents stay as they are (legacy).
  - `storeLocation` (new) — store location name; the list visibility key.
  - All other fields unchanged, but their values are computed from the store-filtered transaction set only.

- **`financing_accounts`** — no schema change; the existing `storeLocation` field is now populated from the Program Settings value for new accounts.

- **`accessory_locations`** — unchanged; remains the canonical list of store locations (`name`, `active`, `isPrimary`). The app now also reads `isPrimary` to identify the primary store location for the legacy rule.

- **SharedPreferences (`TechCitySettings`)** — one new flag saved with the store location by Program Settings, recording whether the chosen location is the primary one.

- **`inventory`**, **`accessory_inventory`**, **`app_settings`**, **`app_config`** — unchanged.

**Model classes:** the End of Day summary data gains the store location name. Transaction, expense, and reconciliation models are unchanged (they already carry the name fields).

**Query consideration:** adding a store location condition to the date/status/ordered list queries means either new Firestore composite indexes or client-side filtering of the already date-scoped result. Firestore cannot query for a *missing* field, so the legacy rule for primary-store devices (include records with no location) requires client-side filtering of the date-scoped result on those devices. The technical plan decides the approach.

## Business Rules (Summary)

1. The device's Program Settings Store Location defines its instance; it must be chosen from the dropdown, and it must be configured before any location-scoped record can be created or viewed.
2. Every location-scoped record is stamped with the store location **name** at creation (existing fields) and is never re-tagged. No location ID fields are added.
3. Visibility on every location-scoped screen is an exact match between the record's stored location name and the device's configured store location name.
4. **Legacy rule:** records with a missing or empty location, legacy "All" reconciliations, and legacy date-only End of Day reports belong to the primary store location (`isPrimary` true in `accessory_locations`) and are visible only on devices configured with that store location. They are never modified, deleted, or re-tagged.
5. Phone Inventory reconciliations are created (password-gated) for a store location chosen from a dropdown (default: the device's own, any listed location allowed) and are visible only to devices on that location; "All" is no longer an option for new records.
6. Device, Accessory, and Service transaction lists, the Ledger view, and Expenses show only same-store records; totals, balances, and sequence numbers derive from the filtered set.
7. End of Day generation reads only same-store transactions for every section; saved reports are keyed per date **and** store location name, and the report list is per store.
8. Account Receivable and Financing Accounts are shared and not filtered. New financing accounts take their `storeLocation` from Program Settings.
9. Device transaction notifications fire only for same-store transactions.
10. Renaming a store location is not supported.

## Edge Cases and Error Handling

- **No store location configured:** location-scoped screens show the configure-first message and no data; creation, End of Day generation, and saving are blocked. Shared modules still work.
- **Store location changed on a device:** confirmation dialog on save; the change applies when screens are next opened/resumed. Records already created keep their original store. Screens already open in the background re-query on resume.
- **Record with missing or empty location** (legacy transactions created before location stamping, service transactions saved with an empty location, expenses saved with no location): visible only on primary-store devices (legacy rule). They remain in Firestore untouched and are still visible in the shared Account Receivable module where applicable.
- **Legacy record whose location name differs in spelling or casing** from the canonical name (for example a hand-typed value saved before the dropdown existed): it is not empty, so the legacy rule does not apply, and it will not match any device. No data check or backfill is included in this feature.
- **Two stores generating End of Day for the same date:** each produces and saves its own document; neither overwrites or includes the other's transactions.
- **Primary-store device and a legacy End of Day report for the same date:** the legacy report opens and displays as today. Saving a regenerated report writes a new per-store document and never overwrites the legacy one.
- **`isPrimary` flag changed in `accessory_locations` after setup:** the device picks up the change the next time Program Settings is saved (see Open Questions, round 3).
- **Store location name in the End of Day document ID:** store location names must not contain the `/` character (a Firestore document ID restriction). Names chosen from `accessory_locations` are expected to satisfy this.
- **Device sold from a different store than it is stocked in:** allowed (unchanged). The transaction belongs to the selling store; the inventory item's `location` becomes the selling store; a reconciliation for the original store created afterwards will no longer include that item.
- **Reconciliation created for another location:** saved under that location and hidden from the creating device, with an informational message.
- **Reconciliation with location "All" (legacy):** kept; visible only on primary-store devices (legacy rule).
- **Store location deactivated in `accessory_locations`:** it disappears from the dropdowns; a device still configured with that name keeps working against its existing records until the setting is changed. Records are not affected.
- **Offline / Firestore failure while loading the location dropdown:** the Program Settings field shows the saved value and cannot be changed to a new one until the list loads; the reconciliation dialog shows an error and cannot create.
- **In-House installment payments and receivable settlements collected at a different store** than the original sale: recorded on the original sale document as today, so they are attributed to the selling store and produce no ledger or End of Day entry at the collecting store (existing behaviour, unchanged).
- **Same store location on multiple devices:** all such devices see the same records; the feature separates by store, not by device.

## Resolved Questions (round 1)

1. **Legacy records without a store location:** treated as belonging to the primary store location; visible only on devices configured with that location.
2. **Legacy End of Day reports:** same; visible only on primary-store devices.
3. **Reconciliation "All" option:** removed for new records; existing "All" records are kept.
4. **Creating a reconciliation for a different store:** allowed, behind the existing password gate.
5. **Device transaction notifications:** limited to same-store transactions.
6. **Matching by name vs ID:** match by name only; no ID fields added; renaming a location is not supported.
7. **Cross-store payments:** accepted as-is for now.
8. **Financing account `storeLocation`:** corrected in this feature so new accounts use the Program Settings value.

## Resolved Questions (round 2)

1. **Legacy location identity:** use the `accessory_locations` document with `isPrimary` true, not a hardcoded name.
2. **Legacy "All" reconciliations:** visible only on primary-store devices.
3. **Legacy End of Day documents:** never overwritten or deleted; new saves always go to the per-store document.
4. **Legacy records with variant spelling/casing:** no data check or backfill; the primary-store rule covers only missing or empty locations.
5. **Reconciliation created for another store:** an informational message is enough.
6. **Confirmation dialog on changing store location:** wanted.

## Open Questions (round 3)

1. **When is "primary" evaluated?** The spec records whether the chosen store location is primary at the moment Program Settings is saved, so a later change to the `isPrimary` flag in `accessory_locations` only takes effect after settings are saved again on each device. Is that acceptable, or should the app re-check the flag every time a location-scoped screen opens (one extra Firestore read per screen open, but no re-save needed)? - this is fine he spec records whether the chosen store location is primary at the moment Program Settings is saved, so a later change to the `isPrimary` flag in `accessory_locations` only takes effect after settings are saved again on each device
