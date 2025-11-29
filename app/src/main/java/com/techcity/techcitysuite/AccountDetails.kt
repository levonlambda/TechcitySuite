package com.techcity.techcitysuite

/**
 * Data class representing account details for a payment source
 * Used to capture which account received the payment/downpayment
 */
data class AccountDetails(
    val accountName: String = "",
    val accountType: String = ""
) {
    // No-argument constructor required for Firestore
    constructor() : this("")
}