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
    var balance: Double = 0.0
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
     * Get all entries sorted by transaction number
     */
    fun getEntriesSorted(): List<LedgerEntry> {
        return entries.sortedByDescending { it.transactionNumber }
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
     * Clear all ledgers (for testing or reset)
     */
    fun clearAll() {
        cashLedger.entries.clear()
        cashLedger.balance = 0.0
        gcashLedger.entries.clear()
        gcashLedger.balance = 0.0
        paymayaLedger.entries.clear()
        paymayaLedger.balance = 0.0
        othersLedger.entries.clear()
        othersLedger.balance = 0.0
        nextTransactionNumber = 1
    }
}