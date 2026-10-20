# Feature Spec: Overview Module

## Description

Add an **Overview** module that gives the owner a cross-branch picture of sales and of how much physical cash each branch is holding. The screen shows **one card per branch**, and each card holds:

1. **Cash on Hand**: **Today** and **Running** (cash accumulated since it was last retrieved), with a swipe-left action to record a retrieval.
2. Three collapsible sales blocks, **Daily Sales** (defaults to today), **Monthly Sales** (defaults to the current month) and **Yearly Sales** (current calendar year). At most one block is open at a time; Daily is open by default, and tapping the open block collapses it so all three can be closed.

Each sales block shows a **period total** and the same color-coded rows as the End of Day report:

- **Cash Sale** — total of fully paid (Cash Transaction) sales for the period, devices and accessories combined, regardless of how the customer paid.
- **Home Credit Sale** — total selling price of Home Credit sales in the period (downpayment plus financed balance).
- **Skyro Sale** — same for Skyro.
- **Salmon Sale** — same for Salmon.
- **In-House Sale** — same for In-House installment sales.

Like the other optional modules (End of Day, Phone Inventory, Financing Accounts, Expenses, Stock Transfer), the Overview module is **enabled or disabled per device from Program Settings** and appears as a card on the Main Menu only when enabled. It is **off by default**.

To keep Firestore reads low, **Daily Sales and Cash on Hand (Today) are computed live** from today's transactions, while **Monthly and Yearly Sales and Cash on Hand (Running) are read from the saved End of Day documents**, which are generated daily per branch.

The module is **read-only for sales data**. The only write it performs is recording a **cash retrieval** (Feature 4).

Unlike every other screen in the app, the Overview is **not filtered to the device's own branch**. Every section shows every branch so the owner can compare and manage them from one device.

---

## Resolved Decisions

Carried over from the reviews of this spec:

- **Cash** means all fully paid (Cash Transaction) sales regardless of payment source (cash, GCash, PayMaya, bank transfer, credit card, others).
- **Password:** enabling the toggle per device is enough control to open the Overview. The settings password is still required to record a cash retrieval.
- **Yearly period** is fixed to the current year. No year picker.
- **Transactions with no branch recorded** (legacy records) are counted under the **primary** branch.
- **In-House** uses the total amount due including interest, matching End of Day.
- **One card per branch.** Cash on Hand, Daily, Monthly and Yearly all live inside the same branch card. There is **no "All Branches" totals card** in this version.
- **Collapsible periods.** Each card shows the three period headers (with totals) and expands at most one at a time; Daily is open by default, and tapping the open header collapses it so all three can be closed. Changing the Daily date or the Monthly month expands that period in every card.
- **Period total** is shown in each block's header, and rows follow the End of Day colors (Cash green, Home Credit red, Skyro light blue, Salmon orange, In-House purple) in a label / amount / percent layout, the percent being each row's whole-number share of the period total (0% when the total is zero). **No Downpayment / Balance sub-rows**, to keep the cards short.
- **Default on install:** off.
- **Period selector in the header.** Daily, Monthly and Yearly are all shown on the same screen; the blue header carries three pills (Daily date, Monthly month, Yearly year) styled like End of Day's date selector.
- **Monthly and Yearly** are built from End of Day documents rather than raw transactions.
- **Cash on Hand is shown per branch**, for every branch, not only the device's own branch. The extra End of Day reads per branch are accepted.
- **Retrieval excludes Today.** A retrieval collects the Running amount only, so each branch still performs its own End of Day count for the current day.
- **Retrieval is always the full Running amount.** No partial retrievals.
- **Running starts on 16 October 2026** for every branch that has never been retrieved.
- **Retrieval is triggered by swiping a branch's Cash on Hand card to the left**, then entering the settings password, then confirming.
- **Expenses are ignored** in cash on hand.
- **Running is read from End of Day documents.** Days without a report are not computed live.
- **Only a "Last retrieved" line** is shown. No retrieval history list in this version.
- **No Total line.** The Cash on Hand card shows Today and Running only.
- **Swipe feedback:** the card slides with the finger and reveals a "Retrieve" hint behind it. Releasing past the threshold starts the flow; releasing earlier snaps the card back.

