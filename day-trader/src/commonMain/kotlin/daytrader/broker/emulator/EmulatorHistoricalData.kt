package daytrader.broker.emulator

import daytrader.broker.SymbolMarkets
import daytrader.domain.OhlcBar
import daytrader.domain.RthMarketSessions
import daytrader.domain.TouchTurnDefaults
import daytrader.domain.TouchTurnLogic
import daytrader.domain.FiveMinuteConfirmationLogic
import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnLiquidityThresholds
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnSignalContext
import daytrader.domain.TouchTurnTradeSide
import daytrader.domain.requiresDailyHistoricalBootstrap
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sin

internal object EmulatorHistoricalData {
    private const val ADR_SESSION_COUNT = 16

    /** Stable sort order so seed-catalog symbols split ~50/50 green/red each session day. */
    private val catalogColorOrder: List<String> by lazy {
        EmulatorSeedCatalog.instruments().keys.sorted()
    }

    fun firstFifteenMinuteCandle(
        symbol: String,
        instrument: EmulatorInstrument,
        config: BrokerEmulatorConfig,
        nowEpochMillis: Long = System.currentTimeMillis(),
        sessionCandleFetchIndex: Int = 0
    ): Result<OhlcBar> {
        val marketZoneId = instrument.marketZoneId
        config.pinnedTouchTurnSignalContext?.firstCandle?.let { pinned ->
            return Result.success(pinned)
        }
        config.touchTurnScenario?.let { scenario ->
            return Result.success(
                EmulatorStaticTouchTurnBars.openingFifteenMinuteBar(
                    instrument = instrument,
                    scenario = scenario,
                    marketZoneId = marketZoneId,
                    nowEpochMillis = nowEpochMillis
                )
            )
        }
        val sessionYmd = sessionDayYyyyMmDd(marketZoneId, nowEpochMillis)
        val profile = symbolProfile(
            symbol = symbol,
            sessionYmd = sessionYmd,
            colorMode = config.firstCandleColorMode,
            sessionCandleFetchIndex = sessionCandleFetchIndex,
            alternateFirstCandleColor = config.alternateFirstCandleColor
        )
        val ref = instrument.referencePrice
        val range = ref * profile.intradayRangePct
        val greenCandle = resolveFirstCandleIsGreen(
            norm = SymbolMarkets.normalizeSymbol(symbol),
            sessionYmd = sessionYmd,
            colorMode = config.firstCandleColorMode,
            sessionCandleFetchIndex = sessionCandleFetchIndex,
            alternateFirstCandleColor = config.alternateFirstCandleColor
        )
        val ohlc = turnZoneCompliantOpeningOhlc(ref = ref, range = range, greenCandle = greenCandle)
        val open = ohlc.open
        val high = ohlc.high
        val low = ohlc.low
        val close = ohlc.close
        val barTime = resolveFirstCandleBarTime(
            marketZoneId = marketZoneId,
            secondsUntilClose = config.firstCandleSecondsUntilClose,
            nowEpochMillis = nowEpochMillis
        )
        val volume = ref * 50_000.0 * profile.intradayRangePct.coerceAtLeast(0.01)
        return Result.success(
            OhlcBar(
                open = open,
                high = high,
                low = low,
                close = close,
                time = barTime,
                volume = volume
            )
        )
    }

