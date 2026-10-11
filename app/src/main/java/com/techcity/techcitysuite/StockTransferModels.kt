package com.techcity.techcitysuite

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot

/**
 * Data classes for the Stock Transfer module (collection "stock_transfers").
 *
 * The transfer document carries a snapshot of every phone and accessory in it so the
 * detail screen can render from the transfer document alone. Phones are keyed by their
 * inventory document ID; accessories are keyed by SKU.
 */

/**
 * One phone (or tablet/laptop) unit in a transfer.
 */
data class TransferPhoneItem(
    val inventoryId: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val ram: String = "",
    val storage: String = "",
    val color: String = "",
    val imei1: String = "",
    val imei2: String = "",
    val serialNumber: String = "",
    val originalStatus: String = "",
    val originalLocation: String = "",
    val verified: Boolean = false,
    val verifiedBy: String = "",
    val verifiedAt: Timestamp? = null,
    val scannedType: String = "",
    val scannedValue: String = "",
    val missing: Boolean = false
) {
    constructor() : this("")

    fun toMap(): Map<String, Any?> = mapOf(
        "inventoryId" to inventoryId,
        "manufacturer" to manufacturer,
        "model" to model,
        "ram" to ram,
        "storage" to storage,
        "color" to color,
        "imei1" to imei1,
        "imei2" to imei2,
        "serialNumber" to serialNumber,
        "originalStatus" to originalStatus,
        "originalLocation" to originalLocation,
        "verified" to verified,
        "verifiedBy" to verifiedBy,
        "verifiedAt" to verifiedAt,
        "scannedType" to scannedType,
        "scannedValue" to scannedValue,
        "missing" to missing
    )

    companion object {
        fun fromMap(key: String, map: Map<String, Any?>): TransferPhoneItem = TransferPhoneItem(
            inventoryId = map["inventoryId"] as? String ?: key,
            manufacturer = map["manufacturer"] as? String ?: "",
            model = map["model"] as? String ?: "",
            ram = map["ram"] as? String ?: "",
            storage = map["storage"] as? String ?: "",
            color = map["color"] as? String ?: "",
            imei1 = map["imei1"] as? String ?: "",
            imei2 = map["imei2"] as? String ?: "",
            serialNumber = map["serialNumber"] as? String ?: "",
            originalStatus = map["originalStatus"] as? String ?: "",
            originalLocation = map["originalLocation"] as? String ?: "",
            verified = map["verified"] as? Boolean ?: false,
            verifiedBy = map["verifiedBy"] as? String ?: "",
            verifiedAt = map["verifiedAt"] as? Timestamp,
            scannedType = map["scannedType"] as? String ?: "",
            scannedValue = map["scannedValue"] as? String ?: "",
            missing = map["missing"] as? Boolean ?: false
        )
    }
}

/**
 * One accessory SKU in a transfer, tracked by quantity.
 * originOnHand / originOnDisplay record how many units were taken from each origin
 * bucket at Create, so a delete can put them back exactly.
 */
data class TransferAccessoryItem(
    val sku: String = "",
    val barcode: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val category: String = "",
    val quantity: Int = 0,
    val receivedQuantity: Int = 0,
    val missingQuantity: Int = 0,
    val originOnHand: Int = 0,
    val originOnDisplay: Int = 0,
    val lastReceivedBy: String = "",
    val lastReceivedAt: Timestamp? = null
) {
    constructor() : this("")

    val displayName: String
        get() = "$manufacturer $model".trim()

    val remainingQuantity: Int
        get() = (quantity - receivedQuantity).coerceAtLeast(0)

    fun toMap(): Map<String, Any?> = mapOf(
        "sku" to sku,
        "barcode" to barcode,
        "manufacturer" to manufacturer,
        "model" to model,
        "category" to category,
        "quantity" to quantity,
        "receivedQuantity" to receivedQuantity,
        "missingQuantity" to missingQuantity,
        "originBuckets" to mapOf(
            "onHand" to originOnHand,
            "onDisplay" to originOnDisplay
        ),
        "lastReceivedBy" to lastReceivedBy,
        "lastReceivedAt" to lastReceivedAt
    )

    companion object {
        fun fromMap(key: String, map: Map<String, Any?>): TransferAccessoryItem {
            @Suppress("UNCHECKED_CAST")
            val buckets = map["originBuckets"] as? Map<String, Any?> ?: emptyMap()
            return TransferAccessoryItem(
                sku = map["sku"] as? String ?: key,
                barcode = map["barcode"] as? String ?: "",
                manufacturer = map["manufacturer"] as? String ?: "",
                model = map["model"] as? String ?: "",
                category = map["category"] as? String ?: "",
                quantity = (map["quantity"] as? Number)?.toInt() ?: 0,
                receivedQuantity = (map["receivedQuantity"] as? Number)?.toInt() ?: 0,
                missingQuantity = (map["missingQuantity"] as? Number)?.toInt() ?: 0,
                originOnHand = (buckets["onHand"] as? Number)?.toInt() ?: 0,
                originOnDisplay = (buckets["onDisplay"] as? Number)?.toInt() ?: 0,
                lastReceivedBy = map["lastReceivedBy"] as? String ?: "",
                lastReceivedAt = map["lastReceivedAt"] as? Timestamp
            )
        }
    }
}

