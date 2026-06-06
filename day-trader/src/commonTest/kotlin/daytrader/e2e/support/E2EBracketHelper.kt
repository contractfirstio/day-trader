package daytrader.e2e.support

import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnTradeSide

object E2EBracketHelper {
    fun liquidityPlan(
        symbol: String = E2ETestFixtures.SYMBOL,
        entry: Double = 100.0,
        stopLoss: Double = 99.0,
        takeProfit: Double = 101.0
    ): TouchTurnOrderPlan = TouchTurnOrderPlanner.buildOrderPlan(
        symbol = symbol,
        setup = TouchTurnBracketSetup(
            range = 2.0,
            rangeThreshold = 0.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.RED,
            side = TouchTurnTradeSide.LONG,
            entry = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit
        ),
        maxDollars = 500,
        currencyCode = "USD",
        openingBarClose = 100.85
    )!!
}