    fun touchTurnSignalContext(
        symbol: String,
        instrument: EmulatorInstrument,
        config: BrokerEmulatorConfig,
        nowEpochMillis: Long = System.currentTimeMillis(),
        sessionCandleFetchIndex: Int = 0,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Result<TouchTurnSignalContext> {
        config.pinnedTouchTurnSignalContext?.let { pinned ->
            return Result.success(pinned)
        }
        val marketZoneId = instrument.marketZoneId
        val sessionYmd = sessionDayYyyyMmDd(marketZoneId, nowEpochMillis)
        val opening = firstFifteenMinuteCandle(
            symbol = symbol,
            instrument = instrument,
            config = config,
            nowEpochMillis = nowEpochMillis,
            sessionCandleFetchIndex = sessionCandleFetchIndex
        ).getOrElse { return Result.failure(it) }
        val history = listOf(opening)
        val dailyBars = if (rules.enables.requiresDailyHistoricalBootstrap()) {
            buildDailyBars(symbol, instrument, sessionYmd)
        } else {
            null
        }
        return TouchTurnLogic.deriveTouchTurnSignalContext(
            bars = history,
            marketZoneId = marketZoneId,
            sessionDayYyyyMmdd = sessionYmd,
            explicitFirstCandle = opening,
            dailyBars = dailyBars,
            rules = rules
        )
    }

    private fun fifteenMinuteBarHistory(
        symbol: String,
        instrument: EmulatorInstrument,
        config: BrokerEmulatorConfig,
        nowEpochMillis: Long,
        sessionCandleFetchIndex: Int,
        sessionYmd: String,
        opening: OhlcBar
    ): List<OhlcBar> {
        val marketZoneId = instrument.marketZoneId
        val todayOpenMillis = opening.time?.let {
            TouchTurnLogic.barStartEpochMillis(it, marketZoneId)
        } ?: nowEpochMillis
        val profile = symbolProfile(symbol, sessionYmd)
        val ref = instrument.referencePrice
        val sessionDate = localDateFromYyyyMmDd(sessionYmd)
        var cursor = TouchTurnLogic.previousRthTradingDay(sessionDate)
        val priorOpenings = mutableListOf<OhlcBar>()
        var guard = 0
        while (priorOpenings.size < TouchTurnDefaults.VOLUME_SMA_PERIODS && guard < 45) {
            guard++
            val bar = syntheticSessionOpeningBar(
                sessionYmd = yyyyMmDd(cursor),
                marketZoneId = marketZoneId,
                ref = ref,
                profile = profile,
                dayOffset = priorOpenings.size
            )
            val dayKey = TouchTurnLogic.barDayKey(bar.time)
            if (dayKey != null && dayKey < sessionYmd) {
                priorOpenings.add(0, bar)
            }
            cursor = TouchTurnLogic.previousRthTradingDay(cursor.minusDays(1))
        }
        val atrBars = ((TouchTurnDefaults.ATR_LOOKBACK_PERIODS + 1) downTo 1).map { offset ->
            val barOpenMillis = todayOpenMillis - offset * TouchTurnLogic.FIRST_CANDLE_BAR_DURATION_MS
            syntheticIntradayBar(
                barOpenMillis = barOpenMillis,
                marketZoneId = marketZoneId,
                ref = ref,
                profile = profile,
                slotOffset = offset
            )
        }
        return (priorOpenings + atrBars + opening).sortedBy { TouchTurnLogic.barTimeSortKey(it.time) }
    }

    private fun syntheticSessionOpeningBar(
        sessionYmd: String,
        marketZoneId: String,
        ref: Double,
        profile: SymbolProfile,
        dayOffset: Int
    ): OhlcBar {
        val session = RthMarketSessions.forZoneId(marketZoneId)
        val barTime = "%s  %02d:%02d:00".format(sessionYmd, session.openHour, session.openMinute)
        val range = ref * profile.intradayRangePct
        val open = ref - range * profile.openBias
        val close = open + range * profile.closeBias * 0.4
        val high = maxOf(open, close) + range * 0.12
        val low = minOf(open, close) - range * 0.08
        return OhlcBar(
            open = open,
            high = high,
            low = low,
            close = close,
            time = barTime,
            volume = ref * 50_000.0 * profile.intradayRangePct * (0.85 + 0.02 * dayOffset)
        )
    }

    private fun syntheticIntradayBar(
        barOpenMillis: Long,
        marketZoneId: String,
        ref: Double,
        profile: SymbolProfile,
        slotOffset: Int
    ): OhlcBar {
        val range = ref * profile.intradayRangePct * (0.7 + 0.1 * sin(slotOffset.toDouble()))
        val mid = ref + range * sin(slotOffset * 0.3)
        val open = mid - range * 0.3
        val close = mid + range * 0.25
        val high = maxOf(open, close) + range * 0.2
        val low = minOf(open, close) - range * 0.15
        return OhlcBar(
            open = open,
            high = high,
            low = low,
            close = close,
            time = TouchTurnLogic.formatIbBarOpenTime(barOpenMillis, marketZoneId),
            volume = ref * 8_000.0 * (0.8 + 0.05 * slotOffset)
        )
    }

    private fun localDateFromYyyyMmDd(ymd: String): LocalDate =
        LocalDate.of(
            ymd.substring(0, 4).toInt(),
            ymd.substring(4, 6).toInt(),
            ymd.substring(6, 8).toInt()
        )

    private fun yyyyMmDd(date: LocalDate): String =
        "%04d%02d%02d".format(date.year, date.monthValue, date.dayOfMonth)

    fun fourteenDayAdr(
        symbol: String,
        instrument: EmulatorInstrument,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Result<Double> {
        val sessionYmd = sessionDayYyyyMmDd(instrument.marketZoneId, nowEpochMillis)
        val dailyBars = buildDailyBars(symbol, instrument, sessionYmd)
        return TouchTurnLogic.computeAdr14(dailyBars, excludeSessionDayYyyyMmdd = sessionYmd)
    }

    /**
     * Sets bar open so that [TouchTurnLogic.firstCandleCloseStatus] becomes CLOSED after
     * [secondsUntilClose] (bar end = now + secondsUntilClose, bar start = end − 15m).
     */
    internal fun resolveFirstCandleBarTime(
        marketZoneId: String,
        secondsUntilClose: Long?,
        nowEpochMillis: Long
    ): String {
        val accelerated = secondsUntilClose
        if (accelerated != null && accelerated > 0) {
            val barEnd = nowEpochMillis + accelerated * 1_000L
            val barOpen = barEnd - TouchTurnLogic.FIRST_CANDLE_BAR_DURATION_MS
            return TouchTurnLogic.formatIbBarOpenTime(barOpen, marketZoneId)
        }
        val sessionYmd = sessionDayYyyyMmDd(marketZoneId, nowEpochMillis)
        return barTimeForSession(sessionYmd, marketZoneId)
    }

    fun buildDailyBars(symbol: String, instrument: EmulatorInstrument, excludeDay: String): List<OhlcBar> {
        val profile = symbolProfile(symbol, excludeDay)
        val ref = instrument.referencePrice
        val days = (1..ADR_SESSION_COUNT).map { offset ->
            val ymd = offsetDayYmd(excludeDay, offset - ADR_SESSION_COUNT)
            val range = ref * profile.dailyRangePct * (0.85 + 0.3 * sin(offset.toDouble()))
            val open = ref + range * sin(offset * 0.7)
            val close = open + range * profile.closeBias * 0.5
            val high = maxOf(open, close) + range * 0.4
            val low = minOf(open, close) - range * 0.35
            OhlcBar(
                open = open,
                high = high,
                low = low,
                close = close,
                time = "$ymd  16:00:00"
            )
        }
        return days
    }

    private fun sessionDayYyyyMmDd(marketZoneId: String, nowEpochMillis: Long): String =
        TouchTurnLogic.sessionDayYyyyMmDd(marketZoneId, nowEpochMillis)

    private fun barTimeForSession(ymd: String, marketZoneId: String): String {
        val session = RthMarketSessions.forZoneId(marketZoneId)
        val openTime = "%02d:%02d:00".format(session.openHour, session.openMinute)
        return "$ymd  $openTime"
    }

    private fun offsetDayYmd(baseYmd: String, dayDelta: Int): String {
        val year = baseYmd.take(4).toIntOrNull() ?: 2026
        val month = baseYmd.drop(4).take(2).toIntOrNull() ?: 5
        val day = baseYmd.takeLast(2).toIntOrNull() ?: 20
        var d = day + dayDelta
        var m = month
        var y = year
        while (d < 1) {
            m--
            if (m < 1) {
                m = 12
                y--
            }
            d += 28
        }
        while (d > 28) {
            d -= 28
            m++
            if (m > 12) {
                m = 1
                y++
            }
        }
        return "%04d%02d%02d".format(y, m, d)
    }

    /**
     * Green/red first candle (green → short, red → long):
     * - Seed-catalog symbols: even/odd index vs session day flips color (~6/5 split per day).
     * - Other symbols: keyed hash fallback (still flips when [sessionYmd] changes).
     */
    internal fun firstCandleIsGreen(norm: String, sessionYmd: String): Boolean {
        val idx = catalogColorOrder.indexOf(norm)
        if (idx >= 0) {
            val sessionEven = abs(sessionYmd.hashCode()) % 2 == 0
            val catalogEven = idx % 2 == 0
            return sessionEven == catalogEven
        }
        return abs("$norm|$sessionYmd".hashCode()) % 2 == 0
    }

    internal fun resolveFirstCandleIsGreen(
        norm: String,
        sessionYmd: String,
        colorMode: EmulatorFirstCandleColorMode,
        sessionCandleFetchIndex: Int,
        alternateFirstCandleColor: Boolean
    ): Boolean = when (colorMode) {
        EmulatorFirstCandleColorMode.GREEN -> true
        EmulatorFirstCandleColorMode.RED -> false
        EmulatorFirstCandleColorMode.AUTO -> when {
            alternateFirstCandleColor && sessionCandleFetchIndex > 0 ->
                sessionCandleFetchIndex % 2 == 1
            else -> firstCandleIsGreen(norm, sessionYmd)
        }
    }

    internal fun symbolProfile(
        symbol: String,
        sessionYmd: String,
        colorMode: EmulatorFirstCandleColorMode = EmulatorFirstCandleColorMode.AUTO,
        sessionCandleFetchIndex: Int = 0,
        alternateFirstCandleColor: Boolean = false
    ): SymbolProfile {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        val hash = abs(norm.hashCode())
        val liquid = norm in LIQUID_SYMBOLS
        val greenCandle = resolveFirstCandleIsGreen(
            norm = norm,
            sessionYmd = sessionYmd,
            colorMode = colorMode,
            sessionCandleFetchIndex = sessionCandleFetchIndex,
            alternateFirstCandleColor = alternateFirstCandleColor
        )
        return SymbolProfile(
            intradayRangePct = if (liquid) 0.018 else 0.012,
            dailyRangePct = if (liquid) 0.022 else 0.016,
            openBias = if (hash % 2 == 0) 0.35 else -0.25,
            closeBias = if (greenCandle) 0.55 else -0.45
        )
    }

    internal data class SymbolProfile(
        val intradayRangePct: Double,
        val dailyRangePct: Double,
        val openBias: Double,
        val closeBias: Double
    )

    internal data class OpeningOhlc(
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double
    )

    /**
     * Synthetic opening 15m bar whose close sits in the Touch Turn zone:
     * green (short) → lower third; red (long) → upper third.
     */
    internal fun turnZoneCompliantOpeningOhlc(
        ref: Double,
        range: Double,
        greenCandle: Boolean
    ): OpeningOhlc = if (greenCandle) {
        val open = ref - range * 0.30
        val close = open + range * 0.06
        OpeningOhlc(
            open = open,
            high = open + range * 0.50,
            low = open - range * 0.06,
            close = close
        )
    } else {
        val open = ref + range * 0.28
        val close = open - range * 0.06
        OpeningOhlc(
            open = open,
            high = open + range * 0.06,
            low = open - range * 0.50,
            close = close
        )
    }

    private val LIQUID_SYMBOLS = setOf("SPY", "QQQ", "AAPL", "NVDA", "700")

    /**
     * Synthetic closed 5m bars after a 15m sweep for emulator / manual testing.
     * [config.fiveMinuteHammerBarIndex] selects which slot (0..2) prints a valid hammer.
     */
    fun fiveMinuteBarsSince(
        openingFifteenMinuteBar: OhlcBar,
        side: TouchTurnTradeSide,
        config: BrokerEmulatorConfig,
        afterBarOpenEpochMs: Long,
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): List<OhlcBar> {
        config.touchTurnScenario?.let { scenario ->
            val scenarioSide = when {
                scenario.isGreenOpeningBar -> TouchTurnTradeSide.SHORT
                else -> TouchTurnTradeSide.LONG
            }
            return listOf(
                syntheticHammerBar(
                    openingFifteenMinuteBar = openingFifteenMinuteBar,
                    side = scenarioSide,
                    barOpenEpoch = afterBarOpenEpochMs,
                    marketZoneId = marketZoneId,
                    openPrice = openingFifteenMinuteBar.close,
                    barRangeFractionOfSpan = 0.05
                )
            )
        }
        val barDurationMs = fiveMinuteBarDurationMs(config)
        val bars = mutableListOf<OhlcBar>()
        val slots = (FiveMinuteConfirmationLogic.TTL_MS / barDurationMs).toInt().coerceAtMost(3)
        var priorClose = openingFifteenMinuteBar.close
        for (index in 0 until slots) {
            val barOpenEpoch = afterBarOpenEpochMs + index * barDurationMs
            val barEndEpoch = barOpenEpoch + barDurationMs
            if (nowEpochMillis < barEndEpoch) continue
            val bar = when {
                index == config.fiveMinuteInvalidatingBarIndex ->
                    syntheticInvalidatingBar(
                        openingFifteenMinuteBar = openingFifteenMinuteBar,
                        side = side,
                        barOpenEpoch = barOpenEpoch,
                        marketZoneId = marketZoneId,
                        openPrice = priorClose
                    )
                index == config.fiveMinuteHammerBarIndex ->
                syntheticHammerBar(
                    openingFifteenMinuteBar = openingFifteenMinuteBar,
                    side = side,
                    barOpenEpoch = barOpenEpoch,
                    marketZoneId = marketZoneId,
                    openPrice = priorClose
                )
            else ->
                syntheticNonHammerBar(
                    openingFifteenMinuteBar = openingFifteenMinuteBar,
                    side = side,
                    barOpenEpoch = barOpenEpoch,
                    marketZoneId = marketZoneId,
                    slotIndex = index,
                    openPrice = priorClose
                )
            }
            priorClose = bar.close
            bars += bar
        }
        return bars
    }

    private fun fiveMinuteBarDurationMs(config: BrokerEmulatorConfig): Long =
        config.fiveMinuteBarSecondsUntilClose?.times(1_000L)
            ?: FiveMinuteConfirmationLogic.BAR_DURATION_MS

    internal fun syntheticHammerBar(
        openingFifteenMinuteBar: OhlcBar,
        side: TouchTurnTradeSide,
        barOpenEpoch: Long,
        marketZoneId: String,
        openPrice: Double = openingFifteenMinuteBar.close,
        barRangeFractionOfSpan: Double = 0.08
    ): OhlcBar {
        val setup = TouchTurnLogic.computeBracketSetup(
            openingFifteenMinuteBar,
            TouchTurnLiquidityThresholds(thresholdDailyAtr = 0.0),
            TouchTurnRuleConfig.DEFAULT
        )
        val takeProfit = setup.takeProfit
        val span = (openingFifteenMinuteBar.high - openingFifteenMinuteBar.low).coerceAtLeast(0.5)
        val cushion = span * 0.01
        val mid = (openingFifteenMinuteBar.low + openingFifteenMinuteBar.high) / 2.0
        val barRange = span * barRangeFractionOfSpan
        val body = barRange * 0.12
        val rejection = body * 2.5
        val opposite = body * 0.08
        val tradeSide = setup.side
        val open = when (tradeSide) {
            TouchTurnTradeSide.SHORT ->
                if (FiveMinuteConfirmationLogic.entryPastTakeProfit(setup, openPrice)) {
                    openingFifteenMinuteBar.high - span * 0.02
                } else {
                    openPrice
                }
            TouchTurnTradeSide.LONG ->
                if (FiveMinuteConfirmationLogic.entryPastTakeProfit(setup, openPrice)) {
                    openingFifteenMinuteBar.low + span * 0.02
                } else {
                    openPrice
                }
        }
        val bar = when (tradeSide) {
            TouchTurnTradeSide.LONG -> {
                val maxClose = takeProfit - cushion
                val close = (open + body).coerceAtMost(maxClose).coerceAtMost(openingFifteenMinuteBar.high)
                val low = (open - rejection).coerceAtLeast(openingFifteenMinuteBar.low)
                val high = (close + opposite).coerceAtMost(openingFifteenMinuteBar.high)
                OhlcBar(
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    time = TouchTurnLogic.formatIbBarOpenTime(barOpenEpoch, marketZoneId),
                    volume = mid * 2_000.0
                )
            }
            TouchTurnTradeSide.SHORT -> {
                val minClose = takeProfit + cushion
                val close = (open - body).coerceAtLeast(minClose).coerceAtLeast(openingFifteenMinuteBar.low)
                val high = (open + rejection).coerceAtMost(openingFifteenMinuteBar.high)
                val low = (close - opposite).coerceAtLeast(openingFifteenMinuteBar.low)
                OhlcBar(
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    time = TouchTurnLogic.formatIbBarOpenTime(barOpenEpoch, marketZoneId),
                    volume = mid * 2_000.0
                )
            }
        }
        check(!FiveMinuteConfirmationLogic.entryPastTakeProfit(setup, bar.close)) {
            "Emulator hammer close ${bar.close} crossed 15m take-profit $takeProfit for $tradeSide"
        }
        return bar
    }

    private fun syntheticInvalidatingBar(
        openingFifteenMinuteBar: OhlcBar,
        side: TouchTurnTradeSide,
        barOpenEpoch: Long,
        marketZoneId: String,
        openPrice: Double = openingFifteenMinuteBar.close
    ): OhlcBar {
        val mid = (openingFifteenMinuteBar.low + openingFifteenMinuteBar.high) / 2.0
        val span = (openingFifteenMinuteBar.high - openingFifteenMinuteBar.low).coerceAtLeast(0.5)
        val barRange = span * 0.06
        val open = openPrice
        val close = when (side) {
            TouchTurnTradeSide.LONG -> openingFifteenMinuteBar.low - span * 0.05
            TouchTurnTradeSide.SHORT -> openingFifteenMinuteBar.high + span * 0.05
        }
        val high = maxOf(open, close) + barRange * 0.15
        val low = minOf(open, close) - barRange * 0.15
        return OhlcBar(
            open = open,
            high = high,
            low = low,
            close = close,
            time = TouchTurnLogic.formatIbBarOpenTime(barOpenEpoch, marketZoneId),
            volume = mid * 1_500.0
        )
    }

    private fun syntheticNonHammerBar(
        openingFifteenMinuteBar: OhlcBar,
        side: TouchTurnTradeSide,
        barOpenEpoch: Long,
        marketZoneId: String,
        slotIndex: Int,
        openPrice: Double = openingFifteenMinuteBar.close
    ): OhlcBar {
        val mid = (openingFifteenMinuteBar.low + openingFifteenMinuteBar.high) / 2.0
        val span = (openingFifteenMinuteBar.high - openingFifteenMinuteBar.low).coerceAtLeast(0.5)
        val barRange = span * 0.06
        val open = openPrice
        val drift = barRange * 0.55 * if (slotIndex % 2 == 0) 1.0 else -1.0
        val close = (open + drift).coerceIn(openingFifteenMinuteBar.low, openingFifteenMinuteBar.high)
        val high = maxOf(open, close) + barRange * 0.2
        val low = minOf(open, close) - barRange * 0.2
        return OhlcBar(
            open = open,
            high = high.coerceAtMost(openingFifteenMinuteBar.high),
            low = low.coerceAtLeast(openingFifteenMinuteBar.low),
            close = close,
            time = TouchTurnLogic.formatIbBarOpenTime(barOpenEpoch, marketZoneId),
            volume = mid * 1_500.0
        )
    }
}
