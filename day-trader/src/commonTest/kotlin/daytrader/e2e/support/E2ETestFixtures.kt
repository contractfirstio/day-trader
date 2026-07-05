package daytrader.e2e.support

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.onSessionStarted
import daytrader.domain.beginTouchTurnSession
import daytrader.gateway.LiveQuote

/**
 * Thin aliases over [TouchTurnMarketFixtures] for legacy E2E imports.
 * New tests should use [TouchTurnMarketFixtures] and [TouchTurnMarketScenario] directly.
 */
object E2ETestFixtures {
    const val SYMBOL = TouchTurnMarketFixtures.SYMBOL
    const val SESSION_DATE = TouchTurnMarketFixtures.SESSION_DATE
    const val DEPLOYMENT_ID = "dep-e2e-1"
    const val DEPLOYMENT_ID_2 = "dep-e2e-2"
    const val ATR14 = TouchTurnMarketFixtures.ATR14
    const val VOLUME_SMA20 = TouchTurnMarketFixtures.VOLUME_SMA20
    const val BAR_CLOSE_EPOCH_MS = TouchTurnMarketFixtures.BAR_CLOSE_EPOCH_MS

    fun nonLiquidityOpeningBar() = TouchTurnMarketFixtures.nonLiquidityOpeningBar()

    fun redLiquidityOpeningBar() = TouchTurnMarketFixtures.redLiquidityOpeningBar()

    fun liquidityOpeningBar() = TouchTurnMarketFixtures.greenLiquidityOpeningBar()

    fun bootstrapContext(bar: daytrader.domain.OhlcBar = nonLiquidityOpeningBar()) =
        TouchTurnMarketFixtures.bootstrapContext(bar)

    fun stoppedDeployment(
        symbol: String = SYMBOL,
        maxDollars: Int = 500,
    ) = defaultStrategyDeployment(
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        symbol = symbol,
        maxDollars = maxDollars,
        status = DeploymentStatus.STOPPED
    ).copy(id = DEPLOYMENT_ID)

    fun runningDeployment(
        symbol: String = SYMBOL,
        maxDollars: Int = 500,
        sessionDate: String = SESSION_DATE
    ) = defaultStrategyDeployment(
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        symbol = symbol,
        maxDollars = maxDollars,
        status = DeploymentStatus.RUNNING
    )
        .copy(id = DEPLOYMENT_ID)
        .onSessionStarted(sessionDate)
        .beginTouchTurnSession(sessionDate)

    fun liveQuote(
        symbol: String = SYMBOL,
        bid: Double,
        ask: Double,
        last: Double = (bid + ask) / 2.0
    ): LiveQuote = TouchTurnMarketFixtures.liveQuote(
        symbol = symbol,
        bid = bid,
        ask = ask,
        last = last,
        quoteEpochMillis = System.currentTimeMillis()
    )
}
