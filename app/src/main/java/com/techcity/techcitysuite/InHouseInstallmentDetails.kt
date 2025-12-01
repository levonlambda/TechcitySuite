package com.techcity.techcitysuite

import com.google.firebase.Timestamp

/**
 * Data class representing payment details for In-House Installment Transactions
 * Contains downpayment info, interest calculation, payment terms, and balance tracking
 */
data class InHouseInstallmentDetails(
    // Customer information
    val customerName: String = "",  // NEW: Customer name for In-House transactions

    // Downpayment information
    val downpaymentAmount: Double = 0.0,
    val downpaymentSource: String = "",
    val accountDetails: AccountDetails = AccountDetails(),

    // Interest calculation
    val interestPercent: Double = 0.0,
    val interestAmount: Double = 0.0,

    // Payment terms
    val monthsToPay: Int = 0,
    val monthlyAmount: Double = 0.0,

    // Totals and payment status
    val balance: Double = 0.0,
    val totalAmountDue: Double = 0.0,
    val isBalancePaid: Boolean = false,

    // Remaining balance tracking (for partial payments)
    val remainingBalance: Double = 0.0,
    val lastPaymentDate: String? = null,
    val lastPaymentAmount: Double? = null,

    // Balance payment tracking (populated when isBalancePaid becomes true)
    val balancePaidDate: String? = null,
    val balancePaidBy: String? = null,
    val balancePaidTimestamp: Timestamp? = null
) {
    // No-argument constructor required for Firestore
    constructor() : this("")
}