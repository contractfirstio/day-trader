package daytrader.broker.emulator

import daytrader.broker.SymbolMarkets
import daytrader.domain.OhlcBar
import daytrader.domain.RthMarketSessions
import daytrader.domain.TouchTurnLogic
import daytrader.platform.currentSessionDateIso
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
        val sessionYmd = sessionDayYyyyMmDd()
        val profile = symbolProfile(
            symbol = symbol,
            sessionYmd = sessionYmd,
            colorMode = config.firstCandleColorMode,
            sessionCandleFetchIndex = sessionCandleFetchIndex,
            alternateFirstCandleColor = config.alternateFirstCandleColor
        )
        val ref = instrument.referencePrice
        val range = ref * profile.intradayRangePct
        val open = ref - range * profile.openBias
        val close = open + range * profile.closeBias
        val high = maxOf(open, close) + range * 0.15
        val low = minOf(open, close) - range * 0.10
        val barTime = resolveFirstCandleBarTime(
            marketZoneId = instrument.marketZoneId,
            secondsUntilClose = config.firstCandleSecondsUntilClose,
            nowEpochMillis = nowEpochMillis
        )
        return Result.success(
            OhlcBar(
                open = open,
                high = high,
                low = low,
                close = close,
                time = barTime
            )
        )
    }

    fun fourteenDayAdr(symbol: String, instrument: EmulatorInstrument): Result<Double> {
        val sessionYmd = sessionDayYyyyMmDd()
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
        val sessionYmd = sessionDayYyyyMmDd()
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

    private fun sessionDayYyyyMmDd(): String {
        val iso = currentSessionDateIso()
        return iso.replace("-", "")
    }

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

    private val LIQUID_SYMBOLS = setOf("SPY", "QQQ", "AAPL", "NVDA", "700")
}
