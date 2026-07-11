# Technical Plan: Filter Button Label Renames

## Context

Approved spec: `_specs/filter-button-label-renames.md` (open questions answered: "TC" confirmed; badges/summary labels stay full-word; Service Transactions' Skyro button added to scope). Label-only renames: "Skyro" → **SKY** and "In-House" → **TC** on the Device Transaction list, Accessory Transaction list, Accounts Receivable, and (Skyro only) Service Transaction list screens; "Home Credit" → **HC** on the Financing Accounts screen. No behavior, logic, or data changes.

Verified facts:
- All seven labels are static XML. No Kotlin code sets or reads these buttons' text (`filterSkyroButton.text` / `filterInHouseButton.text` / `filterHomeCreditButton.text` have zero code references), so this is a pure layout/resource change with no logic impact.
- The Financing Accounts button text comes from the string resource `filter_home_credit`, which is referenced **only** by `activity_financing_account_list.xml` (verified repo-wide) — changing its value cannot leak to other screens.

## Files to Create

None.

## Files to Modify (5)

All changes are `android:text` attribute values (or one string-resource value). Button ids, sizes, colors, ordering, click listeners, and everything else stay byte-identical.

1. **`app/src/main/res/layout/activity_device_transaction_list.xml`**
   - Line 274 (`filterSkyroButton`): `android:text="Skyro"` → `android:text="SKY"`
   - Line 308 (`filterInHouseButton`): `android:text="In-House"` → `android:text="TC"`

2. **`app/src/main/res/layout/activity_accessory_transaction_list.xml`**
   - Line 253 (`filterSkyroButton`): `"Skyro"` → `"SKY"`
   - Line 287 (`filterInHouseButton`): `"In-House"` → `"TC"`

3. **`app/src/main/res/layout/activity_account_receivable.xml`**
   - Line 219 (`filterSkyroButton`): `"Skyro"` → `"SKY"`
   - Line 253 (`filterInHouseButton`): `"In-House"` → `"TC"`

4. **`app/src/main/res/layout/activity_service_transaction_list.xml`**
   - Line 261 (`filterSkyroButton`): `"Skyro"` → `"SKY"`

5. **`app/src/main/res/values/strings.xml`**
   - Line 50: `<string name="filter_home_credit">Home Credit</string>` → `<string name="filter_home_credit">HC</string>`
   - The resource **name** stays `filter_home_credit` (no rename → no other file touched); only the displayed value changes. Sole consumer is the Financing Accounts filter button.

## Explicitly NOT changed

- Stored `transactionType` / `financingCompany` strings, filter enums, and query logic.
- Transaction-type badges on list/receivable cards, summary labels ("Skyro Receivable", "In-House Receivable", "Total Amount (Skyro)" etc.), New Transaction dropdown options, End-of-Day report rows, dialogs — all stay full-word per spec.
- No layout sizing/spacing adjustments.

## Implementation Order

Single pass — the five files are independent; edit all, then build.

## Dependencies / Libraries

None.

## Migration / Data Considerations

None. No Firestore, no model, no index changes.

## Risks

- Practically none: no code references these labels, so the only failure mode is a typo in the XML. ViewBinding is unaffected (no id changes).

## Verification

Build: `./gradlew assembleDebug`.

Manual checks:
1. Device Transactions list → filter row shows **SKY** and **TC**; tapping each still filters Skyro / In-House transactions and the summary label still says the full word.
2. Accessory Transactions list → same two renames, same behavior.
3. Accounts Receivable → row reads All, HC, **SKY**, Salmon, **TC**; filters behave as before (TC still shows In-House + Credit Card rows per the credit-card feature).
4. Service Transactions list → **SKY** button filters Skyro Payment transactions; total label still reads "(Skyro)".
5. Financing Accounts → row reads All, **HC**, Salmon, Skyro, Samsung Finance; HC filters Home Credit accounts; Add/Edit dropdown still shows "Home Credit" in full.
