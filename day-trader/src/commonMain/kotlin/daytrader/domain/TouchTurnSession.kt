package daytrader.domain

import kotlinx.serialization.Serializable

/** Status of the first 15-minute RTH candle fetch for the current session. */
@Serializable
enum class TouchTurnCandleStatus {
    LOADING,
    READY,
    FAILED
}

/** Whether the first 15-minute bar has finished (15 minutes after bar open). */
@Serializable
enum class FirstCandleCloseStatus {
    FORMING,
    CLOSED,
    UNKNOWN
}

/** Liquidity is only evaluated after the first 15-minute bar has closed. */
@Serializable
enum class LiquidityCandleEvaluation {
    AWAITING_CLOSE,
    LIQUIDITY,
    NOT_LIQUIDITY,
    UNKNOWN
}

/** Whether orders may be placed within [TouchTurnDefaults.ENTRY_WINDOW_AFTER_CLOSE_MS] of bar close. */
@Serializable
enum class TouchTurnEntryWindowStatus {
    AWAITING_BAR_CLOSE,
    WITHIN_WINDOW,
    EXPIRED,
    UNKNOWN
}

/** Close-location and timing gate after liquidity check (same 15m candle, no second candle wait). */
@Serializable
enum class TouchTurnCloseConfirmation {
    AWAITING_LIQUIDITY,
    PASSED,
    FAILED,
    /** Bar closed more than [TouchTurnDefaults.CLOSE_CONFIRMATION_AFTER_CLOSE_MS] ago. */
    EXPIRED,
    UNKNOWN
}

/** Legacy Touch Turn session field (superseded by [TouchTurnSessionStopLogic] open-deadline auto-stop). */
@Serializable
enum class TouchTurnNoPositionCancelOutcome {
    PENDING,
    /** No open position at deadline — bracket orders would be cancelled. */
    WOULD_CANCEL_LOGGED,
    /** Open position at deadline — brackets are left working. */
    KEPT_HAS_POSITION
}

/** Standard candle colour from day open vs 15m bar close. */
@Serializable
enum class FirstCandleColor {
    GREEN,
    RED,
    DOJI
}

@Serializable
data class OhlcBar(
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    /** IB bar time, e.g. `20250522  09:30:00`. */
    val time: String? = null
) {
    val range: Double get() = high - low
}

/** Intended trade direction after a liquidity opening bar. */
@Serializable
enum class TouchTurnTradeSide {
    LONG,
    SHORT
}

/**
 * Bracket levels derived from the first 15-minute candle (Touch Turn liquidity setup).
 * Green bar → short at high, TP at 61.8% of range; red bar → long at low, TP at 38.2% of range.
 * Stop is half the entry-to-TP distance beyond entry (with a minimum).
 */
@Serializable
data class TouchTurnBracketSetup(
    val range: Double,
    val rangeThreshold: Double,
    val isLiquidityCandle: Boolean,
    val candleColor: FirstCandleColor,
    val side: TouchTurnTradeSide,
    val entry: Double,
    val stopLoss: Double,
    val takeProfit: Double
) {
    val isActionable: Boolean
        get() = isLiquidityCandle && candleColor != FirstCandleColor.DOJI
}

/** Wall-clock ISO local timestamps when each Touch Turn pipeline step completed. */
@Serializable
data class TouchTurnMilestoneTimestamps(
    val startingSessionAt: String? = null,
    val dataReadyAt: String? = null,
    val dataFailedAt: String? = null,
    val barClosedAt: String? = null,
    val liquidityEvaluatedAt: String? = null,
    val closeConfirmedAt: String? = null,
    val ordersPlacedAt: String? = null,
    val positionOpenedAt: String? = null,
    val closingSessionAt: String? = null
)

