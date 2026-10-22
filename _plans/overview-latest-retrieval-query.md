# Technical Plan: Overview Latest Retrieval Query (Server-side limit 1)

Spec: `_specs/overview-latest-retrieval-query.md`

## Approach Summary

One function body changes in `OverviewActivity`, plus one composite index created in the Firebase console **before** the build is installed on any device.

---

## Files to Modify

### 1. `app/src/main/java/com/techcity/techcitysuite/OverviewActivity.kt`

**Imports**
- Add `import com.google.firebase.firestore.Query` next to the other Firestore imports.

**Part 4 — `fetchLatestRetrieval(storeLocation)`**
Replace the body:
```kotlin
val snapshot = db.collection(AppConstants.COLLECTION_CASH_RETRIEVALS)
    .whereEqualTo("storeLocation", storeLocation)
    .get()
    .await()
return snapshot.documents
    .map { CashRetrieval.fromDocument(it) }
    .maxWithOrNull(compareBy<CashRetrieval> { it.rangeEnd }.thenBy { it.recordedAt?.seconds ?: 0L })
```
with
```kotlin
val snapshot = db.collection(AppConstants.COLLECTION_CASH_RETRIEVALS)
    .whereEqualTo("storeLocation", storeLocation)
    .orderBy("rangeEnd", Query.Direction.DESCENDING)
    .orderBy("recordedAt", Query.Direction.DESCENDING)
    .limit(1)
    .get()
    .await()
return snapshot.documents.firstOrNull()?.let { CashRetrieval.fromDocument(it) }
```
Signature, return type and the KDoc stay. Callers (`loadLatestRetrievals`) are unchanged.

### 2. Firestore composite index (console, not a repo file)

| Setting | Value |
|---|---|
| Collection ID | `cash_retrievals` |
| Field 1 | `storeLocation` — Ascending |
| Field 2 | `rangeEnd` — Descending |
| Field 3 | `recordedAt` — Descending |
| Query scope | Collection |

Equivalent `firestore.indexes.json` entry, recorded here for reference (the project has no Firebase CLI config, so this is documentation only):
```json
{
  "collectionGroup": "cash_retrievals",
  "queryScope": "COLLECTION",
  "fields": [
    { "fieldPath": "storeLocation", "order": "ASCENDING" },
    { "fieldPath": "rangeEnd", "order": "DESCENDING" },
    { "fieldPath": "recordedAt", "order": "DESCENDING" }
  ]
}
```

---

## Files Explicitly Unchanged

- `CashRetrieval.kt`, `Constants.kt`, `firestore.rules`, layouts, manifest.
- `loadLatestRetrievals`, `computeRunning`, `recordRetrieval` and the rest of `OverviewActivity`.

---

## Implementation Order

1. **Create the index in the Firebase console** and wait until its status reads **Enabled**.
2. Edit `fetchLatestRetrieval` and add the `Query` import.
3. `./gradlew assembleDebug`; fix compile errors only.
4. Manual checks: Overview opens with Running and "Last retrieved" identical to the previous build for every branch; record a retrieval, confirm Running goes to ₱0.00 and "Last retrieved" updates; a never-retrieved branch still starts from the configured start date; Firestore usage shows one `cash_retrievals` read per branch.

---

## Dependencies / Libraries

None new. `com.google.firebase.firestore.Query` is already on the classpath and used elsewhere in the app.

---

## Migration and Data Considerations

- No document changes. Existing retrievals already carry `storeLocation`, `rangeEnd` and `recordedAt`.
- The index must exist before the APK reaches devices. Index creation is free and typically completes in minutes for a small collection.

---

## Risks and Things to Watch

- **Index missing at runtime** fails the whole summaries load (Cash on Hand, Yearly, current Monthly all show "Could not load"). The Logcat error contains a console link that creates the exact index; use it if the manual definition was mistyped.
- **Index lives only in the Firebase project**, like the app's other composite indexes. If the project is ever recreated, rebuild it from the table above.
- **Documents without `rangeEnd` or `recordedAt`** are excluded by an ordered query instead of being sorted last. The app always writes both; accepted.
