package com.techcity.techcitysuite

/**
 * Data class representing payment details for Cash Transactions
 * Contains amount paid, payment source, and account details
 */
data class CashPaymentDetails(
    val amountPaid: Double = 0.0,
    val paymentSource: String = "",
    val accountDetails: AccountDetails = AccountDetails()
) {
    // No-argument constructor required for Firestore
    constructor() : this(0.0)
}