# Feature Spec: Accessory Search Dropdown by Manufacturer or Model

## Description

Enhance the search field on the Accessory Transaction screen (`AccessoryTransactionActivity`) so that staff can find an accessory by typing part of its **manufacturer** or **model**, not just by scanning or entering a full barcode. As the user types, an autocomplete dropdown appears listing **active** catalog accessories whose manufacturer or model matches what was typed. Each suggestion is shown in the form **Manufacturer - Model - Price**. Selecting a suggestion auto-fills the **Accessory Name** and **Price** fields (and stores the matched barcode/SKU on the transaction) exactly the way a successful barcode lookup does today.

This builds on the existing barcode lookup ([accessory-barcode-scan-lookup](accessory-barcode-scan-lookup.md)). The existing **Scan** button and full/partial-barcode **Search** button behavior are preserved; this feature adds a text-driven, name-based search path in the same field. Only the **Accessory Details** entry section at the top of the screen is affected. Transaction saving, the transaction-type cards (Cash / Home Credit / Skyro / Salmon / In-House), and all downstream pricing calculations are unchanged.

---

## User Stories / Use Cases

1. **As store staff**, I want to type a brand or model name into the search field and pick the accessory from a dropdown, so that I can ring up a sale without knowing or scanning the barcode.
2. **As store staff**, I want each dropdown suggestion to show the manufacturer, model, and price together, so that I can confirm I'm selecting the right item (and the right variant/price) before choosing it.
3. **As store staff**, I want the dropdown to only show accessories that are currently active in the catalog, so that I don't accidentally select a discontinued item.
4. **As store staff**, I still want to scan a barcode or type a barcode and search, so the existing lookups keep working alongside the new name search.
5. **As store staff**, I still want to be able to type a free-form accessory name and price manually (e.g. for items not in the catalog), so the dropdown is an optional shortcut, not a requirement.

---

## UI/UX Flow

### Search field behavior

1. The existing search/barcode input field on the Accessory Details card doubles as a name search. The user can type either a barcode or free text (manufacturer / model).
2. As the user types (from a small minimum number of characters, e.g. 2), the app matches the typed text against the **manufacturer** and **model** of active catalog accessories and shows an autocomplete dropdown directly below the field.
3. Each dropdown row is rendered as **`Manufacturer - Model - ₱Price`** (e.g. `Anker - PowerPort III 65W - ₱1,499.00`). Rows with no price on file show a clear placeholder in the price position (e.g. `— no price`).
4. The dropdown updates as the user continues typing (narrowing the matches). If nothing matches, no suggestions are shown.

### Select and auto-fill flow

1. User taps a suggestion in the dropdown.
2. The app auto-fills:
   - **Accessory Name** ← the matched product's `manufacturer + model` (same value the barcode lookup fills).
   - **Price** ← the matched product's **retail price**, flowing through the existing price listeners so all dependent amounts recalculate and the form re-validates.
3. The matched **barcode** and **SKU** are stored (same as a barcode lookup) so they are written to the saved transaction and so inventory decrement can apply on save.
4. A short confirmation Toast may be shown (consistent with the barcode "Product found and filled in" message).
5. If the selected product has no price on file, the Accessory Name is filled, the Price is left for manual entry, and the user is informed (same behavior as the barcode path).

### Interaction with existing barcode lookup

1. The **Scan** button still opens the camera scanner and, on success, auto-fills via the existing barcode path.
2. The **Search** button still performs the existing partial-barcode lookup on the typed value.
3. If the user manually edits the field text after an auto-fill (without picking a new suggestion), the previously matched barcode/SKU are cleared — same rule as today — until they select a new suggestion or search again.

---

## Data Model

No new Firestore collections and no schema changes. This feature **reads** from the existing accessories catalog (see [accessory-barcode-scan-lookup](accessory-barcode-scan-lookup.md)):

- **`accessory_products`** — source of the suggestions.
  - `manufacturer` (string) — matched against the typed text (case-insensitive, partial).
  - `model` (string) — matched against the typed text (case-insensitive, partial).
  - `barcode` (string) — stored on the transaction when a suggestion is selected. May be absent on some products.
  - `internalSku` (string, = document ID) — the product's SKU; stored on the transaction and used for the inventory decrement.
  - `active` (boolean) — soft-delete flag; only `active = true` products appear in the dropdown.
