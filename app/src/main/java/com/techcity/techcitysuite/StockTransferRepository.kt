package com.techcity.techcitysuite

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * All Firestore reads and writes for the Stock Transfer module.
 *
 * Every multi-document mutation (Create, Confirm, Complete with discrepancies, Delete,
 * per-item verification) runs inside a Firestore transaction: all reads happen before
 * the first write, and counters are changed with atomic increments. Each accessory
 * counter change writes an accessory_inventory_adjustments audit entry in the same
 * transaction, with source "mobile-stock-transfer".
 */
object StockTransferRepository {

    // ============================================================================
    // START OF PART 1: TYPES
    // ============================================================================

    /**
     * Who is acting and from which store. Built by the Activity from Program Settings
     * and the signed-in Firebase account.
     */
    data class TransferContext(
        val userName: String,
        val userEmail: String,
        val userId: String,
        val storeLocation: String,
        val storeLocationId: String
    )

    /**
     * Build the acting context from Program Settings and the signed-in account.
     */
    fun contextFrom(context: android.content.Context): TransferContext {
        val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val userName = prefs.getString(AppConstants.KEY_USER, "") ?: ""
        return TransferContext(
            userName = userName.ifBlank { "Unknown" },
            userEmail = AuthManager.currentEmail(),
            userId = AuthManager.currentUser()?.uid ?: "",
            storeLocation = StoreLocationHelper.getStoreLocation(context),
            storeLocationId = prefs.getString(AppConstants.KEY_STORE_LOCATION_ID, "") ?: ""
        )
    }

    /**
     * Result of classifying a scanned value on the sending side.
     */
    sealed class ScanResult {
        data class PhoneFound(val item: InventoryItem) : ScanResult()
        data class PhoneBoxBarcode(val manufacturer: String, val model: String) : ScanResult()
        data class AccessoryFound(
            val sku: String,
            val barcode: String,
            val manufacturer: String,
            val model: String,
            val category: String
        ) : ScanResult()
        data class Ineligible(val message: String) : ScanResult()
        object NotFound : ScanResult()
    }

    /**
     * Result of Complete with discrepancies: phones whose inventory record was no longer
     * reserved for this transfer were left untouched and are reported here.
     */
    data class CompletionResult(val skippedPhones: List<String>)

