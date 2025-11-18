package com.techcity.techcitysuite

/**
 * Processes transactions and creates appropriate ledger entries
 * based on transaction type and payment methods
 */
object TransactionProcessor {

    /**
     * Process a transaction and create ledger entries
     *
     * @param transactionType Type of transaction (Cash In, Cash Out, etc.)
     * @param amount The transaction amount
     * @param customerPays The amount customer pays (including fees)
     * @param sourceOfFunds The source/destination of funds (GCash, PayMaya, etc.)
     * @param paidWith Optional payment method (only for Cash In, Mobile Loading, etc.)
     * @param isPaidWithChecked Whether the "Paid with" checkbox is checked
     * @param notes Transaction notes
     * @return Transaction number assigned to this transaction
     */
    fun processTransaction(
        transactionType: String,
        amount: Double,
        customerPays: Double,
        sourceOfFunds: String,
        paidWith: String? = null,
        isPaidWithChecked: Boolean = false,
        notes: String = ""
    ): Int {
        // Get next transaction number (shared across all transaction types)
        val transactionNumber = LedgerManager.getNextTransactionNumber()

        when (transactionType) {
            "Cash In" -> processCashIn(
                transactionNumber,
                amount,
                customerPays,
                sourceOfFunds,
                paidWith,
                isPaidWithChecked,
                notes
            )

            "Mobile Loading Service" -> processMobileLoading(
                transactionNumber,
                amount,
                customerPays,
                sourceOfFunds,
                paidWith,
                isPaidWithChecked,
                notes
            )

            "Skyro Payment" -> processSkyroPayment(
                transactionNumber,
                amount,
                customerPays,
                sourceOfFunds,
                paidWith,
                isPaidWithChecked,
                notes
            )

            "Home Credit Payment" -> processHomeCreditPayment(
                transactionNumber,
                amount,
                customerPays,
                sourceOfFunds,
                paidWith,
                isPaidWithChecked,
                notes
            )

            "Cash Out" -> processCashOut(
                transactionNumber,
                amount,
                customerPays,
                sourceOfFunds,
                notes
            )

            "Misc Payment" -> processMiscPayment(
                transactionNumber,
                amount,
                sourceOfFunds,
                notes
            )
        }

        return transactionNumber
    }

    /**
     * Process Cash In transaction
     * - Customer pays: Credit to Cash Ledger (or selected ledger if "Paid with" is checked)
     * - Transfer To: Debit to selected ledger (GCash, PayMaya, Others)
     */
    private fun processCashIn(
        transactionNumber: Int,
        amount: Double,
        customerPays: Double,
        transferTo: String,
        paidWith: String?,
        isPaidWithChecked: Boolean,
        notes: String
    ) {
        // Credit entry: Where the payment comes from
        val creditLedger = if (isPaidWithChecked && paidWith != null) {
            LedgerType.fromString(paidWith)
        } else {
            LedgerType.CASH
        }

        LedgerManager.addCredit(
            ledgerType = creditLedger,
            transactionNumber = transactionNumber,
            transactionType = "Cash In",
            amount = customerPays,
            notes = notes
        )

        // Debit entry: Where the funds are transferred to
        val debitLedger = LedgerType.fromString(transferTo)
        LedgerManager.addDebit(
            ledgerType = debitLedger,
            transactionNumber = transactionNumber,
            transactionType = "Cash In",
            amount = amount,
            notes = notes
        )
    }

    /**
     * Process Mobile Loading Service transaction
     * - Customer pays: Credit to Cash Ledger (or selected ledger if "Paid with" is checked)
     * - Load Source: Debit to selected ledger (GCash, Reloader SIM mapped to Others)
     */
    private fun processMobileLoading(
        transactionNumber: Int,
        amount: Double,
        customerPays: Double,
        loadSource: String,
        paidWith: String?,
        isPaidWithChecked: Boolean,
        notes: String
    ) {
        // Credit entry: Where the payment comes from
        val creditLedger = if (isPaidWithChecked && paidWith != null) {
            LedgerType.fromString(paidWith)
        } else {
            LedgerType.CASH
        }

        LedgerManager.addCredit(
            ledgerType = creditLedger,
            transactionNumber = transactionNumber,
            transactionType = "Mobile Loading Service",
            amount = customerPays,
            notes = notes
        )

        // Debit entry: Where the load comes from
        // "Reloader SIM" maps to OTHERS ledger
        val debitLedger = if (loadSource == "Reloader SIM") {
            LedgerType.OTHERS
        } else {
            LedgerType.fromString(loadSource)
        }

        LedgerManager.addDebit(
            ledgerType = debitLedger,
            transactionNumber = transactionNumber,
            transactionType = "Mobile Loading Service",
            amount = amount,
            notes = notes
        )
    }

