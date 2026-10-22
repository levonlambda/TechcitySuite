# Technical Plan: Financing Accounts Live Listener (Read Reduction)

Spec: `_specs/financing-account-live-listener.md`

## Approach Summary

One file changes: `FinancingAccountListActivity`. The one-shot `get()` in `loadAccounts()` becomes an `addSnapshotListener` with a `ListenerRegistration` removed in `onDestroy`, the `onResume` call to `loadAccounts()` is removed, and the two-year cutoff is rounded to the first of the month. No other files, no schema, no index, no dependency changes.

---

## Files to Create

None.

---

## Files to Modify

### 1. `app/src/main/java/com/techcity/techcitysuite/FinancingAccountListActivity.kt`

**Imports**
- Add `import com.google.firebase.firestore.ListenerRegistration` next to the other Firestore imports.
- Keep `com.google.firebase.firestore.Query` (still used for `orderBy`).
- Keep `kotlinx.coroutines.tasks.await`, `withContext`, `Dispatchers` (still used by `deleteAccountFromFirestore`).

**Part 1 — Properties**
- Add, next to `adapter`:
  ```kotlin
  private var accountsListener: ListenerRegistration? = null
  ```

**Part 1 — `onResume()`**
- Remove the whole override. It currently only calls `loadAccounts()`.

**Part 1 — `onDestroy()`**
- Add `accountsListener?.remove()` before the existing scope cancel:
  ```kotlin
  override fun onDestroy() {
      super.onDestroy()
      accountsListener?.remove()
      scope.coroutineContext[Job]?.cancel()
  }
  ```

**Part 3 — `loadAccounts()`**
Replace the body. The progress/empty/recycler visibility lines at the top stay. The coroutine and `get().await()` are replaced by a listener:

```kotlin
private fun loadAccounts() {
    binding.progressBar.visibility = View.VISIBLE
    binding.emptyMessage.visibility = View.GONE
    binding.accountsRecyclerView.visibility = View.GONE

    // Remove any existing listener
    accountsListener?.remove()

    // Stable cutoff: first day of the month, two years back, midnight Asia/Manila.
    // Firestore keys listener resume tokens by the exact query (filter values included);
    // a cutoff that changes every millisecond would make every open a brand-new query.
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila"))
    calendar.add(Calendar.YEAR, -2)
    calendar.set(Calendar.DAY_OF_MONTH, 1)
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    val cutoff = com.google.firebase.Timestamp(calendar.time)

    accountsListener = db.collection(AppConstants.COLLECTION_FINANCING_ACCOUNTS)
        .whereGreaterThan("createdAt", cutoff)
        .orderBy("createdAt", Query.Direction.DESCENDING)
        .addSnapshotListener { snapshot, error ->
            binding.progressBar.visibility = View.GONE

            if (error != null) {
                Toast.makeText(this@FinancingAccountListActivity, "Error: ${error.message}", Toast.LENGTH_SHORT).show()
                return@addSnapshotListener
            }

            val accounts = snapshot?.documents?.map { doc ->
                FinancingAccount(
                    id = doc.id,
                    financingCompany = doc.getString("financingCompany") ?: "",
                    customerName = doc.getString("customerName") ?: "",
                    accountNumber = doc.getString("accountNumber") ?: "",
                    purchaseDate = doc.getString("purchaseDate") ?: "",
                    contactNumber = doc.getString("contactNumber") ?: "",
                    devicePurchased = doc.getString("devicePurchased"),
                    monthlyPayment = doc.getDouble("monthlyPayment"),
                    term = doc.getString("term"),
                    downpayment = doc.getDouble("downpayment"),
                    financedAmount = doc.getDouble("financedAmount"),
                    createdAt = doc.getTimestamp("createdAt"),
                    createdBy = doc.getString("createdBy") ?: "",
                    storeLocation = doc.getString("storeLocation") ?: ""
                )
            } ?: emptyList()

            allAccounts.clear()
            allAccounts.addAll(accounts)
            applyFilters()
        }
}
```

The document-to-model mapping block is moved verbatim from the current coroutine; no field is added or removed.

**Everything else in the file is unchanged**: `onCreate` (still calls `loadAccounts()` last), setup methods, swipe callback, `applyFilters`, `matchesDateSearch`, `updateEmptyState`, delete dialog and `deleteAccountFromFirestore`, `openEditMode`, adapter, formatters.

---

## Files Explicitly Unchanged

