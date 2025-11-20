package com.techcity.techcitysuite

import java.text.SimpleDateFormat
import java.util.*

/**
 * Enum for ledger types
 */
enum class LedgerType {
    CASH,
    GCASH,
    PAYMAYA,
    OTHERS;

    companion object {
        fun fromString(value: String): LedgerType {
            return when (value.uppercase()) {
                "CASH" -> CASH
                "GCASH" -> GCASH
                "PAYMAYA" -> PAYMAYA
                else -> OTHERS
            }
        }
    }
}

/**
 * Enum for entry types in ledger
 */
enum class LedgerEntryType {
    CREDIT,  // Money coming in
    DEBIT    // Money going out
}

/**
 * Data class for a single ledger entry
 */

data class LedgerEntry(
    val id: String = UUID.randomUUID().toString(),
    val transactionNumber: Int,
    val transactionType: String,  // Cash In, Cash Out, Mobile Loading Service, etc.
    val entryType: LedgerEntryType,  // CREDIT or DEBIT
    val amount: Double,
    val ledgerSource: LedgerType,  // Which ledger this entry belongs to
    val notes: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val date: String = SimpleDateFormat("M/d/yyyy", Locale.US).format(Date()),
    val time: String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
)

/**
 * Data class for a ledger (Cash, GCash, PayMaya, Others)
 */
data class Ledger(
    val type: LedgerType,
    val entries: MutableList<LedgerEntry> = mutableListOf(),
    var balance: Double = 0.0,
    val customOrder: MutableList<String> = mutableListOf()  // Store entry IDs in custom order
) {
    /**
     * Add a credit entry (money coming in)
     */
    fun addCredit(
        transactionNumber: Int,
        transactionType: String,
        amount: Double,
        notes: String = ""
    ) {
        val entry = LedgerEntry(
            transactionNumber = transactionNumber,
            transactionType = transactionType,
            entryType = LedgerEntryType.CREDIT,
            amount = amount,
            ledgerSource = this.type,  // Add the ledger type
            notes = notes
        )
        entries.add(entry)
        balance += amount
    }

    /**
     * Add a debit entry (money going out)
     */
    fun addDebit(
        transactionNumber: Int,
        transactionType: String,
        amount: Double,
        notes: String = ""
    ) {
        val entry = LedgerEntry(
            transactionNumber = transactionNumber,
            transactionType = transactionType,
            entryType = LedgerEntryType.DEBIT,
            amount = amount,
            ledgerSource = this.type,  // Add the ledger type
            notes = notes
        )
        entries.add(entry)
        balance -= amount
    }

    /**
     * Get entries in custom order (if set) or by transaction number
     */
    fun getEntriesSorted(): List<LedgerEntry> {
        return if (customOrder.isNotEmpty()) {
            // Return entries in custom order
            val orderedEntries = mutableListOf<LedgerEntry>()
            customOrder.forEach { id ->
                entries.find { it.id == id }?.let { orderedEntries.add(it) }
            }
            // Add any entries not in custom order (new entries)
            entries.forEach { entry ->
                if (!orderedEntries.contains(entry)) {
                    orderedEntries.add(entry)
                }
            }
            orderedEntries
        } else {
            // Default sorting by transaction number ASCENDING (oldest first)
            entries.sortedBy { it.transactionNumber }
        }
    }

    /**
     * Update custom order based on current entry positions
     */
    fun updateCustomOrder(orderedEntries: List<LedgerEntry>) {
        customOrder.clear()
        orderedEntries.forEach { entry ->
            customOrder.add(entry.id)
        }
    }

    /**
     * Delete entries by transaction number
     */
    fun deleteByTransactionNumber(transactionNumber: Int) {
        val entriesToDelete = entries.filter { it.transactionNumber == transactionNumber }

        entriesToDelete.forEach { entry ->
            // Reverse the balance change
            if (entry.entryType == LedgerEntryType.CREDIT) {
                balance -= entry.amount
            } else {
                balance += entry.amount
            }

            // Remove from entries list
            entries.remove(entry)

            // Remove from custom order if present
            customOrder.remove(entry.id)
        }
    }

    /**
     * Check if transaction number exists in this ledger
     */
    fun hasTransaction(transactionNumber: Int): Boolean {
        return entries.any { it.transactionNumber == transactionNumber }
    }
}

/**
 * Manager class for all ledgers
 */
object LedgerManager {
    // All ledgers
    private val cashLedger = Ledger(LedgerType.CASH)
    private val gcashLedger = Ledger(LedgerType.GCASH)
    private val paymayaLedger = Ledger(LedgerType.PAYMAYA)
    private val othersLedger = Ledger(LedgerType.OTHERS)

    // Global transaction counter (shared across all transaction types)
    private var nextTransactionNumber = 1

    // Custom order for All Credits view
    private val allCreditsCustomOrder = mutableListOf<String>()

    /**
     * Get the next transaction number and increment counter
     */
    fun getNextTransactionNumber(): Int {
        return nextTransactionNumber++
    }

