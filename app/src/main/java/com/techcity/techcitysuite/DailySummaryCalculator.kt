package com.techcity.techcitysuite

/**
 * Shared daily-summary math.
 *
 * Every data class and function in this object is a verbatim copy of the corresponding
 * private member of EndOfDayReportActivity (only the `private` modifier is dropped). It is
 * used by the Overview module now; EndOfDayReportActivity still keeps its own copies and
 * can be switched to this object in a later task without any behaviour change.
 *
 * Each process* function expects the transactions of ONE store location (callers group
 * by branch first), exactly as the End of Day report calls them.
 */
object DailySummaryCalculator {

    data class SalesSummary(
        val totalSales: Double,
        val cashSales: TransactionBreakdown,
        val homeCreditSales: InstallmentBreakdown,
        val skyroSales: InstallmentBreakdown,
        val salmonSales: InstallmentBreakdown,
        val inHouseSales: InstallmentBreakdown
    )

    data class TransactionBreakdown(
        val count: Int,
        val amount: Double
    )

    data class InstallmentBreakdown(
        val count: Int,
        val amount: Double,
        val downpayment: Double,
        val balance: Double
    )

    data class CashFlowSummary(
        val cash: Double,
        val gcash: Double,
        val paymaya: Double,
        val bankTransfer: Double,
        val creditCard: Double,
        val others: Double,
        val hcReceivable: Double,
        val skyroReceivable: Double,
        val salmonReceivable: Double,
        val inHouseReceivable: Double,
        val brandZeroSubsidy: Double
    )

    data class ServiceCashFlowSummary(
        val cashInflow: Double,
        val cashOutflow: Double,
        val gcashInflow: Double,
        val gcashOutflow: Double,
        val paymayaInflow: Double,
        val paymayaOutflow: Double,
        val othersInflow: Double,
        val othersOutflow: Double
    )

