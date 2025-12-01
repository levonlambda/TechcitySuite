package com.techcity.techcitysuite

/**
 * Data class representing an Accessory Transaction
 * Stored in Firebase under "accessory_transactions" collection
 */
data class AccessoryTransaction(
    val id: String = "",

    // Date and Time Fields
    val date: String = "",                    // yyyy-MM-dd format for querying
    val month: String = "",                   // yyyy-MM format
    val year: String = "",                    // yyyy format
    val dateSold: String = "",                // M/d/yyyy display format
    val time: String = "",                    // HH:mm:ss format

    // User and Location
    val user: String = "",
    val userLocation: String = "",
    val deviceId: String = "",                // App device ID

    // Accessory Details
    val accessoryName: String = "",

    // Pricing Information
    val price: Double = 0.0,
    val discountAmount: Double = 0.0,
    val discountPercent: Double = 0.0,
    val finalPrice: Double = 0.0,

    // Transaction Type
    val transactionType: String = "",         // Cash, Home Credit, Skyro, In-House

    // Payment Details (only one will be populated based on transactionType)
    val cashPayment: CashPaymentDetails? = null,
    val homeCreditPayment: AccessoryHomeCreditPaymentDetails? = null,
    val skyroPayment: AccessorySkyroPaymentDetails? = null,
    val inHouseInstallment: AccessoryInHouseInstallmentDetails? = null,

    // Transaction Status
    val status: String = "",                  // "completed", "cancelled", etc.
    val createdBy: String = "",
    val notes: String = "",

    // Sort Order (for custom ordering in list view)
    val sortOrder: Int = 0
) {
    // No-argument constructor required for Firestore
    constructor() : this("")
}

/**
 * Home Credit payment details for accessories (no Brand Zero)
 */
data class AccessoryHomeCreditPaymentDetails(
    val downpaymentAmount: Double = 0.0,
    val downpaymentSource: String = "",
    val accountDetails: AccountDetails? = null,
    val balance: Double = 0.0,
    val isBalancePaid: Boolean = false
) {
    constructor() : this(0.0)
}

/**
 * Skyro payment details for accessories (no Brand Zero)
 */
data class AccessorySkyroPaymentDetails(
    val downpaymentAmount: Double = 0.0,
    val downpaymentSource: String = "",
    val accountDetails: AccountDetails? = null,
    val balance: Double = 0.0,
    val isBalancePaid: Boolean = false
) {
    constructor() : this(0.0)
}

/**
 * In-House Installment details for accessories
 */
data class AccessoryInHouseInstallmentDetails(
    val customerName: String = "",            // Customer name for In-House
    val downpaymentAmount: Double = 0.0,
    val downpaymentSource: String = "",
    val accountDetails: AccountDetails? = null,
    val interestPercent: Double = 0.0,
    val interestAmount: Double = 0.0,
    val monthsToPay: Int = 0,
    val monthlyAmount: Double = 0.0,
    val balance: Double = 0.0,
    val totalAmountDue: Double = 0.0,
    val isBalancePaid: Boolean = false,
    val remainingBalance: Double = 0.0
) {
    constructor() : this("")
}