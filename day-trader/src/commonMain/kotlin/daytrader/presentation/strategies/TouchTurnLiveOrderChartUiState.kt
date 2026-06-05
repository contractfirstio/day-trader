package daytrader.presentation.strategies

import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnPlannedBracket
import daytrader.domain.TouchTurnSessionContext
import daytrader.gateway.LiveQuote
import daytrader.gateway.WorkingOrder

enum class TouchTurnPriceChartContext {
    /** Streaming marks on the Opening 15-minute bar step while the bar is still forming. */
    OPENING_BAR_FORMING,
    /** Marks with bracket/order levels after orders are in play. */
    ORDERS_AND_POSITION
}

data class TouchTurnLiveOrderChartUiState(
    val symbol: String,
    val currencyCode: String,
    val priceHistory: List<Double>,
    val currentPrice: Double?,
    val levels: List<TouchTurnOrderLevelUi>,
    val context: TouchTurnPriceChartContext = TouchTurnPriceChartContext.ORDERS_AND_POSITION,
    /** Shown under the chart title when live bid/ask are required for paper fills. */
    val statusHint: String? = null,
    /** Bid/ask/last and distance to entry — rendered under the chart canvas. */
    val quoteStrip: TouchTurnQuoteStripUi? = null
)

object TouchTurnLiveOrderChartUiMapper {
    /** Keep streaming marks while brackets are working or entry was permitted. */
    fun shouldRecordPrices(session: TouchTurnSessionContext): Boolean =
        session.ordersPlacedForSession || session.entryOrdersPermitted == true

    fun build(
        symbol: String,
        currencyCode: String,
        priceHistory: List<Double>,
        currentPrice: Double?,
        openOrders: List<WorkingOrder>,
        plannedBracket: TouchTurnPlannedBracket?,
        bracketSetup: TouchTurnBracketSetup?,
        statusHint: String? = null,
        quote: LiveQuote? = null,
        closestApproach: TouchTurnClosestApproachUi? = null
    ): TouchTurnLiveOrderChartUiState? {
        val levels = TouchTurnLiveOrderLevels.chartLevels(
            openOrders = openOrders,
            plannedBracket = plannedBracket,
            bracketSetup = bracketSetup
        )
        val hasPrice = currentPrice != null && currentPrice > 0.0
        if (levels.isEmpty() && priceHistory.isEmpty() && !hasPrice) return null
        val quoteStrip = TouchTurnQuoteStripUiMapper.from(
            quote = quote,
            currencyCode = currencyCode,
            bracketSetup = bracketSetup,
            levels = levels,
            closestApproach = closestApproach
        )
        return TouchTurnLiveOrderChartUiState(
            symbol = symbol,
            currencyCode = currencyCode,
            priceHistory = priceHistory,
            currentPrice = currentPrice,
            levels = levels,
            context = TouchTurnPriceChartContext.ORDERS_AND_POSITION,
            statusHint = statusHint,
            quoteStrip = quoteStrip
        )
    }
}
