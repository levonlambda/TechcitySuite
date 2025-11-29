package com.techcity.techcitysuite

/**
 * Application-wide constants
 * Single source of truth for SharedPreferences keys, Firebase collection names, etc.
 */
object AppConstants {

    // ============================================================================
    // SHARED PREFERENCES
    // ============================================================================

    const val PREFS_NAME = "TechCitySettings"

    // User and Location
    const val KEY_USER = "user"
    const val KEY_STORE_LOCATION = "store_location"

    // Account Settings
    const val KEY_CASH_ACCOUNT = "cash_account"
    const val KEY_GCASH_ACCOUNT = "gcash_account"
    const val KEY_PAYMAYA_ACCOUNT = "paymaya_account"
    const val KEY_QRPH_ACCOUNT = "qrph_account"
    const val KEY_CREDIT_CARD_ACCOUNT = "credit_card_account"
    const val KEY_OTHER_ACCOUNT = "other_account"

    // ============================================================================
    // FIREBASE COLLECTION NAMES
    // ============================================================================

    const val COLLECTION_INVENTORY = "inventory"
    const val COLLECTION_PHONES = "phones"
    const val COLLECTION_DEVICE_TRANSACTIONS = "device_transactions"
    const val COLLECTION_APP_SETTINGS = "app_settings"
    const val COLLECTION_PRICE_CONFIGURATIONS = "price_configurations"
    const val COLLECTION_SUPPLIERS = "suppliers"
    const val COLLECTION_PROCUREMENTS = "procurements"
    const val COLLECTION_SUPPLIER_LEDGER = "supplier_ledger"
    const val COLLECTION_INVENTORY_ARCHIVES = "inventory_archives"

    // ============================================================================
    // TRANSACTION TYPES
    // ============================================================================

    const val TRANSACTION_TYPE_CASH = "Cash Transaction"
    const val TRANSACTION_TYPE_HOME_CREDIT = "Home Credit Transaction"
    const val TRANSACTION_TYPE_SKYRO = "Skyro Transaction"
    const val TRANSACTION_TYPE_IN_HOUSE = "In-House Installment"

    // ============================================================================
    // PAYMENT SOURCES
    // ============================================================================

    const val PAYMENT_SOURCE_CASH = "Cash"
    const val PAYMENT_SOURCE_GCASH = "GCash"
    const val PAYMENT_SOURCE_PAYMAYA = "PayMaya"
    const val PAYMENT_SOURCE_BANK_TRANSFER = "Bank Transfer"
    const val PAYMENT_SOURCE_CREDIT_CARD = "Credit Card"
    const val PAYMENT_SOURCE_OTHERS = "Others"

    // ============================================================================
    // DEVICE TYPES
    // ============================================================================

    const val DEVICE_TYPE_PHONE = "Phone"
    const val DEVICE_TYPE_TABLET = "Tablet"
    const val DEVICE_TYPE_LAPTOP = "Laptop"
    const val DEVICE_TYPE_PRINTER = "Printer"

    // ============================================================================
    // SUBSIDY PERCENTAGES (Brand Zero)
    // ============================================================================

    const val SUBSIDY_PERCENT_PHONE = 3.0      // 3% for phones
    const val SUBSIDY_PERCENT_TABLET = 3.0     // 3% for tablets
    const val SUBSIDY_PERCENT_LAPTOP = 8.0     // 8% for laptops

    // ============================================================================
    // TRANSACTION STATUS
    // ============================================================================

    const val STATUS_COMPLETED = "completed"
    const val STATUS_VOID = "void"
    const val STATUS_RETURNED = "returned"

    // ============================================================================
    // INVENTORY STATUS
    // ============================================================================

    const val INVENTORY_STATUS_ON_HAND = "On-Hand"
    const val INVENTORY_STATUS_SOLD = "Sold"
    const val INVENTORY_STATUS_ON_DISPLAY = "On-Display"
}