- **`accessory_pricing`** — document ID is the SKU. Holds `retailPrice` (number, PHP), used both for the price shown in each suggestion row and for filling the Price field on selection.

> Building the suggestion list requires pairing each active product with its price:
> 1. Read active products from `accessory_products`.
> 2. Read `accessory_pricing/{sku}` for each to obtain `retailPrice` for the row label and the Price field.

This screen continues to write only to `accessory_transactions` (including `barcode` and `sku` as established in the barcode lookup feature). **No inventory counters are read or written while browsing/searching** — inventory is only decremented at save time, unchanged from today.

---

## Business Rules and Logic

1. **Search scope.** Matching is against `manufacturer` and `model` only. The match is case-insensitive and partial (substring), consistent with the existing partial-barcode search.
2. **Active only.** Products with `active = false` are excluded from the dropdown entirely.
3. **Suggestion label format.** Each row displays `Manufacturer - Model - Price`. Price is formatted as PHP currency (e.g. `₱1,499.00`); products with no `retailPrice` on file show a placeholder instead of a number.
4. **What selection fills.** Selecting a suggestion fills the Accessory Name with `manufacturer + model` and the Price with `retailPrice`, matching the barcode lookup's auto-fill so transaction history is consistent regardless of how the item was found.
5. **Barcode/SKU capture.** On selection, the matched `barcode` and `internalSku` are stored and saved on the transaction, and drive the inventory decrement at save time (same as barcode lookup).
6. **Manual entry preserved.** Auto-filled values remain editable, and staff can ignore the dropdown and type a free-form name/price. Free-typed items (no SKU) do not touch inventory.
7. **Minimum characters.** The dropdown only queries/filters once the user has typed a small minimum (e.g. 2 characters) to avoid showing the entire catalog.
8. **Read-only browsing.** Searching and selecting perform reads only; nothing is created, updated, or deleted in the catalog, pricing, or inventory while building suggestions.

---

## Edge Cases and Error Handling

1. **No matches** — the dropdown shows nothing; the user can keep typing or fall back to manual entry / scanning.
2. **Product matched but no `accessory_pricing` / missing `retailPrice`** — the suggestion shows a "no price" placeholder; on selection, the Accessory Name fills but the Price is left for manual entry, and the user is informed.
3. **Large catalog / performance** — matching should remain responsive as the user types; suggestions may be capped at a reasonable number of rows to keep the dropdown usable.
4. **Duplicate manufacturer+model with different SKUs/prices** — each is shown as its own row (differentiated by price and, if needed, other detail) so the user can pick the intended variant.
5. **Empty or too-short input** — no dropdown is shown; the field behaves like a normal barcode/search field.
6. **User edits text after selecting** — matched barcode/SKU are cleared until a new selection or search occurs (see UI/UX flow).
7. **No network / Firestore read failure** — show an error message and leave the form untouched; the user can retry or enter details manually.
8. **Inactive product edge** — an item deactivated after the catalog was loaded should not be selectable; the active filter is applied when building the suggestion list.

---

## Open Questions

1. **When to load the catalog for suggestions.** Should the active products (and their prices) be fetched once when the screen opens and filtered locally as the user types, or queried from Firestore on each keystroke? Local caching is faster and cheaper but may show a product that was just deactivated; per-keystroke is always fresh but slower and more read-heavy. - i prefer it to query once the user inputs in the field and presses the magnifying glass button
2. **Minimum characters and result cap.** Is 2 characters the right threshold to start showing suggestions, and should the number of dropdown rows be capped (e.g. top 10)? - lets make it min of 3 characters
3. **Row detail when manufacturer+model collide.** If two active products share the same manufacturer and model but differ by SKU/price, is `Manufacturer - Model - Price` enough to tell them apart, or should the SKU/category also appear in the row? - for now lets keep it simple and s how manufacturer-model-price. please take into account that the main users of this app is on mobile phone so it may not fit the width if we show manufacturer-model- price. maybe we can adjust the layout to show manufactuer then in a line below it, tab and show the model and price 
4. **Price placeholder wording.** For products with no price on file, what exact text should appear in the price position of the row (e.g. `— no price`, `Price N/A`)? just show 0.00
5. **Field hint/label.** Should the field's hint be updated to communicate that it now accepts a manufacturer/model search as well as a barcode? - no need.
