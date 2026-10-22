# Feature Spec: Financing Accounts Live Listener (Read Reduction)

## Description

Cut the Firestore reads of the **Financing Accounts** module by replacing the one-shot fetch that runs on every open and every return with a single real-time listener that is attached once per visit, and by making the query's two-year cutoff stable so Firestore can resume the listener cheaply between visits.

Today `FinancingAccountListActivity` fetches the full two-year window (currently 796 documents) in `onCreate` **and again** in `onResume`, so the first open bills about 1,592 reads. Every return from the Add, Edit or Detail screens fires `onResume` and bills another 796, whether or not anything was saved. Nothing is kept between visits.

With this feature a visit costs the full window **once, on attach**, and then only one read per account that changes while the screen is open. Returning from Add, Edit or Detail costs nothing because the listener never detaches. Reopening the module within about 30 minutes of leaving it costs only the documents that changed in between (minimum one read). Opening after a longer gap costs the full window again.

There are **no new Firestore writes, no schema changes and no new indexes**. The Add screen, Detail screen, model class and layouts are untouched.

The second device, which only views accounts, now sees additions, edits and deletes made on the first device **live**, without leaving and reopening the module.

---

## Resolved Decisions

- **Listener replaces the earlier "Step 2" result-launcher idea.** With a live listener there is no need to report saved document ids back from the Add screen or to patch single rows; the listener delivers every change. The Add screen stays exactly as it is.
- **Listener is activity-scoped.** Attached in `onCreate`, removed in `onDestroy`. It stays attached across `onPause`/`onResume`, so navigating to Add, Edit or Detail and back does not re-attach. A process-wide singleton listener was considered and deferred; it can be a later step if the bill is still too high.
- **`onResume` no longer loads.** The duplicate load on first open (the earlier "Step 1") is removed as part of this change.
- **The two-year cutoff is rounded to the first day of the month**, midnight Asia/Manila, two years back. Firestore identifies a query by its exact filter values; a cutoff computed from the current millisecond makes every open a brand-new query and defeats resume. Rounding to the month makes every open within a calendar month the identical query. The visible side effect is that the list may show up to one extra month of old accounts at the start of each month; accepted.
- **The existing one-shot `get()` is removed**, not kept alongside the listener. The listener's first snapshot replaces it.
- **Delete keeps its local removal.** The listener will also deliver the delete; the local removal is harmless and keeps the existing immediate feedback. No change to the delete code.
- **Same pattern as the Device and Accessory transaction lists**, which already use `addSnapshotListener` with a `ListenerRegistration` removed in `onDestroy`.

---

## User Stories

**As the staff member who views financing accounts on the second device**, I want the list to reflect accounts added, edited or deleted on the counter device without having to leave and reopen the module, so I always see current data.

**As the store owner**, I want the Financing Accounts module to stop re-reading all 796 accounts on every return from a sub-screen, so the module can be used freely on several devices without inflating the Firestore bill.

---

## UI/UX Flow

No visible change to screens, layouts, filters, search, swipe actions, Add, Edit or Detail.

Behavioral differences the user may notice:

1. **Returning from Add, Edit or Detail no longer shows the progress bar** or briefly blanks the list, because no reload happens. The list is already current.
2. **Changes from another device appear on their own** while the list is open. A new account slides into its sorted position; an edited account updates in place; a deleted account disappears. The total financed amount and entry count update at the same time.
3. If a snapshot arrives **while the user is mid-swipe** on a row, the swipe resets (the row snaps back). This is the standard RecyclerView behavior on a full refresh and is the same as the Device and Accessory lists today.

---

## Data Model

No changes.

- Collection: `financing_accounts` (unchanged).
- Model: `FinancingAccount` (unchanged).
- Query shape: `whereGreaterThan("createdAt", cutoff)` + `orderBy("createdAt", DESC)` (unchanged shape; only the cutoff value changes from "now minus two years" to "first of month, two years back").
- Index: the existing single-field automatic index on `createdAt` is sufficient, as it is today.

---

## Business Rules

### Cutoff computation

