package daytrader.presentation.strategies

import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnSessionContext

/** Live IB marks while the first 15-minute RTH bar is still forming. */
object TouchTurnFormingBarPriceChartUiMapper {
    fun shouldRecordPrices(session: TouchTurnSessionContext): Boolean =
        session.status == TouchTurnCandleStatus.READY &&
            session.candle != null &&
            session.candleCloseStatus() == FirstCandleCloseStatus.FORMING

    fun build(
        deployment: StrategyDeployment,
        session: TouchTurnSessionContext,
        priceHistory: List<Double>,
        currentPrice: Double?
    ): TouchTurnLiveOrderChartUiState? {
        if (deployment.strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return null
        if (!shouldRecordPrices(session)) return null
        return TouchTurnLiveOrderChartUiState(
            symbol = deployment.symbol,
            currencyCode = session.currencyCode,
            priceHistory = priceHistory,
            currentPrice = currentPrice,
            levels = emptyList(),
            context = TouchTurnPriceChartContext.OPENING_BAR_FORMING
        )
    }
}