---

## Feature 1: Overview Toggle in Program Settings

### User Story

**As the store owner**, I want to turn the Overview module on only for the devices I use for oversight, so branch staff devices keep their normal menu.

### UI/UX Flow

1. Open **Program Settings** (password protected as today) and scroll to **Feature Settings**.
2. A new toggle row **"Overview"** with the description **"Show Overview in main menu"** appears after the existing Stock Transfer toggle, styled like the other feature toggles.
3. The toggle is **off by default** on a fresh install.
4. Saving settings stores the toggle. Returning to the Main Menu shows or hides the Overview card immediately, the same way the other feature cards react.

### Business Rules

- The toggle is a per-device setting stored with the other feature flags. It is not synced between devices.
- Turning the module off hides the card only; no data is removed.

---

## Feature 2: Overview Card on the Main Menu

### User Story

**As the store owner**, I want a single entry point on the Main Menu that opens the overview.

### UI/UX Flow

1. When the Overview toggle is on, a new **"Overview"** card appears on the Main Menu using the same card style as the existing feature cards (icon, title, one-line description such as "Cash on hand and sales across all branches").
2. The card is placed after the **Expenses** card, keeping the current card order otherwise unchanged.
3. Tapping the card opens the **Overview** screen.

### Business Rules

- The card follows the existing show/hide behaviour: it is re-evaluated every time the Main Menu is shown.
- No password prompt is required to open the Overview beyond the usual app login.

---

## Feature 3: Overview Screen — Sales Sections

### User Story

**As the store owner**, I want to see each branch's cash sales, downpayments collected, and financed balances for today, this month, and this year on one screen, so I can compare branch performance and know what is owed by each financing partner without tapping through filters.

### UI/UX Flow

1. The screen opens with the standard TechCity blue header titled **"Overview"**, a back button, and a **refresh** icon button.
2. A second header row holds the **period selector**: three equal pills in the translucent style of End of Day's date selector, each with a small caption and the current value:
   - **Daily:** the date (for example "Today" or "10/18/2026") with a dropdown arrow; tapping opens a date picker. Past dates can be chosen. Future dates are not selectable.
   - **Monthly:** the month (for example "Oct 2026") with a dropdown arrow; tapping opens a month/year picker. Past months can be chosen. Future months are not selectable.
   - **Yearly:** the current year (for example "2026"). No picker.
3. Under the header, the body is a single vertical scroll holding the **branch list**: one card per active store location, sorted alphabetically by branch name. Each card shows:
   - **Branch name** as the card title, using the shortened display name used elsewhere (for example "Kapatagan").
   - Three collapsible blocks, separated by thin dividers: **Daily Sales**, **Monthly Sales**, **Yearly Sales**. Each block has:
     - A tappable header with a chevron, the block title, the period label (for example "Today", "OCT", "2026"; the month block shows the abbreviated month in capitals only, the year is in the header pill) and the **period total** on the right, styled like the End of Day sales card headers (TechCity blue, bold). The header is always visible, so all three totals can be read at a glance.
     - When expanded, rows with the label on the left, then the amount, then its share of the period total as a whole percent (for example "15%") on the right, in End of Day colors: **Cash Sale** (green), **Home Credit Sale** (red), **Skyro Sale** (light blue), **Salmon Sale** (orange), **In-House Sale** (purple). Each financing row is the sale amount; there are no Downpayment / Balance sub-rows.
     - **At most one block is expanded at a time** per card. Daily is expanded by default; tapping another header expands it and collapses the open one; tapping the open header collapses it, leaving all three closed. Changing the Daily date or the Monthly month expands that period in every card.
   - The **Cash on Hand** block (Feature 4) on a tinted panel, below the Yearly Sales block.
   - All amounts are shown in peso format with two decimals (for example "₱12,345.00"). Zero values display as "₱0.00", not blank.