@Serializable
data class TouchTurnSessionContext(
    val sessionDate: String,
    val status: TouchTurnCandleStatus,
    val candle: OhlcBar? = null,
    val setup: TouchTurnBracketSetup? = null,
    val errorMessage: String? = null,
    val milestones: TouchTurnMilestoneTimestamps = TouchTurnMilestoneTimestamps(),
    /** ISO currency for price display (e.g. HKD on SEHK). */
    val currencyCode: String = "USD",
    /** IANA zone for bar close time (e.g. Asia/Hong_Kong). */
    val marketZoneId: String = "America/New_York",
    /** Average daily range (high − low) over the last 14 completed sessions. */
    val adr14: Double? = null,
    /** Liquidity threshold = [adr14] × [TouchTurnDefaults.ADR_LIQUIDITY_RATIO] (25%). */
    val rangeThreshold: Double = 0.0,
    /** Set when the bar closes: true if a liquidity bracket was eligible to be logged/placed. */
    val entryOrdersPermitted: Boolean? = null,
    /** True when bracket orders were actually logged/placed for this session. */
    val ordersPlacedForSession: Boolean = false,
    /** After entry window: whether an open position existed (only evaluated when [ordersPlacedForSession]). */
    val noPositionBracketCancelOutcome: TouchTurnNoPositionCancelOutcome? = null,
    /** Written once when the no-trade / trade decision is known. */
    val decisionOutcome: TouchTurnSessionOutcome? = null,
    val plannedQuantity: Int? = null,
    val plannedBracket: TouchTurnPlannedBracket? = null,
    /** Filled legs for a closed run (from persisted run record or derived from fills). */
    val executedBracketLegs: List<TouchTurnOrderRole> = emptyList()
) {
    fun sessionOrdersPlaced(): Boolean = ordersPlacedForSession || entryOrdersPermitted == true
    val liquidityThresholdFromAdr: Double?
        get() = adr14?.let { TouchTurnLogic.liquidityRangeThreshold(it) }
    fun candleCloseStatus(nowEpochMillis: Long = System.currentTimeMillis()): FirstCandleCloseStatus =
        TouchTurnLogic.firstCandleCloseStatus(candle, marketZoneId, nowEpochMillis, sessionDate)

    fun liquidityEvaluation(nowEpochMillis: Long = System.currentTimeMillis()): LiquidityCandleEvaluation =
        TouchTurnLogic.liquidityCandleEvaluation(candle, marketZoneId, rangeThreshold, nowEpochMillis)

    fun firstCandleColor(): FirstCandleColor? = candle?.let { TouchTurnLogic.firstCandleColor(it) }

    fun entryWindowStatus(nowEpochMillis: Long = System.currentTimeMillis()): TouchTurnEntryWindowStatus =
        TouchTurnLogic.entryWindowStatus(candle, marketZoneId, nowEpochMillis)

    fun closeConfirmation(nowEpochMillis: Long = System.currentTimeMillis()): TouchTurnCloseConfirmation =
        TouchTurnLogic.closeConfirmation(candle, setup, marketZoneId, nowEpochMillis)

    /** Live panel: elapsed since the most recent 09:30 RTH open in [marketZoneId] (wall clock). */
    fun millisSinceLastMarketOpen(
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Long = TouchTurnLogic.millisSinceLastMarketOpenWallClock(marketZoneId, nowEpochMillis)

    /** Live panel: time until the next 09:30 RTH open in [marketZoneId] (wall clock). */
    fun millisUntilNextMarketOpen(
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Long = TouchTurnLogic.millisUntilNextMarketOpen(marketZoneId, nowEpochMillis)
}

object TouchTurnLogic {
    const val BAR_DURATION_MINUTES = 15
    const val FIRST_CANDLE_BAR_DURATION_MS = BAR_DURATION_MINUTES * 60 * 1000L
    private const val BAR_DURATION_MS = FIRST_CANDLE_BAR_DURATION_MS
    private val IB_BAR_TIME_REGEX = Regex("""(\d{4})(\d{2})(\d{2})\s+(\d{1,2}):(\d{2}):(\d{2})""")
    private val IB_BAR_TIME_WITH_SUFFIX_REGEX = Regex(
        """(\d{4})(\d{2})(\d{2})\s+(\d{1,2}):(\d{2}):(\d{2})(?:\s+([A-Za-z_/]+))?"""
    )

    fun sessionDayYyyyMmDd(
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): String {
        val zone = java.time.ZoneId.of(marketZoneId)
        return java.time.Instant.ofEpochMilli(nowEpochMillis)
            .atZone(zone)
            .toLocalDate()
            .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
    }

    /**
     * IB may append an exchange/API zone suffix (e.g. `20260526 09:00:00 MET`).
     * Converts the timestamp into [marketZoneId] for bar-close logic (MET → London is −1h in summer).
     */
    fun normalizeIbBarTimeToMarketZone(barTime: String?, marketZoneId: String): String? {
        val trimmed = barTime?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val match = IB_BAR_TIME_WITH_SUFFIX_REGEX.find(trimmed) ?: return trimmed
        val (year, month, day, hour, minute, second, suffix) = match.destructured
        val sourceZoneId = suffix.takeIf { it.isNotBlank() }?.let { ibBarTimeSuffixToZoneId(it) } ?: marketZoneId
        return runCatching {
            val sourceZone = java.time.ZoneId.of(sourceZoneId)
            val targetZone = java.time.ZoneId.of(marketZoneId)
            val local = java.time.LocalDateTime.of(
                year.toInt(),
                month.toInt(),
                day.toInt(),
                hour.toInt(),
                minute.toInt(),
                second.toInt()
            )
            val instant = local.atZone(sourceZone).toInstant()
            formatIbBarOpenTime(instant.toEpochMilli(), marketZoneId)
        }.getOrElse { trimmed }
    }

    fun ibBarTimeSuffixToZoneId(suffix: String): String? = when (suffix.trim().uppercase()) {
        "MET", "MEZ", "CET", "CEST", "EET", "EEST" -> "Europe/Berlin"
        "UTC", "GMT" -> "UTC"
        "US/EASTERN", "EST", "EDT" -> "America/New_York"
        "LON", "BST", "GB", "UK" -> "Europe/London"
        "HK", "HKT" -> "Asia/Hong_Kong"
        else -> null
    }

    /**
     * Picks the first 15m RTH bar for [sessionDayYyyyMmdd], normalizing IB timestamps into [marketZoneId].
     * Prefers the scheduled session open bar (e.g. 08:00 London) when present.
     */
    fun selectFirstFifteenMinuteBar(
        bars: List<OhlcBar>,
        marketZoneId: String,
        sessionDayYyyyMmdd: String
    ): OhlcBar? {
        val normalized = bars
            .filter { it.high > 0.0 && it.low > 0.0 && it.high >= it.low }
            .map { bar ->
                bar.copy(time = normalizeIbBarTimeToMarketZone(bar.time, marketZoneId))
            }
            .filter { barDayKey(it.time) == sessionDayYyyyMmdd }
        if (normalized.isEmpty()) return null

        val sessionDateIso = runCatching {
            val y = sessionDayYyyyMmdd.substring(0, 4).toInt()
            val m = sessionDayYyyyMmdd.substring(4, 6).toInt()
            val d = sessionDayYyyyMmdd.substring(6, 8).toInt()
            "%04d-%02d-%02d".format(y, m, d)
        }.getOrNull()
        val expectedOpenMillis = sessionDateIso?.let {
            marketOpenEpochMillis(it, marketZoneId, firstCandleBarTime = null)
        }
        val expectedBarTime = expectedOpenMillis?.let { formatIbBarOpenTime(it, marketZoneId) }
        expectedBarTime?.let { expected ->
            normalized.firstOrNull { it.time == expected }?.let { return it }
        }
        return normalized.minByOrNull { barTimeSortKey(it.time) }
    }

    fun barTimeSortKey(time: String?): String = time?.trim().orEmpty()

    /**
     * IB historical bar `time` is the bar **open** (e.g. `20250522  09:30:00` for 09:30–09:45).
     * The candle is closed when wall-clock time in [marketZoneId] is at or past open + 15 minutes.
     */
    fun firstCandleCloseStatus(
        candle: OhlcBar?,
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
        sessionDateIso: String? = null
    ): FirstCandleCloseStatus {
        val time = candle?.time
        val scheduledBarEndMillis = sessionDateIso?.let { date ->
            marketOpenEpochMillis(date, marketZoneId)?.plus(BAR_DURATION_MS)
        }
        if (scheduledBarEndMillis != null && nowEpochMillis >= scheduledBarEndMillis) {
            return FirstCandleCloseStatus.CLOSED
        }
        if (time == null) return FirstCandleCloseStatus.UNKNOWN
        val barEndMillis = barEndEpochMillis(time, marketZoneId) ?: return FirstCandleCloseStatus.UNKNOWN
        return if (nowEpochMillis >= barEndMillis) {
            FirstCandleCloseStatus.CLOSED
        } else {
            FirstCandleCloseStatus.FORMING
        }
    }

    fun closeStatusLabel(status: FirstCandleCloseStatus): String = when (status) {
        FirstCandleCloseStatus.CLOSED -> "Candle closed"
        FirstCandleCloseStatus.FORMING -> "Candle still forming"
        FirstCandleCloseStatus.UNKNOWN -> "Close status unknown"
    }

    fun entryWindowDeadlineEpochMillis(barTime: String, marketZoneId: String): Long? =
        barEndEpochMillis(barTime, marketZoneId)?.plus(TouchTurnDefaults.CLOSE_CONFIRMATION_AFTER_CLOSE_MS)

    fun entryWindowStatus(
        candle: OhlcBar?,
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): TouchTurnEntryWindowStatus {
        val time = candle?.time ?: return TouchTurnEntryWindowStatus.UNKNOWN
        val barEnd = barEndEpochMillis(time, marketZoneId) ?: return TouchTurnEntryWindowStatus.UNKNOWN
        if (nowEpochMillis < barEnd) return TouchTurnEntryWindowStatus.AWAITING_BAR_CLOSE
        val deadline = entryWindowDeadlineEpochMillis(time, marketZoneId) ?: return TouchTurnEntryWindowStatus.UNKNOWN
        return if (nowEpochMillis <= deadline) {
            TouchTurnEntryWindowStatus.WITHIN_WINDOW
        } else {
            TouchTurnEntryWindowStatus.EXPIRED
        }
    }

    fun entryWindowRemainingMillis(
        candle: OhlcBar?,
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Long? {
        val time = candle?.time ?: return null
        val barEnd = barEndEpochMillis(time, marketZoneId) ?: return null
        if (nowEpochMillis < barEnd) return null
        val deadline = entryWindowDeadlineEpochMillis(time, marketZoneId) ?: return null
        return (deadline - nowEpochMillis).coerceAtLeast(0)
    }

    fun entryWindowStatusLabel(status: TouchTurnEntryWindowStatus): String = when (status) {
        TouchTurnEntryWindowStatus.AWAITING_BAR_CLOSE -> "Entry window opens when the bar closes"
        TouchTurnEntryWindowStatus.WITHIN_WINDOW -> "Entry window open (1 min after bar close)"
        TouchTurnEntryWindowStatus.EXPIRED -> "Entry window closed — deadline passed"
        TouchTurnEntryWindowStatus.UNKNOWN -> "Entry window unknown"
    }

    fun closeConfirmation(
        candle: OhlcBar?,
        setup: TouchTurnBracketSetup?,
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): TouchTurnCloseConfirmation {
        val bar = candle ?: return TouchTurnCloseConfirmation.UNKNOWN
        if (firstCandleCloseStatus(bar, marketZoneId, nowEpochMillis) != FirstCandleCloseStatus.CLOSED) {
            return TouchTurnCloseConfirmation.AWAITING_LIQUIDITY
        }
        val bracket = setup ?: return TouchTurnCloseConfirmation.AWAITING_LIQUIDITY
        if (!bracket.isLiquidityCandle || !bracket.isActionable) return TouchTurnCloseConfirmation.FAILED                 
        if (!closeConfirmationWithinDeadline(bar, marketZoneId, nowEpochMillis)) {
            return TouchTurnCloseConfirmation.EXPIRED
        }
        val passes = closeConfirmsTurn(bracket, bar.close)
        return if (passes) TouchTurnCloseConfirmation.PASSED else TouchTurnCloseConfirmation.FAILED
    }

    /** True when [nowEpochMillis] is within one minute after the 15m bar close. */
    fun closeConfirmationWithinDeadline(
        candle: OhlcBar,
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val time = candle.time ?: return false
        val barEnd = barEndEpochMillis(time, marketZoneId) ?: return false
        return nowEpochMillis >= barEnd &&
            nowEpochMillis <= barEnd + TouchTurnDefaults.CLOSE_CONFIRMATION_AFTER_CLOSE_MS
    }

    fun closeConfirmationRemainingMillis(
        candle: OhlcBar?,
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Long? {
        val time = candle?.time ?: return null
        val barEnd = barEndEpochMillis(time, marketZoneId) ?: return null
        if (nowEpochMillis < barEnd) return null
        val deadline = barEnd + TouchTurnDefaults.CLOSE_CONFIRMATION_AFTER_CLOSE_MS
        return (deadline - nowEpochMillis).coerceAtLeast(0)
    }

    /**
     * Green liquidity bar (short): close below entry confirms the turn.
     * Red liquidity bar (long): close above entry confirms the turn.
     */
    fun closeConfirmsTurn(setup: TouchTurnBracketSetup, close: Double): Boolean = when (setup.candleColor) {
        FirstCandleColor.GREEN -> close < setup.entry
        FirstCandleColor.RED -> close > setup.entry
        FirstCandleColor.DOJI -> false
    }

    fun closePositionRatio(bar: OhlcBar): Double? {
        val range = bar.range
        if (range <= 0.0) return null
        return ((bar.close - bar.low) / range).coerceIn(0.0, 1.0)
    }

    fun entryWindowExpiredAlert(
        candle: OhlcBar?,
        marketZoneId: String
    ): String {
        val time = candle?.time ?: "unknown"
        return "Close confirmation must complete within 1 minute of the 15-minute bar close. " +
            "The confirmation window for bar $time has expired — no orders will be placed."
    }

    /**
     * RTH session open for Touch Turn: first 15m bar open time when known, otherwise 09:30 on [sessionDateIso]
     * in [marketZoneId] (US / HK regular session).
     */
    fun sessionDateIsoInMarketZone(
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): String {
        val zone = java.time.ZoneId.of(marketZoneId)
        return java.time.Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate().toString()
    }

    /** Cash equity sessions run Monday–Friday (weekends closed; holidays not excluded). */
    fun isRthTradingDay(date: java.time.LocalDate): Boolean {
        val day = date.dayOfWeek
        return day != java.time.DayOfWeek.SATURDAY && day != java.time.DayOfWeek.SUNDAY
    }

    fun nextRthTradingDay(date: java.time.LocalDate): java.time.LocalDate {
        var cursor = date
        while (!isRthTradingDay(cursor)) {
            cursor = cursor.plusDays(1)
        }
        return cursor
    }

    fun previousRthTradingDay(date: java.time.LocalDate): java.time.LocalDate {
        var cursor = date
        while (!isRthTradingDay(cursor)) {
            cursor = cursor.minusDays(1)
        }
        return cursor
    }

    /** Today's 09:30 RTH open in [marketZoneId] from wall-clock [nowEpochMillis]; null on weekends. */
    fun marketOpenEpochMillisForZone(
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Long? {
        val zone = java.time.ZoneId.of(marketZoneId)
        val date = java.time.Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()
        if (!isRthTradingDay(date)) return null
        return marketOpenEpochMillis(
            sessionDateIsoInMarketZone(marketZoneId, nowEpochMillis),
            marketZoneId,
            firstCandleBarTime = null
        )
    }

    fun marketOpenEpochMillis(
        sessionDateIso: String,
        marketZoneId: String,
        firstCandleBarTime: String? = null
    ): Long? {
        firstCandleBarTime?.let { barStartEpochMillis(it, marketZoneId) }?.let { return it }
        val session = RthMarketSessions.forZoneId(marketZoneId)
        return sessionOpenLocalDateTime(sessionDateIso, session.openHour, session.openMinute)
            ?.atZone(java.time.ZoneId.of(marketZoneId))
            ?.toInstant()
            ?.toEpochMilli()
    }

    /** Today's RTH cash session close in [marketZoneId] for [sessionDateIso]. */
    fun marketCloseEpochMillis(
        sessionDateIso: String,
        marketZoneId: String
    ): Long? {
        val session = RthMarketSessions.forZoneId(marketZoneId)
        val openLocal = sessionOpenLocalDateTime(sessionDateIso, session.openHour, session.openMinute) ?: return null
        val zone = java.time.ZoneId.of(marketZoneId)
        val date = openLocal.toLocalDate()
        return rthCloseOnDate(date, zone, session.closeHour, session.closeMinute)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Most recent RTH open at or before [nowEpochMillis]: today's session open if past it,
     * otherwise the previous calendar day's 09:30 (weekends/holidays not adjusted).
     */
    fun lastMarketOpenEpochMillis(
        sessionDateIso: String,
        marketZoneId: String,
        firstCandleBarTime: String? = null,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Long? {
        val todayOpen = marketOpenEpochMillis(sessionDateIso, marketZoneId, firstCandleBarTime) ?: return null
        if (nowEpochMillis >= todayOpen) return todayOpen
        val parts = sessionDateIso.trim().split("-")
        if (parts.size != 3) return null
        val session = RthMarketSessions.forZoneId(marketZoneId)
        return runCatching {
            val zone = java.time.ZoneId.of(marketZoneId)
            java.time.LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                .minusDays(1)
                .atTime(session.openHour, session.openMinute, 0)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    fun millisSinceLastMarketOpen(
        sessionDateIso: String,
        marketZoneId: String,
        firstCandleBarTime: String? = null,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Long? {
        val open = lastMarketOpenEpochMillis(sessionDateIso, marketZoneId, firstCandleBarTime, nowEpochMillis)
            ?: return null
        return (nowEpochMillis - open).coerceAtLeast(0)
    }

    fun formatDurationClock(millis: Long): String {
        val totalSec = millis / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return when {
            hours > 0 -> "${hours}h ${minutes.toString().padStart(2, '0')}m ${seconds.toString().padStart(2, '0')}s"
            minutes > 0 -> "${minutes}m ${seconds.toString().padStart(2, '0')}s"
            else -> "${seconds}s"
        }
    }

    fun formatElapsedSinceMarketOpen(elapsedMillis: Long): String = formatDurationClock(elapsedMillis)

    fun formatCountdownToNextMarketOpen(remainingMillis: Long): String = formatDurationClock(remainingMillis)

    /**
     * Next scheduled RTH open (09:30) in [marketZoneId] from wall-clock [nowEpochMillis].
     * Skips Saturday and Sunday in the market's local calendar (holidays not excluded).
     */
    fun nextMarketOpenEpochMillis(
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Long {
        val zone = java.time.ZoneId.of(marketZoneId)
        val now = java.time.Instant.ofEpochMilli(nowEpochMillis).atZone(zone)
        var date = now.toLocalDate()
        val session = RthMarketSessions.forZoneId(marketZoneId)
        if (!isRthTradingDay(date)) {
            date = nextRthTradingDay(date)
            return rthOpenOnDate(date, zone, session.openHour, session.openMinute).toInstant().toEpochMilli()
        }
        val todayOpen = rthOpenOnDate(date, zone, session.openHour, session.openMinute)
        if (now.isBefore(todayOpen)) {
            return todayOpen.toInstant().toEpochMilli()
        }
        date = nextRthTradingDay(date.plusDays(1))
        return rthOpenOnDate(date, zone, session.openHour, session.openMinute).toInstant().toEpochMilli()
    }

    fun millisUntilNextMarketOpen(
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Long {
        val next = nextMarketOpenEpochMillis(marketZoneId, nowEpochMillis)
        return (next - nowEpochMillis).coerceAtLeast(0)
    }

    /** Whether wall-clock time is within today's RTH cash session (09:30–close local). */
    fun isRthMarketOpen(
        session: RthMarketSession,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Boolean = isRthMarketOpen(
        zoneId = session.zoneId,
        openHour = session.openHour,
        openMinute = session.openMinute,
        closeHour = session.closeHour,
        closeMinute = session.closeMinute,
        nowEpochMillis = nowEpochMillis
    )

    fun isRthMarketOpen(
        zoneId: String,
        openHour: Int,
        openMinute: Int,
        closeHour: Int,
        closeMinute: Int,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val zone = java.time.ZoneId.of(zoneId)
        val now = java.time.Instant.ofEpochMilli(nowEpochMillis).atZone(zone)
        if (!isRthTradingDay(now.toLocalDate())) return false
        val todayOpen = rthOpenOnDate(now.toLocalDate(), zone, openHour, openMinute)
        val todayClose = rthCloseOnDate(now.toLocalDate(), zone, closeHour, closeMinute)
        return !now.isBefore(todayOpen) && now.isBefore(todayClose)
    }

    /** Most recent 09:30 RTH open in [marketZoneId] from wall-clock [nowEpochMillis] (skips weekends). */
    fun lastMarketOpenEpochMillisWallClock(
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Long {
        val session = RthMarketSessions.forZoneId(marketZoneId)
        val zone = java.time.ZoneId.of(marketZoneId)
        val now = java.time.Instant.ofEpochMilli(nowEpochMillis).atZone(zone)
        var date = now.toLocalDate()
        if (!isRthTradingDay(date)) {
            date = previousRthTradingDay(date)
            return rthOpenOnDate(date, zone, session.openHour, session.openMinute).toInstant().toEpochMilli()
        }
        val todayOpen = rthOpenOnDate(date, zone, session.openHour, session.openMinute)
        val sessionDate = if (!now.isBefore(todayOpen)) {
            date
        } else {
            previousRthTradingDay(date.minusDays(1))
        }
        return rthOpenOnDate(sessionDate, zone, session.openHour, session.openMinute).toInstant().toEpochMilli()
    }

    fun millisSinceLastMarketOpenWallClock(
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Long {
        val last = lastMarketOpenEpochMillisWallClock(marketZoneId, nowEpochMillis)
        return (nowEpochMillis - last).coerceAtLeast(0)
    }

    fun nextMarketOpenLocalLabel(
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): String {
        val zone = java.time.ZoneId.of(marketZoneId)
        val next = java.time.Instant.ofEpochMilli(nextMarketOpenEpochMillis(marketZoneId, nowEpochMillis))
            .atZone(zone)
        val time = next.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        return "$time ${marketOpenZoneAbbrev(marketZoneId)}"
    }

    fun marketOpenZoneAbbrev(marketZoneId: String): String = when (marketZoneId) {
        "Asia/Hong_Kong" -> "HKT"
        "America/New_York" -> "ET"
        "Europe/London", "Europe/Berlin" -> "UK"
        else -> marketZoneId
    }

    private fun rthOpenOnDate(
        date: java.time.LocalDate,
        zone: java.time.ZoneId,
        openHour: Int,
        openMinute: Int
    ): java.time.ZonedDateTime =
        date.atTime(openHour, openMinute, 0).atZone(zone)

    private fun rthCloseOnDate(
        date: java.time.LocalDate,
        zone: java.time.ZoneId,
        closeHour: Int,
        closeMinute: Int
    ): java.time.ZonedDateTime =
        date.atTime(closeHour, closeMinute, 0).atZone(zone)

    private fun sessionOpenLocalDateTime(
        sessionDateIso: String,
        openHour: Int,
        openMinute: Int
    ): java.time.LocalDateTime? {
        val parts = sessionDateIso.trim().split("-")
        if (parts.size != 3) return null
        return runCatching {
            java.time.LocalDateTime.of(
                parts[0].toInt(),
                parts[1].toInt(),
                parts[2].toInt(),
                openHour,
                openMinute,
                0
            )
        }.getOrNull()
    }

    /**
     * Liquidity candle ⇔ range (high − low) > [rangeThreshold] (exceeds 25% of 14-day ADR).
     * Only computed once the bar has closed; while forming, returns [LiquidityCandleEvaluation.AWAITING_CLOSE].
     */
    fun liquidityCandleEvaluation(
        candle: OhlcBar?,
        marketZoneId: String,
        rangeThreshold: Double,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): LiquidityCandleEvaluation {
        return when (firstCandleCloseStatus(candle, marketZoneId, nowEpochMillis)) {
            FirstCandleCloseStatus.FORMING -> LiquidityCandleEvaluation.AWAITING_CLOSE
            FirstCandleCloseStatus.UNKNOWN -> LiquidityCandleEvaluation.UNKNOWN
            FirstCandleCloseStatus.CLOSED -> {
                val bar = candle ?: return LiquidityCandleEvaluation.UNKNOWN
                if (isLiquidityCandle(bar, rangeThreshold)) {
                    LiquidityCandleEvaluation.LIQUIDITY
                } else {
                    LiquidityCandleEvaluation.NOT_LIQUIDITY
                }
            }
        }
    }

    fun isLiquidityCandle(bar: OhlcBar, rangeThreshold: Double): Boolean = bar.range > rangeThreshold

    fun liquidityEvaluationLabel(evaluation: LiquidityCandleEvaluation): String = when (evaluation) {
        LiquidityCandleEvaluation.AWAITING_CLOSE -> "Liquidity: pending (candle still forming)"
        LiquidityCandleEvaluation.LIQUIDITY -> "Liquidity candle"
        LiquidityCandleEvaluation.NOT_LIQUIDITY -> "Not a liquidity candle"
        LiquidityCandleEvaluation.UNKNOWN -> "Liquidity: unknown"
    }

    /**
     * Green/red candle from [OhlcBar.open] (day open) and [OhlcBar.close] (15m bar close):
     * - Green: close > open
     * - Red: close < open
     * - Doji: close == open
     */
    fun firstCandleColor(bar: OhlcBar): FirstCandleColor = when {
        bar.close > bar.open -> FirstCandleColor.GREEN
        bar.close < bar.open -> FirstCandleColor.RED
        else -> FirstCandleColor.DOJI
    }

    fun candleColorLabel(color: FirstCandleColor): String = when (color) {
        FirstCandleColor.GREEN -> "Green candle (close > open)"
        FirstCandleColor.RED -> "Red candle (close < open)"
        FirstCandleColor.DOJI -> "Flat candle (close = open)"
    }

    /** Epoch millis when the 15-minute bar completes (exclusive end = start + 15m). */
    fun barEndEpochMillis(barTime: String, marketZoneId: String): Long? =
        barStartEpochMillis(barTime, marketZoneId)?.plus(BAR_DURATION_MS)

    /**
     * ADR = mean daily range (high − low) over the last [TouchTurnDefaults.ADR_LOOKBACK_DAYS]
     * completed sessions. Excludes today's in-progress daily bar when [excludeSessionDayYyyyMmdd] is set.
     */
    fun computeAdr14(
        dailyBars: List<OhlcBar>,
        excludeSessionDayYyyyMmdd: String? = null
    ): Result<Double> {
        val valid = dailyBars
            .filter { it.high > 0.0 && it.low > 0.0 && it.high >= it.low }
            .filter { bar ->
                val day = barDayKey(bar.time) ?: return@filter false
                excludeSessionDayYyyyMmdd == null || day != excludeSessionDayYyyyMmdd
            }
            .sortedBy { barDayKey(it.time).orEmpty() }
        val lastSessions = valid.takeLast(TouchTurnDefaults.ADR_LOOKBACK_DAYS)
        if (lastSessions.size < TouchTurnDefaults.ADR_LOOKBACK_DAYS) {
            return Result.failure(
                IllegalStateException(
                    "Need ${TouchTurnDefaults.ADR_LOOKBACK_DAYS} completed daily bars for ADR, got ${lastSessions.size}"
                )
            )
        }
        val adr = lastSessions.map { it.range }.average()
        return Result.success(adr)
    }

    fun liquidityRangeThreshold(adr14: Double): Double = adr14 * TouchTurnDefaults.ADR_LIQUIDITY_RATIO

    fun barDayKey(barTime: String?): String? {
        val trimmed = barTime?.trim() ?: return null
        val match = Regex("""(\d{8})""").find(trimmed) ?: return null
        return match.groupValues[1]
    }

    fun barStartEpochMillis(barTime: String, marketZoneId: String): Long? {
        val normalized = normalizeIbBarTimeToMarketZone(barTime, marketZoneId) ?: return null
        val match = IB_BAR_TIME_REGEX.find(normalized.trim()) ?: return null
        val (year, month, day, hour, minute, second) = match.destructured
        return runCatching {
            val zone = java.time.ZoneId.of(marketZoneId)
            java.time.LocalDateTime.of(
                year.toInt(),
                month.toInt(),
                day.toInt(),
                hour.toInt(),
                minute.toInt(),
                second.toInt()
            ).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /** IB historical bar open timestamp, e.g. `20250522  09:30:00`. */
    fun formatIbBarOpenTime(epochMillis: Long, marketZoneId: String): String {
        val zdt = java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.of(marketZoneId))
        return "%04d%02d%02d  %02d:%02d:%02d".format(
            zdt.year,
            zdt.monthValue,
            zdt.dayOfMonth,
            zdt.hour,
            zdt.minute,
            zdt.second
        )
    }

    /**
     * @param rangeThreshold Min bar range (high − low) for a liquidity candle, e.g. 25% of 14-day ADR.
     * @param minStopDistance Minimum entry-to-stop distance (spread / noise protection).
     */
    fun computeBracketSetup(
        bar: OhlcBar,
        rangeThreshold: Double,
        minStopDistance: Double = TouchTurnDefaults.MIN_STOP_DISTANCE
    ): TouchTurnBracketSetup {
        val range = bar.range
        val color = firstCandleColor(bar)
        val liquidity = isLiquidityCandle(bar, rangeThreshold)
        return when (color) {
            FirstCandleColor.GREEN -> {
                val tpDistance = range * TouchTurnDefaults.TAKE_PROFIT_FIB_RATIO_GREEN
                val entry = bar.high
                val takeProfit = entry - tpDistance
                val stopDistance = maxOf(tpDistance / 2.0, minStopDistance)
                TouchTurnBracketSetup(
                    range = range,
                    rangeThreshold = rangeThreshold,
                    isLiquidityCandle = liquidity,
                    candleColor = color,
                    side = TouchTurnTradeSide.SHORT,
                    entry = entry,
                    stopLoss = entry + stopDistance,
                    takeProfit = takeProfit
                )
            }
            FirstCandleColor.RED -> {
                val tpDistance = range * TouchTurnDefaults.TAKE_PROFIT_FIB_RATIO_RED
                val entry = bar.low
                val takeProfit = entry + tpDistance
                val stopDistance = maxOf(tpDistance / 2.0, minStopDistance)
                TouchTurnBracketSetup(
                    range = range,
                    rangeThreshold = rangeThreshold,
                    isLiquidityCandle = liquidity,
                    candleColor = color,
                    side = TouchTurnTradeSide.LONG,
                    entry = entry,
                    stopLoss = entry - stopDistance,
                    takeProfit = takeProfit
                )
            }
            FirstCandleColor.DOJI -> TouchTurnBracketSetup(
                range = range,
                rangeThreshold = rangeThreshold,
                isLiquidityCandle = liquidity,
                candleColor = color,
                side = TouchTurnTradeSide.LONG,
                entry = bar.close,
                stopLoss = bar.close,
                takeProfit = bar.close
            )
        }
    }

    fun tradeSideLabel(side: TouchTurnTradeSide): String = when (side) {
        TouchTurnTradeSide.LONG -> "Long"
        TouchTurnTradeSide.SHORT -> "Short"
    }

    fun takeProfitFibLabel(color: FirstCandleColor): String = when (color) {
        FirstCandleColor.GREEN -> "61.8%"
        FirstCandleColor.RED -> "38.2%"
        FirstCandleColor.DOJI -> "—"
    }

    fun orderPreviewSummary(setup: TouchTurnBracketSetup): String {
        val action = tradeSideLabel(setup.side).lowercase()
        val fibPct = takeProfitFibLabel(setup.candleColor)
        return when (setup.candleColor) {
            FirstCandleColor.GREEN ->
                "Green liquidity bar → $action at bar high, take profit at $fibPct of range below entry, stop half that distance above high."
            FirstCandleColor.RED ->
                "Red liquidity bar → $action at bar low, take profit at $fibPct of range above entry, stop half that distance below low."
            FirstCandleColor.DOJI -> "Flat candle (open = close) — no directional bracket."
        }
    }
}

object TouchTurnDefaults {
    const val ADR_LOOKBACK_DAYS = 14
    /** Liquidity when first 15m range exceeds this fraction of 14-day ADR. */
    const val ADR_LIQUIDITY_RATIO = 0.25
    const val MIN_STOP_DISTANCE = 0.05
    /** Green (short) liquidity bar: take-profit distance as fraction of bar range. */
    const val TAKE_PROFIT_FIB_RATIO_GREEN = 0.618
    /** Red (long) liquidity bar: take-profit distance as fraction of bar range. */
    const val TAKE_PROFIT_FIB_RATIO_RED = 0.382
    /** Max time after 15m bar close to pass close confirmation and place entry orders. */
    const val CLOSE_CONFIRMATION_AFTER_CLOSE_MS = 60_000L
    @Deprecated("Use CLOSE_CONFIRMATION_AFTER_CLOSE_MS", ReplaceWith("CLOSE_CONFIRMATION_AFTER_CLOSE_MS"))
    const val ENTRY_WINDOW_AFTER_CLOSE_MS = CLOSE_CONFIRMATION_AFTER_CLOSE_MS
    /** For short setups (green liquidity candle), require close in the lower X of range. */
    const val CLOSE_POSITION_SHORT_MAX = 0.35
    /** For long setups (red liquidity candle), require close in the upper X of range. */
    const val CLOSE_POSITION_LONG_MIN = 0.65
    const val RTH_SESSION_OPEN_HOUR = 9
    const val RTH_SESSION_OPEN_MINUTE = 30
}

fun StrategyDeployment.beginTouchTurnSession(sessionDate: String): StrategyDeployment {
    if (strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return this
    val startedAt = inProgressSession()?.startedAt ?: currentSessionTimestampIso()
    return copy(
        touchTurnSession = TouchTurnSessionContext(
            sessionDate = sessionDate,
            status = TouchTurnCandleStatus.LOADING,
            milestones = TouchTurnMilestoneTimestamps(startingSessionAt = startedAt)
        )
    )
}

fun StrategyDeployment.withTouchTurnCandle(
    sessionDate: String,
    candle: OhlcBar,
    adr14: Double,
    rangeThreshold: Double = TouchTurnLogic.liquidityRangeThreshold(adr14)
): StrategyDeployment {
    if (strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return this
    val setup = TouchTurnLogic.computeBracketSetup(candle, rangeThreshold)
    return copy(
        touchTurnSession = TouchTurnSessionContext(
            sessionDate = sessionDate,
            status = TouchTurnCandleStatus.READY,
            candle = candle,
            setup = setup,
            adr14 = adr14,
            rangeThreshold = rangeThreshold
        )
    )
}

/** Stores fetched first 15-minute candle only (no bracket setup until the bar closes). */
fun StrategyDeployment.withFirstFifteenMinuteCandle(
    sessionDate: String,
    candle: OhlcBar,
    adr14: Double,
    currencyCode: String = "USD",
    marketZoneId: String = "America/New_York"
): StrategyDeployment {
    if (strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return this
    val threshold = TouchTurnLogic.liquidityRangeThreshold(adr14)
    val prior = touchTurnSession?.milestones
    val at = currentSessionTimestampIso()
    return copy(
        touchTurnSession = TouchTurnSessionContext(
            sessionDate = sessionDate,
            status = TouchTurnCandleStatus.READY,
            candle = candle,
            currencyCode = currencyCode,
            marketZoneId = marketZoneId,
            adr14 = adr14,
            rangeThreshold = threshold,
            milestones = TouchTurnMilestoneTimestamps(
                startingSessionAt = prior?.startingSessionAt ?: inProgressSession()?.startedAt ?: at,
                dataReadyAt = at
            )
        )
    )
}

/** Persists bracket setup and liquidity flag once the first candle has closed. */
fun StrategyDeployment.withLiquidityEvaluatedIfClosed(
    enforceCloseConfirmation: Boolean = true,
    nowEpochMillis: Long = System.currentTimeMillis()
): StrategyDeployment {
    if (strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return this
    val session = touchTurnSession ?: return this
    val candle = session.candle ?: return this
    if (session.candleCloseStatus(nowEpochMillis) != FirstCandleCloseStatus.CLOSED) return this
    if (session.setup != null) return this
    val setup = TouchTurnLogic.computeBracketSetup(candle, session.rangeThreshold)
    val closeConfirmation = TouchTurnLogic.closeConfirmation(candle, setup, session.marketZoneId, nowEpochMillis)
    val entryOrdersPermitted = setup.isLiquidityCandle &&
        setup.isActionable &&
        (!enforceCloseConfirmation || closeConfirmation == TouchTurnCloseConfirmation.PASSED)
    val decisionOutcome = when {
        !setup.isLiquidityCandle -> TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY
        !setup.isActionable -> TouchTurnSessionOutcome.NO_TRADE_DOJI
        enforceCloseConfirmation && closeConfirmation == TouchTurnCloseConfirmation.EXPIRED ->
            TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED
        enforceCloseConfirmation && closeConfirmation == TouchTurnCloseConfirmation.FAILED ->
            TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED
        else -> null
    }
    val at = currentSessionTimestampIso()
    val milestones = session.milestones.let { m ->
        m.copy(
            barClosedAt = m.barClosedAt ?: at,
            liquidityEvaluatedAt = at,
            closeConfirmedAt = m.closeConfirmedAt
                ?: if (closeConfirmation == TouchTurnCloseConfirmation.PASSED) at else null
        )
    }
    val updatedSession = session.copy(
        setup = setup,
        entryOrdersPermitted = entryOrdersPermitted,
        decisionOutcome = decisionOutcome ?: session.decisionOutcome,
        milestones = milestones
    )
    TouchTurnDecisionLog.liquidityEvaluated(
        instanceId = id,
        symbol = symbol,
        session = updatedSession,
        setup = setup,
        enforceCloseConfirmation = enforceCloseConfirmation,
        closeConfirmation = closeConfirmation,
        entryOrdersPermitted = entryOrdersPermitted,
        decisionOutcome = updatedSession.decisionOutcome,
        nowEpochMillis = nowEpochMillis
    )
    return copy(touchTurnSession = updatedSession)
}

fun StrategyDeployment.withTouchTurnDecisionOutcome(outcome: TouchTurnSessionOutcome): StrategyDeployment {
    if (strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return this
    val session = touchTurnSession ?: return this
    if (session.decisionOutcome != null) return this
    return copy(touchTurnSession = session.copy(decisionOutcome = outcome))
}

fun StrategyDeployment.withOrdersPlacedForSession(plan: TouchTurnOrderPlan? = null): StrategyDeployment {
    if (strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return this
    val session = touchTurnSession ?: return this
    val at = currentSessionTimestampIso()
    val milestones = session.milestones.copy(
        ordersPlacedAt = session.milestones.ordersPlacedAt ?: at
    )
    val bracket = plan?.toPlannedBracket()
    return copy(
        touchTurnSession = session.copy(
            ordersPlacedForSession = true,
            decisionOutcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
            plannedQuantity = plan?.quantity ?: session.plannedQuantity,
            plannedBracket = bracket ?: session.plannedBracket,
            milestones = milestones
        )
    )
}

/** Records when the broker first reports an open position for this run. */
fun StrategyDeployment.withTouchTurnPositionOpenedIfNeeded(): StrategyDeployment {
    if (strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return this
    val session = touchTurnSession ?: return this
    if (session.milestones.positionOpenedAt != null) return this
    val at = currentSessionTimestampIso()
    return copy(
        touchTurnSession = session.copy(
            milestones = session.milestones.copy(positionOpenedAt = at)
        )
    )
}

/** Records when the run enters the closing / auto-stop phase. */
fun StrategyDeployment.withTouchTurnClosingMilestoneIfNeeded(): StrategyDeployment {
    if (strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return this
    val session = touchTurnSession ?: return this
    if (session.milestones.closingSessionAt != null) return this
    val at = currentSessionTimestampIso()
    return copy(
        touchTurnSession = session.copy(
            milestones = session.milestones.copy(closingSessionAt = at)
        )
    )
}

fun StrategyDeployment.withNoPositionBracketCancelEvaluated(
    outcome: TouchTurnNoPositionCancelOutcome
): StrategyDeployment {
    if (strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return this
    val session = touchTurnSession ?: return this
    return copy(touchTurnSession = session.copy(noPositionBracketCancelOutcome = outcome))
}

fun StrategyDeployment.withTouchTurnCandleFailed(
    sessionDate: String,
    message: String
): StrategyDeployment {
    if (strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return this
    val prior = touchTurnSession?.milestones
    val at = currentSessionTimestampIso()
    return copy(
        touchTurnSession = TouchTurnSessionContext(
            sessionDate = sessionDate,
            status = TouchTurnCandleStatus.FAILED,
            errorMessage = message,
            decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
            milestones = TouchTurnMilestoneTimestamps(
                startingSessionAt = prior?.startingSessionAt ?: inProgressSession()?.startedAt ?: at,
                dataFailedAt = at
            )
        )
    )
}

/**
 * Single post-stop session for pipeline UI, recap chart, and close panel.
 * Prefers the latest closed run with broker fills; otherwise the latest closed run with milestones.
 */
fun StrategyDeployment.touchTurnPostStopSession(): StrategySession? {
    if (strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return null
    inProgressSession()?.takeIf { it.sessionTrades.isNotEmpty() }?.let { return it }
    sessionHistory
        .filter { it.status == SessionStatus.CLOSED && it.sessionTrades.isNotEmpty() }
        .maxByOrNull { it.stoppedAt.ifBlank { it.startedAt } }
        ?.let { return it }
    return sessionHistory
        .filter {
            it.status == SessionStatus.CLOSED &&
                (it.touchTurnMilestones != null || it.touchTurnRunRecord != null)
        }
        .maxByOrNull { it.stoppedAt.ifBlank { it.startedAt } }
}

/** Most recent closed Touch Turn run used for pipeline / recap / close. */
fun StrategyDeployment.lastClosedTouchTurnSession(): StrategySession? = touchTurnPostStopSession()

/** Closed or in-progress run whose fills power the post-session Orders recap chart. */
fun StrategyDeployment.touchTurnRecapSessionRun(): StrategySession? = touchTurnPostStopSession()

/** Broker fills for the Trading tab order recap chart (running or most recent closed run with fills). */
fun StrategyDeployment.touchTurnRecapSessionTrades(): List<SessionTrade> =
    touchTurnRecapSessionRun()?.sessionTrades.orEmpty()

/** Realized P&L for the recap chart — from fills on the same run as [touchTurnRecapSessionTrades]. */
fun StrategyDeployment.touchTurnRecapSessionPnl(): Double? {
    val trades = touchTurnRecapSessionTrades()
    if (trades.isEmpty()) return null
    val fromFills = trades.sessionRealizedPnL()
    if (fromFills != 0.0) return fromFills
    return touchTurnRecapSessionRun()?.pnl
}

/**
 * Live or last-closed session context for the Trading tab pipeline detail panels.
 * After stop, [touchTurnSession] is cleared but opening bar / ADR are restored from [StrategySession.touchTurnRunRecord].
 */
fun StrategyDeployment.touchTurnAnalysisSession(): TouchTurnSessionContext? {
    touchTurnSession?.let { return it }
    return touchTurnPostStopSession()?.toTouchTurnAnalysisContext()
}

fun StrategySession.toTouchTurnAnalysisContext(): TouchTurnSessionContext? {
    val record = touchTurnRunRecord
    val milestones = touchTurnMilestones ?: record?.milestones ?: return null
    val inputs = record?.marketInputs
    val candle = inputs?.openingBar
    val adr = inputs?.adr14
    val threshold = adr?.let { TouchTurnLogic.liquidityRangeThreshold(it) } ?: 0.0
    val setup = candle?.let { TouchTurnLogic.computeBracketSetup(it, threshold) }
    val plannedBracket = record?.decision?.plannedBracket
    val outcome = record?.decision?.outcome
    val executedBracketLegs = record?.decision?.executedLegs?.takeIf { it.isNotEmpty() }
        ?: TouchTurnBracketExecution.resolveFromTrades(
            trades = sessionTrades,
            plannedBracket = plannedBracket,
            bracketSetup = setup,
            sessionPnl = pnl.takeIf { sessionTrades.isNotEmpty() }
        )
    return TouchTurnSessionContext(
        sessionDate = date,
        status = when {
            inputs?.dataErrorMessage != null -> TouchTurnCandleStatus.FAILED
            candle == null -> TouchTurnCandleStatus.LOADING
            else -> TouchTurnCandleStatus.READY
        },
        candle = candle,
        setup = setup,
        errorMessage = inputs?.dataErrorMessage,
        milestones = milestones,
        currencyCode = inputs?.currencyCode ?: "USD",
        marketZoneId = inputs?.marketZoneId ?: "America/New_York",
        adr14 = adr,
        rangeThreshold = threshold,
        entryOrdersPermitted = when (outcome) {
            TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED -> true
            TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
            TouchTurnSessionOutcome.NO_TRADE_DOJI,
            TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
            TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED,
            TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED -> false
            else -> hadLiquidityCandle == true && setup?.isActionable == true
        },
        ordersPlacedForSession = ordersPlacedForCandle == true ||
            outcome == TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
        decisionOutcome = outcome,
        plannedQuantity = record?.decision?.plannedQuantity,
        plannedBracket = plannedBracket,
        executedBracketLegs = executedBracketLegs
    )
}
