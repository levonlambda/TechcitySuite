# Feature Spec: Branch Location Header Labels

## Description

Show the device's configured **branch (store) location** on screen so staff can always tell which store instance they are working in. The value shown is the Store Location chosen in **Program Settings → User Settings → Store Location** (the same value that drives per-store data separation).

The label appears in four places:

- **Main Menu** — below the "Business Management System" subtitle.
- **Device Transactions** list — in the header, on the same row as the date, centered.
- **Accessory Transactions** list — same placement.
- **Service Transactions** list — same placement.

This is a display-only feature. No data is written, no queries change, and no behaviour changes.

---

## Feature 1: Branch Location on the Main Menu

### User Story

**As branch staff**, when I open the app I want to see which store location this device is set to, so I know at a glance whose records I am looking at.

### UI/UX Flow

1. Open the app; the Main Menu shows the TechCity logo, the "TechCity Suite" title and the "Business Management System" subtitle as today.
2. Directly **below the subtitle**, a new centered line shows the branch location, for example **"TC-Kapatagan"**. It uses a smaller, secondary style consistent with the subtitle (same size or slightly smaller, muted colour, optionally with a small location pin icon).
3. If no store location is configured, the line reads **"No store location set"** in a warning colour, so the problem is obvious before any transaction is attempted.
4. The line refreshes every time the Main Menu is shown (including on return from Program Settings), so changing the store location is reflected immediately.

### Business Rules

- The value shown is the Program Settings store location name, read from the device's saved settings.
- The label is informational; tapping it does nothing.
- The rest of the menu layout (cards, spacing, scroll area) is unchanged apart from the extra line pushing the cards down by one text row.

---

## Feature 2: Branch Location on the Transaction List Headers

### User Story

**As branch staff**, while viewing device, accessory, or service transactions I want to see the branch location alongside the date, so I know the list is filtered to my store.

### UI/UX Flow

1. Open **Device Transactions**, **Accessory Transactions**, or **Service Transactions**.
2. In the blue header, the row that shows the **date** (for example "Today" or "9/26/2026") now also shows the **branch location**:
   - The date stays where it is (left, under the screen title).
   - The branch location is shown **on the same row, centered horizontally** across the header, in the same small white secondary style as the date (same size, same reduced opacity).
   - The calendar (date picker) button stays at the right edge of the title area as today.
3. The date picker, filter chips, summary totals, and the transaction list behave exactly as today.
4. If no store location is configured, the centered label reads **"No store location set"**, matching the empty-state message the list already shows.

### Business Rules

- The three list screens show the same value from the same source as the Main Menu.
- On the Accessory Transactions screen, where the date label currently sits on its own row below the title row, the date label and the new branch label share that row (date left, branch centered) so the placement is consistent across all three screens.
- The label is informational; tapping it does nothing.
- Existing header ids and behaviour (title, date label, date picker button, summary row) are preserved.

---

## Data Model

No Firestore changes. No new SharedPreferences keys. The feature reads the existing store location preference only.

**Layouts touched:** the Main Menu layout and the three transaction list layouts each gain one text view for the branch location. No other screens change.

## Business Rules (Summary)

1. The branch location shown everywhere is the Program Settings store location name.
2. Main Menu: shown below the "Business Management System" subtitle, centered, secondary style.
3. Device / Accessory / Service Transactions: shown on the date row, centered, same style as the date.
4. Unconfigured store location shows "No store location set" in a warning style.
5. Labels refresh whenever the screen is shown; they are display-only.

## Edge Cases and Error Handling

- **No store location configured:** every label shows "No store location set" instead of being blank.
- **Store location changed in Program Settings:** returning to the Main Menu or reopening a list screen shows the new value; no restart required.
- **Long store location names:** the label is single-line and truncates with an ellipsis rather than wrapping or pushing the date/date-picker out of place.
- **Narrow screens:** on the transaction lists the centered label must not overlap the date on the left or the calendar button on the right; when space is tight the branch label truncates first.

## Open Questions

1. **Menu label wording:** show just the name (e.g. "TC-Kapatagan"), or with a prefix such as "Branch: TC-Kapatagan"? The spec assumes the name alone with a small location pin icon. - Hardcode to remove the "TC-" and just show Kapatagan
2. **Icon:** is a location pin icon wanted on the Main Menu label and/or the list headers, or plain text only? - no icon needed
3. **Unconfigured wording and colour:** "No store location set" in a warning (amber/red) colour, or the same muted style as the normal label? -warning color is nice
4. **Other screens:** should the same label also be added to the Ledger, Expenses, End of Day list, and Phone Inventory headers for consistency, or only the four screens requested? - only on the screen requested