5. There is no "All Branches" totals card.
6. Each period loads independently. While a period is loading, its block in every card shows "Loading…"; if it fails, the block shows "Could not load" and an error line with a **Retry** button appears above the cards, so the Daily block (which is small) appears without waiting for Yearly.
7. The screen loads everything on open. Changing the Daily date or the Monthly month re-fetches only that period. The header refresh button re-fetches everything.
8. Tapping a sales row does nothing in this version (display only).

### Business Rules — Period Scope

- **Daily** includes transactions whose transaction date equals the selected date.
- **Monthly** includes the days of the selected month, from the 1st to the last day of the month (or to today, for the current month).
- **Yearly** includes the days of the current year from January 1 to today.
- All periods use the app's GMT+8 convention, matching how transactions and End of Day reports are stamped.

### Business Rules — Daily Source (live)

- The Daily section is computed **live from transactions**, the same way the End of Day report computes its sales summary, so it is correct even before today's End of Day report has been generated.
- **Today auto-updates.** While the screen is open, Firestore listeners watch today's completed device, accessory and service transactions. A sale recorded on any device updates the Daily block (when it shows today), the Cash on Hand "Today" figure, and the Monthly and Yearly totals (which include today) without tapping refresh. A past Daily date is fetched once and does not auto-update. The listeners stop when the screen closes.
- Only **device transactions** and **accessory transactions** are included. **Service transactions** are excluded from the sales figures (they feed only Cash on Hand).
- Only transactions with status **completed** are included. Voided and returned transactions are excluded.
- A transaction belongs to the branch recorded on it at the time of sale. Transactions are **not** re-attributed if a device later changes its branch.
- Transactions with **no branch recorded** are counted under the **primary** store location.

### Business Rules — Monthly and Yearly Source (End of Day documents)

- The Monthly and Yearly sections are built by **summing the saved End of Day reports** for each day in the period, per branch. Each End of Day report already contains, per branch and per day, the cash sales amount, and the downpayment and balance for Home Credit, Skyro, Salmon and In-House, for devices and accessories separately. The Overview sums devices plus accessories.
- **Today** is the exception: today's figures in the Monthly and Yearly sections come from the **live Daily computation** (for today's date), not from an End of Day document, so the month and year totals are current even before today's report is generated. If today's report has already been saved, the live figures are still used.
- **Legacy End of Day reports** saved before per-branch reports existed (no branch on the document) are counted under the **primary** branch.
- A day with **no End of Day report** for a branch contributes zero for that branch and day. Monthly and Yearly do not flag missing days on screen; the Cash on Hand block's warning line and the retrieval confirmation still report missing days in the Running range.
- An End of Day report is a **snapshot taken when it was generated**. If transactions were added or voided after the report was saved, the Overview shows the saved figures, not the corrected ones. Re-generating and re-saving that day's report from the End of Day module corrects the Overview.

### Business Rules — Figure Definitions

These definitions match the End of Day report exactly so the Overview and End of Day never disagree for the same day and branch.

| Figure | Definition |
|---|---|
| **Period total** | Cash Sale + Home Credit Sale + Skyro Sale + Salmon Sale + In-House Sale amounts, the same sum End of Day shows as its sales card total. |
| **Cash Sale** | Sum of the final selling price of all **Cash Transaction** type sales (devices + accessories), regardless of how the customer paid. |
| **Home Credit Sale** | Sum of the final selling price of Home Credit sales, the same figure as End of Day's "Home Credit Sale" line. |
| **Skyro Sale** | Same for Skyro sales. |
| **Salmon Sale** | Same for Salmon sales. |
| **In-House Sale** | Same for In-House installment sales (selling price, not the total amount due with interest). |

- The figures are the sales **made** in the period. They do not change when a partner later remits payment or when an In-House customer pays an installment.
- Downpayments, financed balances and Brand Zero subsidies are not shown separately in this version.
- The Cash Sale figure does not include downpayments on financed sales; those are part of the financing type's sale amount.

