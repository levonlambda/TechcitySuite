# Technical Plan: Branch Swap From Menu Label

Spec: `_specs/branch-swap-from-menu-label.md`

Resolved spec decisions carried into this plan:
- No chevron or dropdown icon on the label. Plain text with a touch ripple only.
- The picker and the confirmation dialog show **shortened display names** ("Kapatagan"), matching the label.
- The "Change Store Location?" confirmation is kept after picking a different branch.
- A toast "Switched to <display name>" is shown after a successful swap.

## Approach Summary

- **Reuse the existing password dialog.** `MenuActivity.showPasswordDialog()` currently opens Program Settings on success. It is generalised to accept an `onSuccess` callback. The kebab menu passes "open Program Settings"; the branch label passes "open the branch picker". The dialog layout, messages, Enter-key handling and error handling are untouched.
- **One new custom dialog** (`dialog_branch_picker.xml`) handles all picker states: loading, loaded list (radio buttons), empty, and load-error with Retry. Radio buttons are created programmatically from the loaded store locations, so the layout stays static.
- **Shared read and write go through `StoreLocationHelper`.** The picker loads branches with the existing `loadActiveLocations()` (same source as Program Settings). A new `saveStoreLocation()` writes the same three SharedPreferences keys Program Settings writes (name, id, primary flag) using the existing `AppConstants` keys. A new display-name overload shortens an arbitrary name the same way the label does.
- **No change to how other screens or the notification listener read the branch.** They already read SharedPreferences on resume or at event time, so the swap takes effect immediately with no restart and no listener re-registration.

---

## Files to Create

### 1. `app/src/main/res/layout/dialog_branch_picker.xml`
Vertical `LinearLayout`, `padding 24dp`, white background, styled like `dialog_password.xml` (same `#3F51B5` accent):
- Title `TextView` "Select Branch" (20sp, bold, accent colour, centered).
- Subtitle `TextView` "Choose the store location for this device" (14sp, gray, centered).
- `ProgressBar` `@+id/progressBar` (indeterminate, centered, accent tint) — visible while loading.
- `TextView` `@+id/statusMessage` (14sp, gray, centered, `visibility gone`) — used for "Could not load store locations. Check your connection and try again." and "No store locations available".
- `ScrollView` (`match_parent` × `wrap_content`, `maxHeight` behaviour via `layout_weight` not needed; keep simple) containing `RadioGroup` `@+id/branchRadioGroup` (`visibility gone`). Radio buttons are added in code; each uses `maxLines 2`, `ellipsize end`, `minHeight 48dp`, `textSize 16sp`.
- Horizontal `LinearLayout` `@+id/buttonsLayout` with:
  - `Button` `@+id/cancelButton` "Cancel" (outlined style, accent stroke/text, like the password dialog).
  - `Button` `@+id/retryButton` "Retry" (filled, accent background, `visibility gone`) — shown only in the load-error state.

---

## Files to Modify

### 2. `app/src/main/java/com/techcity/techcitysuite/StoreLocationHelper.kt`
- Add `fun getDisplayName(storeLocationName: String): String` — returns `storeLocationName.removePrefix("TC-").trim()`. Overload of the existing context-based function; the existing function is **not** modified.
- Add `fun saveStoreLocation(context: Context, option: StoreLocationOption)` — writes `AppConstants.KEY_STORE_LOCATION` = `option.name`, `AppConstants.KEY_STORE_LOCATION_ID` = `option.id`, `AppConstants.KEY_STORE_LOCATION_IS_PRIMARY` = `option.isPrimary` to the `AppConstants.PREFS_NAME` preferences with `apply()`. No other keys are touched.
- `loadActiveLocations()` is reused as is.

### 3. `app/src/main/res/layout/activity_menu.xml`
- On the existing `branchLocationLabel` `TextView` add:
  - `android:clickable="true"`, `android:focusable="true"`
  - `android:background="?attr/selectableItemBackground"` (touch ripple, no visual change at rest)
  - `android:paddingStart="12dp"`, `android:paddingEnd="12dp"`, `android:paddingTop="4dp"`, `android:paddingBottom="4dp"` so the ripple and touch target are usable. The label stays centered because it is `wrap_content` with start/end constrained to parent.
