# Technical Plan: Overview Summary Cache (Incremental End of Day Sync)

Spec: `_specs/overview-summary-cache.md`

## Approach Summary

- **One new class** `OverviewSummaryCache` owns the local JSON file: load, save, merge, prune, range filtering and the watermark. It has no Firestore dependency beyond the `Timestamp` type, so it is unit-testable.
- **`OverviewActivity` keeps its shape.** The in-memory `summaryCache` list, `summaryCacheStart`, `aggregateSummaries`, `computeRunning`, `renderMonthlyFromCache`, `renderYearly` and the retrieval flow are all unchanged. Only the step that *fills* `summaryCache` changes: instead of one year-long range query it calls a new `syncSummaries()` that decides between first-visit load, gap load and incremental load, then hands back the filtered list.
- **`loadMonthly()` for a past month** first checks whether the month lies inside the cached range; if so it aggregates from the cache with no query. Otherwise it runs its existing per-month query, unchanged.
- **Hard refresh**: a long-press listener on the existing refresh button deletes the cache file and calls `loadAll()`.
- **No Firestore writes added. No index. No End of Day changes.**

---

## Files to Create

### 1. `app/src/main/java/com/techcity/techcitysuite/OverviewSummaryCache.kt`

`class OverviewSummaryCache(private val file: File)`. Constructed by the Activity with `File(filesDir, "overview_summaries_cache.json")`. All disk access is plain `java.io`, callers wrap it in `Dispatchers.IO`. Serialization uses `org.json` (platform, no dependency).

**State**
- `docs: MutableMap<String, Map<String, Any?>>` — document id → trimmed document map (`_id`, `date`, `storeLocation`, `salesSummary`, `cashFlowSummary`, `timestamp: Timestamp?`). The map shape matches what `fetchDailySummaries` returns today so `aggregateSummaries` works on it unchanged.
- `watermark: Timestamp?` — greatest `timestamp` seen; `null` means "no cache / first visit".
- `cacheStart: String` — earliest date the full or gap loads have covered (`yyyy-MM-dd`); empty when no cache.
- `val isEmpty get() = watermark == null`

**Functions**
- `fun load(): Boolean` — reads and parses the file. On missing file, `version != 1`, or any exception: clears state, deletes the file if present, returns `false`. Never throws.
- `fun save()` — prunes docs dated before `pruneBefore` (see below), then writes `version`, `cacheStart`, `watermarkSeconds`/`watermarkNanos` and the `docs` array. Each doc is written as the trimmed map with `timestamp` replaced by `timestampSeconds`/`timestampNanos`. Writes to a temp file in the same directory then renames over the target, so a crash mid-write cannot leave a half file. Exceptions are caught and logged (`e.printStackTrace()`); the in-memory state stays valid.
- `fun merge(fetched: List<Map<String, Any?>>)` — for each doc: `docs[_id] = trim(doc)`; if its `timestamp` is a `Timestamp` greater than `watermark`, advance `watermark`. (Comparison: `Timestamp` implements `Comparable`.)
- `fun inRange(startDate: String, endDate: String): List<Map<String, Any?>>` — `docs.values.filter { date in startDate..endDate }`.
- `fun covers(startDate: String): Boolean` — `!isEmpty && cacheStart.isNotEmpty() && startDate >= cacheStart`.
- `fun clear()` — resets state and deletes the file.
- `var pruneBefore: String` — set by the Activity to January 1 of the previous year before `save()`; `save()` drops `docs` with `date < pruneBefore`. Default empty = no pruning.

**Private helpers**
- `trim(doc)` keeps only the six fields listed above.
- `toJson(value)` / `fromJson(value)` recursive converters between Kotlin `Map`/`List`/`Number`/`String`/`Boolean`/`null` and `JSONObject`/`JSONArray`. Numbers come back as `Int`/`Long`/`Double`; the Overview's parsers already read figures with `(x as? Number)?.toDouble()`, so this is safe. `JSONObject.NULL` maps to `null`.

---

## Files to Modify

### 2. `app/src/main/java/com/techcity/techcitysuite/OverviewActivity.kt`

**Imports (top of file)**
- Add `import com.google.firebase.Timestamp` (the file currently refers to it fully-qualified once, at `formatDateTime`; that usage is left as is).
- Add `import java.io.File`.

**Part 1 — Properties (after the `summaryCache` block at lines ~82–85)**
- Add `private lateinit var diskCache: OverviewSummaryCache`.
- Existing `summaryCache`, `summaryCacheStart`, `summaryCacheLoaded` stay and keep their meaning (the in-memory, date-filtered list the aggregation reads).