### Business Rules — Branch List

- The branch list is the set of **active** store locations, the same list used by the Program Settings Store Location dropdown and the branch picker.
- Every active branch is listed even if it has no figures in the period; its figures show as zero.
- Figures recorded under a branch name that is **not in the active list** are not shown anywhere in this version (there is no "All Branches" totals card).

---

## Feature 4: Cash on Hand by Branch (Today, Running, Retrieval)

### User Story

**As the store owner**, I want to see how much physical cash each branch is holding, split into today's cash and the cash carried over from previous days that I have not yet collected, so I know when to visit each branch and how much to expect. When I collect, I want to record it from the same screen so the running amount resets.

**As branch staff**, I want today's cash to stay out of the owner's retrieval so I can still count the drawer against the End of Day report at closing.

### Worked Example

Branch Kapatagan, last retrieved on the evening of 15 October. Daily cash figures from its End of Day reports:

| Day | Cash for the day |
|---|---|
| 16 October | ₱5,000.00 |
| 17 October | ₱7,000.00 |
| 18 October (today, so far, live) | ₱3,000.00 |

On 18 October the Kapatagan card shows:

- **Today:** ₱3,000.00 (live, keeps changing as sales come in)
- **Running:** ₱12,000.00 (16 + 17 October, from End of Day reports)

If the owner retrieves on 18 October, they collect **₱12,000.00** (Running). Today's ₱3,000.00 stays in the drawer for the End of Day count. Immediately after, the card shows Today ₱3,000.00, Running ₱0.00, "Last retrieved: 10/18/2026 2:35 PM". On 19 October, Running becomes 18 October's final cash (from its End of Day report) and Today starts again from zero.

### UI/UX Flow

1. The **Cash on Hand** block sits at the **bottom of each branch card**, below the Yearly Sales block, on a tinted panel titled **"Cash on Hand"**.
2. The block shows:
   - **Today** — cash received today, computed live.
   - **Running** — cash from previous days since the last retrieval (or since 16 October 2026 if never retrieved), not yet collected.
   - A line **"Last retrieved: <date> <time>"**, or **"Never retrieved"** when there is no record.
   - When any day in the Running range has no End of Day report, a small warning line **"N days without End of Day report"** in the warning colour.
3. The block shows **"Loading…"** while figures are fetched, independent of the sales blocks. A failed load shows "Could not load" in the block and the error line with Retry described in Edge Cases.
4. **Retrieval gesture:** the user **swipes the Cash on Hand panel to the left**. The panel follows the finger and, as it slides, reveals a **"Retrieve"** hint (label and icon) on the right-hand side behind it. Releasing after the panel has travelled past the threshold (about 40% of its width) completes the swipe and starts the retrieval flow. Releasing earlier snaps the panel back with no effect. Swiping right does nothing. Ordinary taps do nothing. A mostly vertical drag scrolls the list as usual and does not start a swipe.
5. After a completed swipe, the **existing settings password dialog** appears (same dialog, messages and behaviour as the kebab menu and branch swap).
6. On a correct password, a **confirmation dialog** appears:
   - Title: **"Retrieve Cash — <branch>"**
   - Body: **"Running cash to retrieve: ₱12,000.00"**, the range covered (**"16 Oct 2026 – 17 Oct 2026"**), and, if applicable, the warning **"2 days in this range have no End of Day report. The amount may be understated."**
   - A reminder line: **"Today's cash (₱3,000.00) is not included."**
   - Buttons: **Cancel** / **Confirm Retrieval**.
7. On **Confirm Retrieval**, the retrieval is saved, a toast **"Retrieved ₱12,000.00 from Kapatagan"** is shown, and the card refreshes: Running becomes ₱0.00 and "Last retrieved" updates to now.
8. If Running is **₱0.00** when the swipe completes (for example the branch was already retrieved today), the password dialog still appears, and the confirmation dialog instead says **"Nothing to retrieve. Running cash is ₱0.00."** with only a **Close** button. No retrieval is recorded.