- `AddFinancingAccountActivity.kt`, `FinancingAccountDetailActivity.kt`, `FinancingAccount.kt`, `Constants.kt`
- `activity_financing_account_list.xml` and all other layouts, strings, manifest
- `firestore.rules`, Firestore indexes
- `build.gradle.kts`, `gradle/libs.versions.toml`

---

## Implementation Order

1. Add the `ListenerRegistration` import and the `accountsListener` property.
2. Replace the body of `loadAccounts()` as above.
3. Delete the `onResume()` override.
4. Add `accountsListener?.remove()` to `onDestroy()`.
5. `./gradlew assembleDebug`; fix compile errors only.
6. Manual checks (two devices, or one device plus the Firebase console):
   - Open the module: list, totals, entry count, filters and search behave exactly as before.
   - Press Add, cancel, come back: no progress bar, list unchanged.
   - Add an account on device A: it appears on device B's open list in sorted position without any action on B; totals and count update.
   - Edit an account on A: B's row updates in place.
   - Delete on A: row disappears on B.
   - Delete on B itself: row disappears immediately, no error.
   - Rotate the device: list reloads cleanly (activity recreate attaches a fresh listener).
   - Back out and reopen: works; optionally confirm in Firebase console Usage that a reopen within a few minutes bills far fewer than the full document count. Console figures lag by minutes to hours, so test with only one device active.
   - Confirm in Logcat or a temporary log line that the cutoff equals the 1st of the month two years back at 00:00:00 Asia/Manila. Remove the log line before finishing.

---

## Dependencies / Libraries

None new. `ListenerRegistration` and `addSnapshotListener` are already used by `DeviceTransactionListActivity` and `AccessoryTransactionListActivity`.

---

## Migration and Data Considerations

- No document changes, no backfill, no index.
- The automatic single-field index on `createdAt` already serves the range-plus-order query, as it does today.
- Firestore offline persistence is the Android default and is not disabled anywhere in the app; it is required for the resume-within-30-minutes behavior.

---

## Risks and Things to Watch

- **Resume only works if the query is byte-identical between attaches.** Any future change that reintroduces a time-of-open value into the filter will silently return to full-window billing on every open. The comment in `loadAccounts()` records why the cutoff is rounded.
- **A listener error ends the listener.** After an error the list shows the last snapshot until the module is reopened. Same as the Device and Accessory lists; accepted.
- **Full `notifyDataSetChanged()` on each snapshot** resets any in-progress swipe and any RecyclerView scroll animation. Scroll position is preserved by the layout manager. Accepted; matches existing lists.
- **Stale `position` in the delete dialog's cancel path** if a snapshot rebuilds the list while the dialog is open. The delete itself is keyed by document id so data is safe. The `notifyItemChanged(position)` on cancel may target a shifted or out-of-range row; pre-existing behavior, not changed here.
- **Window widens by up to one month** compared with today's exact two-year cutoff. Up to roughly 30 extra old accounts may be visible early in a month. The "month year" search rule in `matchesDateSearch` still uses `currentYear - 2`, which is unaffected.
- **Reads are billed on every attach after a ~30-minute gap**, so the per-open cost for a device used only occasionally stays at the full window. The process-wide listener or on-disk cache remain available as later steps if needed.

---

## Addendum (22 Oct 2026): Bottom Summary Bar Inset Padding

Spec: addendum of the same date in `_specs/financing-account-live-listener.md`.

### `app/src/main/java/com/techcity/techcitysuite/FinancingAccountListActivity.kt`

**Imports** — add next to `androidx.core.content.ContextCompat`:
```kotlin
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
```

**Part 1 — `onCreate()`** — add `setupBottomBarInsets()` after `setupFab()` and before `loadAccounts()`.

**Part 2 — new method after `setupFab()`:**
```kotlin
private fun setupBottomBarInsets() {
    val basePaddingBottom = binding.bottomSummaryBar.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(binding.bottomSummaryBar) { view, insets ->
        val navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        view.updatePadding(bottom = basePaddingBottom + navBarInset)
        insets
    }
}
```
The base padding is captured once so repeated inset callbacks do not accumulate.

### Files Explicitly Unchanged
- `activity_financing_account_list.xml` — the 12dp padding stays; the inset is added in code.

### Dependencies
None new. `androidx.core:core-ktx` is already declared in `app/build.gradle.kts`.

### Manual checks
- Total Financed and Entries figures are fully visible above the navigation bar with gesture navigation and with three-button navigation.
- The floating add button sits above the summary bar in both modes.

### Risks
- Only this screen is fixed. Other screens with bottom bars have the same overlap and are left for a separate task.