    /**
     * Process device sales - uses finalPrice and nested payment objects
     */
    fun processDeviceSales(transactions: List<Map<String, Any?>>): SalesSummary {
        var cashCount = 0
        var cashAmount = 0.0

        var hcCount = 0
        var hcAmount = 0.0
        var hcDownpayment = 0.0
        var hcBalance = 0.0

        var skyroCount = 0
        var skyroAmount = 0.0
        var skyroDownpayment = 0.0
        var skyroBalance = 0.0

        var salmonCount = 0
        var salmonAmount = 0.0
        var salmonDownpayment = 0.0
        var salmonBalance = 0.0

        var inHouseCount = 0
        var inHouseAmount = 0.0
        var inHouseDownpayment = 0.0
        var inHouseBalance = 0.0

        for (data in transactions) {
            val transactionType = data["transactionType"] as? String ?: ""
            // Use finalPrice as the total sale amount
            val finalPrice = (data["finalPrice"] as? Number)?.toDouble() ?: 0.0

            when (transactionType) {
                AppConstants.TRANSACTION_TYPE_CASH -> {
                    cashCount++
                    cashAmount += finalPrice
                }
                AppConstants.TRANSACTION_TYPE_HOME_CREDIT -> {
                    // Get nested homeCreditPayment object
                    val hcPayment = data["homeCreditPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (hcPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (hcPayment["balance"] as? Number)?.toDouble() ?: 0.0

                    hcCount++
                    hcAmount += finalPrice
                    hcDownpayment += downpayment
                    hcBalance += balance
                }
                AppConstants.TRANSACTION_TYPE_SKYRO -> {
                    // Get nested skyroPayment object
                    val skyroPayment = data["skyroPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (skyroPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (skyroPayment["balance"] as? Number)?.toDouble() ?: 0.0

                    skyroCount++
                    skyroAmount += finalPrice
                    skyroDownpayment += downpayment
                    skyroBalance += balance
                }
                AppConstants.TRANSACTION_TYPE_SALMON -> {
                    // Get nested salmonPayment object
                    val salmonPayment = data["salmonPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (salmonPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (salmonPayment["balance"] as? Number)?.toDouble() ?: 0.0

                    salmonCount++
                    salmonAmount += finalPrice
                    salmonDownpayment += downpayment
                    salmonBalance += balance
                }
                AppConstants.TRANSACTION_TYPE_IN_HOUSE -> {
                    // Get nested inHouseInstallment object
                    val ihPayment = data["inHouseInstallment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (ihPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    // For In-House, balance is totalAmountDue (includes interest)
                    val balance = (ihPayment["totalAmountDue"] as? Number)?.toDouble()
                        ?: (ihPayment["balance"] as? Number)?.toDouble() ?: 0.0

                    inHouseCount++
                    inHouseAmount += finalPrice
                    inHouseDownpayment += downpayment
                    inHouseBalance += balance
                }
            }
        }

        val totalSales = cashAmount + hcAmount + skyroAmount + salmonAmount + inHouseAmount

        return SalesSummary(
            totalSales = totalSales,
            cashSales = TransactionBreakdown(cashCount, cashAmount),
            homeCreditSales = InstallmentBreakdown(hcCount, hcAmount, hcDownpayment, hcBalance),
            skyroSales = InstallmentBreakdown(skyroCount, skyroAmount, skyroDownpayment, skyroBalance),
            salmonSales = InstallmentBreakdown(salmonCount, salmonAmount, salmonDownpayment, salmonBalance),
            inHouseSales = InstallmentBreakdown(inHouseCount, inHouseAmount, inHouseDownpayment, inHouseBalance)
        )
    }

    /**
     * Process accessory sales - uses finalPrice and nested payment objects
     */
    fun processAccessorySales(transactions: List<Map<String, Any?>>): SalesSummary {
        var cashCount = 0
        var cashAmount = 0.0

        var hcCount = 0
        var hcAmount = 0.0
        var hcDownpayment = 0.0
        var hcBalance = 0.0

        var skyroCount = 0
        var skyroAmount = 0.0
        var skyroDownpayment = 0.0
        var skyroBalance = 0.0

        var salmonCount = 0
        var salmonAmount = 0.0
        var salmonDownpayment = 0.0
        var salmonBalance = 0.0

        var inHouseCount = 0
        var inHouseAmount = 0.0
        var inHouseDownpayment = 0.0
        var inHouseBalance = 0.0

        for (data in transactions) {
            val transactionType = data["transactionType"] as? String ?: ""
            val finalPrice = (data["finalPrice"] as? Number)?.toDouble() ?: 0.0

            when (transactionType) {
                AppConstants.TRANSACTION_TYPE_CASH -> {
                    cashCount++
                    cashAmount += finalPrice
                }
                AppConstants.TRANSACTION_TYPE_HOME_CREDIT -> {
                    val hcPayment = data["homeCreditPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (hcPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (hcPayment["balance"] as? Number)?.toDouble() ?: 0.0

                    hcCount++
                    hcAmount += finalPrice
                    hcDownpayment += downpayment
                    hcBalance += balance
                }
                AppConstants.TRANSACTION_TYPE_SKYRO -> {
                    val skyroPayment = data["skyroPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (skyroPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (skyroPayment["balance"] as? Number)?.toDouble() ?: 0.0

                    skyroCount++
                    skyroAmount += finalPrice
                    skyroDownpayment += downpayment
                    skyroBalance += balance
                }
                AppConstants.TRANSACTION_TYPE_SALMON -> {
                    val salmonPayment = data["salmonPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (salmonPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (salmonPayment["balance"] as? Number)?.toDouble() ?: 0.0

                    salmonCount++
                    salmonAmount += finalPrice
                    salmonDownpayment += downpayment
                    salmonBalance += balance
                }
                AppConstants.TRANSACTION_TYPE_IN_HOUSE -> {
                    val ihPayment = data["inHouseInstallment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (ihPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (ihPayment["totalAmountDue"] as? Number)?.toDouble()
                        ?: (ihPayment["balance"] as? Number)?.toDouble() ?: 0.0

                    inHouseCount++
                    inHouseAmount += finalPrice
                    inHouseDownpayment += downpayment
                    inHouseBalance += balance
                }
            }
        }

        val totalSales = cashAmount + hcAmount + skyroAmount + salmonAmount + inHouseAmount

        return SalesSummary(
            totalSales = totalSales,
            cashSales = TransactionBreakdown(cashCount, cashAmount),
            homeCreditSales = InstallmentBreakdown(hcCount, hcAmount, hcDownpayment, hcBalance),
            skyroSales = InstallmentBreakdown(skyroCount, skyroAmount, skyroDownpayment, skyroBalance),
            salmonSales = InstallmentBreakdown(salmonCount, salmonAmount, salmonDownpayment, salmonBalance),
            inHouseSales = InstallmentBreakdown(inHouseCount, inHouseAmount, inHouseDownpayment, inHouseBalance)
        )
    }

    /**
     * Process device cash flow - uses nested payment objects for payment sources
     */
    fun processDeviceCashFlow(transactions: List<Map<String, Any?>>): CashFlowSummary {
        var cash = 0.0
        var gcash = 0.0
        var paymaya = 0.0
        var bankTransfer = 0.0
        var creditCard = 0.0
        var others = 0.0

        var hcReceivable = 0.0
        var skyroReceivable = 0.0
        var salmonReceivable = 0.0
        var inHouseReceivable = 0.0
        var brandZeroSubsidy = 0.0

        for (data in transactions) {
            val transactionType = data["transactionType"] as? String ?: ""
            val finalPrice = (data["finalPrice"] as? Number)?.toDouble() ?: 0.0

            when (transactionType) {
                AppConstants.TRANSACTION_TYPE_CASH -> {
                    // Get payment source from nested cashPayment object
                    val cashPayment = data["cashPayment"] as? Map<String, Any?> ?: emptyMap()
                    val paymentSource = cashPayment["paymentSource"] as? String ?: "Cash"

                    when (paymentSource) {
                        "Cash" -> cash += finalPrice
                        "GCash" -> gcash += finalPrice
                        "PayMaya" -> paymaya += finalPrice
                        "Bank Transfer" -> bankTransfer += finalPrice
                        "Credit Card" -> creditCard += finalPrice
                        "Others" -> others += finalPrice
                        else -> cash += finalPrice
                    }
                }
                AppConstants.TRANSACTION_TYPE_HOME_CREDIT -> {
                    val hcPayment = data["homeCreditPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (hcPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (hcPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val dpSource = hcPayment["downpaymentSource"] as? String ?: "Cash"
                    val subsidy = (hcPayment["brandZeroSubsidy"] as? Number)?.toDouble() ?: 0.0

                    // Downpayment goes to payment source
                    if (downpayment > 0) {
                        when (dpSource) {
                            "Cash" -> cash += downpayment
                            "GCash" -> gcash += downpayment
                            "PayMaya" -> paymaya += downpayment
                            "Bank Transfer" -> bankTransfer += downpayment
                            "Credit Card" -> creditCard += downpayment
                            "Others" -> others += downpayment
                            else -> cash += downpayment
                        }
                    }
                    hcReceivable += balance
                    brandZeroSubsidy += subsidy
                }
                AppConstants.TRANSACTION_TYPE_SKYRO -> {
                    val skyroPayment = data["skyroPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (skyroPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (skyroPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val dpSource = skyroPayment["downpaymentSource"] as? String ?: "Cash"
                    val subsidy = (skyroPayment["brandZeroSubsidy"] as? Number)?.toDouble() ?: 0.0

                    if (downpayment > 0) {
                        when (dpSource) {
                            "Cash" -> cash += downpayment
                            "GCash" -> gcash += downpayment
                            "PayMaya" -> paymaya += downpayment
                            "Bank Transfer" -> bankTransfer += downpayment
                            "Credit Card" -> creditCard += downpayment
                            "Others" -> others += downpayment
                            else -> cash += downpayment
                        }
                    }
                    skyroReceivable += balance
                    brandZeroSubsidy += subsidy
                }
                AppConstants.TRANSACTION_TYPE_SALMON -> {
                    val salmonPayment = data["salmonPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (salmonPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (salmonPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val dpSource = salmonPayment["downpaymentSource"] as? String ?: "Cash"
                    val subsidy = (salmonPayment["brandZeroSubsidy"] as? Number)?.toDouble() ?: 0.0

                    if (downpayment > 0) {
                        when (dpSource) {
                            "Cash" -> cash += downpayment
                            "GCash" -> gcash += downpayment
                            "PayMaya" -> paymaya += downpayment
                            "Bank Transfer" -> bankTransfer += downpayment
                            "Credit Card" -> creditCard += downpayment
                            "Others" -> others += downpayment
                            else -> cash += downpayment
                        }
                    }
                    salmonReceivable += balance
                    brandZeroSubsidy += subsidy
                }
                AppConstants.TRANSACTION_TYPE_IN_HOUSE -> {
                    val ihPayment = data["inHouseInstallment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (ihPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val totalAmountDue = (ihPayment["totalAmountDue"] as? Number)?.toDouble()
                        ?: (ihPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val dpSource = ihPayment["downpaymentSource"] as? String ?: "Cash"

                    if (downpayment > 0) {
                        when (dpSource) {
                            "Cash" -> cash += downpayment
                            "GCash" -> gcash += downpayment
                            "PayMaya" -> paymaya += downpayment
                            "Bank Transfer" -> bankTransfer += downpayment
                            "Credit Card" -> creditCard += downpayment
                            "Others" -> others += downpayment
                            else -> cash += downpayment
                        }
                    }
                    inHouseReceivable += totalAmountDue
                }
            }
        }

        return CashFlowSummary(
            cash = cash,
            gcash = gcash,
            paymaya = paymaya,
            bankTransfer = bankTransfer,
            creditCard = creditCard,
            others = others,
            hcReceivable = hcReceivable,
            skyroReceivable = skyroReceivable,
            salmonReceivable = salmonReceivable,
            inHouseReceivable = inHouseReceivable,
            brandZeroSubsidy = brandZeroSubsidy
        )
    }

    /**
     * Process accessory cash flow - uses nested payment objects
     */
    fun processAccessoryCashFlow(transactions: List<Map<String, Any?>>): CashFlowSummary {
        var cash = 0.0
        var gcash = 0.0
        var paymaya = 0.0
        var bankTransfer = 0.0
        var creditCard = 0.0
        var others = 0.0

        var hcReceivable = 0.0
        var skyroReceivable = 0.0
        var salmonReceivable = 0.0
        var inHouseReceivable = 0.0
        var brandZeroSubsidy = 0.0

        for (data in transactions) {
            val transactionType = data["transactionType"] as? String ?: ""
            val finalPrice = (data["finalPrice"] as? Number)?.toDouble() ?: 0.0

            when (transactionType) {
                AppConstants.TRANSACTION_TYPE_CASH -> {
                    val cashPayment = data["cashPayment"] as? Map<String, Any?> ?: emptyMap()
                    val paymentSource = cashPayment["paymentSource"] as? String ?: "Cash"

                    when (paymentSource) {
                        "Cash" -> cash += finalPrice
                        "GCash" -> gcash += finalPrice
                        "PayMaya" -> paymaya += finalPrice
                        "Bank Transfer" -> bankTransfer += finalPrice
                        "Credit Card" -> creditCard += finalPrice
                        "Others" -> others += finalPrice
                        else -> cash += finalPrice
                    }
                }
                AppConstants.TRANSACTION_TYPE_HOME_CREDIT -> {
                    val hcPayment = data["homeCreditPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (hcPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (hcPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val dpSource = hcPayment["downpaymentSource"] as? String ?: "Cash"
                    val subsidy = (hcPayment["brandZeroSubsidy"] as? Number)?.toDouble() ?: 0.0

                    if (downpayment > 0) {
                        when (dpSource) {
                            "Cash" -> cash += downpayment
                            "GCash" -> gcash += downpayment
                            "PayMaya" -> paymaya += downpayment
                            "Bank Transfer" -> bankTransfer += downpayment
                            "Credit Card" -> creditCard += downpayment
                            "Others" -> others += downpayment
                            else -> cash += downpayment
                        }
                    }
                    hcReceivable += balance
                    brandZeroSubsidy += subsidy
                }
                AppConstants.TRANSACTION_TYPE_SKYRO -> {
                    val skyroPayment = data["skyroPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (skyroPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (skyroPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val dpSource = skyroPayment["downpaymentSource"] as? String ?: "Cash"
                    val subsidy = (skyroPayment["brandZeroSubsidy"] as? Number)?.toDouble() ?: 0.0

                    if (downpayment > 0) {
                        when (dpSource) {
                            "Cash" -> cash += downpayment
                            "GCash" -> gcash += downpayment
                            "PayMaya" -> paymaya += downpayment
                            "Bank Transfer" -> bankTransfer += downpayment
                            "Credit Card" -> creditCard += downpayment
                            "Others" -> others += downpayment
                            else -> cash += downpayment
                        }
                    }
                    skyroReceivable += balance
                    brandZeroSubsidy += subsidy
                }
                AppConstants.TRANSACTION_TYPE_SALMON -> {
                    val salmonPayment = data["salmonPayment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (salmonPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val balance = (salmonPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val dpSource = salmonPayment["downpaymentSource"] as? String ?: "Cash"
                    val subsidy = (salmonPayment["brandZeroSubsidy"] as? Number)?.toDouble() ?: 0.0

                    if (downpayment > 0) {
                        when (dpSource) {
                            "Cash" -> cash += downpayment
                            "GCash" -> gcash += downpayment
                            "PayMaya" -> paymaya += downpayment
                            "Bank Transfer" -> bankTransfer += downpayment
                            "Credit Card" -> creditCard += downpayment
                            "Others" -> others += downpayment
                            else -> cash += downpayment
                        }
                    }
                    salmonReceivable += balance
                    brandZeroSubsidy += subsidy
                }
                AppConstants.TRANSACTION_TYPE_IN_HOUSE -> {
                    val ihPayment = data["inHouseInstallment"] as? Map<String, Any?> ?: emptyMap()
                    val downpayment = (ihPayment["downpaymentAmount"] as? Number)?.toDouble() ?: 0.0
                    val totalAmountDue = (ihPayment["totalAmountDue"] as? Number)?.toDouble()
                        ?: (ihPayment["balance"] as? Number)?.toDouble() ?: 0.0
                    val dpSource = ihPayment["downpaymentSource"] as? String ?: "Cash"

                    if (downpayment > 0) {
                        when (dpSource) {
                            "Cash" -> cash += downpayment
                            "GCash" -> gcash += downpayment
                            "PayMaya" -> paymaya += downpayment
                            "Bank Transfer" -> bankTransfer += downpayment
                            "Credit Card" -> creditCard += downpayment
                            "Others" -> others += downpayment
                            else -> cash += downpayment
                        }
                    }
                    inHouseReceivable += totalAmountDue
                }
            }
        }

        return CashFlowSummary(
            cash = cash,
            gcash = gcash,
            paymaya = paymaya,
            bankTransfer = bankTransfer,
            creditCard = creditCard,
            others = others,
            hcReceivable = hcReceivable,
            skyroReceivable = skyroReceivable,
            salmonReceivable = salmonReceivable,
            inHouseReceivable = inHouseReceivable,
            brandZeroSubsidy = brandZeroSubsidy
        )
    }

    fun processServiceCashFlow(transactions: List<Map<String, Any?>>): ServiceCashFlowSummary {
        var cashIn = 0.0
        var cashOut = 0.0
        var gcashIn = 0.0
        var gcashOut = 0.0
        var paymayaIn = 0.0
        var paymayaOut = 0.0
        var othersIn = 0.0
        var othersOut = 0.0

        for (data in transactions) {
            val creditLedger = data["creditLedgerType"] as? String ?: ""
            val creditAmount = (data["creditAmount"] as? Number)?.toDouble() ?: 0.0
            val debitLedger = data["debitLedgerType"] as? String ?: ""
            val debitAmount = (data["debitAmount"] as? Number)?.toDouble() ?: 0.0

            when (creditLedger) {
                "Cash" -> cashIn += creditAmount
                "GCash" -> gcashIn += creditAmount
                "PayMaya" -> paymayaIn += creditAmount
                "Others" -> othersIn += creditAmount
            }

            when (debitLedger) {
                "Cash" -> cashOut += debitAmount
                "GCash" -> gcashOut += debitAmount
                "PayMaya" -> paymayaOut += debitAmount
                "Others" -> othersOut += debitAmount
            }
        }

        return ServiceCashFlowSummary(
            cashInflow = cashIn,
            cashOutflow = cashOut,
            gcashInflow = gcashIn,
            gcashOutflow = gcashOut,
            paymayaInflow = paymayaIn,
            paymayaOutflow = paymayaOut,
            othersInflow = othersIn,
            othersOutflow = othersOut
        )
    }

    /**
     * Parse SalesSummary from saved Firebase data
     */
    fun parseSalesSummary(data: Map<*, *>?): SalesSummary {
        if (data == null) return SalesSummary(
            totalSales = 0.0,
            cashSales = TransactionBreakdown(0, 0.0),
            homeCreditSales = InstallmentBreakdown(0, 0.0, 0.0, 0.0),
            skyroSales = InstallmentBreakdown(0, 0.0, 0.0, 0.0),
            salmonSales = InstallmentBreakdown(0, 0.0, 0.0, 0.0),
            inHouseSales = InstallmentBreakdown(0, 0.0, 0.0, 0.0)
        )

        val cashData = data["cash"] as? Map<*, *>
        val hcData = data["homeCredit"] as? Map<*, *>
        val skyroData = data["skyro"] as? Map<*, *>
        val salmonData = data["salmon"] as? Map<*, *>
        val inHouseData = data["inHouse"] as? Map<*, *>

        return SalesSummary(
            totalSales = (data["totalSales"] as? Number)?.toDouble() ?: 0.0,
            cashSales = TransactionBreakdown(
                count = (cashData?.get("count") as? Number)?.toInt() ?: 0,
                amount = (cashData?.get("amount") as? Number)?.toDouble() ?: 0.0
            ),
            homeCreditSales = InstallmentBreakdown(
                count = (hcData?.get("count") as? Number)?.toInt() ?: 0,
                amount = (hcData?.get("amount") as? Number)?.toDouble() ?: 0.0,
                downpayment = (hcData?.get("downpayment") as? Number)?.toDouble() ?: 0.0,
                balance = (hcData?.get("balance") as? Number)?.toDouble() ?: 0.0
            ),
            skyroSales = InstallmentBreakdown(
                count = (skyroData?.get("count") as? Number)?.toInt() ?: 0,
                amount = (skyroData?.get("amount") as? Number)?.toDouble() ?: 0.0,
                downpayment = (skyroData?.get("downpayment") as? Number)?.toDouble() ?: 0.0,
                balance = (skyroData?.get("balance") as? Number)?.toDouble() ?: 0.0
            ),
            salmonSales = InstallmentBreakdown(
                count = (salmonData?.get("count") as? Number)?.toInt() ?: 0,
                amount = (salmonData?.get("amount") as? Number)?.toDouble() ?: 0.0,
                downpayment = (salmonData?.get("downpayment") as? Number)?.toDouble() ?: 0.0,
                balance = (salmonData?.get("balance") as? Number)?.toDouble() ?: 0.0
            ),
            inHouseSales = InstallmentBreakdown(
                count = (inHouseData?.get("count") as? Number)?.toInt() ?: 0,
                amount = (inHouseData?.get("amount") as? Number)?.toDouble() ?: 0.0,
                downpayment = (inHouseData?.get("downpayment") as? Number)?.toDouble() ?: 0.0,
                balance = (inHouseData?.get("balance") as? Number)?.toDouble() ?: 0.0
            )
        )
    }

    /**
     * Parse CashFlowSummary from saved Firebase data
     */
    fun parseCashFlowSummary(data: Map<*, *>?): CashFlowSummary {
        if (data == null) return CashFlowSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        return CashFlowSummary(
            cash = (data["cash"] as? Number)?.toDouble() ?: 0.0,
            gcash = (data["gcash"] as? Number)?.toDouble() ?: 0.0,
            paymaya = (data["paymaya"] as? Number)?.toDouble() ?: 0.0,
            bankTransfer = (data["bankTransfer"] as? Number)?.toDouble() ?: 0.0,
            creditCard = (data["creditCard"] as? Number)?.toDouble() ?: 0.0,
            others = (data["others"] as? Number)?.toDouble() ?: 0.0,
            hcReceivable = (data["hcReceivable"] as? Number)?.toDouble() ?: 0.0,
            skyroReceivable = (data["skyroReceivable"] as? Number)?.toDouble() ?: 0.0,
            salmonReceivable = (data["salmonReceivable"] as? Number)?.toDouble() ?: 0.0,
            inHouseReceivable = (data["inHouseReceivable"] as? Number)?.toDouble() ?: 0.0,
            brandZeroSubsidy = (data["brandZeroSubsidy"] as? Number)?.toDouble() ?: 0.0
        )
    }

    /**
     * Parse ServiceCashFlowSummary from saved Firebase data
     */
    fun parseServiceCashFlowSummary(data: Map<*, *>?): ServiceCashFlowSummary {
        if (data == null) return ServiceCashFlowSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        return ServiceCashFlowSummary(
            cashInflow = (data["cashInflow"] as? Number)?.toDouble() ?: 0.0,
            cashOutflow = (data["cashOutflow"] as? Number)?.toDouble() ?: 0.0,
            gcashInflow = (data["gcashInflow"] as? Number)?.toDouble() ?: 0.0,
            gcashOutflow = (data["gcashOutflow"] as? Number)?.toDouble() ?: 0.0,
            paymayaInflow = (data["paymayaInflow"] as? Number)?.toDouble() ?: 0.0,
            paymayaOutflow = (data["paymayaOutflow"] as? Number)?.toDouble() ?: 0.0,
            othersInflow = (data["othersInflow"] as? Number)?.toDouble() ?: 0.0,
            othersOutflow = (data["othersOutflow"] as? Number)?.toDouble() ?: 0.0
        )
    }
}
