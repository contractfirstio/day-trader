package daytrader.e2e.support

import daytrader.domain.DeploymentStatus
import daytrader.domain.OhlcBar
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnSignalContext
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.onSessionStarted
import daytrader.domain.beginTouchTurnSession
import daytrader.gateway.LiveQuote

object E2ETestFixtures {
    const val SYMBOL = "AAPL"
    const val SESSION_DATE = "2026-06-04"
    const val DEPLOYMENT_ID = "dep-e2e-1"
    const val DEPLOYMENT_ID_2 = "dep-e2e-2"
    const val ATR14 = 2.45
    const val VOLUME_SMA20 = 980_000.0

    /** Range 0.30 < ATR liquidity threshold 0.6125 → not a liquidity candle. */
    fun nonLiquidityOpeningBar(): OhlcBar = OhlcBar(
        open = 100.0,
        high = 100.30,
        low = 100.0,
        close = 100.15,
        time = "20260604  09:30:00",
        volume = 50_000.0
    )

    /** Red liquidity bar: range 1.0 >= threshold; close in lower band for long confirmation. */
    fun redLiquidityOpeningBar(): OhlcBar = OhlcBar(
        open = 101.0,
        high = 101.0,
        low = 100.0,
        close = 100.20,
        time = "20260604  09:30:00",
        volume = 800_000.0
    )

    fun liquidityOpeningBar(): OhlcBar = OhlcBar(
        open = 100.0,
        high = 101.0,
        low = 100.0,
        close = 100.85,
        time = "20260604  09:30:00",
        volume = 800_000.0
    )

    fun bootstrapContext(bar: OhlcBar = nonLiquidityOpeningBar()): TouchTurnSignalContext =
        TouchTurnSignalContext(
            firstCandle = bar,
            atr14 = ATR14,
            dailyAtr14 = ATR14,
            volumeSma20 = VOLUME_SMA20
        )

    /** After 2026-06-04 09:45:00 America/New_York first 15m bar close. */
    const val BAR_CLOSE_EPOCH_MS = 1_780_580_700_000L + 10_000L

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
    ): LiveQuote = LiveQuote(
        symbol = symbol,
        bid = bid,
        ask = ask,
        last = last,
        quoteEpochMillis = System.currentTimeMillis()
    )
}
