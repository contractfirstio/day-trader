package daytrader.presentation.strategies

import daytrader.domain.StrategyDeployment
import daytrader.domain.isTouchTurn
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnSessionContext
import daytrader.gateway.LiveQuote

/** Live marks from data-ready through bar/liquidity/confirm until brackets are placed. */
object TouchTurnFormingBarPriceChartUiMapper {
    /** From data-ready until the engine commits bracket placement for the session. */
    fun shouldRecordPrices(session: TouchTurnSessionContext): Boolean =
        session.status == TouchTurnCandleStatus.READY &&
            session.openingBarTime != null &&
            session.milestones.dataReadyAt != null &&
            !session.ordersPlacedForSession

    fun build(
        deployment: StrategyDeployment,
        session: TouchTurnSessionContext,
        priceHistory: List<Double>,
        currentPrice: Double?,
        statusHint: String? = null,
        quote: LiveQuote? = null,
        closestApproach: TouchTurnClosestApproachUi? = null
    ): TouchTurnLiveOrderChartUiState? {
        if (!deployment.isTouchTurn) return null
        if (!shouldRecordPrices(session)) return null
        val levels = TouchTurnLiveOrderLevels.chartLevels(
            openOrders = emptyList(),
            plannedBracket = session.plannedBracket,
            bracketSetup = session.setup
        )
        val quoteStrip = TouchTurnQuoteStripUiMapper.from(
            quote = quote,
            currencyCode = session.currencyCode,
            bracketSetup = session.setup,
            levels = levels,
            closestApproach = closestApproach
        )
        return TouchTurnLiveOrderChartUiState(
            symbol = deployment.symbol,
            currencyCode = session.currencyCode,
            priceHistory = priceHistory,
            currentPrice = currentPrice,
            levels = levels,
            context = TouchTurnPriceChartContext.OPENING_BAR_FORMING,
            statusHint = statusHint,
            quoteStrip = quoteStrip
        )
    }
}
