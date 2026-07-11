package com.techcity.techcitysuite

import com.google.firebase.Timestamp

data class Expense(
    val documentId: String = "",
    val description: String = "",
    val amount: Double = 0.0,
    val date: String = "",        // yyyy-MM-dd (GMT+8)
    val monthKey: String = "",    // yyyy-MM (GMT+8)
    val createdBy: String = "",
    val storeLocation: String = "",
    val storeLocationId: String = "",
    val timestamp: Timestamp? = null
) {
    // No-argument constructor required for Firestore
    constructor() : this("")
}