**Part 2 — `onCreate` (line ~180)**
- After the binding is inflated: `diskCache = OverviewSummaryCache(File(filesDir, CACHE_FILE_NAME))`.
- After the existing `binding.refreshButton.setOnClickListener { loadAll() }` add:
  ```kotlin
  binding.refreshButton.setOnLongClickListener {
      Toast.makeText(this, "Reloading all End of Day reports", Toast.LENGTH_SHORT).show()
      scope.launch {
          withContext(Dispatchers.IO) { diskCache.clear() }
          loadAll()
      }
      true
  }
  ```

**Part 3 — `loadSummariesAndRender()` (lines ~369–401)**
Replace the block
```kotlin
summaryCache = if (unionStart <= yesterday) {
    withContext(Dispatchers.IO) { fetchDailySummaries(unionStart, yesterday) }
} else {
    emptyList()
}
summaryCacheStart = unionStart
summaryCacheLoaded = true
```
with
```kotlin
summaryCache = syncSummaries(unionStart, yesterday)
summaryCacheStart = unionStart
summaryCacheLoaded = true
```
Everything else in the function (retrievals, `computeRunning`, renders, error handling) is unchanged. A failure inside `syncSummaries` propagates to the existing `catch`, which marks Cash, Yearly and Monthly as ERROR, so the stale cache is never rendered after a failed sync.

**Part 3 — new `syncSummaries()` (placed right after `loadSummariesAndRender`)**
```kotlin
/**
 * Bring the on-disk End of Day cache up to date with as few reads as possible and return the
 * documents dated within [neededStart, endDate].
 *  - First visit: one range query date >= neededStart (no upper bound).
 *  - Needed range moved earlier: one gap query [neededStart, cacheStart).
 *  - Otherwise: one query timestamp > watermark.
 */
private suspend fun syncSummaries(neededStart: String, endDate: String): List<Map<String, Any?>> {
    return withContext(Dispatchers.IO) {
        if (diskCache.isEmpty) diskCache.load()

        if (diskCache.isEmpty) {
            // FIRESTORE READ: full load, once per device
            diskCache.merge(fetchDailySummariesFrom(neededStart))
            diskCache.cacheStart = neededStart
        } else {
            if (!diskCache.covers(neededStart)) {
                // FIRESTORE READ: gap load, rare
                diskCache.merge(fetchDailySummaries(neededStart, shiftDate(diskCache.cacheStart, -1)))
                diskCache.cacheStart = neededStart
            }
            // FIRESTORE READ: 1 read if nothing changed, else 1 per changed report
            diskCache.merge(fetchDailySummariesChangedAfter(diskCache.watermark!!))
        }

        diskCache.pruneBefore = firstDayOfPreviousYear()
        diskCache.save()
        diskCache.inRange(neededStart, endDate)
    }
}
```
Note: on the first visit the full load has no upper bound, so `watermark` is the greatest timestamp among **all** documents dated on or after `neededStart`, including today's. This is what makes the `timestamp > watermark` query safe: no document in the needed range can have a timestamp below the watermark yet be missing from the cache.

**Part 3 — `loadMonthly()` (lines ~447–470)**
Inside the `try`, before the existing `fetchDailySummaries(start, end)` call, add a cache short-circuit:
```kotlin
val docs = if (summaryCacheLoaded && diskCache.covers(start)) {
    diskCache.inRange(start, end)
} else {
    withContext(Dispatchers.IO) { fetchDailySummaries(start, end) }
}
```
The rest of the function (`month != monthlyMonth` guard, `aggregateSummaries`, `renderPeriodSection(..., includeToday = false)`, error handling) is unchanged. `summaryCacheLoaded` guards against reading the disk cache before `syncSummaries` has run in this visit, so a past month never shows pre-sync data.

**Part 4 — Fetchers (after `fetchDailySummaries`, lines ~492–504)**
Add two siblings with the same return shape (doc data map plus `_id`):
```kotlin
/** End of Day documents for all branches with date >= startDate (no upper bound). */
private suspend fun fetchDailySummariesFrom(startDate: String): List<Map<String, Any?>>
    // .whereGreaterThanOrEqualTo("date", startDate).get().await()

/** End of Day documents saved or re-saved after the given server timestamp. */
private suspend fun fetchDailySummariesChangedAfter(after: Timestamp): List<Map<String, Any?>>
    // .whereGreaterThan("timestamp", after).get().await()
```
The existing `fetchDailySummaries(start, end)` stays for the gap load and for past months outside the cache.

**Part 9 — Date utilities**
- Add `private fun firstDayOfPreviousYear(): String = "${todayDate.substring(0, 4).toInt() - 1}-01-01"`.
- `shiftDate` already exists and is reused for the gap query's end date.

**Companion object**
- Add `private const val CACHE_FILE_NAME = "overview_summaries_cache.json"`.

