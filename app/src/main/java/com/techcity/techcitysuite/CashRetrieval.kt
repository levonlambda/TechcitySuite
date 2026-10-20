package com.techcity.techcitysuite

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue

/**
 * One cash retrieval recorded from the Overview module.
 * Stored in Firebase under the "cash_retrievals" collection, one document per retrieval.
 *
 * The most recent document for a store location (greatest rangeEnd) determines where that
 * branch's Running cash on hand starts counting again.
 */
data class CashRetrieval(
    val id: String = "",
    val storeLocation: String = "",           // Full store location name, e.g. "TC-Kapatagan"
    val storeLocationId: String = "",         // accessory_locations document ID
    val recordedAt: Timestamp? = null,        // Server timestamp when the retrieval was recorded
    val rangeStart: String = "",              // yyyy-MM-dd, first day covered by this retrieval
    val rangeEnd: String = "",                // yyyy-MM-dd, last day covered (always "yesterday" at record time)
    val amount: Double = 0.0,                 // Running cash collected
    val daysMissingReport: Int = 0,           // Days in the range with no End of Day document
    val recordedBy: String = "",              // Program Settings user name
    val recordedByAccount: String = ""        // Signed-in Firebase account email
) {
    // No-argument constructor required for Firestore
    constructor() : this("")

    fun toFirestoreMap(): Map<String, Any?> = hashMapOf(
        "storeLocation" to storeLocation,
        "storeLocationId" to storeLocationId,
        "recordedAt" to FieldValue.serverTimestamp(),
        "rangeStart" to rangeStart,
        "rangeEnd" to rangeEnd,
        "amount" to amount,
        "daysMissingReport" to daysMissingReport,
        "recordedBy" to recordedBy,
        "recordedByAccount" to recordedByAccount
    )

    companion object {
        fun fromDocument(doc: DocumentSnapshot): CashRetrieval = CashRetrieval(
            id = doc.id,
            storeLocation = doc.getString("storeLocation") ?: "",
            storeLocationId = doc.getString("storeLocationId") ?: "",
            recordedAt = doc.getTimestamp("recordedAt"),
            rangeStart = doc.getString("rangeStart") ?: "",
            rangeEnd = doc.getString("rangeEnd") ?: "",
            amount = doc.getDouble("amount") ?: 0.0,
            daysMissingReport = doc.getLong("daysMissingReport")?.toInt() ?: 0,
            recordedBy = doc.getString("recordedBy") ?: "",
            recordedByAccount = doc.getString("recordedByAccount") ?: ""
        )
    }
}
