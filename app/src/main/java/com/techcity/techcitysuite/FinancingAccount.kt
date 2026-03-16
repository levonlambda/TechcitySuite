package com.techcity.techcitysuite

import com.google.firebase.Timestamp

data class FinancingAccount(
    val id: String = "",
    val financingCompany: String = "",
    val customerName: String = "",
    val accountNumber: String = "",
    val purchaseDate: String = "",
    val contactNumber: String = "",
    val devicePurchased: String? = null,
    val monthlyPayment: Double? = null,
    val term: String? = null,
    val downpayment: Double? = null,
    val financedAmount: Double? = null,
    val createdAt: Timestamp? = null,
    val createdBy: String = "",
    val storeLocation: String = ""
) {
    // No-argument constructor required for Firestore
    constructor() : this("")
}