**Unchanged on purpose:** `loadAll`, `retrySection`, `loadTodayLive`, `onTodayLiveChanged`, `loadLatestRetrievals`, `loadDaily`, `fetchTransactions`, `fetchLatestRetrieval`, Part 5 aggregation, Part 6 rendering, Part 7 pickers, Part 8 swipe/retrieval, `onDestroy`.

### 3. (none) `EndOfDayReportActivity.kt`, `Constants.kt`, layouts, manifest
No changes. The End of Day save already writes `"timestamp" to FieldValue.serverTimestamp()` (line ~2326). No new constants are needed outside the Activity's companion.

---

## Implementation Order

1. `OverviewSummaryCache.kt` — state, `load`/`save` with JSON round trip, `merge`, `inRange`, `covers`, `clear`, pruning.
2. `OverviewActivity.kt` imports, property, companion constant, `onCreate` wiring (construct cache, long-press).
3. `fetchDailySummariesFrom`, `fetchDailySummariesChangedAfter`, `firstDayOfPreviousYear`.
4. `syncSummaries` and the three-line replacement inside `loadSummariesAndRender`.
5. `loadMonthly` cache short-circuit.
6. `./gradlew assembleDebug`; fix compile errors only.
7. Manual checks (watch the Firestore usage panel or add temporary `Log.d` with returned document counts, removed before finishing):
   - Fresh install: first open logs the full count (about 294 × branches); file exists in `files/`; figures match the current build exactly for Yearly, Monthly and Running.
   - Close and reopen: incremental query returns 0 docs; figures identical; open is noticeably faster.
   - Save an End of Day report for yesterday on a branch device, reopen Overview: incremental query returns 1 doc; Running and Monthly/Yearly change accordingly.
   - Re-generate and re-save an existing report with a different figure: incremental returns 1 doc; the cached copy is replaced (figure moves, not doubled).
   - Pick a past month in the current year: no Firestore query; figures match the current build.
   - Pick a month in the previous year (before cache start): per-month query runs as before.
   - Record a retrieval: Running recomputed from cache, no End of Day query.
   - Long-press refresh: toast, file deleted, full load count logged again.
   - Airplane mode after a cache exists: Monthly/Yearly/Cash show "Could not load" with Retry, no stale figures; cache file untouched; Retry after reconnecting runs the incremental query.
   - Corrupt the file (e.g. via Device Explorer, write garbage): next open behaves as first visit, no crash.

---

## Dependencies / Libraries

None new. `org.json` and `java.io` are platform; `com.google.firebase.Timestamp` is already on the classpath.

---

## Migration and Data Considerations

- **No Firestore data or schema changes.** No new collections, fields, indexes or rules.
- **Local file only**, created on first open after this build. Uninstall or "Clear storage" removes it; the next open is a first visit.
- **Existing devices** running the current build simply perform one full load on their first open after updating, then go incremental.
- **Legacy documents** (no `timestamp`) are included by the first full load and never re-fetched, which is correct because they never change.

---

## Risks and Things to Watch

- **Watermark correctness depends on the full load having no upper date bound.** If someone later "optimises" `fetchDailySummariesFrom` back to `[start, yesterday]`, a report for today saved before the full load would be excluded, its timestamp could be below the watermark, and tomorrow that day would read as missing. Keep the comment on `syncSummaries` explaining this.
- **`whereGreaterThan("timestamp", ...)` excludes documents with no `timestamp` field.** That is intended (legacy docs), but any future End of Day change that stops writing `timestamp` would silently stop the Overview from seeing new reports. The End of Day save path is the single thing this feature relies on.
- **Trimmed documents.** `OverviewSummaryCache.trim` keeps only `_id`, `date`, `storeLocation`, `salesSummary`, `cashFlowSummary`, `timestamp`. If the Overview later needs another End of Day field, `trim` must be extended and the cache `version` bumped so existing files are rebuilt.
- **Stale cache on failed sync.** The plan deliberately does **not** render from the on-disk cache when the incremental query fails; the section shows the normal error. Rendering stale figures silently would be worse than an error.
- **File writes on the IO dispatcher only.** `load`/`save`/`clear` must never run on `Dispatchers.Main`.
- **Picker race in `loadMonthly`.** The cache short-circuit returns synchronously, so the existing `if (month != monthlyMonth) return@launch` guard still runs before rendering; keep it.
- **Hard refresh while a load is in flight.** `loadAll()` already cancels `mainJob`, `dailyJob` and `monthlyJob`; `clear()` runs before `loadAll()` so the new `syncSummaries` sees an empty cache. No extra locking needed because all cache mutation happens inside `syncSummaries`, which is only ever reached through `mainJob` or `retrySection`'s launch after `loadAll` has set the flags.
- **Retrieval query growth** (noted in the original plan) is unchanged by this feature and remains the next biggest read cost.