/**
 * A stock transfer between two branches.
 */
data class StockTransfer(
    val id: String = "",
    val reference: String = "",
    val status: String = AppConstants.TRANSFER_STATUS_PENDING,
    val hasDiscrepancies: Boolean = false,
    val fromLocation: String = "",
    val fromLocationId: String = "",
    val toLocation: String = "",
    val toLocationId: String = "",
    val date: String = "",
    val createdTime: String = "",
    val createdAt: Timestamp? = null,
    val createdBy: String = "",
    val completedAt: Timestamp? = null,
    val completedBy: String = "",
    val phoneCount: Int = 0,
    val phoneVerifiedCount: Int = 0,
    val accessoryUnitCount: Int = 0,
    val accessoryReceivedCount: Int = 0,
    val missingPhoneCount: Int = 0,
    val missingAccessoryUnitCount: Int = 0,
    val phones: Map<String, TransferPhoneItem> = emptyMap(),
    val accessories: Map<String, TransferAccessoryItem> = emptyMap(),
    val phoneInventoryIds: List<String> = emptyList()
) {
    constructor() : this("")

    val isPending: Boolean
        get() = status == AppConstants.TRANSFER_STATUS_PENDING

    val isCompleted: Boolean
        get() = status == AppConstants.TRANSFER_STATUS_COMPLETED

    /** Phones sorted for display: manufacturer, then model, then IMEI. */
    val sortedPhones: List<TransferPhoneItem>
        get() = phones.values.sortedWith(
            compareBy({ it.manufacturer.lowercase() }, { it.model.lowercase() }, { it.imei1 })
        )

    /** Accessories sorted for display: manufacturer, then model. */
    val sortedAccessories: List<TransferAccessoryItem>
        get() = accessories.values.sortedWith(
            compareBy({ it.manufacturer.lowercase() }, { it.model.lowercase() })
        )

    /** True when every phone is verified and every accessory is fully received. */
    val isFullyVerified: Boolean
        get() = phones.values.all { it.verified } &&
            accessories.values.all { it.receivedQuantity >= it.quantity }

    val verifiedPhoneCount: Int
        get() = phones.values.count { it.verified }

    val receivedAccessoryUnits: Int
        get() = accessories.values.sumOf { it.receivedQuantity }

    val totalAccessoryUnits: Int
        get() = accessories.values.sumOf { it.quantity }

    companion object {
        /**
         * Parse a stock_transfers document. Returns null when the document has no data.
         */
        fun fromDocument(doc: DocumentSnapshot): StockTransfer? {
            val data = doc.data ?: return null

            @Suppress("UNCHECKED_CAST")
            val phonesRaw = data["phones"] as? Map<String, Map<String, Any?>> ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val accessoriesRaw = data["accessories"] as? Map<String, Map<String, Any?>> ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val ids = data["phoneInventoryIds"] as? List<String> ?: emptyList()

            return StockTransfer(
                id = doc.id,
                reference = data["reference"] as? String ?: "",
                status = data["status"] as? String ?: AppConstants.TRANSFER_STATUS_PENDING,
                hasDiscrepancies = data["hasDiscrepancies"] as? Boolean ?: false,
                fromLocation = data["fromLocation"] as? String ?: "",
                fromLocationId = data["fromLocationId"] as? String ?: "",
                toLocation = data["toLocation"] as? String ?: "",
                toLocationId = data["toLocationId"] as? String ?: "",
                date = data["date"] as? String ?: "",
                createdTime = data["createdTime"] as? String ?: "",
                createdAt = data["createdAt"] as? Timestamp,
                createdBy = data["createdBy"] as? String ?: "",
                completedAt = data["completedAt"] as? Timestamp,
                completedBy = data["completedBy"] as? String ?: "",
                phoneCount = (data["phoneCount"] as? Number)?.toInt() ?: 0,
                phoneVerifiedCount = (data["phoneVerifiedCount"] as? Number)?.toInt() ?: 0,
                accessoryUnitCount = (data["accessoryUnitCount"] as? Number)?.toInt() ?: 0,
                accessoryReceivedCount = (data["accessoryReceivedCount"] as? Number)?.toInt() ?: 0,
                missingPhoneCount = (data["missingPhoneCount"] as? Number)?.toInt() ?: 0,
                missingAccessoryUnitCount = (data["missingAccessoryUnitCount"] as? Number)?.toInt() ?: 0,
                phones = phonesRaw.mapValues { (key, value) -> TransferPhoneItem.fromMap(key, value) },
                accessories = accessoriesRaw.mapValues { (key, value) -> TransferAccessoryItem.fromMap(key, value) },
                phoneInventoryIds = ids
            )
        }
    }
}
