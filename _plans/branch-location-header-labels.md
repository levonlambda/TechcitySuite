# Technical Plan: Branch Location Header Labels

Spec: `_specs/branch-location-header-labels.md`

## Approach Summary

- **Display-only.** Read the store location already saved by Program Settings (via the existing `StoreLocationHelper`) and show it as a label on four screens. No Firestore or preference changes.
- **One shared display rule.** A small helper function produces the label text: the store location name with a leading `TC-` prefix removed (hardcoded per the resolved spec question), or `No store location set` when nothing is configured. Each screen applies the same styling rule: normal secondary style when configured, warning colour when not.
- **Same layout pattern on all three list screens.** The existing `dateLabel` is wrapped in a horizontal row together with a new `branchLocationLabel`; the branch label takes the remaining width and centers its text, truncating with an ellipsis. Existing ids and behaviour are preserved.
- **Menu label refreshes on resume**, alongside the existing feature-visibility refresh, so a change in Program Settings shows immediately.

---

## Files to Create

None.

---

## Files to Modify

### 1. `app/src/main/java/com/techcity/techcitysuite/StoreLocationHelper.kt`
- Add `const val NO_STORE_LOCATION_LABEL = "No store location set"`.
- Add `fun getDisplayName(context): String` — returns the configured store location with a leading `TC-` removed (case-sensitive `removePrefix("TC-")`, trimmed); returns `NO_STORE_LOCATION_LABEL` when not configured.
- No change to the existing visibility functions.

### 2. `app/src/main/res/layout/activity_menu.xml`
- Add a `TextView` `@+id/branchLocationLabel` directly after `appSubtitle`:
  - `wrap_content` × `wrap_content`, `textSize 14sp`, `textStyle bold`, `textColor @color/gray`, `maxLines 1`, `ellipsize end`, `layout_marginTop 2dp`.
  - Constraints: top to bottom of `appSubtitle`, start/end to parent (centered).
- Change the `ScrollView` constraint `layout_constraintTop_toBottomOf` from `@id/appSubtitle` to `@id/branchLocationLabel`.

### 3. `app/src/main/java/com/techcity/techcitysuite/MenuActivity.kt`
- Add `private fun updateBranchLocationLabel()`:
  - Sets `binding.branchLocationLabel.text = StoreLocationHelper.getDisplayName(this)`.
  - Colour: `@color/gray` when `StoreLocationHelper.isConfigured(this)`, `@color/orange` (warning) otherwise.
- Call it from `onResume()` next to the existing `updateFeatureVisibility()` call.

### 4. `app/src/main/res/layout/activity_device_transaction_list.xml`
- Inside the vertical column that holds `titleText` and `dateLabel`: replace the bare `dateLabel` `TextView` with a horizontal `LinearLayout` (`match_parent` × `wrap_content`, `gravity center_vertical`) containing:
  - the existing `dateLabel` `TextView`, unchanged attributes and id;
  - a new `TextView` `@+id/branchLocationLabel`: `0dp` width, `layout_weight 1`, `gravity center`, `textSize 14sp`, `textColor @color/white`, `alpha 0.8`, `maxLines 1`, `ellipsize end`, `layout_marginStart 8dp`.
- Everything else in the header (title, calendar `menuButton`, summary row) is untouched.

### 5. `app/src/main/res/layout/activity_service_transaction_list.xml`
- Identical change to the device list layout (same header structure).

### 6. `app/src/main/res/layout/activity_accessory_transaction_list.xml`
- The `dateLabel` currently sits alone below the title row. Wrap it in a horizontal `LinearLayout` (`match_parent` × `wrap_content`, `gravity center_vertical`, `layout_marginTop 4dp` moved from the label to the row) containing the existing `dateLabel` (id and attributes unchanged, `marginTop` removed) and a new `branchLocationLabel` with the same attributes as in the device layout.

### 7. `app/src/main/java/com/techcity/techcitysuite/DeviceTransactionListActivity.kt`
- Add `private fun updateBranchLocationLabel()` that sets `binding.branchLocationLabel.text = StoreLocationHelper.getDisplayName(this)` and, when not configured, sets the text colour to `@color/yellow` (warning colour readable on the blue header) with full alpha; otherwise white with the layout's default alpha.
- Call it from `onResume()` (existing override).

### 8. `app/src/main/java/com/techcity/techcitysuite/AccessoryTransactionListActivity.kt`
- Same as the device list activity, called from its existing `onResume()`.

### 9. `app/src/main/java/com/techcity/techcitysuite/ServiceTransactionListActivity.kt`
- Same as the device list activity, called from its existing `onResume()`.

---

## Files Explicitly Unchanged

- `ProgramSettingsActivity.kt`, `Constants.kt`, all Firestore-touching code.
- Ledger, Expenses, End of Day, Phone Inventory screens (spec limits the label to the four requested screens).
- `colors.xml` — existing `gray`, `orange`, `yellow`, `white` are reused; no new colours.

---

## Implementation Order

1. `StoreLocationHelper.kt` — add the display-name function and label constant.
2. `activity_menu.xml`, then `MenuActivity.kt`.
3. `activity_device_transaction_list.xml`, then `DeviceTransactionListActivity.kt`.
4. `activity_service_transaction_list.xml`, then `ServiceTransactionListActivity.kt`.
5. `activity_accessory_transaction_list.xml`, then `AccessoryTransactionListActivity.kt`.
6. Build with `./gradlew assembleDebug`; fix compile errors only.

---

## Dependencies / Libraries

None.

---

## Migration and Data Considerations

None. No stored data is read differently or written.

---

## Risks and Things to Watch

- **Prefix stripping is display-only.** The stored/matched value stays `TC-Kapatagan`; only the label shows `Kapatagan`. Do not reuse `getDisplayName` anywhere a real match is required.
- **Centering is within the date row's available width**, which on the device/service screens excludes the calendar button column. This keeps the label from overlapping the button on narrow screens, at the cost of being centered in the row rather than the full header width.
- **Accessory layout margin move.** The `marginTop` currently on `dateLabel` must move to the new wrapping row so vertical spacing is unchanged.
- **ViewBinding ids.** The three list layouts are nowhere near the id cap (unlike the End of Day layout), so adding one id each is safe.
- **Warning colour on the blue header.** `orange` is used on the white menu background; `yellow` is used on the blue headers for contrast. If the user prefers one colour everywhere, this is a one-line change per screen.
