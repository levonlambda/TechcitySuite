# Feature Spec: Accessory Inventory Counter on Sale & Delete

## Description

Make accessory sales keep the shared accessory inventory counters in sync. When an accessory that exists in the product catalog is sold, the app decrements the appropriate stock bucket (`onDisplay` first, then `onHand`) and increments `sold` for that product at the current store location. When such a transaction is later deleted, the app reverses exactly what it did — restoring the bucket it decremented and decrementing `sold`.

Accessories that are free-typed (not in the catalog, no SKU) are unaffected — they have no inventory record, so nothing is counted for them.

To target the right inventory record, the Program Settings screen is updated so that choosing a Store Location also captures that location's `locationId` (the `accessory_locations` document ID), not just its name.

This feature reads/writes the existing accessories inventory collections defined in `_plans/accessories-firestore-schema.md`. **Note:** the buckets behave per the store's real workflow (see Business Rules), which is the *opposite* of how the schema reference document describes `onDisplay`/`onHand` — that is intentional and must be coordinated with the web app (see Cross-App Coordination).

---

## User Stories / Use Cases

1. **As store staff**, when I sell a catalogued accessory, I want the shop-floor stock count to go down automatically, so the inventory stays accurate without manual adjustments.
2. **As store staff**, when the shop-floor stock (`onDisplay`) is empty, I still want to be able to complete the sale by drawing from back-room stock (`onHand`), so I'm never blocked from selling an item that physically exists.
3. **As store staff**, when both shop-floor and back-room stock show zero, I still want the sale to go through (counts can be out of sync), but I want a warning so I know the inventory needs attention.
4. **As a store owner**, when a wrong accessory transaction is deleted, I want the inventory counters restored to exactly what they were, so deletions don't permanently distort stock and sales figures.
5. **As store staff**, when I sell a free-typed accessory (something not in our catalog), I don't expect any inventory change, because that item isn't tracked.
6. **As a store owner**, I want to pick our store's location from a list in Settings so the app knows which branch's inventory to update.

---

## UI/UX Flow

### A. Program Settings — Store Location selection

