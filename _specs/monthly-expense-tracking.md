
/g# Feature Spec: Monthly Expense Tracking

## Description

A new **Expenses** module for recording store expenses on a per-month basis. The screen follows the same navigation pattern as the End of Day report: **left and right arrow buttons** move between months, and a **total for the displayed month** is shown at the top. Every expense entry recorded is added to the **current month**.

Each expense entry consists of a **description** and an **amount**. The module's visibility is controlled by a new toggle in the **Feature Settings** section of **Program Settings**, following the same pattern as the existing Account Receivable / End of Day / Phone Inventory toggles.

---

## Feature 1: Monthly Expenses Screen

### User Story

**As a store owner**, I want to see all expenses recorded for a given month with a running total at the top, and flip between months with left/right buttons, so I can review how much the store spent in any month.

### UI/UX Flow

1. From the main menu, the user taps the new **Expenses** menu item (visible only when the feature toggle is on).
2. The Expenses screen opens showing the **current month** (GMT+8), e.g. "July 2026".
3. At the top of the screen:
   - A header row with a **left arrow**, the **month label** (month + year), and a **right arrow** — same traversal pattern as the End of Day report's date navigation.
   - A prominent **Total Expenses** figure for the displayed month.
4. Below the header, a scrolling list of the month's expense entries, newest first. Each entry shows:
   - Description
   - Amount
   - Date recorded (day within the month)
5. Tapping the **left arrow** moves to the previous month; the **right arrow** moves to the next month. The list and total refresh for the newly selected month.
6. An **Add Expense** button is available on the screen (see Feature 2).
7. If the displayed month has no entries, an empty state message is shown (e.g. "No expenses recorded for July 2026") and the total shows 0.00.

### Business Rules

- The month shown on open is always the current month in GMT+8.
- The total at the top is the sum of all expense amounts for the displayed month only.
- Month traversal is unbounded backwards (any past month can be viewed). Months with no data simply show the empty state.
- Amounts are displayed with 2 decimal places using the app's existing currency formatting.

---

## Feature 2: Add an Expense Entry

### User Story

**As a store owner**, I want to quickly record an expense by entering a description and an amount, so the expense is captured against the current month.

### UI/UX Flow

1. On the Expenses screen, the user taps **Add Expense**.
2. An entry form appears (dialog or dedicated screen) with:
   - **Description** — free text, required
   - **Amount** — numeric, required, greater than zero
3. The user taps **Save**. The entry is stored with the current date/time (GMT+8).
4. The Expenses screen returns to / refreshes the **current month**, showing the new entry in the list and the updated total.
5. A **Cancel** action dismisses the form without saving.

### Business Rules

- Every new expense is recorded against the **current month** (the month of the save timestamp in GMT+8) — regardless of which month the user was viewing when they tapped Add Expense.
- Description must not be empty or whitespace-only.
- Amount must be a valid number greater than zero; invalid input shows an inline validation error and blocks saving.
- The save writes to Firestore; a save failure shows an error message and the entry is not added to the list.

---

## Feature 3: Feature Toggle in Program Settings

### User Story

**As a store owner**, I want to turn the Expenses module on or off from Program Settings, so it only appears in the menu when I want to use it.

### UI/UX Flow

1. Open **Program Settings** → **Feature Settings** section.
2. A new **Expenses** switch appears alongside the existing Account Receivable, End of Day, and Phone Inventory switches.
3. Toggling the switch and saving settings shows/hides the Expenses item on the main menu, following the exact behavior of the existing feature switches.

### Business Rules

- The toggle is persisted in the existing `"TechCitySettings"` SharedPreferences, as a new flag (e.g. `expenses_enabled`), consistent with `account_receivable_enabled` / `end_of_day_enabled` / `phone_inventory_enabled`.
- When the toggle is off, the Expenses menu item is hidden; recorded data is not deleted — it reappears when re-enabled.

---

## Data Model

New Firestore collection: **`expenses`**. Each document is one expense entry, conceptually:

- `description` — free-text description of the expense
- `amount` — expense amount (numeric)
- `date` — date recorded (GMT+8)
- `monthKey` — a queryable month identifier (e.g. `"2026-07"`) so a month's entries and total can be fetched with a single equality query
- `timestamp` — Firestore server timestamp

A matching Kotlin data class (e.g. `Expense`) is added to the models. No existing collections or model classes are modified.

## Business Rules (Summary)

1. Expenses are grouped and totaled **per calendar month** (GMT+8).
2. New entries always land in the **current month** at save time.
3. An entry is only a **description + amount**; both are required, amount must be > 0.
4. Left/right arrows traverse months, matching the End of Day report navigation pattern; the top shows the displayed month's total.
5. Module visibility is controlled by a Feature Settings toggle in Program Settings, persisted in SharedPreferences like the existing feature flags.

## Edge Cases and Error Handling

- **Empty month**: viewing a month with no entries shows an empty state and a total of 0.00 — no error.
- **Month boundary**: an entry saved at 11:59 PM on the last day of the month (GMT+8) belongs to that month; one saved a minute later belongs to the new month.
- **Invalid input**: empty description or zero/negative/non-numeric amount blocks saving with a validation message.
- **Offline / Firestore failure**: save and load failures show an error message; the total is never shown stale as if it included a failed save.
- **Rounding**: amounts stored and displayed to 2 decimal places; the month total is the sum of the stored per-entry amounts so list and total always agree.
- **Toggle off with data present**: hiding the module never touches stored expense data.

## Open Questions

1. **Edit / delete entries**: should the user be able to edit or delete an expense entry after it is saved (e.g. to fix a typo in the amount)? If yes, should deletion require a confirmation dialog? - yes, requires a confirmation dialog and password 
2. **Future months**: should the right arrow be disabled once the current month is displayed, or may the user navigate into future (empty) months? - should allow navigation of current and previous months not empty months
3. **Default toggle state**: should the Expenses feature be enabled or disabled by default on first run? (Existing flags differ: Account Receivable / End of Day default on, Phone Inventory defaults off.) - disabled by default
4. **Categories**: is description + amount enough, or would expense categories (e.g. Utilities, Supplies, Rent) be useful for a later iteration? - lets keep it simple and just do description for now
5. **End of Day interaction**: expenses are a standalone record and do **not** touch the ledgers or End of Day reconciliation — confirm this is the intent (i.e. cash physically taken from the drawer for an expense is still handled through the existing Cash Out flow, separate from this module). - for now not connected to end of day