    /**
     * Process Skyro Payment transaction
     * - Customer pays: Credit to Cash Ledger (or selected ledger if "Paid with" is checked)
     * - Payment Method: Debit to selected ledger (GCash, PayMaya, Others)
     */
    private fun processSkyroPayment(
        transactionNumber: Int,
        amount: Double,
        customerPays: Double,
        paymentMethod: String,
        paidWith: String?,
        isPaidWithChecked: Boolean,
        notes: String
    ) {
        // Credit entry: Where the payment comes from
        val creditLedger = if (isPaidWithChecked && paidWith != null) {
            LedgerType.fromString(paidWith)
        } else {
            LedgerType.CASH
        }

        LedgerManager.addCredit(
            ledgerType = creditLedger,
            transactionNumber = transactionNumber,
            transactionType = "Skyro Payment",
            amount = customerPays,
            notes = notes
        )

        // Debit entry: Where the payment goes
        val debitLedger = LedgerType.fromString(paymentMethod)
        LedgerManager.addDebit(
            ledgerType = debitLedger,
            transactionNumber = transactionNumber,
            transactionType = "Skyro Payment",
            amount = amount,
            notes = notes
        )
    }

    /**
     * Process Home Credit Payment transaction
     * - Customer pays: Credit to Cash Ledger (or selected ledger if "Paid with" is checked)
     * - Payment Method: Debit to selected ledger (GCash, PayMaya, Others)
     */
    private fun processHomeCreditPayment(
        transactionNumber: Int,
        amount: Double,
        customerPays: Double,
        paymentMethod: String,
        paidWith: String?,
        isPaidWithChecked: Boolean,
        notes: String
    ) {
        // Credit entry: Where the payment comes from
        val creditLedger = if (isPaidWithChecked && paidWith != null) {
            LedgerType.fromString(paidWith)
        } else {
            LedgerType.CASH
        }

        LedgerManager.addCredit(
            ledgerType = creditLedger,
            transactionNumber = transactionNumber,
            transactionType = "Home Credit Payment",
            amount = customerPays,
            notes = notes
        )

        // Debit entry: Where the payment goes
        val debitLedger = LedgerType.fromString(paymentMethod)
        LedgerManager.addDebit(
            ledgerType = debitLedger,
            transactionNumber = transactionNumber,
            transactionType = "Home Credit Payment",
            amount = amount,
            notes = notes
        )
    }

    /**
     * Process Cash Out transaction
     * - Amount: Debit from Cash Ledger
     * - Customer Pays: Credit to selected ledger based on Source of Funds
     */
    private fun processCashOut(
        transactionNumber: Int,
        amount: Double,
        customerPays: Double,
        sourceOfFunds: String,
        notes: String
    ) {
        // Debit entry: Money leaving Cash Ledger
        LedgerManager.addDebit(
            ledgerType = LedgerType.CASH,
            transactionNumber = transactionNumber,
            transactionType = "Cash Out",
            amount = amount,
            notes = notes
        )

        // Credit entry: Money coming from source
        val creditLedger = LedgerType.fromString(sourceOfFunds)
        LedgerManager.addCredit(
            ledgerType = creditLedger,
            transactionNumber = transactionNumber,
            transactionType = "Cash Out",
            amount = customerPays,
            notes = notes
        )
    }

    /**
     * Process Misc Payment transaction
     * - Amount: Credit to ledger based on payment method
     */
    private fun processMiscPayment(
        transactionNumber: Int,
        amount: Double,
        paymentMethod: String,
        notes: String
    ) {
        // Credit entry: Money received via payment method
        val creditLedger = LedgerType.fromString(paymentMethod)
        LedgerManager.addCredit(
            ledgerType = creditLedger,
            transactionNumber = transactionNumber,
            transactionType = "Misc Payment",
            amount = amount,
            notes = notes
        )
    }
}