| Today's date (Asia/Manila) | Cutoff sent to Firestore |
|---|---|
| Any time on Oct 1–31, 2026 | createdAt > Oct 1, 2024 00:00:00 |
| Any time on Nov 1–30, 2026 | createdAt > Nov 1, 2024 00:00:00 |

The cutoff is computed once, when the listener is attached in `onCreate`. A visit that straddles midnight on the last day of a month keeps the old cutoff until the module is reopened; accepted.

### Listener lifecycle

1. `onCreate` attaches the listener and shows the progress bar.
2. Every snapshot (first and subsequent) rebuilds `allAccounts` from the snapshot's documents, hides the progress bar, and calls `applyFilters()` so the current company filter and search text are re-applied and the totals, entry count and empty state refresh.
3. `onResume` does nothing data-related.
4. `onDestroy` removes the listener and cancels the coroutine scope as today.

### Expected reads per action

| Action | Today | After |
|---|---|---|
| Open the module (first time, or after more than ~30 min away) | 1,592 | 796 |
| Open the module within ~30 min of last closing it | 1,592 | changed documents only, minimum 1 |
| Return from Add, Edit or Detail | 796 | 0 |
| Open Add, then cancel | 796 | 0 |
| Another device adds, edits or deletes while list is open | n/a (not visible) | 1 per change |
| Delete on this device | 0 | 1 (the listener's delivery of the removal) |

"796" is the document count on 22 October 2026 and grows as accounts are added.

The resume-within-30-minutes behavior relies on Firestore's default offline persistence on Android, which is enabled in this app (no code sets it off), and on the query being byte-identical between attaches, which the month-rounded cutoff guarantees within a month.

---

## Edge Cases and Error Handling

- **Listener error** (permissions, network failure at attach): hide the progress bar and show the same `Toast` "Error: …" the module shows today. Firestore terminates a listener after delivering an error, so the list stays at whatever it last showed until the module is reopened. This matches the Device and Accessory lists.
- **Empty result**: clear `allAccounts`, run `applyFilters()`, which shows the existing empty-state message.
- **Offline open**: Firestore delivers the cached result immediately (from persistence) and then the server result when connectivity returns. No special handling; the user sees the last-known list.
- **Delete dialog open when a snapshot arrives**: the dialog holds the account's document id and its list position. The delete itself uses the id, so it is unaffected. The `notifyItemChanged(position)` calls on cancel use a possibly stale position; this is pre-existing behavior and is left as is.
- **Month boundary while the module is open**: the cutoff stays at the value computed on attach. Accounts are only ever older, never newer, than the cutoff, so nothing is hidden incorrectly; the window is at most one month wider than two years.
- **Process death while on Add/Edit**: Android recreates the list activity, `onCreate` attaches a fresh listener. If the previous attach was less than ~30 minutes ago, resume applies; otherwise the full window is billed once.

---

## Out of Scope

- Process-wide (singleton) listener that survives leaving the module.
- Local on-disk cache with a watermark (the earlier "Step 3").
- Any change to `AddFinancingAccountActivity`, `FinancingAccountDetailActivity`, `FinancingAccount`, layouts, strings or Firestore rules.
- Manual refresh button.

---

## Open Questions

None.

---

## Addendum (22 Oct 2026): Bottom Summary Bar Covered by Navigation Bar

Requested after the listener implementation was reviewed on a device.

**Problem.** The app targets SDK 36, so Android draws the screen edge-to-edge and the system navigation bar overlays the bottom summary bar. The Total Financed and Entries figures are partly hidden behind it.

**Resolution.** Add the navigation bar's actual height to the summary bar's bottom padding at runtime, via a window insets listener on the bar. A fixed padding value was rejected because gesture and three-button navigation bars differ in height, so no single number fits every device.

**UI effect.** The summary bar grows taller by the navigation bar height; its figures sit fully above the bar. The floating add button is anchored above the summary bar and moves up with it. No layout XML change.

**Out of scope.** Other screens with the same overlap, and the status bar overlapping screen headers, are not addressed here.