    /**
     * Get current transaction number without incrementing
     */
    fun getCurrentTransactionNumber(): Int {
        return nextTransactionNumber
    }

    /**
     * Get ledger by type
     */
    fun getLedger(type: LedgerType): Ledger {
        return when (type) {
            LedgerType.CASH -> cashLedger
            LedgerType.GCASH -> gcashLedger
            LedgerType.PAYMAYA -> paymayaLedger
            LedgerType.OTHERS -> othersLedger
        }
    }

    /**
     * Get ledger by string name
     */
    fun getLedgerByName(name: String): Ledger {
        val type = LedgerType.fromString(name)
        return getLedger(type)
    }

    /**
     * Add credit entry to a specific ledger
     */
    fun addCredit(
        ledgerType: LedgerType,
        transactionNumber: Int,
        transactionType: String,
        amount: Double,
        notes: String = ""
    ) {
        getLedger(ledgerType).addCredit(transactionNumber, transactionType, amount, notes)
    }

    /**
     * Add debit entry to a specific ledger
     */
    fun addDebit(
        ledgerType: LedgerType,
        transactionNumber: Int,
        transactionType: String,
        amount: Double,
        notes: String = ""
    ) {
        getLedger(ledgerType).addDebit(transactionNumber, transactionType, amount, notes)
    }


    /**
     * Get all ledgers
     */
    fun getAllLedgers(): Map<LedgerType, Ledger> {
        return mapOf(
            LedgerType.CASH to cashLedger,
            LedgerType.GCASH to gcashLedger,
            LedgerType.PAYMAYA to paymayaLedger,
            LedgerType.OTHERS to othersLedger
        )
    }

    /**
     * Get total balance across all ledgers
     */
    fun getTotalBalance(): Double {
        return cashLedger.balance + gcashLedger.balance +
                paymayaLedger.balance + othersLedger.balance
    }

    /**
     * Get all credit entries from all ledgers in custom order
     */
    fun getAllCreditsOrdered(): List<LedgerEntry> {
        // Collect all credit entries from all ledgers
        val allCreditEntries = mutableListOf<LedgerEntry>()
        val allLedgers = getAllLedgers()

        allLedgers.forEach { (_, ledger) ->
            val creditEntries = ledger.entries.filter { it.entryType == LedgerEntryType.CREDIT }
            allCreditEntries.addAll(creditEntries)
        }

        // Return in custom order if set
        return if (allCreditsCustomOrder.isNotEmpty()) {
            val orderedEntries = mutableListOf<LedgerEntry>()
            allCreditsCustomOrder.forEach { id ->
                allCreditEntries.find { it.id == id }?.let { orderedEntries.add(it) }
            }
            // Add any entries not in custom order (new entries)
            allCreditEntries.forEach { entry ->
                if (!orderedEntries.contains(entry)) {
                    orderedEntries.add(entry)
                }
            }
            orderedEntries
        } else {
            // Default sorting by transaction number ASCENDING (oldest first)
            allCreditEntries.sortedBy { it.transactionNumber }
        }
    }

    /**
     * Delete a transaction across all affected ledgers
     */
    fun deleteTransaction(transactionNumber: Int): Boolean {
        var deleted = false

        // Check and delete from all ledgers
        getAllLedgers().forEach { (type, ledger) ->
            if (ledger.hasTransaction(transactionNumber)) {
                ledger.deleteByTransactionNumber(transactionNumber)
                deleted = true
            }
        }

        // Also remove from All Credits custom order if present
        val entriesToRemove = getAllLedgers().values
            .flatMap { it.entries }
            .filter { it.transactionNumber == transactionNumber }
            .map { it.id }

        allCreditsCustomOrder.removeAll(entriesToRemove)

        return deleted
    }

    /**
     * Get transaction details for deletion confirmation
     */
    fun getTransactionDetails(transactionNumber: Int): List<LedgerEntry> {
        val entries = mutableListOf<LedgerEntry>()

        getAllLedgers().forEach { (_, ledger) ->
            entries.addAll(ledger.entries.filter { it.transactionNumber == transactionNumber })
        }

        return entries
    }

    /**
     * Update custom order for All Credits view
     */
    fun updateAllCreditsOrder(orderedEntries: List<LedgerEntry>) {
        allCreditsCustomOrder.clear()
        orderedEntries.forEach { entry ->
            allCreditsCustomOrder.add(entry.id)
        }
    }

    /**
     * Clear all ledgers (for testing or reset)
     */
    fun clearAll() {
        cashLedger.entries.clear()
        cashLedger.balance = 0.0
        cashLedger.customOrder.clear()

        gcashLedger.entries.clear()
        gcashLedger.balance = 0.0
        gcashLedger.customOrder.clear()

        paymayaLedger.entries.clear()
        paymayaLedger.balance = 0.0
        paymayaLedger.customOrder.clear()

        othersLedger.entries.clear()
        othersLedger.balance = 0.0
        othersLedger.customOrder.clear()

        allCreditsCustomOrder.clear()
        nextTransactionNumber = 1
    }
}