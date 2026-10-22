# Feature Spec: Overview Latest Retrieval Query (Server-side limit 1)

## Description

Cap the Firestore reads of the Overview's **latest retrieval lookup** at one document per branch.

Today `fetchLatestRetrieval` in `OverviewActivity` fetches **every** `cash_retrievals` document for a branch and picks the one with the greatest `rangeEnd` on the device. That costs one read per retrieval ever recorded, per branch, on every open, every refresh and after every retrieval saved. With daily retrievals it reaches about 365 reads per branch per year.

With this change Firestore sorts and limits server-side, so each branch costs exactly one read regardless of history. A **composite index** on `cash_retrievals` is required.

Nothing on screen changes.

---

## Resolved Decisions

- **Same winner as today.** Greatest `rangeEnd` wins; ties resolve to the greatest `recordedAt`. `rangeEnd` is a `yyyy-MM-dd` string, so Firestore's string order equals the current client-side comparison.
- **Index is created in the Firebase console before the build ships**, the same way the app's existing composite indexes (Accounts Receivable, accessory list) are managed. The definition is recorded in the technical plan for reference.
- **No data model change.** The subcollection alternative (which needs no composite index) is rejected because it would require migrating existing retrieval documents.
- **No code outside `fetchLatestRetrieval` changes.**

---

## Feature: Latest Retrieval in One Read

### User Story

**As the store owner**, I want the Overview's cost to stay flat as retrievals accumulate, so recording a retrieval every day does not make the screen progressively more expensive to open.

### UI/UX Flow

No visible change. Running, "Last retrieved" and the retrieval flow behave exactly as today.

### Business Rules

- The latest retrieval for a branch is the `cash_retrievals` document with `storeLocation` equal to the branch name, ordered by `rangeEnd` descending then `recordedAt` descending, limited to one.
- A branch with no retrievals yields no document; Running starts at the configured start date, as today.
- A retrieval document missing `rangeEnd` or `recordedAt` is **not** returned by an ordered query. Every document the app has written sets both, so this is accepted.

---

## Data Model

### Existing data read

- `cash_retrievals`: `storeLocation`, `rangeEnd`, `recordedAt`. No new fields.

### New Firestore configuration

- **Composite index**, collection `cash_retrievals`, query scope Collection:

| Field | Order |
|---|---|
| `storeLocation` | Ascending |
| `rangeEnd` | Descending |
| `recordedAt` | Descending |

---

## Firestore Cost Summary

Three branches, one open of the Overview:

| | Today | After change |
|---|---|---|
| Latest retrieval reads, 1 month of daily retrievals | ~90 | 3 |
| Latest retrieval reads, 1 year of daily retrievals | ~1,095 | 3 |
| Reads after recording one retrieval | same as above, again | 3 |
| Writes | 0 | 0 |

---

## Edge Cases and Error Handling

- **Index missing or still building:** the query fails. Because the lookup runs inside the summaries load, Cash on Hand, Yearly and the current Monthly block show "Could not load" with Retry. Retry succeeds once the index is Enabled. The build must not ship before that.
- **Offline:** unchanged; same `get()` with default source as today.
- **Two retrievals with identical `rangeEnd`:** the later `recordedAt` wins, as today.

## Open Questions

None.
