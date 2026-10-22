# Feature Spec: Overview Summary Cache (Incremental End of Day Sync)

## Description

Cut the Firestore reads of the **Overview** module by keeping a local copy of the End of Day documents (`daily_summaries`) on the device and, on every open, fetching only the documents that were **saved or re-saved since the last visit**.

Today the Overview re-reads every End of Day document from January 1 to yesterday, for every branch, on **every open and every refresh**. On 22 October 2026 with three branches that is roughly 880 billed reads per open, growing by one per branch per day until the year rolls over. Nothing is kept between visits.

With this feature a device pays the full year **once**, on its first visit. Every later open costs **one read when nothing changed**, plus one read per End of Day report that was saved or re-generated since. There are **no new Firestore writes**: the End of Day save already stamps each document with a server `timestamp`, and that is the only signal this feature needs.

Nothing on screen changes. Figures, labels, sections, pickers, swipe-to-retrieve and error handling all behave exactly as they do now.

---

## Resolved Decisions

- **Scope is reads only.** The Overview's screens, layouts, figure definitions and retrieval flow are unchanged. This is a data-access change inside `OverviewActivity`.
- **Cache lives on the device**, in the app's private files directory, as one JSON file. It is per device, not per account, and is never synced anywhere.
- **Watermark is a server timestamp**, copied from the newest `timestamp` field among the documents the device has seen. The device clock is never used, so a branch device with a wrong clock cannot cause a report to be skipped.
- **Only the fields the Overview uses are cached:** document id, `date`, `storeLocation`, `salesSummary`, `cashFlowSummary` and `timestamp`. Transaction id lists and the other End of Day fields are dropped to keep the file small.
- **Past months inside the cached range are served from the cache** with no query. Months earlier than the cached range still use the existing per-month server query.
- **The refresh button keeps working as it does now** (re-fetch everything), but the End of Day part of "everything" becomes the incremental query. A **long-press on the refresh button** clears the cache and does a full reload, as the escape hatch when the cache is suspected to be wrong.
- **End of Day module is not touched.** It already writes `timestamp = FieldValue.serverTimestamp()` on every save and overwrites, never deletes, so no change is needed there.
- **No new indexes.** A range query on the single `timestamp` field uses the automatic single-field index.

---

## Feature 1: Local End of Day Cache

### User Story

**As the store owner**, I want the Overview to open without paying for the same hundreds of End of Day documents every time, so the module can be used freely on several devices without inflating the Firestore bill.

### UI/UX Flow

There is no visible change. The Overview opens, shows "Loading…" in the Monthly, Yearly and Cash on Hand blocks, then the figures. The only perceptible difference is that later opens are faster because almost nothing is downloaded.

### Business Rules — First Visit on a Device

1. No cache file exists.
2. The Overview computes the **needed start date** exactly as today: the earlier of January 1 of the current year and every branch's Running start date.
3. It runs **one range query** for End of Day documents with `date >= needed start` (no upper bound, so a report already saved for today is included and will be in the cache when it becomes "yesterday").
4. Every returned document is stored in the cache. The **watermark** is set to the greatest `timestamp` among them. The **cache start** is set to the needed start date.
5. Monthly, Yearly and Running are built from the cache, filtered by date, exactly as they are built from the query result today.

### Business Rules — Later Visits

