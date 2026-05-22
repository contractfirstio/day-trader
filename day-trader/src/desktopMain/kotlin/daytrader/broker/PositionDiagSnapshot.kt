package daytrader.broker

/**
 * Full position pricing / P&L pipeline snapshot (on by default; set DAY_TRADER_IB_REDACT_LOGS=true to hide).
 */
internal data class PositionDiagSnapshot(
    val trigger: String,
    val conid: Int,
    val symbol: String,
    val localSymbol: String,
    val tradingClass: String,
    val exchange: String,
    val primaryExch: String,
    val currency: String,
    val quantity: Int,
    /** Contract-level magnifier from details / UK default. */
    val priceMagnifierUsed: Int,
    val avgMagnifierUsed: Int,
    val marketMagnifierUsed: Int,
    /** Raw priceMagnifier from IB ContractDetails (0 if not yet received). */
    val contractDetailsMagnifier: Int,
    /** Inferred default from currency/exchange before contract details. */
    val defaultMagnifierInferred: Int,
    val positionAvgCostRaw: Double,
    val portfolioAvgCostRaw: Double?,
    val portfolioMarketRaw: Double?,
    val tickLastRaw: Double?,
    val bidRaw: Double?,
    val askRaw: Double?,
    val bidAskMidRaw: Double?,
    val priorCloseRaw: Double?,
    val historicalCloseRaw: Double?,
    val portfolioMarketDistinctFromAvg: Boolean,
    val needsHistoricalFallback: Boolean,
    val avgRawUsed: Double,
    val avgSource: String,
    val marketRawUsed: Double,
    val marketSource: String,
    val avgMajor: Double,
    val marketMajor: Double,
    val spreadRaw: Double,
    val spreadMajor: Double,
    /** P&L with stored magnifier (what the blotter uses). */
    val computedPnL: Double,
    val displayCurrency: String,
    val ibUnrealizedPnL: Double?,
    /** Sanity: (raw spread) × qty, no magnifier — wrong if prices are pence. */
    val pnlIfMagnifier1: Double,
    /** Sanity: (raw spread ÷ 100) × qty — correct for UK pence if magnifier should be 100. */
    val pnlIfMagnifier100: Double,
    /** Sanity: assume both raw prices are pence → GBP total. */
    val expectedGbpIfBothPence: Double,
    /** Values written to the blotter. */
    val blotterAvgPrice: Double,
    val blotterMarketPrice: Double,
    val blotterUnrealizedPnL: Double
)