1. In `ProgramSettingsActivity`, the **Store Location** field becomes a selectable list of locations loaded from the `accessory_locations` collection (showing each location's name), instead of a free-text field.
2. When the user selects a location and saves, the app stores **both**:
   - the location **name** (as today), and
   - the location's **`locationId`** (the `accessory_locations` document ID).
3. On opening Settings, the currently configured location is pre-selected if it still matches a location in the list.
4. If the locations can't be loaded (offline), the field should still show the previously saved location name and not block saving other settings.

### B. Selling a catalogued accessory (Accessory Transaction save)

1. Staff completes an accessory transaction where the item came from a catalog match (barcode scan or search), i.e. the transaction has a non-empty `sku`. This applies to **all** transaction types (Cash, Home Credit, Skyro, In-House).
2. On save, in addition to writing the transaction document, the app updates that product's inventory at the configured store location:
   - Decrement **`onDisplay`** by 1 if `onDisplay ≥ 1`;
   - otherwise decrement **`onHand`** by 1 if `onHand ≥ 1`;
   - in either case increment **`sold`** by 1.
3. If **both** `onDisplay` and `onHand` are 0 (or the inventory record doesn't exist / location isn't configured), the **sale still saves**, but **no counter is changed** and the user sees a warning (e.g. "Sale recorded, but inventory could not be updated (out of stock or not configured)").
4. The transaction document records what was done to inventory so it can be reversed later (which bucket, which SKU, which location).

### C. Deleting an accessory transaction (undo)

1. Deletion happens through the existing flow in `AccessoryTransactionListActivity` (swipe-left → password → confirm).
2. On confirmed delete, before/while removing the document:
   - If the transaction recorded an inventory change, the app **reverses it**: increment the same bucket that was decremented (`onDisplay` or `onHand`) by 1, and decrement `sold` by 1.
   - If the transaction recorded **no** inventory change (free-typed item, out-of-stock-at-sale, or a transaction created before this feature existed), the app simply deletes it with no counter change.
3. The user sees the usual success message; if the reversal fails, an error is shown and the deletion does not silently lose inventory accuracy (see Edge Cases).

---

## Data Model

No new collections. Uses the existing accessories schema (`_plans/accessories-firestore-schema.md`):

### `accessory_inventory` (existing)
- Document ID: `{sku}__{locationId}`.
- Buckets used by this feature: `onDisplay`, `onHand`, `sold`, plus `lastUpdated`.
- All counter changes go through Firestore transactions using atomic increments (per the schema's Critical Rules — never read-modify-write from the client).

### `accessory_transactions` (existing — new fields added)
New fields stamped on the document at save time so a delete can reverse precisely:
- `inventoryUpdated` (boolean) — `true` only if a counter was actually changed by this sale.
- `inventorySku` (string) — the SKU whose inventory was decremented.
- `inventoryLocationId` (string) — the location whose inventory was decremented.
- `inventoryBucket` (string) — which bucket was decremented: `"onDisplay"` or `"onHand"`.
- `inventoryQuantity` (number) — always `1` for now (quantity is implicit), stored so future multi-quantity support reverses correctly.

All five are additive; existing documents simply lack them and are treated as "no inventory change" on delete.

### Settings (SharedPreferences — new key)
- Existing: `store_location` (name string) — kept.
- New: a store location **ID** key (e.g. `store_location_id`) holding the selected `accessory_locations` document ID.

### `accessory_locations` (existing — read only)
- Read to populate the Store Location selector (`name` for display, document ID for the stored `locationId`).

---

## Business Rules and Logic

1. **Catalog vs. free-typed discriminator.** Inventory is touched **only** when the transaction's `sku` is non-empty. Free-typed accessories never affect inventory.
2. **Quantity is implicit = 1.** Every accessory transaction represents one unit. No quantity UI is added.
3. **Decrement order: `onDisplay` first, then `onHand`.** Shop-floor stock is sold before back-room stock. (This reflects the store's actual workflow and intentionally differs from the schema reference document's definitions.)
4. **Sales never blocked.** If neither bucket has stock, or the inventory record/location is missing, the sale is still recorded; only the counter update is skipped, with a warning.
5. **`sold` increments on every counted sale** and is reversed on delete of a counted sale. (This means `sold` is no longer strictly monotonic — see Cross-App Coordination.)
6. **Atomicity.** The transaction document write and the inventory counter change should be consistent with each other (a counted sale should not exist without its decrement, and vice versa). Likewise, deletion and its reversal should be consistent.
7. **Reversal is marker-driven.** Delete reverses only what the transaction's `inventory*` marker fields say was done — never inferred. No marker → no reversal.
8. **All transaction types count.** The item is sold regardless of payment method, so Cash / Home Credit / Skyro / In-House all decrement inventory the same way.
9. **Location comes from Settings at sale time.** The decremented inventory record is `{inventorySku}__{configured locationId}`.

---

## Edge Cases and Error Handling

1. **No store location configured (no `locationId`).** Sale saves; inventory skipped; warning shown. (Same path as out-of-stock.)
2. **Inventory document doesn't exist for `{sku}__{locationId}`.** Treated as zero stock → sale saves, no decrement, warning. (Per schema, the web app creates inventory docs lazily; this feature does not create them on a sale.)
3. **Both buckets zero.** Sale saves; no decrement; warning.
4. **Counter would go negative.** Not possible under the rule above, because a bucket is only decremented when it is ≥ 1.
5. **Delete of a free-typed or pre-feature transaction (no marker).** Deletes normally, no counter change.
6. **Delete reversal fails (e.g. network).** Show an error; do not leave the system in a state where the document is gone but the counter wasn't restored (deletion + reversal should succeed or fail together).
7. **Selected store location no longer exists in `accessory_locations`.** Settings should handle gracefully (keep the saved name, prompt re-selection); a sale against a missing location falls into the "inventory skipped + warning" path.
8. **Double sale / double delete.** The marker prevents a delete from reversing twice; standard guarding so a counter isn't adjusted more than once per action.
9. **Locations fail to load in Settings (offline).** Other settings remain editable and savable; the location selector degrades gracefully.

---

## Cross-App Coordination (important)

The same Firestore database is shared with the web app ("Tech City Inventory System"), which the schema reference calls the canonical owner. Two intentional divergences must be mirrored in the web app to avoid counter corruption:

1. **Sale decrements `onDisplay`-first-then-`onHand`**, whereas the web app's `recordAccessorySale` decrements `onHand`. The web app's sale logic must be updated to match, or the two clients will disagree on which bucket a sale affects.
2. **`sold` can now decrease** (when a counted sale is deleted), whereas the web app/schema treats `sold` as monotonically non-decreasing. The web app and any reporting built on `sold` must account for reversals.

These are action items for whoever owns the web app; they are outside the mobile code changes but required for consistency.

---

## Out of Scope

- Multi-quantity accessory sales (quantity stays implicit = 1).
- Moving stock between `onHand` and `onDisplay` from the mobile app.
- Writing per-sale audit records to `accessory_inventory_adjustments` (per schema, `sold` is the audit trail for sales; this feature does not add adjustment entries).
- Editing an existing accessory transaction's item/price and re-syncing inventory (only create and delete are covered).

---

## Open Questions

1. **Store Location selector control.** Should the location be a dropdown (like the existing inventory-status dropdown) or a different picker? (Assumption: a dropdown populated from `accessory_locations`.) - dropdown is fine
2. **Edit flow.** If a transaction can be edited later (not just created/deleted), should inventory re-sync on edit? (Assumption: out of scope for now.) - no edit exists right now so we can safely disregard this concern
3. **Warning wording / severity.** Is a Toast sufficient for the "inventory not updated" warning, or should it be a blocking dialog the user must acknowledge? - toast is fine