1. The cache file is loaded from disk (no Firestore cost).
2. If the needed start date is **earlier** than the cache start (for example a branch's Running range reaches further back than before), a **gap query** for `date >= needed start` and `date < cache start` is run and merged, and the cache start moves back. In normal use this never happens.
3. **One incremental query** is run: End of Day documents with `timestamp > watermark`. Results are merged into the cache **by document id**, so a re-generated report replaces its earlier copy. The watermark advances to the greatest timestamp seen.
4. The cache is saved to disk.
5. Monthly, Yearly and Running are built from the cache as before.

### Business Rules — What Is Served From the Cache

| Section | Source today | Source with this feature |
|---|---|---|
| Daily (any date) | live transactions | unchanged |
| Cash on Hand — Today | live transactions | unchanged |
| Cash on Hand — Running | year-long query | cache |
| Monthly — current month | year-long query | cache |
| Monthly — past month inside cached range | its own per-month query | cache, **no query** |
| Monthly — past month before cached range | its own per-month query | unchanged |
| Yearly | year-long query | cache |

### Business Rules — Correctness

- **Re-generated reports are picked up.** Re-saving an End of Day report overwrites the same document and gives it a new server timestamp, so the next open fetches it and replaces the cached copy.
- **Legacy reports** (document id is the bare date, no `storeLocation`, no `timestamp`) are fetched by the first full load through the date range and never change, so they never need to be re-fetched. If a branch later saves a per-store report for the same date, that report has a timestamp, is fetched incrementally, and takes precedence under the existing "per-store beats legacy" rule.
- **A report saved offline** by a branch device has no server timestamp until it syncs. The incremental query runs on the server and sees it only once it is committed, with a commit-time timestamp later than any watermark, so it is never missed.
- **Documents with a date outside the needed range** (for example a report from last year that someone re-generated) are returned by the incremental query, cost one read, and are stored. They do not affect any figure unless a period includes that date.
- **Deleted documents** are not tracked. End of Day never deletes, so this cannot cause a stale figure.
- **Year rollover.** On January 1 the needed start moves forward. The cache simply keeps serving; documents dated before the new needed start remain in the file and still serve past-month views of the previous year.

### Business Rules — Cache Lifetime and Pruning

- The cache is kept indefinitely across app restarts.
- When the cache is saved, documents dated **before January 1 of the previous year** are pruned, so the file holds at most about two years of reports. A past month older than that uses the existing server query.
- Clearing the app's storage or uninstalling removes the cache; the next open is a first visit again.

---

## Feature 2: Hard Refresh

### User Story

**As the store owner**, if a figure looks wrong I want a way to throw the local copy away and reload everything from Firestore.

### UI/UX Flow

1. **Long-press the refresh button** in the Overview header.
2. A toast says **"Reloading all End of Day reports"**.
3. The cache file is deleted and the screen performs a first-visit load (full range query).
4. A normal tap on the refresh button behaves as it does today, except that the End of Day part uses the incremental query.

---

## Data Model

### Existing data read

- `daily_summaries`: the fields `date`, `storeLocation`, `salesSummary`, `cashFlowSummary` and `timestamp`. No new fields are required; `timestamp` is already written by `EndOfDayReportActivity`.

### New data

- **No new Firestore collections, documents or fields.**
- **Local cache file** `overview_summaries_cache.json` in the app's private files directory:

```json
{
  "version": 1,
  "cacheStart": "2026-01-01",
  "watermarkSeconds": 1761100000,
  "watermarkNanos": 123000000,
  "docs": [
    {
      "_id": "2026-10-21_TechCity Kapatagan",
      "date": "2026-10-21",
      "storeLocation": "TechCity Kapatagan",
      "timestampSeconds": 1761100000,
      "timestampNanos": 123000000,
      "salesSummary": { "devices": { "...": "..." }, "accessories": { "...": "..." } },
      "cashFlowSummary": { "devices": { "...": "..." }, "accessories": { "...": "..." }, "services": { "...": "..." } }
    }
  ]
}
```

A `version` mismatch or any parse failure discards the file and triggers a first-visit load.

---

## Firestore Cost Summary

Three branches, 22 October 2026, one open of the Overview:

| Read | Today | First open with cache | Later opens |
|---|---|---|---|
| Store locations | ~3 | ~3 | ~3 |
| Today's transactions (3 listeners) | today's docs | same | same |
| Latest retrieval (1 query per branch) | all retrievals per branch | same | same |
| End of Day documents | ~880 (Jan 1 to yesterday) | ~880 (one time) | **1 + reports saved since** |
| Past month picked | ~90 | ~90 if before cache start, else **0** | same |
| **Writes** | 0 | 0 | 0 |

The End of Day save path is unchanged: one write per saved report, as now.

---

## Edge Cases and Error Handling

- **Cache file missing, unreadable or wrong version:** treated as a first visit. A full load runs and a fresh file is written. No error is shown.
- **Disk write fails:** the figures still render from memory for this visit. The next open is a first visit again. No error is shown to the user; the failure is logged.
- **Incremental query fails (offline):** Monthly (current month), Yearly and Cash on Hand show "Could not load" with Retry exactly as today. The cache on disk is left as it was. Figures are **not** rendered from the stale cache, so the user never sees numbers that may be out of date without knowing it.
- **Gap query fails:** same handling as an incremental query failure.
- **Retrieval recorded:** the new Running range starts later than the old one, so no gap query is needed. Running is recomputed from the cache as it is today.
- **Two Overview devices:** each has its own cache and watermark; they do not interact.
- **Document returned by the incremental query with a date in the future or today:** stored, ignored by every period until that date is in range.
- **Picker change while loading:** unchanged; the existing job cancellation applies.

## Open Questions

1. Is a **long-press on the refresh button** acceptable as the hard-refresh gesture, or would you prefer a kebab menu item or no escape hatch at all?
2. Is **two calendar years** the right pruning horizon for the local file?
