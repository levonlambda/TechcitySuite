package com.techcity.techcitysuite

import android.content.Context
import android.provider.Settings
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Singleton manager for application settings
 * Handles loading and saving settings from/to Firebase
 */
object AppSettingsManager {

    // ============================================================================
    // START OF PART 1: PROPERTIES
    // ============================================================================

    private const val PREFS_NAME = "TechCityDevicePrefs"
    private const val KEY_DEVICE_ID = "device_id"

    // Firebase collection names
    private const val COLLECTION_APP_SETTINGS = "app_settings"
    private const val COLLECTION_APP_CONFIG = "app_config"
    private const val DOC_SETTINGS_PASSWORD = "settings_password"

    // Current loaded settings (cached in memory)
    private var currentSettings: AppSettings? = null

    // Device ID for this device
    private var deviceId: String? = null

    // ============================================================================
    // END OF PART 1: PROPERTIES
    // ============================================================================


    // ============================================================================
    // START OF PART 2: DEVICE ID MANAGEMENT
    // ============================================================================

    /**
     * Get or generate a unique device ID
     * Uses Android ID as base, with fallback to generated UUID
     */
    fun getDeviceId(context: Context): String {
        if (deviceId != null) {
            return deviceId!!
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var storedId = prefs.getString(KEY_DEVICE_ID, null)

        if (storedId == null) {
            // Try to get Android ID first
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )

            // Use Android ID if available, otherwise generate UUID
            storedId = if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") {
                "device_$androidId"
            } else {
                "device_${UUID.randomUUID()}"
            }

            // Store for future use
            prefs.edit().putString(KEY_DEVICE_ID, storedId).apply()
        }

        deviceId = storedId
        return storedId
    }

    // ============================================================================
    // END OF PART 2: DEVICE ID MANAGEMENT
    // ============================================================================


    // ============================================================================
    // START OF PART 3: SETTINGS LOADING
    // ============================================================================

    /**
     * Load settings from Firebase for this device
     * Returns cached settings if already loaded
     */
    suspend fun loadSettings(context: Context): AppSettings? {
        val db = Firebase.firestore
        val devId = getDeviceId(context)

        return try {
            val document = db.collection(COLLECTION_APP_SETTINGS)
                .document(devId)
                .get()
                .await()

            if (document.exists()) {
                currentSettings = document.toObject(AppSettings::class.java)
                currentSettings
            } else {
                // No settings exist yet for this device
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Get current settings (from cache)
     * Call loadSettings first to ensure settings are loaded
     */
    fun getCurrentSettings(): AppSettings? {
        return currentSettings
    }

    /**
     * Force reload settings from Firebase
     */
    suspend fun refreshSettings(context: Context): AppSettings? {
        currentSettings = null
        return loadSettings(context)
    }

    // ============================================================================
    // END OF PART 3: SETTINGS LOADING
    // ============================================================================


    // ============================================================================
    // START OF PART 4: SETTINGS SAVING
    // ============================================================================

    /**
     * Save settings to Firebase for this device
     */
    suspend fun saveSettings(context: Context, settings: AppSettings): Boolean {
        val db = Firebase.firestore
        val devId = getDeviceId(context)

        // Create settings with device ID and updated timestamp
        val settingsToSave = settings.copy(
            deviceId = devId,
            lastUpdated = System.currentTimeMillis()
        )

        return try {
            db.collection(COLLECTION_APP_SETTINGS)
                .document(devId)
                .set(settingsToSave)
                .await()

            // Update cache
            currentSettings = settingsToSave
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ============================================================================
    // END OF PART 4: SETTINGS SAVING
    // ============================================================================


    // ============================================================================
    // START OF PART 5: PASSWORD VERIFICATION
    // ============================================================================

    /**
     * Verify the settings password against Firebase
     */
    suspend fun verifyPassword(password: String): Boolean {
        val db = Firebase.firestore

        return try {
            val document = db.collection(COLLECTION_APP_CONFIG)
                .document(DOC_SETTINGS_PASSWORD)
                .get()
                .await()

            if (document.exists()) {
                val storedPassword = document.getString("password") ?: ""
                password == storedPassword
            } else {
                // No password set yet - create default password "admin"
                createDefaultPassword(db)
                password == "admin"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Create default password in Firebase if none exists
     */
    private suspend fun createDefaultPassword(db: FirebaseFirestore) {
        try {
            val passwordDoc = hashMapOf(
                "password" to "admin",
                "createdAt" to System.currentTimeMillis()
            )

            db.collection(COLLECTION_APP_CONFIG)
                .document(DOC_SETTINGS_PASSWORD)
                .set(passwordDoc)
                .await()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Change the settings password
     */
    suspend fun changePassword(newPassword: String): Boolean {
        val db = Firebase.firestore

        return try {
            val passwordDoc = hashMapOf(
                "password" to newPassword,
                "updatedAt" to System.currentTimeMillis()
            )

            db.collection(COLLECTION_APP_CONFIG)
                .document(DOC_SETTINGS_PASSWORD)
                .set(passwordDoc)
                .await()

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ============================================================================
    // END OF PART 5: PASSWORD VERIFICATION
    // ============================================================================


    // ============================================================================
    // START OF PART 6: HELPER METHODS
    // ============================================================================

    /**
     * Clear cached settings (useful for testing or logout)
     */
    fun clearCache() {
        currentSettings = null
    }

    /**
     * Check if settings have been loaded
     */
    fun hasSettings(): Boolean {
        return currentSettings != null
    }

    // ============================================================================
    // END OF PART 6: HELPER METHODS
    // ============================================================================
}