### Business Rules — Daily Cash Definition

A day's **cash** for a branch is the **Cash** line of the End of Day report's daily ledger summary, computed the same way:

- **Devices:** cash-source payments on device transactions. This is the full price of Cash Transactions paid in **Cash**, plus the downpayments on Home Credit, Skyro, Salmon and In-House device sales whose downpayment source is **Cash**.
- **Accessories:** the same rule applied to accessory transactions.
- **Services:** service transactions' **cash inflow minus cash outflow** (for example cash received for a GCash cash-in, minus cash paid out for a cash-out).
- GCash, PayMaya, bank transfer, credit card and "Others" payments are **not** cash on hand.
- Only completed transactions count, as in End of Day.
- **Expenses are ignored.** Cash paid out as an expense does not reduce cash on hand, matching End of Day.

### Business Rules — Today

- **Today** is computed **live** from today's transactions for each branch, whether or not today's End of Day report exists. It changes during the day as sales are recorded.
- Today always reflects the current calendar date (GMT+8). It is not affected by the Daily Sales date picker.
- Today is **never** included in a retrieval and is **never** reset by one.

### Business Rules — Running

- **Running** for a branch is the sum of the daily cash of every day in the **Running range**: from the **day after the last retrieval's end date** (or from **16 October 2026** if the branch has never been retrieved) **up to and including yesterday**.
- Each day's cash is read from that day's **End of Day document** for the branch (saved cash flow figures: device cash + accessory cash + service cash inflow minus outflow). Days without an End of Day document contribute **zero** and are counted in the card's warning line. Missing days are **not** computed live.
- Legacy End of Day documents with no branch are counted under the **primary** branch, consistent with the rest of the app.
- When a day passes without a retrieval, yesterday's cash moves from Today into Running automatically because the date rolled over. No action is needed in the Overview for this to happen.
- If the Running range is empty (last retrieval was today, or today is 16 October 2026 and nothing has been retrieved), Running is ₱0.00 with no warning.

### Business Rules — Retrieval

- A retrieval collects the **full Running amount** and **only** the Running amount. It is recorded against the branch whose card was held, regardless of which device records it.
- Each retrieval stores: the branch (name and id), the date and time recorded, the **range covered** (start and end dates; the end date is always yesterday), the **amount** (Running at that moment), the **number of days in the range without an End of Day report**, and who recorded it (the Program Settings user name and the signed-in account).
- Running for a branch is always computed from that branch's **most recent retrieval**. A second retrieval on the same day finds an empty range and is blocked by the "Nothing to retrieve" dialog.
- A retrieval does **not** modify, lock or regenerate any End of Day report, and does not touch transactions.
- Recording a retrieval requires the **settings password** every time. There is no grace period.
- The left swipe is the **only** way to start a retrieval. There is no button, menu item or long-press.
- When the password dialog or the confirmation dialog is cancelled, or after a retrieval completes, the card animates back to its resting position.

---

## Data Model

### Existing data read

