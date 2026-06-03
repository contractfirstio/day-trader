package daytrader.presentation.strategies

import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnPlannedBracket
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
    val statusHint: String? = null
)

object TouchTurnLiveOrderChartUiMapper {
    fun build(
        symbol: String,
        currencyCode: String,
        priceHistory: List<Double>,
        currentPrice: Double?,
        openOrders: List<WorkingOrder>,
        plannedBracket: TouchTurnPlannedBracket?,
        bracketSetup: TouchTurnBracketSetup?,
        statusHint: String? = null
    ): TouchTurnLiveOrderChartUiState? {
        val levels = TouchTurnLiveOrderLevels.chartLevels(
            openOrders = openOrders,
            plannedBracket = plannedBracket,
            bracketSetup = bracketSetup
        )
        val hasPrice = currentPrice != null && currentPrice > 0.0
        if (levels.isEmpty() && priceHistory.isEmpty() && !hasPrice) return null
        return TouchTurnLiveOrderChartUiState(
            symbol = symbol,
            currencyCode = currencyCode,
            priceHistory = priceHistory,
            currentPrice = currentPrice,
            levels = levels,
            context = TouchTurnPriceChartContext.ORDERS_AND_POSITION,
            statusHint = statusHint
        )
    }
}
