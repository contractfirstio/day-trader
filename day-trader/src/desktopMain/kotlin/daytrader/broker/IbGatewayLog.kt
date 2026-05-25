package daytrader.broker

import com.ib.client.Contract

/**
 * IB Gateway console logging is **off by default** so Touch Turn order logs stay readable.
 * Set DAY_TRADER_IB_LOGS=true to enable connection, positions, ticks, and diagnostics.
 *
 * DAY_TRADER_IB_DEBUG=true — stack traces on errors (requires IB logs enabled).
 * DAY_TRADER_IB_REDACT_LOGS=true — shorter redacted messages when IB logs are enabled.
 */
internal object IbGatewayLog {
    private val loggingEnabled: Boolean =
        System.getenv("DAY_TRADER_IB_LOGS")?.equals("true", ignoreCase = true) == true

    private val debugEnabled: Boolean =
        System.getenv("DAY_TRADER_IB_DEBUG")?.equals("true", ignoreCase = true) == true

    private val redactLogs: Boolean =
        System.getenv("DAY_TRADER_IB_REDACT_LOGS")?.equals("true", ignoreCase = true) == true

    fun isPositionDiagEnabled(): Boolean = loggingEnabled && !redactLogs

    fun info(message: String) {
        emit("[IB] $message")
    }

    fun debug(message: String) {
        if (debugEnabled) emit("[IB] DEBUG $message")
    }

    fun apiError(reqId: Int, errorCode: Int, errorMsg: String? = null) {
        if (!loggingEnabled) return
        if (!errorMsg.isNullOrBlank() && !redactLogs) {
            emit("[IB] API error reqId=$reqId code=$errorCode msg=$errorMsg")
        } else {
            emit("[IB] API error reqId=$reqId code=$errorCode")
        }
    }

    fun connected(endpoint: String, clientId: Int) {
        info("Connected endpoint=$endpoint clientId=$clientId")
    }

    fun connecting(endpoint: String, clientId: Int) {
        info("Connecting endpoint=$endpoint clientId=$clientId")
    }

    fun disconnected() {
        info("Disconnected")
    }

    fun connectionClosed() {
        info("Connection closed")
    }

    fun nextValidId(orderId: Int) {
        info("Session ready nextOrderId=$orderId")
    }

    fun requestingPositions() {
        info("Requesting positions")
    }

    fun requestingOpenOrders() {
        info("Requesting open orders")
    }

    fun openOrdersLoadComplete(count: Int) {
        info("Open orders load complete count=$count")
    }

    fun requestingExecutions(reqId: Int) {
        info("Requesting executions reqId=$reqId")
    }

    fun executionsLoadComplete(count: Int) {
        info("Executions load complete count=$count")
    }

    fun positionsLoadComplete(openCount: Int) {
        info("Positions load complete openCount=$openCount")
    }

    fun positionApplied(contract: Contract, quantity: Int, avgCostRaw: Double, magnifier: Int) {
        if (redactLogs) {
            info("Position applied ${contractTag(contract)} magnifier=$magnifier")
        } else {
            info(
                "Position applied symbol=${contract.symbol()} qty=$quantity " +
                    "avgCostRaw=${fmt(avgCostRaw)} magnifier=$magnifier ${contractTag(contract)}"
            )
        }
    }

    fun positionRemoved(contract: Contract) {
        info("Position closed ${contractTag(contract)}")
    }

    fun portfolioUpdateSkipped(contract: Contract) {
        debug("Portfolio update skipped (no open position) ${contractTag(contract)}")
    }

    fun portfolioUpdateApplied(
        contract: Contract,
        marketPrice: Double,
        averageCost: Double,
        unrealizedPnL: Double
    ) {
        if (redactLogs) {
            info("Portfolio update ${contractTag(contract)}")
        } else {
            info(
                "Portfolio update symbol=${contract.symbol()} market=${fmt(marketPrice)} " +
                    "avgCost=${fmt(averageCost)} unrealizedPnL=${fmt(unrealizedPnL)} ${contractTag(contract)}"
            )
        }
    }

    fun accountDownloadEnd() {
        info("Account download complete")
    }

    fun marketDataSnapshotComplete(reqId: Int) {
        debug("Market data snapshot complete reqId=$reqId")
    }

    fun contractDetailsApplied(
        reqId: Int,
        contract: Contract,
        ibMagnifier: Int,
        resolvedMagnifier: Int
    ) {
        if (redactLogs) {
            debug("Contract details applied reqId=$reqId magnifier=$resolvedMagnifier")
        } else {
            info(
                "Contract details reqId=$reqId symbol=${contract.symbol()} " +
                    "ibMagnifier=$ibMagnifier resolvedMagnifier=$resolvedMagnifier ${contractTag(contract)}"
            )
        }
    }

    fun historicalCloseApplied(contract: Contract, close: Double) {
        if (redactLogs) {
            debug("Historical close applied ${contractTag(contract)}")
        } else {
            info("Historical close symbol=${contract.symbol()} close=${fmt(close)} ${contractTag(contract)}")
        }
    }