- Active store locations (existing collection, same as Program Settings).
- Device, accessory and service transactions for **today** (and device and accessory transactions for the Daily section's selected date), across all branches, completed only.
- End of Day documents (`daily_summaries`) for each day in the Monthly and Yearly periods for every branch, and for each day in each branch's Running range.

### New data

- **Settings:** one new per-device feature flag for the Overview toggle, stored alongside the existing feature flags.
- **Cash retrievals:** a new Firestore collection, one document per retrieval, holding: branch name, branch id, recorded-at timestamp, range start date, range end date, amount, count of days without an End of Day report, recorded-by user name, recorded-by account. The most recent document per branch determines that branch's Running range. Nothing else is written.

**Screens touched:** Program Settings (one new toggle row), Main Menu (one new card), and one new Overview screen with its section and card layouts.

## Business Rules (Summary)

1. Overview is an optional module toggled per device in Program Settings; off by default. No extra password to open it; the settings password is required to record a retrieval.
2. One screen, one card per active branch holding the Cash on Hand block and the collapsible Daily, Monthly and Yearly blocks (at most one open at a time, Daily by default, all can be closed). The period selector lives in the blue header.
3. Each sales block shows a period total in its header and, when expanded, color-coded rows for Cash Sale, Home Credit Sale, Skyro Sale, Salmon Sale and In-House Sale (sale amounts with their percent of the period total, no sub-rows). There is no "All Branches" totals card.
4. Daily figures and Today's cash are computed live from completed transactions (device and accessory for sales; plus service cash in/out for cash on hand). Services and expenses are excluded from the sales figures; expenses are also excluded from cash on hand.
5. Monthly, Yearly and Running are sums of saved End of Day documents, with today taken live for the sales sections. Missing reports are counted and shown, never computed live.
6. Figure definitions match the End of Day report exactly. Legacy records and legacy reports count under the primary branch.
7. Running covers the day after the last retrieval (or 16 October 2026) through yesterday. A retrieval, started by swiping the branch card left, then password, then confirmation, collects the full Running amount and never Today.

## Edge Cases and Error Handling

- **Offline or fetch failure:** an error line above the cards names the section ("Cash on Hand: Could not load. Check your connection and try again.") with a **Retry** for it, and that block in every card shows "Could not load". Other sections are unaffected. Previously shown figures in the failed section are cleared.
- **Offline when confirming a retrieval:** the save fails, an error message "Could not record retrieval. Check your connection and try again." is shown, and Running is unchanged. Nothing is queued for later.
- **No active store locations, or the location list fails to load:** the body shows a single message ("No store locations found" or the connection error) with one Retry; no cards are shown.
- **No store location on this device:** the Overview still works in full, because no section is filtered by the device's own branch.
- **No transactions in the period:** the block's total and rows show ₱0.00.
- **Missing End of Day reports:** Monthly, Yearly and Running are understated by those days. The Cash on Hand warning line makes this visible for the Running range, and the retrieval confirmation repeats the warning so the owner can cancel and have the branch generate the missing reports first. Monthly and Yearly show no warning.
- **End of Day report saved then transactions changed:** the Overview shows the saved snapshot until the report is re-generated.
- **Retrieval before yesterday's End of Day report exists:** yesterday contributes zero to Running and shows in the warning. If the owner confirms anyway, yesterday's cash is **not** carried into the next Running range (the range end is yesterday), so it would be lost from tracking. The confirmation warning exists to prevent this.
- **Two devices retrieve the same branch at nearly the same time:** both see the same Running and both may record a retrieval; the second one covers the same range and records the same amount. The owner should treat duplicate records for the same range as one retrieval. (Full conflict protection is out of scope for this version.)
- **Yearly reads:** at most one End of Day document per branch per day (about 365 per branch per year). This is far fewer reads than raw transactions but still grows with branch count; the Yearly section loads last and independently.
- **Running reads:** one End of Day document per branch per day since its last retrieval. Retrieving regularly keeps this small.
- **Picker change while loading:** the earlier result for that section is discarded; only the most recently requested period is displayed.
- **Future dates and months** are disabled in the pickers.
- **Branch renamed on the backend:** old transactions, old reports and old retrievals keep the old name and will not match the renamed branch card, so their sales figures are not shown. Running for the renamed branch restarts from 16 October 2026 under the new name.
- **Brand Zero subsidy:** the Home Credit / Skyro / Salmon balance already excludes the subsidy; no adjustment is made.
- **Day rollover while the screen is open:** figures are not re-computed automatically; the refresh button or reopening the screen picks up the new day.
- **Accidental swipe:** releasing before the threshold snaps the card back silently. A mostly vertical drag scrolls the list and never starts a swipe. Cancelling the password or confirmation dialog snaps the card back.
- **Long branch names:** card titles are single line and ellipsize.
- **Narrow screens:** the two-column figure grid collapses to one column if labels and amounts do not fit.

## Open Questions

None. All questions have been resolved; see Resolved Decisions.
