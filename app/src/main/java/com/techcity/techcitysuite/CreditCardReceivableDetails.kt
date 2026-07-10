package com.techcity.techcitysuite

import com.google.firebase.Timestamp

data class CreditCardReceivableDetails(
    val amountCharged: Double = 0.0,
    val feePercent: Double = 0.0,
    val netReceivable: Double = 0.0,
    val isPaid: Boolean = false,
    val paidDate: String? = null,
    val paidBy: String? = null,
    val paidTimestamp: Timestamp? = null
) {
    constructor() : this(0.0)   // Firestore no-arg requirement
}