- No id changes. No icon added. Everything else in the layout is unchanged.

### 4. `app/src/main/java/com/techcity/techcitysuite/MenuActivity.kt`

**Part 1 (properties):**
- Add `private var isBranchFlowActive = false` — guards against a second tap launching a second password dialog before the first one is shown.

**Part 2 (lifecycle):**
- In `onCreate()`, after `setupKebabMenu()`, call a new `setupBranchLabelClick()`.

**Part 5 (kebab menu and password dialog):**
- Change `showPasswordDialog()` to `showPasswordDialog(onSuccess: () -> Unit)`.
  - In the `isValid` branch, replace the inline `Intent(... ProgramSettingsActivity ...)` + `startActivity` with `onSuccess()` after `dialog.dismiss()`.
  - Nothing else inside the function changes (messages, progress handling, Enter key, cancel).
- Update `setupKebabMenu()` to call `showPasswordDialog { startActivity(Intent(this, ProgramSettingsActivity::class.java)) }`, preserving current behaviour exactly.
- Add `setupBranchLabelClick()`:
  - `binding.branchLocationLabel.setOnClickListener`: if `isBranchFlowActive` return; set it `true`; call `showPasswordDialog { showBranchPickerDialog() }`.
  - To reset the flag when the password dialog is cancelled, `showPasswordDialog` gets a `dialog.setOnDismissListener { onDismiss?.invoke() }` hook via a second optional parameter `onDismiss: (() -> Unit)? = null`. The kebab caller does not pass it. The branch caller passes `{ if (!pickerOpened) isBranchFlowActive = false }` logic; simplest implementation: the picker dialog itself sets `isBranchFlowActive = false` in its own dismiss listener, and the password dialog's dismiss listener only resets the flag when the password was not accepted. Implementation detail: track a local `accepted` boolean inside `showPasswordDialog` set to `true` right before `onSuccess()`; the dismiss listener calls `onDismiss` only when `!accepted`.

