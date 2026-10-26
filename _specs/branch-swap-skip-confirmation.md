# Feature Spec: Branch Swap Skip Confirmation

## Description

The Main Menu branch label lets the user switch the device's branch (store location). Today the flow is: tap the branch label → enter the settings password → pick a branch from the "Select Branch" list → confirm in a **"Change Store Location?"** dialog → the branch switches.

This feature **removes the "Change Store Location?" confirmation dialog** from that flow. After a correct password, picking a branch in the list switches the device immediately. The password prompt remains the only gate, and the label and toast still confirm what happened.

This change applies **only** to the Main Menu branch-label shortcut. The Store Location setting inside Program Settings keeps its own confirmation and is not touched.

> Context: the shortcut was introduced by `_specs/branch-swap-from-menu-label.md`, where the confirmation step was chosen as an extra safeguard. In daily use it has proven to be an unnecessary extra tap, since the password already prevents accidental switches and the picker clearly marks the current branch.

---

## Feature 1: Immediate Branch Switch After Selection

### User Story

**As the store owner or a supervisor** who switches branches on one device several times a day, I want the branch to change as soon as I pick it in the list after entering the password, so the switch takes three steps (tap, password, pick) instead of four.

### UI/UX Flow

1. On the Main Menu, tap the branch location label under the subtitle (unchanged).
2. The **existing password dialog** appears with the same validation and error messages as today (unchanged).
3. On a correct password, the **"Select Branch"** picker opens, listing the active store locations by display name with the current branch pre-selected (unchanged).
4. Tapping a **different** branch now:
   - saves the new store location for this device at once,
   - closes the picker,
   - refreshes the Main Menu branch label to the new display name,
   - shows the existing toast, for example **"Switched to Kapatagan"**.
   There is **no** "Change Store Location?" dialog and no Confirm / Cancel step.
5. Tapping the branch that is **already selected** closes the picker with no change (unchanged).
6. **Cancel** or Back on the picker closes it with no change (unchanged).
7. On an **unconfigured** device ("No store location set"), picking a branch saves it immediately, exactly as it already does today.

### Business Rules

- The password gate is **not** weakened in any way: the picker is never shown without a correct settings password, there is no grace period, and no "remember password" behaviour is introduced.
- The branch switch writes the **same per-device store location values** (name, id, primary flag) that it writes today and that Program Settings writes. Nothing else about the save changes.
- Switching branches still changes only which records this device displays and which branch new records are tagged with. No records are moved, copied, or edited.
- The toast and the refreshed label become the user's only post-switch feedback, so both must continue to appear every time a switch happens.
- The **Program Settings** Store Location dropdown keeps its existing "Change Store Location?" confirmation. This spec does not alter that screen.
- All other behaviour of the branch picker (loading indicator, retry on load failure, empty-list message, current-branch marking) is unchanged.

---

## Data Model

No Firestore changes and no new SharedPreferences keys. The same store location values are read and written as today. No layout changes are required; the confirmation dialog being removed is a standard system dialog with no dedicated layout.

## Business Rules (Summary)

1. Main Menu branch swap becomes: tap label → password → pick branch → switched.
2. The "Change Store Location?" confirmation dialog no longer appears in this flow.
3. Password protection, saved values, label refresh, and the "Switched to …" toast are all unchanged.
4. Program Settings keeps its own confirmation; it is out of scope.

## Edge Cases and Error Handling

- **Accidental tap on the wrong branch:** since there is no confirmation, the user recovers by tapping the label again, entering the password, and picking the correct branch. The toast and label make the wrong switch immediately visible.
- **Picking the current branch:** no save, no toast, picker simply closes (as today).
- **Unconfigured device:** behaviour already skips confirmation; it stays identical.
- **Branch list fails to load / is empty:** existing inline message, Retry, and Cancel behaviour are unchanged; no switch happens.
- **Back or Cancel on the picker:** nothing changes.
- **Rapid double-tap on a branch row:** only the first selection is applied; the picker closes immediately on the first pick so a second tap has nothing to act on.
- **Screen rotation or backgrounding while the picker is open:** the picker may be dismissed; no change is saved unless a branch was actually picked.
- **Code paths that only existed to support the confirmation** (for example, restoring the previously selected radio button after a cancelled confirmation) are no longer needed in this flow and can be retired as part of this change, provided no other screen depends on them.

## Open Questions

1. **Program Settings confirmation:** this spec leaves the "Change Store Location?" confirmation in Program Settings as is. Should that one be removed too, or kept because Program Settings is the less frequent, more deliberate path? - keep the one in the program settings
2. **Toast duration:** with no confirmation step, is the current short toast enough, or should it be the longer duration so the user is more likely to notice a wrong pick? - current toast is enough
