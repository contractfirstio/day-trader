package daytrader.broker.emulator

import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnTradeSide

/** Fixed OHLC templates for [EmulatorTouchTurnScenario] (scaled from instrument reference price). */
internal object EmulatorStaticTouchTurnBars {
    /** Dynamic emulator bars (~1.8% of ref). */
    private const val LIQUID_INTRADAY_RANGE_PCT = 0.018
    /** Static scenario bars — wide range so bracket walk has room to breathe on the chart. */
    internal const val SCENARIO_OPENING_RANGE_PCT = 0.08

    fun openingFifteenMinuteBar(
        instrument: EmulatorInstrument,
        scenario: EmulatorTouchTurnScenario,
        marketZoneId: String,
        nowEpochMillis: Long
    ): OhlcBar {
        val ref = instrument.referencePrice
        val range = ref * SCENARIO_OPENING_RANGE_PCT
        val ohlc = EmulatorHistoricalData.turnZoneCompliantOpeningOhlc(
            ref = ref,
            range = range,
            greenCandle = scenario.isGreenOpeningBar
        )
        val barOpenEpoch = nowEpochMillis -
            TouchTurnLogic.FIRST_CANDLE_BAR_DURATION_MS -
            60_000L
        return OhlcBar(
            open = ohlc.open,
            high = ohlc.high,
            low = ohlc.low,
            close = ohlc.close,
            time = TouchTurnLogic.formatIbBarOpenTime(barOpenEpoch, marketZoneId),
            volume = ref * 50_000.0 * SCENARIO_OPENING_RANGE_PCT
        )
    }

    /** Single closed 5m hammer bar; open aligns with the 15m bar close. */
    fun fiveMinuteHammerBar(
        openingFifteenMinuteBar: OhlcBar,
        side: TouchTurnTradeSide,
        marketZoneId: String,
        afterBarOpenEpochMs: Long
    ): OhlcBar = EmulatorHistoricalData.syntheticHammerBar(
        openingFifteenMinuteBar = openingFifteenMinuteBar,
        side = side,
        barOpenEpoch = afterBarOpenEpochMs,
        marketZoneId = marketZoneId,
        openPrice = openingFifteenMinuteBar.close
    )
}
