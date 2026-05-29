# Feature Spec: Accessory Barcode Scan Lookup

## Description

Add a **Scan Barcode** button to the Accessory Transaction screen, placed beside the **Accessory Name** field (the name field is shortened to make room). Tapping the button opens the existing camera barcode scanner (the same one used in the Device Transaction flow). After a barcode is scanned, the app looks it up against the accessory catalog in Firestore. If a matching product is found, the **Accessory Name** field and the **Price** field are auto-filled from the catalog data, so staff can complete an accessory sale without typing the product details by hand.

This feature only changes the **Accessory Details** entry section at the top of `AccessoryTransactionActivity`. It does not change how transactions are saved, the transaction-type cards (Cash / Home Credit / Skyro / In-House), or any pricing calculations downstream of the Price field.

---

## User Stories / Use Cases

1. **As store staff**, I want to scan an accessory's barcode instead of typing its name and price, so that I can ring up accessory sales faster and with fewer typos.
2. **As store staff**, when I scan a barcode that is not in the accessory catalog, I want a clear message so that I know to enter the details manually.
3. **As store staff**, I still want to be able to type the accessory name and price manually (e.g. for items without a barcode), so the scan button is an optional shortcut, not a requirement.

---

## UI/UX Flow

### Layout change (Accessory Details card)

1. The **Accessory Name** input row is changed so the text field no longer spans the full width. A **Scan Barcode** button sits to its right, on the same row, aligned with the name field.
2. The button mirrors the look and behavior of the scan button in the Device Transaction screen (`DeviceTransactionActivity` / `activity_device_transaction.xml`) — same icon/style so the app feels consistent.
3. The **Price** field and all other fields remain unchanged in position and width.

### Scan and auto-fill flow

1. User taps the **Scan Barcode** button.
2. The existing `BarcodeScannerActivity` opens (camera view with the framed capture box). User scans / captures the product barcode.
3. The scanner returns a barcode value to `AccessoryTransactionActivity`.
4. The app shows a brief loading indicator while it looks the barcode up in Firestore.
5. **If a matching active product is found:**
   - The **Accessory Name** field is auto-filled.
   - The **Price** field is auto-filled with the product's **retail price**.
   - The existing price/discount/transaction calculations re-run automatically (same as if the user had typed the values), and the form re-validates so the Save button enables when appropriate.
   - A short confirmation Toast is shown (e.g. "Product found and filled in").
6. **If no matching product is found:** A Toast/message tells the user the barcode was not found in the accessory catalog, and the fields are left as they were so the user can enter details manually.
7. **If the user cancels the scanner** (back / cancel), nothing changes on the form.

---

## Data Model

No new Firestore collections and no schema changes. This feature **reads** from the existing accessories catalog defined in `_plans/accessories-firestore-schema.md`:

- **`accessory_products`** — found by querying `where("barcode", "==", scannedValue)`.
  - `barcode` (string) — the value matched against the scanned code. May be absent on some products.
  - `internalSku` (string, = document ID) — the product's SKU.
  - `manufacturer`, `model`, `category` — descriptive fields available for the name.
  - `active` (boolean) — soft-delete flag; inactive products should be treated as "not available."
- **`accessory_pricing`** — document ID is the SKU. Holds `retailPrice` (number, PHP) and `dealersPrice`. **Retail price is NOT stored on the product document** — it must be read from this separate collection using the SKU as the document key.

> Because of the split above, a successful lookup is effectively a two-step read:
> 1. Find the product in `accessory_products` by `barcode` → obtain its SKU.
> 2. Read `accessory_pricing/{sku}` → obtain `retailPrice` for the Price field.

This screen continues to write only to `accessory_transactions` as it does today. **No inventory counters (`accessory_inventory`) are read or written by this feature** — it is purely a name/price convenience lookup.

---

## Business Rules and Logic

1. **Barcode match is exact.** The scanned value is matched against the `barcode` field exactly (no partial/fuzzy matching). The schema treats `barcode` as globally unique across `accessory_products`, so at most one product is expected.
2. **What fills the Accessory Name field.** Per the request, the **SKU** (`internalSku`) is used to populate the Accessory Name field. (See Open Questions — confirm whether SKU is desired here, or a more human-readable name such as `manufacturer + model`.)
3. **What fills the Price field.** The product's `retailPrice` from `accessory_pricing` populates the Price field, formatted the same way a manually typed price would be, and flows through the existing price listeners so all dependent amounts recalculate.
4. **Manual entry is preserved.** Auto-filled values can still be edited by the user afterward. Scanning is optional.
5. **Inactive products.** If the matched product has `active = false`, treat it as not usable (same path as "not found", with a message indicating the item is inactive).
6. **Scanner reuse.** The feature reuses the existing `BarcodeScannerActivity` and its result contract. No new scanning engine is introduced.
7. **Read-only.** This feature performs reads only; it does not create, update, or delete any catalog, pricing, or inventory documents.

---

## Edge Cases and Error Handling

1. **Barcode not in catalog** — show a "not found" message; leave the form untouched for manual entry.
2. **Product found but no `accessory_pricing` document / missing `retailPrice`** — fill the Accessory Name but leave Price empty (or 0), and inform the user that the price must be entered manually. The form should not crash or fill a bogus price.
3. **Product found but `active = false`** — treat as unavailable (see Business Rule 5).
4. **Scanned value is empty / unreadable** — the scanner handles its own "no barcode detected" state; if it returns an empty result, the form does nothing.
5. **No network / Firestore read failure** — show an error Toast (e.g. "Could not look up barcode, check connection") and leave the form untouched.
6. **Camera permission denied** — handled by the existing `BarcodeScannerActivity` (it shows a permission message and closes). No additional handling needed here.
7. **Scan while fields already have values** — auto-fill overwrites the Accessory Name and Price with the looked-up values (latest scan wins). Other fields (discount, transaction type, etc.) are left as-is.
8. **Multiple products share a barcode (data anomaly)** — not expected per the uniqueness rule; if it occurs, use the first match and proceed.

---

## Open Questions

1. **SKU vs. descriptive name in the Accessory Name field.** The request says to fill the name with the **SKU** (`internalSku`, e.g. `ACC-CHG-0001`). Should the field instead (or additionally) show a human-readable label like `manufacturer + model` (e.g. "Anker PowerPort III 65W"), which is friendlier on receipts and lists? The transaction is saved with `accessoryName`, so this choice affects what appears in transaction history. - Yes show the model instead 
2. **Scanner mode for product barcodes.** `BarcodeScannerActivity` defaults to prioritizing IMEI/serial barcodes (for phones). Accessory barcodes are typically EAN/UPC product codes. Should the scanner be launched in a mode that does **not** prioritize IMEI (the activity already exposes flags for this), to improve product-code detection? - YES
3. **Should the scanned barcode value itself be stored** on the saved accessory transaction (e.g. a new `barcode` field), or is it only used transiently to look up the name and price? (Current assumption: transient only — no change to the saved document.) - yes update the document created in the accessory_transactions to also include the Barcode and the SKU data
