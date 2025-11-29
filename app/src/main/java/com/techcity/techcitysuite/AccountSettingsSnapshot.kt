package com.techcity.techcitysuite

/**
 * Data class representing a snapshot of all account settings at the time of transaction
 * Provides complete audit trail of configured accounts when transaction was made
 */
data class AccountSettingsSnapshot(
    val cashAccount: String = "",
    val gcashAccount: String = "",
    val paymayaAccount: String = "",
    val qrphAccount: String = "",
    val creditCardAccount: String = "",
    val otherAccount: String = ""
) {
    // No-argument constructor required for Firestore
    constructor() : this("")
}