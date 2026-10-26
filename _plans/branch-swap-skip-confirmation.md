# Technical Plan: Branch Swap Skip Confirmation

Spec: `_specs/branch-swap-skip-confirmation.md`

## Summary

Remove the "Change Store Location?" confirmation from the Main Menu branch-picker flow so that picking a different branch applies immediately. The change is confined to the branch-picker section (Part 7) of `MenuActivity.kt`. The password dialog, the picker dialog and its layout, `StoreLocationHelper`, and Program Settings are untouched.

---

## Files to Be Created

None.

## Files to Be Modified

### 1. `app/src/main/java/com/techcity/techcitysuite/MenuActivity.kt`

Only Part 7 ("BRANCH PICKER") changes. Three localized edits:

**(a) Simplify `onBranchPicked()`**

Current behaviour: no-op for the current branch; immediate `applyBranch()` + dismiss when the device is unconfigured; otherwise show an `AlertDialog` titled "Change Store Location?" whose Confirm button calls `applyBranch()` and whose Cancel / outside-cancel paths call `restoreCurrentBranchSelection()`.

New behaviour:

- Keep the current-branch check: if the picked option's name equals the device's current store location, dismiss the picker and return (unchanged).
- For every other case (configured **or** unconfigured device), call `applyBranch(option)` then dismiss the picker. The separate `current.isBlank()` branch collapses into this single path because both now do the same thing.
- Delete the `AlertDialog.Builder` block that built the confirmation, including its title, message, Confirm, Cancel, and cancel-listener handlers.
- Reduce the signature to `onBranchPicked(pickerDialog, option)`. The `radioGroup` and `listener` parameters existed only so a cancelled confirmation could restore the previous radio selection; with no confirmation they are unused.
- Update the KDoc above the function to describe the new behaviour (no-op for the current branch, otherwise switch immediately).

**(b) Update the call site in `loadBranches()`**

Inside the `OnCheckedChangeListener` lambda, change the call to `onBranchPicked(dialog, option)`.

Because the lambda no longer needs to pass the listener back into itself, the two-step `var listener: ... = null` / `listener = ...` self-reference pattern is no longer required. Replace it with a direct `radioGroup.setOnCheckedChangeListener { group, checkedId -> ... }`. The body of the lambda (ignore `View.NO_ID`, resolve the tagged `StoreLocationOption`, call `onBranchPicked`) is otherwise unchanged. The preceding `radioGroup.setOnCheckedChangeListener(null)` before rebuilding the radio buttons stays, since it still prevents the pre-check of the current branch from firing a selection.

**(c) Remove `restoreCurrentBranchSelection()`**

Delete the function and its KDoc. Its only two callers were the Cancel and cancel-listener handlers of the confirmation dialog removed in (a). A repository search confirms no other file references it.

Everything else in Part 7 stays as is: `showBranchPickerDialog()`, the loading / empty / error / retry handling in `loadBranches()`, and `applyBranch()` (save, label refresh, "Switched to …" toast).

---

## Implementation Order

1. Rewrite `onBranchPicked()` per (a).
2. Update the listener and call site in `loadBranches()` per (b).
3. Delete `restoreCurrentBranchSelection()` per (c).
4. Build with `./gradlew assembleDebug` and confirm there are no unresolved references.
5. Manual verification on device (see Testing).

## Dependencies or Libraries Needed

None.

## Migration or Data Considerations

None. No Firestore, SharedPreferences, layout, string resource, or manifest changes. The saved store location values and the code that writes them (`applyBranch()` → `StoreLocationHelper.saveStoreLocation`) are unchanged.

## Testing / Verification

Manual, on a device with at least two active store locations:

1. **Happy path.** Tap the branch label → enter the correct password → pick a different branch. The picker closes at once, no confirmation appears, the label shows the new branch, and the "Switched to …" toast appears.
2. **Current branch.** Repeat and pick the already-selected branch. The picker closes with no toast and no change.
3. **Cancel / Back.** Open the picker and dismiss it with Cancel and with Back. Nothing changes; the label can be tapped again (the `isBranchFlowActive` guard resets).
4. **Unconfigured device.** Clear the store location (or use a fresh install), tap "No store location set", enter the password, pick a branch. It saves immediately, as before.
5. **Downstream screens.** After a switch, open a location-filtered list (for example Device Transactions). It shows records for the new branch without restarting the app.
6. **Program Settings unchanged.** Open Program Settings → change Store Location. The "Change Store Location?" confirmation still appears there.
7. **Password gate unchanged.** Wrong / empty password and Cancel on the password dialog behave exactly as before and never open the picker.

## Risks and Things to Watch Out For

- **Scope discipline.** `ProgramSettingsActivity` has its own "Change Store Location?" confirmation. It must not be touched; the spec explicitly keeps it.
- **Pre-check must not trigger a switch.** `loadBranches()` checks the current branch's radio button before attaching the listener. Keep the `setOnCheckedChangeListener(null)` that precedes the rebuild and attach the new listener only after the loop, otherwise opening the picker could immediately "switch" to the current branch (harmless because of the same-branch guard, but it would dismiss the picker).
- **`isBranchFlowActive` reset.** The flag is cleared by the picker's dismiss listener in `showBranchPickerDialog()`. `onBranchPicked()` must still dismiss the picker on every switch so the flag resets and the label stays tappable.
- **Dead-code removal is limited to this flow.** Only `restoreCurrentBranchSelection()` is removed, and only because its sole callers are deleted. Do not remove or refactor anything else in `MenuActivity`.
- **No accidental whitespace churn.** Edits are to three contiguous regions in Part 7; the rest of the file must remain byte-identical.