**New Part 6: BRANCH PICKER** (new section with the project's `START OF PART` / `END OF PART` comment banners):
- `private fun showBranchPickerDialog()`:
  - Inflate `R.layout.dialog_branch_picker`, bind views, build a non-cancelable-by-outside-touch `AlertDialog` (`setCancelable(true)` so Back dismisses it; Back equals Cancel per spec).
  - `setOnDismissListener { isBranchFlowActive = false }`.
  - `cancelButton` dismisses. `retryButton` calls `loadBranches(...)` again.
  - Call `loadBranches(...)` immediately, then `dialog.show()`.
- `private fun loadBranches(dialog, progressBar, statusMessage, radioGroup, retryButton)`:
  - Show `progressBar`; hide `statusMessage`, `radioGroup`, `retryButton`.
  - `scope.launch { try { val locations = withContext(Dispatchers.IO) { StoreLocationHelper.loadActiveLocations(Firebase.firestore) } ... } catch (e: Exception) { ... } }`.
  - **Success, non-empty:** hide progress; clear `radioGroup`; for each location add a `RadioButton` with `text = StoreLocationHelper.getDisplayName(location.name)`, `tag = location`; check the one whose `name` equals `StoreLocationHelper.getStoreLocation(this)` (none checked if no match or unconfigured); show `radioGroup`. Set `radioGroup.setOnCheckedChangeListener` → `onBranchPicked(dialog, selectedOption)`.
  - **Success, empty:** hide progress; `statusMessage.text = "No store locations available"`; show `statusMessage`; Retry stays hidden.
  - **Failure:** hide progress; `statusMessage.text = "Could not load store locations. Check your connection and try again."`; show `statusMessage` and `retryButton`.
- `private fun onBranchPicked(pickerDialog, option: StoreLocationOption)`:
  - `val current = StoreLocationHelper.getStoreLocation(this)`.
  - If `option.name == current`: `pickerDialog.dismiss()`; return (no change, no confirmation).
  - If `current.isBlank()` (unconfigured device): `applyBranch(option)`; `pickerDialog.dismiss()`; return (no confirmation per spec).
  - Otherwise show an `AlertDialog` titled "Change Store Location?" with message: `This device will switch from "<display current>" to "<display new>" and will only show records for the new store location. Existing records are not moved.` Buttons: **Cancel** (re-check the radio button for the current branch, or clear check if none; picker stays open) / **Confirm** (`applyBranch(option)`; `pickerDialog.dismiss()`).
- `private fun applyBranch(option: StoreLocationOption)`:
  - `StoreLocationHelper.saveStoreLocation(this, option)`.
  - `updateBranchLocationLabel()`.
  - `Toast "Switched to ${StoreLocationHelper.getDisplayName(option.name)}"`.

---

## Files Explicitly Unchanged

- `ProgramSettingsActivity.kt` — its Store Location dropdown, confirmation dialog and `saveSettings()` keep working as the alternate path. Its private key constants match `AppConstants`, so both paths write identical keys.
- `dialog_password.xml` — subtitle text "Password required to access settings" stays as is (the spec asks for the same dialog).
- `Constants.kt`, `AuthManager.kt`, the device-transaction listener in `MenuActivity` (it already evaluates `StoreLocationHelper.matches()` per event, so it follows the new branch automatically).
- All transaction list, ledger, expenses, End of Day and inventory screens — they re-read the branch on resume already.
- `colors.xml`, `strings.xml` — hardcoded strings follow the existing dialog convention in this project.

---

## Implementation Order

1. `StoreLocationHelper.kt` — add `getDisplayName(String)` and `saveStoreLocation()`.
2. `dialog_branch_picker.xml` — create the picker layout.
3. `activity_menu.xml` — make the label clickable with ripple and padding.
4. `MenuActivity.kt`:
   1. Generalise `showPasswordDialog(onSuccess, onDismiss)` and update `setupKebabMenu()`.
   2. Add `isBranchFlowActive`, `setupBranchLabelClick()`, and the call in `onCreate()`.
   3. Add Part 6: `showBranchPickerDialog()`, `loadBranches()`, `onBranchPicked()`, `applyBranch()`.
5. Build with `./gradlew assembleDebug`; fix compile errors only.
6. Manual checks: kebab menu still opens Program Settings after password; label tap asks password; wrong/empty/cancel paths; picker pre-selects current branch; same-branch tap closes; different branch confirms then swaps and label + toast update; unconfigured device sets branch without confirmation; airplane mode shows error + Retry; transaction lists show the new branch on open.

---

## Dependencies / Libraries

None. Uses existing AppCompat `AlertDialog`, Material buttons, Firestore and coroutines already in the project.

---

## Migration and Data Considerations

None. No Firestore writes. The three SharedPreferences keys written are the same ones Program Settings already writes, with the same value semantics (full name such as "TC-Kapatagan", document id, primary flag).

---

## Risks and Things to Watch

- **`showPasswordDialog` signature change.** It has exactly one caller today (`setupKebabMenu`). The refactor must keep the kebab path byte-for-byte equivalent in behaviour: same dialog, same Intent, same error handling.
- **Display name vs. stored name.** The picker shows shortened names but must save and compare the **full** `StoreLocationOption.name`. Keep the option object on the radio button's `tag`; never derive the stored value from the displayed text.
- **Duplicate shortened names.** If two active locations differ only by the "TC-" prefix (e.g. "TC-Kapatagan" and "Kapatagan"), the picker would show two identical rows. Unlikely given current data; noted, not handled.
- **Dialog on rotation.** Like the existing password dialog, the picker is an `AlertDialog` held by the Activity and will be dismissed on configuration change. No state is saved until Confirm, so this is safe. `isBranchFlowActive` resets via the dismiss listener.
- **Guard flag correctness.** The flag must be reset on every exit path: password cancel, password dialog dismissed by any means, picker cancel/back, picker dismiss after swap. All are covered by the two dismiss listeners; make sure `onSuccess` is called **after** `dialog.dismiss()` so the dismiss listener's `accepted` check is already `true`.
- **Ripple background on a bold gray TextView.** `?attr/selectableItemBackground` has no resting appearance, so the label looks identical until pressed. The added padding increases the label's height by about 8dp, pushing the menu cards down slightly.
- **Offline password verification** already surfaces "Error verifying password" and a toast; unchanged.
- **Primary flag.** `saveStoreLocation()` must write `isPrimary` from the loaded option, otherwise legacy-record visibility (primary-store rule) would silently change after a swap.
