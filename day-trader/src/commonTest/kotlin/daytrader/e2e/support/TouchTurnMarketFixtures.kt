package daytrader.e2e.support

import daytrader.broker.emulator.BrokerEmulatorConfig
import daytrader.broker.emulator.EmulatorFirstCandleColorMode
import daytrader.broker.emulator.EmulatorHistoricalData
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnSignalContext
import daytrader.domain.TouchTurnTradeSide
import daytrader.engine.support.FakeBrokerGateway
import daytrader.gateway.LiveQuote

/**
 * Canonical Touch Turn market data for cross-mode E2E parity.
 *
 * IB, paper (hybrid), emulator, and replay tests should source bars, signal context,
 * and session timing from here so identical scenarios produce comparable outcomes.
 */
enum class TouchTurnMarketScenarioId {
    /** Range below liquidity threshold → NO_TRADE_NOT_LIQUIDITY. */
    NON_LIQUIDITY,
    /** Red 15m bar in turn zone → long liquidity fade. */
    RED_LIQUIDITY_LONG,
    /** Green 15m bar in turn zone → short liquidity fade. */
    GREEN_LIQUIDITY_SHORT,
    /** Red liquidity bar used by full trade-lifecycle replay captures. */
    TRADE_LIFECYCLE,
}

data class TouchTurnMarketScenario(
    val id: TouchTurnMarketScenarioId,
    val symbol: String,
    val sessionDate: String,
    val openingBar: OhlcBar,
    val signalContext: TouchTurnSignalContext,
    /** Reference price for emulator quote seeding (typically near bar close). */
    val referencePrice: Double,
    val sessionStartedEpochMs: Long,
    val barCloseEpochMs: Long,
)

object TouchTurnMarketFixtures {
    const val SYMBOL = "AAPL"
    const val SESSION_DATE = "2026-06-04"
    const val ATR14 = 2.45
    const val VOLUME_SMA20 = 980_000.0

    /** 2026-06-04 09:30:00 America/New_York — session start. */
    const val SESSION_STARTED_EPOCH_MS = 1_780_579_800_000L

    /** After 2026-06-04 09:45:00 America/New_York first 15m bar close (+ test skew). */
    const val BAR_CLOSE_EPOCH_MS = 1_780_580_700_000L + 10_000L

    fun scenario(id: TouchTurnMarketScenarioId): TouchTurnMarketScenario = when (id) {
        TouchTurnMarketScenarioId.NON_LIQUIDITY -> nonLiquidity()
        TouchTurnMarketScenarioId.RED_LIQUIDITY_LONG -> redLiquidityLong()
        TouchTurnMarketScenarioId.GREEN_LIQUIDITY_SHORT -> greenLiquidityShort()
        TouchTurnMarketScenarioId.TRADE_LIFECYCLE -> tradeLifecycle()
    }

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

    fun greenLiquidityOpeningBar(): OhlcBar = OhlcBar(
        open = 100.0,
        high = 101.0,
        low = 100.0,
        close = 100.85,
        time = "20260604  09:30:00",
        volume = 800_000.0
    )

    fun bootstrapContext(bar: OhlcBar): TouchTurnSignalContext = TouchTurnSignalContext(
        firstCandle = bar,
        atr14 = ATR14,
        dailyAtr14 = ATR14,
        volumeSma20 = VOLUME_SMA20
    )

    fun liveQuote(
        symbol: String = SYMBOL,
        bid: Double,
        ask: Double,
        last: Double = (bid + ask) / 2.0,
        quoteEpochMillis: Long = BAR_CLOSE_EPOCH_MS,
    ): LiveQuote = LiveQuote(
        symbol = symbol,
        bid = bid,
        ask = ask,
        last = last,
        quoteEpochMillis = quoteEpochMillis
    )

    private fun nonLiquidity(): TouchTurnMarketScenario {
        val bar = nonLiquidityOpeningBar()
        return TouchTurnMarketScenario(
            id = TouchTurnMarketScenarioId.NON_LIQUIDITY,
            symbol = SYMBOL,
            sessionDate = SESSION_DATE,
            openingBar = bar,
            signalContext = bootstrapContext(bar),
            referencePrice = bar.close,
            sessionStartedEpochMs = SESSION_STARTED_EPOCH_MS,
            barCloseEpochMs = BAR_CLOSE_EPOCH_MS,
        )
    }

    private fun redLiquidityLong(): TouchTurnMarketScenario {
        val bar = redLiquidityOpeningBar()
        return TouchTurnMarketScenario(
            id = TouchTurnMarketScenarioId.RED_LIQUIDITY_LONG,
            symbol = SYMBOL,
            sessionDate = SESSION_DATE,
            openingBar = bar,
            signalContext = bootstrapContext(bar),
            referencePrice = bar.close,
            sessionStartedEpochMs = SESSION_STARTED_EPOCH_MS,
            barCloseEpochMs = BAR_CLOSE_EPOCH_MS,
        )
    }

