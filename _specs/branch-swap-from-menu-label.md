# Feature Spec: Branch Swap From Menu Label

## Description

Let the user **switch the device's branch (store location) directly from the Main Menu** by tapping the branch location label shown under the "Business Management System" subtitle.

Today the branch can only be changed by opening the kebab menu, entering the settings password, opening **Program Settings**, picking a new value in the **Store Location** dropdown, and saving. This feature adds a shortcut: tapping the branch label opens the **same password prompt** used by the kebab menu, and on success shows a **branch picker** listing the active store locations. Choosing one swaps the device's branch in place and the Main Menu updates immediately.

The branch label becomes a tappable control, but the Store Location setting in Program Settings continues to work exactly as it does today. Both paths write the same per-device setting, so there is a single source of truth.

---

## Feature 1: Tap Branch Label to Swap Branch

### User Story

**As the store owner or a supervisor** who carries one device between stores, I want to tap the branch name on the Main Menu and switch to another branch after entering the settings password, so I can change branches in a few taps without digging through Program Settings.

**As branch staff**, I do not want the branch to change by accident, so the swap must still be protected by the settings password.

### UI/UX Flow

1. The Main Menu shows the branch location label under the subtitle as today (for example **"Kapatagan"**, or **"No store location set"** in the warning colour when unconfigured).
2. The label is now **tappable**. It gets a subtle visual cue that it is interactive, such as a small dropdown/chevron icon after the name or a light ripple on touch. The text style, size, position and colour rules stay the same as today. - do not put dropdown/chervron icon
3. Tapping the label opens the **existing password dialog** (the same dialog the kebab menu uses), with the same behaviour:
   - Empty password shows "Please enter a password".
   - Incorrect password shows "Incorrect password", clears the field and keeps the dialog open.
   - Verification failure (for example offline) shows "Error verifying password".
   - Enter/Done on the keyboard submits the password.
   - Cancel closes the dialog with no change.
4. On a **correct password**, the password dialog closes and a **"Select Branch"** dialog opens:
   - A loading indicator is shown while the list of active store locations is fetched.
   - The list shows the **active** store locations by their configured name (full names such as "TC-Kapatagan", matching the dropdown in Program Settings).
   - The branch currently set on this device is **marked as selected** (radio button or check mark).
   - **Cancel** closes the dialog with no change.
5. Tapping a **different** branch shows a **confirmation dialog** with the same wording and intent as the existing Program Settings confirmation: the device will switch from the current branch to the chosen branch and will only show records for the new branch; existing records are not moved. Buttons: **Cancel** / **Confirm**.
6. Tapping the branch that is **already selected** simply closes the picker with no change and no confirmation.
7. On **Confirm**:
   - The device's store location is saved exactly as Program Settings would save it (name, location id, and primary flag).
   - The Main Menu branch label refreshes immediately to the new branch's display name.
   - A short toast confirms the switch, for example **"Switched to Kapatagan"**.
8. If the device had **no store location set**, the picker opens with nothing pre-selected, and choosing a branch saves it **without** the change-confirmation step (there is nothing to switch away from). The label changes from the warning-coloured "No store location set" to the branch name.

### Business Rules

- The password is **always required** before the branch picker is shown. There is no "remember password" or grace period for this action.
- The password is the **existing settings password**; no new password or permission level is introduced.
- The branch list shows only **active** store locations, the same set that the Program Settings dropdown offers.
- A branch swap writes the **same per-device values** that Program Settings writes, so Program Settings, the transaction list headers, and all location-filtered screens see the new branch immediately with no restart.
- Swapping branches changes **only** the store location values. User name, account names, feature toggles, inventory status filter, and the signed-in Firebase account are **not** changed.
- Switching branches does **not** move, copy or edit any records. It only changes which records this device displays and which branch new records are tagged with.
- The Main Menu's device transaction notifications continue to follow the device's current branch after a swap, the same way they do after a change made in Program Settings.
- The kebab menu and Program Settings path are **unchanged**. Both ways of changing the branch coexist.

---

## Data Model

No Firestore changes. No new collections, documents or fields.

- **Read:** the active store locations from the existing store locations collection (the same source Program Settings uses for its dropdown).
- **Write:** the existing per-device store location settings (name, id, primary flag) in the app's SharedPreferences. No new keys.

**Layouts touched:** the Main Menu branch label becomes clickable and may gain a small indicator icon. A new simple dialog layout (or a standard single-choice list dialog) is used for the branch picker.

## Business Rules (Summary)

1. Tapping the Main Menu branch label asks for the existing settings password first.
2. After a correct password, a picker lists the active store locations with the current one pre-selected.
3. Choosing a different branch requires confirmation; choosing the current branch does nothing.
4. Confirming saves the same store location values Program Settings saves and refreshes the label at once.
5. Only the store location changes. All other settings and the login session are untouched.
6. Program Settings keeps working as the alternative way to change the branch.

## Edge Cases and Error Handling

- **Offline when fetching branches:** the picker shows an inline message such as "Could not load store locations. Check your connection and try again." with a **Retry** option and **Cancel**. The current branch is not changed.
- **Offline when verifying the password:** the existing "Error verifying password" message is shown and the picker does not open.
- **No active store locations found:** the picker shows "No store locations available" and only a Cancel button.
- **Current branch no longer in the list** (deactivated or renamed on the backend): the picker opens with no item pre-selected. The device keeps its current branch until the user picks one and confirms.
- **Repeated taps on the label:** only one password dialog can be open at a time; extra taps while a dialog is showing are ignored.
- **Password dialog dismissed by Back or Cancel:** nothing changes.
- **Picker or confirmation dismissed by Back:** treated as Cancel; nothing changes.
- **Screen rotation or app backgrounded mid-flow:** the dialog may be dismissed; no partial state is saved. The branch only changes on Confirm.
- **Long branch names:** the picker rows wrap or ellipsize so every row stays tappable; the Main Menu label keeps its single-line ellipsis behaviour.
- **Unconfigured device:** tapping "No store location set" follows the same password-then-pick flow and sets the branch for the first time, replacing the current need to go through Program Settings.

## Open Questions

1. **Visual cue:** should the label show a small dropdown chevron after the branch name to signal it is tappable, or stay plain text with only a touch ripple? - no dropdown chevron. make it plain text with a touch ripple
2. **Picker names:** show full names ("TC-Kapatagan") to match Program Settings, or the shortened display name ("Kapatagan") to match the label? - shortened display to match the label
3. **Confirmation step:** keep the "Change Store Location?" confirmation after picking a branch, or is the password prompt alone enough protection for this shortcut? - yes confirmaation after picking branch
4. **Toast wording:** is "Switched to Kapatagan" acceptable, or is no toast needed since the label itself updates? - its acceptable
