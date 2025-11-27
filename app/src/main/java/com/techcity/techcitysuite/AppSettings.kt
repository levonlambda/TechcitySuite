package com.techcity.techcitysuite

/**
 * Data class representing application settings
 * Stored in Firebase under "app_settings" collection
 */
data class AppSettings(
    val deviceId: String = "",
    val user: String = "",
    val storeLocation: String = "",
    val cashAccount: String = "",
    val gcashAccount: String = "",
    val paymayaAccount: String = "",
    val qrphAccount: String = "",
    val creditCardAccount: String = "",
    val otherAccount: String = "",
    val lastUpdated: Long = System.currentTimeMillis()
) {
    // No-argument constructor required for Firestore
    constructor() : this("")
}