    /** Snapshot of the four adjustable accessory buckets (zeros when the doc is missing). */
    private data class Buckets(
        val onHand: Long,
        val onDisplay: Long,
        val reserved: Long,
        val defective: Long
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "onHand" to onHand,
            "onDisplay" to onDisplay,
            "reserved" to reserved,
            "defective" to defective
        )
    }

    // ============================================================================
    // END OF PART 1: TYPES
    // ============================================================================


    // ============================================================================
    // START OF PART 2: SCAN CLASSIFICATION (READS ONLY)
    // ============================================================================

    /**
     * Classify a scanned value in this order: phone identifier (imei1 / imei2 / serialNumber),
     * phone box barcode (inventory.barcode), accessory barcode (accessory_products.barcode),
     * not found. Exact, trimmed matching.
     */
    suspend fun classifyScan(
        db: FirebaseFirestore,
        rawValue: String,
        originLocation: String,
        draftPhoneIds: Set<String>
    ): ScanResult = withContext(Dispatchers.IO) {
        val value = rawValue.trim()
        if (value.isEmpty()) return@withContext ScanResult.NotFound

        // 1. Phone unit by identifier
        var firstIneligible: String? = null
        for (field in listOf("imei1", "imei2", "serialNumber")) {
            val snapshot = db.collection(AppConstants.COLLECTION_INVENTORY)
                .whereEqualTo(field, value)
                .limit(5)
                .get()
                .await()
            for (doc in snapshot.documents) {
                val item = doc.toObject(InventoryItem::class.java)?.copy(id = doc.id) ?: continue
                val reason = phoneIneligibilityReason(item, originLocation, draftPhoneIds)
                if (reason == null) {
                    return@withContext ScanResult.PhoneFound(item)
                }
                if (firstIneligible == null) firstIneligible = reason
            }
        }
        if (firstIneligible != null) {
            return@withContext ScanResult.Ineligible(firstIneligible)
        }

        // 2. Phone box barcode
        val boxSnapshot = db.collection(AppConstants.COLLECTION_INVENTORY)
            .whereEqualTo("barcode", value)
            .limit(1)
            .get()
            .await()
        boxSnapshot.documents.firstOrNull()?.let { doc ->
            return@withContext ScanResult.PhoneBoxBarcode(
                manufacturer = doc.getString("manufacturer") ?: "",
                model = doc.getString("model") ?: ""
            )
        }

        // 3. Accessory product barcode
        val productSnapshot = db.collection(AppConstants.COLLECTION_ACCESSORY_PRODUCTS)
            .whereEqualTo("barcode", value)
            .limit(5)
            .get()
            .await()
        for (doc in productSnapshot.documents) {
            val active = doc.getBoolean("active") ?: true
            if (!active) continue
            return@withContext ScanResult.AccessoryFound(
                sku = doc.getString("internalSku") ?: doc.id,
                barcode = doc.getString("barcode") ?: value,
                manufacturer = doc.getString("manufacturer") ?: "",
                model = doc.getString("model") ?: "",
                category = doc.getString("category") ?: ""
            )
        }

        ScanResult.NotFound
    }

    /**
     * Spec Rule 10. Returns null when the phone may be added to a transfer from
     * originLocation, otherwise a user-facing reason.
     */
    fun phoneIneligibilityReason(
        item: InventoryItem,
        originLocation: String,
        draftPhoneIds: Set<String>
    ): String? {
        val label = "${item.manufacturer} ${item.model}".trim()
        if (item.isArchived) return "$label is archived"
        if (item.imei1 == AppConstants.PLACEHOLDER_IMEI || item.imei2 == AppConstants.PLACEHOLDER_IMEI) {
            return "Placeholder devices cannot be transferred"
        }
        if (AppConstants.TRANSFER_EXCLUDED_MANUFACTURERS.any { it.equals(item.manufacturer, ignoreCase = true) }) {
            return "Techcity units are excluded from stock transfers"
        }
        if (item.transferId.isNotBlank()) {
            return "$label is already part of a pending stock transfer"
        }
        when {
            item.status.equals(AppConstants.INVENTORY_STATUS_SOLD, ignoreCase = true) ->
                return "$label is marked Sold"
            item.status.equals(AppConstants.INVENTORY_STATUS_RESERVED, ignoreCase = true) ->
                return if (item.transferId.isNotBlank()) {
                    "$label is already reserved for another stock transfer"
                } else {
                    "$label is reserved"
                }
            !item.status.equals(AppConstants.INVENTORY_STATUS_ON_HAND, ignoreCase = true) &&
                !item.status.equals(AppConstants.INVENTORY_STATUS_ON_DISPLAY, ignoreCase = true) ->
                return "$label has status \"${item.status}\" and cannot be transferred"
        }
        if (!item.location.trim().equals(originLocation.trim(), ignoreCase = true)) {
            return "$label belongs to ${item.location.ifBlank { "no location" }}, not $originLocation"
        }
        if (draftPhoneIds.contains(item.id)) return "Already in this transfer"
        return null
    }

    /**
     * onHand / onDisplay of the origin's counter document, or null when it does not exist.
     */
    suspend fun readOriginStock(
        db: FirebaseFirestore,
        sku: String,
        fromLocationId: String
    ): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val doc = inventoryCounterRef(db, sku, fromLocationId).get().await()
        if (!doc.exists()) return@withContext null
        Pair(
            (doc.getLong("onHand") ?: 0L).toInt(),
            (doc.getLong("onDisplay") ?: 0L).toInt()
        )
    }

    /**
     * Whether a scanned value is a phone box barcode (used on the receiving side to tell
     * "scan the IMEI instead" apart from "not part of this transfer").
     */
    suspend fun isPhoneBoxBarcode(db: FirebaseFirestore, rawValue: String): Boolean =
        withContext(Dispatchers.IO) {
            val value = rawValue.trim()
            if (value.isEmpty()) return@withContext false
            val snapshot = db.collection(AppConstants.COLLECTION_INVENTORY)
                .whereEqualTo("barcode", value)
                .limit(1)
                .get()
                .await()
            !snapshot.isEmpty
        }

    // ============================================================================
    // END OF PART 2: SCAN CLASSIFICATION (READS ONLY)
    // ============================================================================


    // ============================================================================
    // START OF PART 3: CREATE
    // ============================================================================

    /**
     * Create a pending transfer: reserve every phone, subtract accessory quantities from
     * the origin (onHand first, then onDisplay), write audit entries. Returns the new
     * transfer document ID. Throws IllegalStateException with a user-facing message when
     * any item is no longer available.
     */
    suspend fun createTransfer(
        db: FirebaseFirestore,
        ctx: TransferContext,
        phones: List<TransferPhoneItem>,
        accessories: List<TransferAccessoryItem>,
        toLocation: String,
        toLocationId: String
    ): String = withContext(Dispatchers.IO) {
        val transferRef = db.collection(AppConstants.COLLECTION_STOCK_TRANSFERS).document()
        val reference = "ST-${System.currentTimeMillis()}-${(100..999).random()}"

        val now = Date()
        val dateString = SimpleDateFormat("M/d/yyyy", Locale.US).format(now)
        val timeString = SimpleDateFormat("h:mm a", Locale.US).format(now)

        db.runTransaction { tx ->
            // ---- Reads first ----
            val phoneSnaps = phones.map { it to tx.get(phoneRef(db, it.inventoryId)) }
            val accessorySnaps = accessories.map {
                it to tx.get(inventoryCounterRef(db, it.sku, ctx.storeLocationId))
            }

            // ---- Validate phones against the live documents ----
            val finalPhones = mutableMapOf<String, TransferPhoneItem>()
            for ((draft, snap) in phoneSnaps) {
                val label = "${draft.manufacturer} ${draft.model} (${draft.imei1.ifBlank { draft.serialNumber }})"
                if (!snap.exists()) {
                    throw IllegalStateException("$label is no longer in inventory")
                }
                val live = snap.toObject(InventoryItem::class.java)?.copy(id = snap.id)
                    ?: throw IllegalStateException("$label could not be read")
                val reason = phoneIneligibilityReason(live, ctx.storeLocation, emptySet())
                if (reason != null) {
                    throw IllegalStateException("$label is no longer available: $reason")
                }
                finalPhones[live.id] = draft.copy(
                    inventoryId = live.id,
                    originalStatus = live.status,
                    originalLocation = live.location
                )
            }

            // ---- Validate accessory stock and compute the bucket split ----
            data class AccessoryPlan(
                val item: TransferAccessoryItem,
                val ref: DocumentReference,
                val before: Buckets,
                val takeFromOnHand: Long,
                val takeFromOnDisplay: Long
            )
            val plans = mutableListOf<AccessoryPlan>()
            for ((draft, snap) in accessorySnaps) {
                if (draft.quantity <= 0) {
                    throw IllegalStateException("${draft.displayName}: quantity must be at least 1")
                }
                if (!snap.exists()) {
                    throw IllegalStateException("No stock recorded at ${ctx.storeLocation} for ${draft.displayName}")
                }
                val before = bucketsOf(snap)
                val qty = draft.quantity.toLong()
                val fromOnHand = minOf(qty, before.onHand)
                val fromOnDisplay = qty - fromOnHand
                if (fromOnDisplay > before.onDisplay) {
                    throw IllegalStateException(
                        "${draft.displayName}: only ${before.onHand + before.onDisplay} available at ${ctx.storeLocation}"
                    )
                }
                plans.add(AccessoryPlan(draft, snap.reference, before, fromOnHand, fromOnDisplay))
            }

            // ---- Writes ----
            val finalAccessories = plans.associate { plan ->
                plan.item.sku to plan.item.copy(
                    receivedQuantity = 0,
                    missingQuantity = 0,
                    originOnHand = plan.takeFromOnHand.toInt(),
                    originOnDisplay = plan.takeFromOnDisplay.toInt()
                )
            }

            val transferData = hashMapOf<String, Any?>(
                "reference" to reference,
                "status" to AppConstants.TRANSFER_STATUS_PENDING,
                "hasDiscrepancies" to false,
                "fromLocation" to ctx.storeLocation,
                "fromLocationId" to ctx.storeLocationId,
                "toLocation" to toLocation,
                "toLocationId" to toLocationId,
                "date" to dateString,
                "createdTime" to timeString,
                "createdAt" to Timestamp.now(),
                "createdBy" to ctx.userName,
                "completedAt" to null,
                "completedBy" to "",
                "phoneCount" to finalPhones.size,
                "phoneVerifiedCount" to 0,
                "accessoryUnitCount" to finalAccessories.values.sumOf { it.quantity },
                "accessoryReceivedCount" to 0,
                "missingPhoneCount" to 0,
                "missingAccessoryUnitCount" to 0,
                "phones" to finalPhones.mapValues { it.value.toMap() },
                "accessories" to finalAccessories.mapValues { it.value.toMap() },
                "phoneInventoryIds" to finalPhones.keys.toList()
            )
            tx.set(transferRef, transferData)

            for (id in finalPhones.keys) {
                tx.update(
                    phoneRef(db, id),
                    mapOf(
                        "status" to AppConstants.INVENTORY_STATUS_RESERVED,
                        "transferId" to transferRef.id
                    )
                )
            }

            for (plan in plans) {
                val updates = mutableMapOf<String, Any>(
                    "lastUpdated" to FieldValue.serverTimestamp()
                )
                val reservedDelta = plan.takeFromOnHand + plan.takeFromOnDisplay
                if (plan.takeFromOnHand > 0) updates["onHand"] = FieldValue.increment(-plan.takeFromOnHand)
                if (plan.takeFromOnDisplay > 0) updates["onDisplay"] = FieldValue.increment(-plan.takeFromOnDisplay)
                // In-transit units stay the origin's stock, held in its reserved bucket
                updates["reserved"] = FieldValue.increment(reservedDelta)
                tx.update(plan.ref, updates)

                addAuditEntry(
                    tx = tx,
                    db = db,
                    ctx = ctx,
                    sku = plan.item.sku,
                    locationId = ctx.storeLocationId,
                    locationName = ctx.storeLocation,
                    before = plan.before,
                    deltaOnHand = -plan.takeFromOnHand,
                    deltaOnDisplay = -plan.takeFromOnDisplay,
                    deltaReserved = reservedDelta,
                    reason = "Stock transfer $reference to $toLocation"
                )
            }

            null
        }.await()

        transferRef.id
    }

    // ============================================================================
    // END OF PART 3: CREATE
    // ============================================================================


    // ============================================================================
    // START OF PART 4: RECEIVING (PER-ITEM)
    // ============================================================================

    /**
     * Mark one phone of a pending transfer as verified.
     */
    suspend fun verifyPhone(
        db: FirebaseFirestore,
        transferId: String,
        inventoryId: String,
        verifiedBy: String,
        scannedType: String,
        scannedValue: String
    ) = withContext(Dispatchers.IO) {
        val transferRef = transferRef(db, transferId)
        db.runTransaction { tx ->
            val snap = tx.get(transferRef)
            val transfer = StockTransfer.fromDocument(snap)
                ?: throw IllegalStateException("Transfer not found")
            if (!transfer.isPending) throw IllegalStateException("This transfer is no longer pending")
            val phone = transfer.phones[inventoryId]
                ?: throw IllegalStateException("Item is not part of this transfer")
            if (phone.verified) throw IllegalStateException("Already verified by ${phone.verifiedBy}")

            tx.update(
                transferRef,
                FieldPath.of("phones", inventoryId, "verified"), true,
                FieldPath.of("phones", inventoryId, "verifiedBy"), verifiedBy,
                FieldPath.of("phones", inventoryId, "verifiedAt"), Timestamp.now(),
                FieldPath.of("phones", inventoryId, "scannedType"), scannedType,
                FieldPath.of("phones", inventoryId, "scannedValue"), scannedValue,
                FieldPath.of("phoneVerifiedCount"), FieldValue.increment(1)
            )
            null
        }.await()
    }

    /**
     * Add received units to one accessory line of a pending transfer.
     */
    suspend fun receiveAccessory(
        db: FirebaseFirestore,
        transferId: String,
        sku: String,
        amount: Int,
        receivedBy: String
    ) = withContext(Dispatchers.IO) {
        if (amount <= 0) throw IllegalArgumentException("Quantity must be at least 1")
        val transferRef = transferRef(db, transferId)
        db.runTransaction { tx ->
            val snap = tx.get(transferRef)
            val transfer = StockTransfer.fromDocument(snap)
                ?: throw IllegalStateException("Transfer not found")
            if (!transfer.isPending) throw IllegalStateException("This transfer is no longer pending")
            val line = transfer.accessories[sku]
                ?: throw IllegalStateException("Item is not part of this transfer")
            if (line.receivedQuantity + amount > line.quantity) {
                throw IllegalStateException("Only ${line.remainingQuantity} remaining for ${line.displayName}")
            }

            tx.update(
                transferRef,
                FieldPath.of("accessories", sku, "receivedQuantity"), FieldValue.increment(amount.toLong()),
                FieldPath.of("accessories", sku, "lastReceivedBy"), receivedBy,
                FieldPath.of("accessories", sku, "lastReceivedAt"), Timestamp.now(),
                FieldPath.of("accessoryReceivedCount"), FieldValue.increment(amount.toLong())
            )
            null
        }.await()
    }

    // ============================================================================
    // END OF PART 4: RECEIVING (PER-ITEM)
    // ============================================================================


    // ============================================================================
    // START OF PART 5: CONFIRM / COMPLETE WITH DISCREPANCIES
    // ============================================================================

    /**
     * Complete a fully verified transfer: phones become On-Hand at the destination,
     * accessory units are added to the destination's onHand.
     */
    suspend fun confirmTransfer(
        db: FirebaseFirestore,
        ctx: TransferContext,
        transferId: String
    ) = withContext(Dispatchers.IO) {
        val transferRef = transferRef(db, transferId)
        val today = SimpleDateFormat("M/d/yyyy", Locale.US).format(Date())

        db.runTransaction { tx ->
            // ---- Reads ----
            val transfer = StockTransfer.fromDocument(tx.get(transferRef))
                ?: throw IllegalStateException("Transfer not found")
            if (!transfer.isPending) throw IllegalStateException("This transfer was already completed")
            if (!transfer.isFullyVerified) throw IllegalStateException("Not all items have been verified")

            val phoneSnaps = transfer.phones.values.map { it to tx.get(phoneRef(db, it.inventoryId)) }
            val destSnaps = transfer.accessories.values.map {
                it to tx.get(inventoryCounterRef(db, it.sku, transfer.toLocationId))
            }
            val originSnaps = transfer.accessories.values.map {
                it to tx.get(inventoryCounterRef(db, it.sku, transfer.fromLocationId))
            }

            for ((phone, snap) in phoneSnaps) {
                if (!isReservedForTransfer(snap, transferId)) {
                    throw IllegalStateException(
                        "${phone.manufacturer} ${phone.model} (${phone.imei1.ifBlank { phone.serialNumber }}) " +
                            "is no longer reserved for this transfer"
                    )
                }
            }

            // ---- Writes ----
            for ((_, snap) in phoneSnaps) {
                movePhoneToDestination(tx, snap.reference, transfer.toLocation, today)
            }

            for ((line, snap) in destSnaps) {
                receiveIntoDestination(
                    tx, db, ctx, transfer, line.sku, line.quantity.toLong(), snap
                )
            }

            for ((line, snap) in originSnaps) {
                returnToOrigin(
                    tx, db, ctx, transfer, line.sku,
                    onHandDelta = 0L, onDisplayDelta = 0L,
                    reservedDelta = -line.quantity.toLong(),
                    snap = snap,
                    reason = "Stock transfer ${transfer.reference} received at ${transfer.toLocation}"
                )
            }

            tx.update(
                transferRef,
                mapOf(
                    "status" to AppConstants.TRANSFER_STATUS_COMPLETED,
                    "hasDiscrepancies" to false,
                    "completedAt" to Timestamp.now(),
                    "completedBy" to ctx.userName
                )
            )
            null
        }.await()
    }

    /**
     * Complete a partially verified transfer. Verified phones and received units move to
     * the destination; unverified phones go back to their original status at the origin;
     * missing accessory units are returned to the origin's onHand.
     */
    suspend fun completeWithDiscrepancies(
        db: FirebaseFirestore,
        ctx: TransferContext,
        transferId: String
    ): CompletionResult = withContext(Dispatchers.IO) {
        val transferRef = transferRef(db, transferId)
        val today = SimpleDateFormat("M/d/yyyy", Locale.US).format(Date())

        db.runTransaction { tx ->
            // ---- Reads ----
            val transfer = StockTransfer.fromDocument(tx.get(transferRef))
                ?: throw IllegalStateException("Transfer not found")
            if (!transfer.isPending) throw IllegalStateException("This transfer was already completed")

            val phoneSnaps = transfer.phones.values.map { it to tx.get(phoneRef(db, it.inventoryId)) }
            val destSnaps = transfer.accessories.values.map {
                it to tx.get(inventoryCounterRef(db, it.sku, transfer.toLocationId))
            }
            val originSnaps = transfer.accessories.values.map {
                it to tx.get(inventoryCounterRef(db, it.sku, transfer.fromLocationId))
            }

            // ---- Writes: phones ----
            val skipped = mutableListOf<String>()
            var missingPhones = 0
            for ((phone, snap) in phoneSnaps) {
                val label = "${phone.manufacturer} ${phone.model} (${phone.imei1.ifBlank { phone.serialNumber }})"
                val intact = isReservedForTransfer(snap, transferId)
                when {
                    phone.verified && intact -> {
                        movePhoneToDestination(tx, snap.reference, transfer.toLocation, today)
                    }
                    !phone.verified && intact -> {
                        restorePhoneAtOrigin(tx, snap.reference, phone.originalStatus)
                        tx.update(transferRef, FieldPath.of("phones", phone.inventoryId, "missing"), true)
                        missingPhones++
                    }
                    else -> {
                        // Inventory record changed outside the app: leave it untouched
                        skipped.add(label)
                        tx.update(transferRef, FieldPath.of("phones", phone.inventoryId, "missing"), true)
                        missingPhones++
                    }
                }
            }

            // ---- Writes: accessories ----
            var missingUnits = 0
            val originBySku = originSnaps.associate { it.first.sku to it.second }
            for ((line, destSnap) in destSnaps) {
                val received = line.receivedQuantity.coerceIn(0, line.quantity).toLong()
                val missing = (line.quantity - received.toInt()).coerceAtLeast(0)

                if (received > 0) {
                    receiveIntoDestination(tx, db, ctx, transfer, line.sku, received, destSnap)
                }
                // Release the in-transit units from the origin's reserved bucket; missing
                // units go back into the origin's onHand in the same write.
                val originSnap = originBySku[line.sku]
                    ?: throw IllegalStateException("Origin stock for ${line.displayName} could not be read")
                returnToOrigin(
                    tx, db, ctx, transfer, line.sku,
                    onHandDelta = missing.toLong(), onDisplayDelta = 0L,
                    reservedDelta = -line.quantity.toLong(),
                    snap = originSnap,
                    reason = if (missing > 0) {
                        "Stock transfer ${transfer.reference}: $missing missing unit(s) returned"
                    } else {
                        "Stock transfer ${transfer.reference} received at ${transfer.toLocation}"
                    }
                )
                if (missing > 0) {
                    tx.update(transferRef, FieldPath.of("accessories", line.sku, "missingQuantity"), missing)
                    missingUnits += missing
                }
            }

            tx.update(
                transferRef,
                mapOf(
                    "status" to AppConstants.TRANSFER_STATUS_COMPLETED,
                    "hasDiscrepancies" to true,
                    "missingPhoneCount" to missingPhones,
                    "missingAccessoryUnitCount" to missingUnits,
                    "completedAt" to Timestamp.now(),
                    "completedBy" to ctx.userName
                )
            )
            CompletionResult(skipped)
        }.await()
    }

    // ============================================================================
    // END OF PART 5: CONFIRM / COMPLETE WITH DISCREPANCIES
    // ============================================================================


    // ============================================================================
    // START OF PART 6: DELETE
    // ============================================================================

    /**
     * Delete a pending transfer and put everything back at the origin.
     */
    suspend fun deleteTransfer(
        db: FirebaseFirestore,
        ctx: TransferContext,
        transferId: String
    ) = withContext(Dispatchers.IO) {
        val transferRef = transferRef(db, transferId)

        db.runTransaction { tx ->
            // ---- Reads ----
            val transfer = StockTransfer.fromDocument(tx.get(transferRef))
                ?: throw IllegalStateException("Transfer not found")
            if (!transfer.isPending) throw IllegalStateException("Completed transfers cannot be deleted")

            val phoneSnaps = transfer.phones.values.map { it to tx.get(phoneRef(db, it.inventoryId)) }
            val originSnaps = transfer.accessories.values.map {
                it to tx.get(inventoryCounterRef(db, it.sku, transfer.fromLocationId))
            }

            // ---- Writes ----
            for ((phone, snap) in phoneSnaps) {
                if (isReservedForTransfer(snap, transferId)) {
                    restorePhoneAtOrigin(tx, snap.reference, phone.originalStatus)
                }
            }

            for ((line, snap) in originSnaps) {
                returnToOrigin(
                    tx, db, ctx, transfer, line.sku,
                    onHandDelta = line.originOnHand.toLong(),
                    onDisplayDelta = line.originOnDisplay.toLong(),
                    reservedDelta = -line.quantity.toLong(),
                    snap = snap,
                    reason = "Stock transfer ${transfer.reference} deleted"
                )
            }

            tx.delete(transferRef)
            null
        }.await()
    }

    // ============================================================================
    // END OF PART 6: DELETE
    // ============================================================================


    // ============================================================================
    // START OF PART 7: PRIVATE HELPERS
    // ============================================================================

    private fun transferRef(db: FirebaseFirestore, id: String): DocumentReference =
        db.collection(AppConstants.COLLECTION_STOCK_TRANSFERS).document(id)

    private fun phoneRef(db: FirebaseFirestore, inventoryId: String): DocumentReference =
        db.collection(AppConstants.COLLECTION_INVENTORY).document(inventoryId)

    private fun inventoryCounterRef(db: FirebaseFirestore, sku: String, locationId: String): DocumentReference =
        db.collection(AppConstants.COLLECTION_ACCESSORY_INVENTORY).document("${sku}__${locationId}")

    private fun bucketsOf(snap: DocumentSnapshot): Buckets {
        if (!snap.exists()) return Buckets(0, 0, 0, 0)
        return Buckets(
            onHand = snap.getLong("onHand") ?: 0L,
            onDisplay = snap.getLong("onDisplay") ?: 0L,
            reserved = snap.getLong("reserved") ?: 0L,
            defective = snap.getLong("defective") ?: 0L
        )
    }

    private fun isReservedForTransfer(snap: DocumentSnapshot, transferId: String): Boolean {
        if (!snap.exists()) return false
        val status = snap.getString("status") ?: ""
        val tid = snap.getString("transferId") ?: ""
        return status.equals(AppConstants.INVENTORY_STATUS_RESERVED, ignoreCase = true) && tid == transferId
    }

    private fun movePhoneToDestination(
        tx: Transaction,
        ref: DocumentReference,
        toLocation: String,
        today: String
    ) {
        tx.update(
            ref,
            mapOf(
                "status" to AppConstants.INVENTORY_STATUS_ON_HAND,
                "location" to toLocation,
                "lastUpdated" to today,
                "transferId" to FieldValue.delete()
            )
        )
    }

    private fun restorePhoneAtOrigin(tx: Transaction, ref: DocumentReference, originalStatus: String) {
        tx.update(
            ref,
            mapOf(
                "status" to originalStatus.ifBlank { AppConstants.INVENTORY_STATUS_ON_HAND },
                "transferId" to FieldValue.delete()
            )
        )
    }

    /**
     * Add units to the destination's onHand, creating the counter document when it does
     * not exist yet, and write the audit entry.
     */
    private fun receiveIntoDestination(
        tx: Transaction,
        db: FirebaseFirestore,
        ctx: TransferContext,
        transfer: StockTransfer,
        sku: String,
        quantity: Long,
        snap: DocumentSnapshot
    ) {
        if (quantity <= 0) return
        val before = bucketsOf(snap)
        if (snap.exists()) {
            tx.update(
                snap.reference,
                mapOf(
                    "onHand" to FieldValue.increment(quantity),
                    "lastUpdated" to FieldValue.serverTimestamp()
                )
            )
        } else {
            tx.set(
                snap.reference,
                mapOf(
                    "sku" to sku,
                    "locationId" to transfer.toLocationId,
                    "locationName" to transfer.toLocation,
                    "onHand" to quantity,
                    "onDisplay" to 0L,
                    "reserved" to 0L,
                    "defective" to 0L,
                    "sold" to 0L,
                    "lastUpdated" to FieldValue.serverTimestamp()
                )
            )
        }
        addAuditEntry(
            tx = tx,
            db = db,
            ctx = ctx,
            sku = sku,
            locationId = transfer.toLocationId,
            locationName = transfer.toLocation,
            before = before,
            deltaOnHand = quantity,
            deltaOnDisplay = 0L,
            reason = "Stock transfer ${transfer.reference} from ${transfer.fromLocation}"
        )
    }

    /**
     * Release in-transit units from the origin's reserved bucket (reservedDelta <= 0), add
     * any returned units back to the origin's onHand / onDisplay, and write one audit entry.
     * When the origin document no longer exists the reserved release is skipped (it cannot
     * go negative) and only the returned units are written into a new document.
     */
    private fun returnToOrigin(
        tx: Transaction,
        db: FirebaseFirestore,
        ctx: TransferContext,
        transfer: StockTransfer,
        sku: String,
        onHandDelta: Long,
        onDisplayDelta: Long,
        reservedDelta: Long,
        snap: DocumentSnapshot,
        reason: String
    ) {
        if (onHandDelta <= 0 && onDisplayDelta <= 0 && reservedDelta == 0L) return
        val before = bucketsOf(snap)
        val effectiveReservedDelta = if (snap.exists()) reservedDelta else 0L
        if (snap.exists()) {
            val updates = mutableMapOf<String, Any>(
                "lastUpdated" to FieldValue.serverTimestamp()
            )
            if (onHandDelta > 0) updates["onHand"] = FieldValue.increment(onHandDelta)
            if (onDisplayDelta > 0) updates["onDisplay"] = FieldValue.increment(onDisplayDelta)
            if (effectiveReservedDelta != 0L) updates["reserved"] = FieldValue.increment(effectiveReservedDelta)
            tx.update(snap.reference, updates)
        } else if (onHandDelta <= 0 && onDisplayDelta <= 0) {
            return
        } else {
            tx.set(
                snap.reference,
                mapOf(
                    "sku" to sku,
                    "locationId" to transfer.fromLocationId,
                    "locationName" to transfer.fromLocation,
                    "onHand" to onHandDelta.coerceAtLeast(0),
                    "onDisplay" to onDisplayDelta.coerceAtLeast(0),
                    "reserved" to 0L,
                    "defective" to 0L,
                    "sold" to 0L,
                    "lastUpdated" to FieldValue.serverTimestamp()
                )
            )
        }
        addAuditEntry(
            tx = tx,
            db = db,
            ctx = ctx,
            sku = sku,
            locationId = transfer.fromLocationId,
            locationName = transfer.fromLocation,
            before = before,
            deltaOnHand = onHandDelta,
            deltaOnDisplay = onDisplayDelta,
            deltaReserved = effectiveReservedDelta,
            reason = reason
        )
    }

    /**
     * Audit entry in accessory_inventory_adjustments (same shape as the web app).
     */
    private fun addAuditEntry(
        tx: Transaction,
        db: FirebaseFirestore,
        ctx: TransferContext,
        sku: String,
        locationId: String,
        locationName: String,
        before: Buckets,
        deltaOnHand: Long,
        deltaOnDisplay: Long,
        deltaReserved: Long = 0L,
        reason: String
    ) {
        val after = before.copy(
            onHand = before.onHand + deltaOnHand,
            onDisplay = before.onDisplay + deltaOnDisplay,
            reserved = before.reserved + deltaReserved
        )
        val delta = mutableMapOf<String, Any>()
        if (deltaOnHand != 0L) delta["onHand"] = deltaOnHand
        if (deltaOnDisplay != 0L) delta["onDisplay"] = deltaOnDisplay
        if (deltaReserved != 0L) delta["reserved"] = deltaReserved

        val entry = hashMapOf<String, Any?>(
            "sku" to sku,
            "locationId" to locationId,
            "locationName" to locationName,
            "userId" to ctx.userId.ifBlank { null },
            "userEmail" to ctx.userEmail.ifBlank { null },
            "timestamp" to Timestamp.now(),
            "before" to before.toMap(),
            "after" to after.toMap(),
            "delta" to delta,
            "reason" to reason,
            "source" to AppConstants.TRANSFER_AUDIT_SOURCE
        )
        tx.set(db.collection(AppConstants.COLLECTION_ACCESSORY_INVENTORY_ADJUSTMENTS).document(), entry)
    }

    // ============================================================================
    // END OF PART 7: PRIVATE HELPERS
    // ============================================================================
}
