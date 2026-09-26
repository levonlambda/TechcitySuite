package com.techcity.techcitysuite

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Store-location visibility helper.
 *
 * Each device is configured with one store location in Program Settings. Location-scoped
 * screens only show records whose stored location name equals the device's store location.
 * A device configured with the primary store location (accessory_locations.isPrimary == true)
 * additionally sees legacy records that carry no location at all.
 */
object StoreLocationHelper {

    const val NOT_CONFIGURED_MESSAGE = "Please configure Store Location in Settings first"
    const val NO_STORE_LOCATION_LABEL = "No store location set"

    data class StoreLocationOption(
        val name: String,
        val id: String,
        val isPrimary: Boolean
    )

    fun getStoreLocation(context: Context): String {
        val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(AppConstants.KEY_STORE_LOCATION, "") ?: ""
    }

    fun isPrimary(context: Context): Boolean {
        val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(AppConstants.KEY_STORE_LOCATION_IS_PRIMARY, false)
    }

    fun isConfigured(context: Context): Boolean {
        return getStoreLocation(context).isNotBlank()
    }

    /**
     * Display-only label for the store location: the name without its "TC-" prefix,
     * or NO_STORE_LOCATION_LABEL when none is configured. Never use this for matching.
     */
    fun getDisplayName(context: Context): String {
        val storeLocation = getStoreLocation(context)
        if (storeLocation.isBlank()) return NO_STORE_LOCATION_LABEL
        return storeLocation.removePrefix("TC-").trim()
    }

    /**
     * Visibility rule: exact (case-sensitive) match on the store location name, or,
     * on a primary-store device, a record with a missing/empty location (legacy rule).
     */
    fun matches(context: Context, recordLocation: String?): Boolean {
        val storeLocation = getStoreLocation(context)
        if (storeLocation.isBlank()) return false
        if (recordLocation.isNullOrBlank()) return isPrimary(context)
        return recordLocation == storeLocation
    }

    /**
     * Load the active store locations from accessory_locations.
     */
    suspend fun loadActiveLocations(db: FirebaseFirestore): List<StoreLocationOption> {
        val snapshot = db.collection(AppConstants.COLLECTION_ACCESSORY_LOCATIONS)
            .get()
            .await()

        val list = mutableListOf<StoreLocationOption>()
        for (document in snapshot.documents) {
            val active = document.getBoolean("active") ?: true
            if (!active) continue
            val name = document.getString("name") ?: continue
            val isPrimary = document.getBoolean("isPrimary") ?: false
            list.add(StoreLocationOption(name = name, id = document.id, isPrimary = isPrimary))
        }
        return list
    }
}