    private fun greenLiquidityShort(): TouchTurnMarketScenario {
        val bar = greenLiquidityOpeningBar()
        return TouchTurnMarketScenario(
            id = TouchTurnMarketScenarioId.GREEN_LIQUIDITY_SHORT,
            symbol = SYMBOL,
            sessionDate = SESSION_DATE,
            openingBar = bar,
            signalContext = bootstrapContext(bar),
            referencePrice = bar.close,
            sessionStartedEpochMs = SESSION_STARTED_EPOCH_MS,
            barCloseEpochMs = BAR_CLOSE_EPOCH_MS,
        )
    }

    private fun tradeLifecycle(): TouchTurnMarketScenario = redLiquidityLong().copy(
        id = TouchTurnMarketScenarioId.TRADE_LIFECYCLE
    )

    private const val SYNTHETIC_FIVE_MIN_BAR_SECONDS = 1L
    private const val DEFAULT_MARKET_ZONE = "America/New_York"

    /** Trade side implied by a canonical liquidity opening bar. */
    fun tradeSide(scenario: TouchTurnMarketScenario): TouchTurnTradeSide = when (scenario.id) {
        TouchTurnMarketScenarioId.GREEN_LIQUIDITY_SHORT -> TouchTurnTradeSide.SHORT
        TouchTurnMarketScenarioId.RED_LIQUIDITY_LONG,
        TouchTurnMarketScenarioId.TRADE_LIFECYCLE -> TouchTurnTradeSide.LONG
        TouchTurnMarketScenarioId.NON_LIQUIDITY -> TouchTurnTradeSide.LONG
    }

    /** Fast synthetic closed 5m bars for IB gateway mocks and cross-mode parity. */
    fun syntheticFiveMinuteBars(
        scenario: TouchTurnMarketScenario,
        hammerBarIndex: Int = 1,
        invalidatingBarIndex: Int? = null,
        afterBarOpenEpochMs: Long = scenario.barCloseEpochMs,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): List<OhlcBar> = EmulatorHistoricalData.fiveMinuteBarsSince(
        openingFifteenMinuteBar = scenario.openingBar,
        side = tradeSide(scenario),
        config = BrokerEmulatorConfig(
            fiveMinuteBarSecondsUntilClose = SYNTHETIC_FIVE_MIN_BAR_SECONDS,
            fiveMinuteHammerBarIndex = hammerBarIndex,
            fiveMinuteInvalidatingBarIndex = invalidatingBarIndex,
        ),
        afterBarOpenEpochMs = afterBarOpenEpochMs,
        marketZoneId = DEFAULT_MARKET_ZONE,
        nowEpochMillis = nowEpochMillis,
    )

    fun syntheticFiveMinuteHammerBars(
        scenario: TouchTurnMarketScenario,
        hammerBarIndex: Int = 1,
    ): List<OhlcBar> = syntheticFiveMinuteBars(scenario, hammerBarIndex = hammerBarIndex)

    fun syntheticFiveMinuteInvalidatingBars(
        scenario: TouchTurnMarketScenario,
        invalidatingBarIndex: Int = 0,
    ): List<OhlcBar> = syntheticFiveMinuteBars(
        scenario,
        hammerBarIndex = -1,
        invalidatingBarIndex = invalidatingBarIndex,
    )
}

fun TouchTurnMarketScenario.applyTo(gateway: ProgrammableIbMarketDataGateway) {
    gateway.bootstrapContext = signalContext
    gateway.refetchContexts = listOf(signalContext)
}

fun TouchTurnMarketScenario.applyTo(gateway: FakeBrokerGateway) {
    gateway.signalContextFetchResult = Result.success(signalContext)
    gateway.candleFetchResult = Result.success(openingBar)
    gateway.refetchSignalContexts = listOf(signalContext)
}

/**
 * Pins emulator historical bootstrap to this scenario's exact signal context so
 * emulator E2E sees the same 15m bar and ATR metadata as IB / paper / replay.
 */
fun TouchTurnMarketScenario.emulatorConfig(
    base: BrokerEmulatorConfig = BrokerEmulatorConfig(
        connectDelayMs = 1,
        marketTickIntervalMs = 50,
        firstCandleSecondsUntilClose = null,
        simulateOrderProgress = false,
    ),
): BrokerEmulatorConfig = base.copy(
    pinnedTouchTurnSignalContext = signalContext,
    firstCandleColorMode = when (id) {
        TouchTurnMarketScenarioId.GREEN_LIQUIDITY_SHORT -> EmulatorFirstCandleColorMode.GREEN
        TouchTurnMarketScenarioId.RED_LIQUIDITY_LONG,
        TouchTurnMarketScenarioId.TRADE_LIFECYCLE -> EmulatorFirstCandleColorMode.RED
        TouchTurnMarketScenarioId.NON_LIQUIDITY -> EmulatorFirstCandleColorMode.AUTO
    },
    alternateFirstCandleColor = false,
    touchTurnScenario = null,
)

fun EmulatorModeTestHarness.Companion.forScenario(
    scope: kotlinx.coroutines.CoroutineScope,
    scenario: TouchTurnMarketScenario,
    base: BrokerEmulatorConfig = BrokerEmulatorConfig(
        connectDelayMs = 1,
        marketTickIntervalMs = 50,
        firstCandleSecondsUntilClose = null,
        simulateOrderProgress = false,
    ),
): EmulatorModeTestHarness = EmulatorModeTestHarness(
    scope = scope,
    config = scenario.emulatorConfig(base)
)
