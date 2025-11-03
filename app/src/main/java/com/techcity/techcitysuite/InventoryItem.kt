package com.techcity.techcitysuite

data class InventoryItem(
    val id: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val ram: String = "",
    val storage: String = "",
    val color: String = "",
    val dealersPrice: Double = 0.0,
    val retailPrice: Double = 0.0,
    val imei1: String = "",
    val imei2: String = "",
    val serialNumber: String = "",
    val barcode: String = "",
    val location: String = "",
    val status: String = "",
    val supplier: String = "",
    val supplierId: String = "",
    val procurementId: String = "",
    val dateAdded: String = "",
    val lastUpdated: String = "",
    val isArchived: Boolean = false
) {
    // No-argument constructor required for Firestore
    constructor() : this("")
}