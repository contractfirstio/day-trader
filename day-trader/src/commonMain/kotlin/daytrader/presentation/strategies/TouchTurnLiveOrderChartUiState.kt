package daytrader.presentation.strategies

import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnPlannedBracket
import daytrader.gateway.WorkingOrder

data class TouchTurnLiveOrderChartUiState(
    val symbol: String,
    val currencyCode: String,
    val priceHistory: List<Double>,
    val currentPrice: Double?,
    val levels: List<TouchTurnOrderLevelUi>
)

object TouchTurnLiveOrderChartUiMapper {
    fun build(
        symbol: String,
        currencyCode: String,
        priceHistory: List<Double>,
        currentPrice: Double?,
        openOrders: List<WorkingOrder>,
        plannedBracket: TouchTurnPlannedBracket?,
        bracketSetup: TouchTurnBracketSetup?
    ): TouchTurnLiveOrderChartUiState? {
        val levels = TouchTurnLiveOrderLevels.fromWorkingOrders(
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
            levels = levels
        )
    }
}