    fun tickPrice(key: String, field: Int, price: Double) {
        if (redactLogs) return
        info("Tick key=$key field=$field price=${fmt(price)}")
    }

    fun positionDiag(snapshot: PositionDiagSnapshot) {
        if (!isPositionDiagEnabled()) return
        val s = snapshot
        emit(
            """
            |[IB] POSITION_DIAG trigger=${s.trigger}
            |  contract conid=${s.conid} symbol=${s.symbol} local=${s.localSymbol} tradingClass=${s.tradingClass}
            |           exch=${s.exchange} primary=${s.primaryExch} ccy=${s.currency}
            |  quantity=${s.quantity}
            |  magnifier contract=${s.priceMagnifierUsed} contractDetails=${s.contractDetailsMagnifier} defaultInferred=${s.defaultMagnifierInferred}
            |  magnifier applied avg=${s.avgMagnifierUsed} market=${s.marketMagnifierUsed}
            |  --- raw inputs from IB ---
            |  position.avgCost=${fmt(s.positionAvgCostRaw)}
            |  portfolio.avgCost=${fmtNullable(s.portfolioAvgCostRaw)} portfolio.market=${fmtNullable(s.portfolioMarketRaw)} portfolio.market!=avg=${s.portfolioMarketDistinctFromAvg}
            |  tick.last=${fmtNullable(s.tickLastRaw)} bid=${fmtNullable(s.bidRaw)} ask=${fmtNullable(s.askRaw)} mid=${fmtNullable(s.bidAskMidRaw)}
            |  priorClose=${fmtNullable(s.priorCloseRaw)} historical.close=${fmtNullable(s.historicalCloseRaw)} needsHistorical=${s.needsHistoricalFallback}
            |  --- selected for P&L ---
            |  avg  source=${s.avgSource} raw=${fmt(s.avgRawUsed)} -> major=${fmt(s.avgMajor)}  (${majorHint(s.avgRawUsed, s.avgMagnifierUsed)})
            |  mkt  source=${s.marketSource} raw=${fmt(s.marketRawUsed)} -> major=${fmt(s.marketMajor)}  (${majorHint(s.marketRawUsed, s.marketMagnifierUsed)})
            |  spread raw=${fmt(s.spreadRaw)} major=${fmt(s.spreadMajor)}
            |  --- P&L sanity (UK: expect ~4.4p/share x qty / 100 = GBP) ---
            |  computed blotter P&L=${fmt(s.computedPnL)} ${s.displayCurrency}  ibPortfolioPnL=${fmtNullable(s.ibUnrealizedPnL)}
            |  if magnifier=1:  ${fmt(s.pnlIfMagnifier1)} ${s.displayCurrency}  (pence treated as pounds?)
            |  if magnifier=100: ${fmt(s.pnlIfMagnifier100)} ${s.displayCurrency}  (pence -> pounds)
            |  if both pence raw: ${fmt(s.expectedGbpIfBothPence)} GBP  (= (mkt-avg)/100 * qty)
            |  --- blotter output ---
            |  blotter avgPrice=${fmt(s.blotterAvgPrice)} marketPrice=${fmt(s.blotterMarketPrice)} unrealizedPnL=${fmt(s.blotterUnrealizedPnL)} ${s.displayCurrency}
            """.trimMargin()
        )
    }

    fun callbackFailure(context: String, e: Exception) {
        if (!loggingEnabled) return
        emit("[IB] Callback failed context=$context error=${e.javaClass.simpleName}")
        if (debugEnabled || !redactLogs) e.printStackTrace()
    }

    fun pacerFailure(e: Exception) {
        if (!loggingEnabled) return
        emit("[IB] Request pacer failed error=${e.javaClass.simpleName}")
        if (debugEnabled || !redactLogs) e.printStackTrace()
    }

    private fun emit(message: String) {
        if (!loggingEnabled) return
        println(message)
    }

    private fun majorHint(raw: Double, magnifier: Int): String =
        if (magnifier > 1) "raw/$magnifier=${fmt(raw / magnifier)}" else "raw unchanged"

    private fun contractTag(contract: Contract): String {
        val parts = mutableListOf<String>()
        val conid = contract.conid()
        if (conid > 0) parts.add("conid=$conid")
        val secType = contract.getSecType()
        if (!secType.isNullOrBlank()) parts.add("secType=$secType")
        val exchange = contract.exchange()
        if (!exchange.isNullOrBlank()) parts.add("exch=$exchange")
        val primary = contract.primaryExch()
        if (!primary.isNullOrBlank()) parts.add("primary=$primary")
        val currency = contract.currency()
        if (!currency.isNullOrBlank()) parts.add("ccy=$currency")
        return if (parts.isEmpty()) "contract=unknown" else parts.joinToString(" ")
    }

    private fun fmt(value: Double): String = String.format("%.4f", value)

    private fun fmtNullable(value: Double?): String =
        if (value == null) "null" else fmt(value)
}
