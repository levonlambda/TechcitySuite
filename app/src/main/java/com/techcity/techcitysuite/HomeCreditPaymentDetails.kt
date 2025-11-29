package com.techcity.techcitysuite

import com.google.firebase.Timestamp

/**
 * Data class representing payment details for Home Credit Transactions
 * Contains downpayment info, Brand Zero subsidy, balance tracking, and payment status
 */
data class HomeCreditPaymentDetails(
    // Downpayment information
    val downpaymentAmount: Double = 0.0,
    val downpaymentSource: String = "",
    val accountDetails: AccountDetails = AccountDetails(),

    // Brand Zero subsidy fields
    val brandZero: Boolean = false,
    val brandZeroSubsidy: Double = 0.0,
    val subsidyPercent: Double = 0.0,

    // Balance and payment status
    val balance: Double = 0.0,
    val isBalancePaid: Boolean = false,

    // Balance payment tracking (populated when isBalancePaid becomes true)
    val balancePaidDate: String? = null,
    val balancePaidBy: String? = null,
    val balancePaidTimestamp: Timestamp? = null
) {
    // No-argument constructor required for Firestore
    constructor() : this(0.0)
}