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
/** Result of validating a post-close historical refetch before liquidity evaluation. */
enum class ClosedFirstCandleRefetchValidation {
    /** Bar is final, matches the opening bar anchor, and safe to persist. */
    READY,
    /** IB may still be updating the bar — retry refetch after a short delay. */
    NOT_YET_FINAL,
    /** Unrecoverable (invalid OHLC, wrong bar, etc.). */
    REJECTED
}

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
    val time: String? = null,
    val volume: Double = 0.0
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
 * Green bar → short at high, TP at 38.2% of range; red bar → long at low, TP at 38.2% of range.
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
    /**
     * IB opening 15m bar open time (e.g. `20260603  09:30:00`) for close timing while [candle] is null.
     * Set at data-ready; [candle] OHLC is filled only after the bar has closed and history is refetched.
     */
    val openingBarTime: String? = null,
    val candle: OhlcBar? = null,
    val setup: TouchTurnBracketSetup? = null,
    val errorMessage: String? = null,
    val milestones: TouchTurnMilestoneTimestamps = TouchTurnMilestoneTimestamps(),
    /** ISO currency for price display (e.g. HKD on SEHK). */
    val currencyCode: String = "USD",
    /** IANA zone for bar close time (e.g. Asia/Hong_Kong). */
    val marketZoneId: String = "America/New_York",
    /** Average daily range (high − low) over the last 14 completed sessions (legacy display). */
    val adr14: Double? = null,
    /** 14-period ATR on 15-minute bars used for liquidity range threshold. */
    val atr14: Double? = null,
    /** 20-period SMA of prior session-opening 15m bar volume (apples-to-apples vs today's open). */
    val volumeSma20: Double? = null,
    /** Liquidity threshold = [atr14] × [TouchTurnDefaults.ATR_LIQUIDITY_RATIO] (25%). */
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
    val executedBracketLegs: List<TouchTurnOrderRole> = emptyList(),
    /** Rule thresholds snapshotted from deployment at session start. */
    val rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
) {
    fun sessionOrdersPlaced(): Boolean = ordersPlacedForSession || entryOrdersPermitted == true

    /** Bar-close milestone was set but closed-bar OHLC refetch never succeeded. */
    fun failedDuringLiquidityRefetch(): Boolean =
        status == TouchTurnCandleStatus.FAILED &&
            decisionOutcome == TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED &&
            milestones.barClosedAt != null &&
            milestones.liquidityEvaluatedAt == null
    val liquidityThresholdFromAtr: Double?
        get() = atr14?.let { TouchTurnLogic.liquidityRangeThresholdFromAtr(it, rules) }

    @Deprecated("Use liquidityThresholdFromAtr", ReplaceWith("liquidityThresholdFromAtr"))
    val liquidityThresholdFromAdr: Double?
        get() = liquidityThresholdFromAtr
    fun resolvedOpeningBarTime(): String? = candle?.time ?: openingBarTime

    fun candleCloseStatus(nowEpochMillis: Long = System.currentTimeMillis()): FirstCandleCloseStatus =
        TouchTurnLogic.firstCandleCloseStatus(
            resolvedOpeningBarTime(),
            marketZoneId,
            nowEpochMillis,
            sessionDate
        )

    fun liquidityEvaluation(nowEpochMillis: Long = System.currentTimeMillis()): LiquidityCandleEvaluation =
        TouchTurnLogic.liquidityCandleEvaluation(
            candle,
            resolvedOpeningBarTime(),
            marketZoneId,
            rangeThreshold,
            nowEpochMillis,
            sessionDate
        )

    fun firstCandleColor(): FirstCandleColor? = candle?.let { TouchTurnLogic.firstCandleColor(it) }

    fun entryWindowStatus(nowEpochMillis: Long = System.currentTimeMillis()): TouchTurnEntryWindowStatus =
        TouchTurnLogic.entryWindowStatus(resolvedOpeningBarTime(), marketZoneId, nowEpochMillis)

    fun closeConfirmation(nowEpochMillis: Long = System.currentTimeMillis()): TouchTurnCloseConfirmation =
        TouchTurnLogic.closeConfirmation(candle, setup, marketZoneId, nowEpochMillis, sessionDate, rules)

    /**
     * Close confirmation for pipeline / UI — mirrors engine gates after liquidity evaluation.
     * Strict [closeConfirmation] may still fail (e.g. turn-zone rules) while [entryOrdersPermitted]
     * is true on the emulator happy path; the UI follows [entryOrdersPermitted] and [decisionOutcome].
     */
    fun pipelineCloseConfirmation(nowEpochMillis: Long = System.currentTimeMillis()): TouchTurnCloseConfirmation {
        if (ordersPlacedForSession ||
            decisionOutcome == TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED ||
            milestones.closeConfirmedAt != null
        ) {
            return TouchTurnCloseConfirmation.PASSED
        }
        when (entryOrdersPermitted) {
            true -> return TouchTurnCloseConfirmation.PASSED
            false -> return when (decisionOutcome) {
                TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
                TouchTurnSessionOutcome.NO_TRADE_LIVE_CLOSE_CONFIRMATION_FAILED,
                TouchTurnSessionOutcome.NO_TRADE_BAR_LIVE_DIVERGENCE ->
                    TouchTurnCloseConfirmation.FAILED
                TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED ->
                    TouchTurnCloseConfirmation.EXPIRED
                else -> closeConfirmation(nowEpochMillis)
            }
            null -> return closeConfirmation(nowEpochMillis)
        }
    }

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
     * Converts the timestamp into [marketZoneId] for bar-close logic (MET → London is −1h vs Berlin).
     */
    fun normalizeIbBarTimeToMarketZone(barTime: String?, marketZoneId: String): String? {
        val trimmed = barTime?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (ibBarTimeLooksMarketLocal(trimmed)) return trimmed
        val match = IB_BAR_TIME_WITH_SUFFIX_REGEX.find(trimmed) ?: return trimmed
        val (year, month, day, hour, minute, second, suffix) = match.destructured
        val sourceZoneId = ibHistoricalBarSourceZoneId(suffix, marketZoneId)
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

    /**
     * Zone IB used when stamping the bar open time, before conversion into [marketZoneId].
     * UK LSE history often arrives as `09:00:00 MET` (Middle European) for the 08:00 London open.
     */
    fun ibHistoricalBarSourceZoneId(suffix: String?, marketZoneId: String): String {
        val trimmedSuffix = suffix?.trim()?.takeIf { it.isNotEmpty() }
        trimmedSuffix?.let { ibBarTimeSuffixToZoneId(it) }?.let { return it }
        if (marketZoneId == RthMarketSessions.EUR.zoneId) {
            // Missing or unknown suffix on raw IB UK bars — wall clock is MET, not London local.
            return "Europe/Berlin"
        }
        return marketZoneId
    }

    fun ibBarTimeSuffixToZoneId(suffix: String): String? = when (suffix.trim().uppercase()) {
        "MET", "MEZ", "MEST", "CET", "CEST", "EET", "EEST", "WET", "WESTERN EUROPEAN" -> "Europe/Berlin"
        "UTC", "GMT" -> "UTC"
        "US/EASTERN", "EST", "EDT", "ET" -> "America/New_York"
        "LON", "BST", "GB", "UK", "LSE" -> "Europe/London"
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
        selectBarNearestScheduledOpen(normalized, expectedOpenMillis, marketZoneId)?.let { return it }
        // Emulator accelerated bars are the latest session-day candle, not scheduled RTH open.
        return normalized.maxByOrNull { barTimeSortKey(it.time) }
    }

    /** Picks the session-day bar whose open is closest to scheduled RTH open (after normalization). */
    fun selectBarNearestScheduledOpen(
        sessionDayBars: List<OhlcBar>,
        expectedOpenEpochMillis: Long?,
        marketZoneId: String
    ): OhlcBar? {
        val anchor = expectedOpenEpochMillis ?: return null
        return sessionDayBars.minByOrNull { bar ->
            val start = bar.time?.let { barStartEpochMillis(it, marketZoneId) } ?: Long.MAX_VALUE
            kotlin.math.abs(start - anchor)
        }
    }

    /**
     * Prior-session opening bar for volume SMA. Uses [selectFirstFifteenMinuteBar]; when IB returns
     * zero volume on the scheduled open slot, falls back to the same-day 15m bar with volume
     * nearest the RTH open.
     */
    fun resolveSessionOpeningFifteenMinuteBar(
        bars: List<OhlcBar>,
        marketZoneId: String,
        sessionDayYyyyMmdd: String
    ): OhlcBar? {
        val primary = selectFirstFifteenMinuteBar(bars, marketZoneId, sessionDayYyyyMmdd) ?: return null
        if (primary.volume > 0.0) return primary
        val normalized = bars
            .filter { it.high > 0.0 && it.low > 0.0 && it.high >= it.low }
            .map { bar -> bar.copy(time = normalizeIbBarTimeToMarketZone(bar.time, marketZoneId)) }
            .filter { barDayKey(it.time) == sessionDayYyyyMmdd }
        val withVolume = normalized.filter { it.volume > 0.0 }
        if (withVolume.isEmpty()) return primary
        val sessionDateIso = sessionDayYyyyMmdd.let { ymd ->
            runCatching {
                val y = ymd.substring(0, 4).toInt()
                val m = ymd.substring(4, 6).toInt()
                val d = ymd.substring(6, 8).toInt()
                "%04d-%02d-%02d".format(y, m, d)
            }.getOrNull()
        }
        val expectedOpenMillis = sessionDateIso?.let {
            marketOpenEpochMillis(it, marketZoneId, firstCandleBarTime = null)
        }
        if (expectedOpenMillis == null) {
            return withVolume.minByOrNull { barTimeSortKey(it.time) } ?: primary
        }
        return withVolume.minByOrNull { bar ->
            val start = bar.time?.let { barStartEpochMillis(it, marketZoneId) } ?: Long.MAX_VALUE
            kotlin.math.abs(start - expectedOpenMillis)
        } ?: primary
    }

    fun barTimeSortKey(time: String?): String = time?.trim().orEmpty()

    fun normalizedBarTimesEqual(
        expected: String?,
        actual: String?,
        marketZoneId: String
    ): Boolean {
        if (expected == null || actual == null) return true
        val normExpected = normalizeIbBarTimeToMarketZone(expected, marketZoneId)
        val normActual = normalizeIbBarTimeToMarketZone(actual, marketZoneId)
        return normExpected == normActual
    }

    /**
     * Milliseconds until the opening 15m bar is considered settled enough for a post-close IB refetch.
     * Returns 0 when [openingBarTime] is unknown or the settle window has already elapsed.
     */
    fun millisUntilClosedBarRefetchReady(
        openingBarTime: String?,
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
        settleMs: Long = TouchTurnDefaults.CLOSED_BAR_REFETCH_SETTLE_MS
    ): Long {
        val barTime = openingBarTime?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
        val barEnd = barEndEpochMillis(barTime, marketZoneId) ?: return 0L
        return (barEnd + settleMs - nowEpochMillis).coerceAtLeast(0)
    }

    /**
     * Validates OHLC from [fetchTouchTurnSignalContext] after the opening bar has closed.
     * Prevents liquidity evaluation on IB's still-forming or mismatched 15m snapshot.
     */
    /**
     * True when two IB bar open timestamps refer to the same 15-minute period (end times within [maxEndSkewMs]).
     * Handles broker/emulator open-time drift (e.g. 14:08:29 vs 14:08:57) without accepting a later bar slot.
     */
    fun openingBarPeriodEndsEqual(
        barTimeA: String,
        barTimeB: String,
        marketZoneId: String,
        maxEndSkewMs: Long = 90_000L
    ): Boolean {
        val endA = barEndEpochMillis(barTimeA, marketZoneId) ?: return false
        val endB = barEndEpochMillis(barTimeB, marketZoneId) ?: return false
        return kotlin.math.abs(endA - endB) <= maxEndSkewMs
    }

    fun validateClosedFirstCandleRefetch(
        candle: OhlcBar,
        openingBarTime: String?,
        marketZoneId: String,
        sessionDateIso: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
        settleMs: Long = TouchTurnDefaults.CLOSED_BAR_REFETCH_SETTLE_MS
    ): Pair<ClosedFirstCandleRefetchValidation, String?> {
        val barTime = candle.time?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ClosedFirstCandleRefetchValidation.REJECTED to "missing bar time"
        if (candle.high <= 0.0 || candle.low <= 0.0 || candle.high < candle.low) {
            return ClosedFirstCandleRefetchValidation.REJECTED to "invalid OHLC (high=${candle.high}, low=${candle.low})"
        }
        val anchor = openingBarTime?.trim()?.takeIf { it.isNotEmpty() }
        if (anchor != null) {
            val anchorEnd = barEndEpochMillis(anchor, marketZoneId)
                ?: return ClosedFirstCandleRefetchValidation.REJECTED to "unparseable opening anchor $anchor"
            if (nowEpochMillis < anchorEnd + settleMs) {
                val waitMs = anchorEnd + settleMs - nowEpochMillis
                return ClosedFirstCandleRefetchValidation.NOT_YET_FINAL to
                    "opening anchor settle not reached (wait ${waitMs}ms)"
            }
            if (firstCandleCloseStatus(anchor, marketZoneId, nowEpochMillis, sessionDateIso) !=
                FirstCandleCloseStatus.CLOSED
            ) {
                return ClosedFirstCandleRefetchValidation.NOT_YET_FINAL to
                    "opening anchor bar still forming by wall clock"
            }
            when {
                normalizedBarTimesEqual(anchor, barTime, marketZoneId) ->
                    return ClosedFirstCandleRefetchValidation.READY to null
                openingBarPeriodEndsEqual(anchor, barTime, marketZoneId) ->
                    return ClosedFirstCandleRefetchValidation.READY to null
                else ->
                    return ClosedFirstCandleRefetchValidation.NOT_YET_FINAL to
                        "refetched bar time $barTime != opening anchor $anchor"
            }
        }
        val barEnd = barEndEpochMillis(barTime, marketZoneId)
            ?: return ClosedFirstCandleRefetchValidation.REJECTED to "unparseable bar time $barTime"
        if (nowEpochMillis < barEnd + settleMs) {
            val waitMs = barEnd + settleMs - nowEpochMillis
            return ClosedFirstCandleRefetchValidation.NOT_YET_FINAL to
                "bar end + ${settleMs}ms settle not reached (wait ${waitMs}ms)"
        }
        if (firstCandleCloseStatus(barTime, marketZoneId, nowEpochMillis, sessionDateIso) !=
            FirstCandleCloseStatus.CLOSED
        ) {
            return ClosedFirstCandleRefetchValidation.NOT_YET_FINAL to "first candle still forming by wall clock"
        }
        return ClosedFirstCandleRefetchValidation.READY to null
    }

    /**
     * IB historical bar `time` is the bar **open** (e.g. `20250522  09:30:00` for 09:30–09:45).
     * The candle is closed when wall-clock time in [marketZoneId] is at or past open + 15 minutes.
     */
    fun firstCandleCloseStatus(
        candle: OhlcBar?,
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
        sessionDateIso: String? = null
    ): FirstCandleCloseStatus =
        firstCandleCloseStatus(candle?.time, marketZoneId, nowEpochMillis, sessionDateIso)

    fun firstCandleCloseStatus(
        barTime: String?,
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
        sessionDateIso: String? = null
    ): FirstCandleCloseStatus {
        val scheduledBarEndMillis = sessionDateIso?.let { date ->
            marketOpenEpochMillis(date, marketZoneId)?.plus(BAR_DURATION_MS)
        }
        if (barTime != null) {
            val barStartMillis = barStartEpochMillis(barTime, marketZoneId)
            val barEndMillis = barEndEpochMillis(barTime, marketZoneId)
            if (barStartMillis != null && barEndMillis != null) {
                return when {
                    nowEpochMillis < barStartMillis &&
                        scheduledBarEndMillis != null &&
                        nowEpochMillis >= scheduledBarEndMillis ->
                        FirstCandleCloseStatus.CLOSED
                    nowEpochMillis < barEndMillis ->
                        FirstCandleCloseStatus.FORMING
                    else ->
                        FirstCandleCloseStatus.CLOSED
                }
            }
        }
        if (scheduledBarEndMillis != null && nowEpochMillis >= scheduledBarEndMillis) {
            return FirstCandleCloseStatus.CLOSED
        }
        if (barTime == null) return FirstCandleCloseStatus.UNKNOWN
        val barEndMillis = barEndEpochMillis(barTime, marketZoneId) ?: return FirstCandleCloseStatus.UNKNOWN
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

    fun entryWindowDeadlineEpochMillis(
        barTime: String,
        marketZoneId: String,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Long? = barEndEpochMillis(barTime, marketZoneId)?.plus(rules.closeConfirmationAfterCloseMs)

    fun entryWindowStatus(
        barTime: String?,
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): TouchTurnEntryWindowStatus {
        val time = barTime ?: return TouchTurnEntryWindowStatus.UNKNOWN
        val barEnd = barEndEpochMillis(time, marketZoneId) ?: return TouchTurnEntryWindowStatus.UNKNOWN
        if (nowEpochMillis < barEnd) return TouchTurnEntryWindowStatus.AWAITING_BAR_CLOSE
        val deadline = entryWindowDeadlineEpochMillis(time, marketZoneId, rules)
            ?: return TouchTurnEntryWindowStatus.UNKNOWN
        return if (nowEpochMillis <= deadline) {
            TouchTurnEntryWindowStatus.WITHIN_WINDOW
        } else {
            TouchTurnEntryWindowStatus.EXPIRED
        }
    }

    fun entryWindowRemainingMillis(
        barTime: String?,
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Long? {
        val time = barTime ?: return null
        val barEnd = barEndEpochMillis(time, marketZoneId) ?: return null
        if (nowEpochMillis < barEnd) return null
        val deadline = entryWindowDeadlineEpochMillis(time, marketZoneId, rules) ?: return null
        return (deadline - nowEpochMillis).coerceAtLeast(0)
    }

    fun entryWindowStatusLabel(status: TouchTurnEntryWindowStatus): String = when (status) {
        TouchTurnEntryWindowStatus.AWAITING_BAR_CLOSE -> "Entry window opens when the bar closes"
        TouchTurnEntryWindowStatus.WITHIN_WINDOW -> "Entry window open (1 min after bar close)"
        TouchTurnEntryWindowStatus.EXPIRED -> "Entry window closed — deadline passed"
        TouchTurnEntryWindowStatus.UNKNOWN -> "Entry window unknown"
    }

    /**
     * Hybrid/live-IB liquidity eval needs streaming bid/ask. Defer until quotes arrive or the
     * post-close entry window expires (avoids one-shot [NO_TRADE_LIVE_QUOTE_UNAVAILABLE] right after refetch).
     */
    fun deferLiquidityEvaluationForLiveQuotes(
        requireLivePriceChecks: Boolean,
        liveBid: Double?,
        liveAsk: Double?,
        entryWindowStatus: TouchTurnEntryWindowStatus
    ): Boolean =
        requireLivePriceChecks &&
            (liveBid == null || liveAsk == null) &&
            entryWindowStatus == TouchTurnEntryWindowStatus.WITHIN_WINDOW

    fun closeConfirmation(
        candle: OhlcBar?,
        setup: TouchTurnBracketSetup?,
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
        sessionDateIso: String? = null,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): TouchTurnCloseConfirmation {
        val bar = candle ?: return TouchTurnCloseConfirmation.UNKNOWN
        if (firstCandleCloseStatus(bar, marketZoneId, nowEpochMillis, sessionDateIso) !=
            FirstCandleCloseStatus.CLOSED
        ) {
            return TouchTurnCloseConfirmation.AWAITING_LIQUIDITY
        }
        val bracket = setup ?: return TouchTurnCloseConfirmation.AWAITING_LIQUIDITY
        if (!bracket.isLiquidityCandle || !bracket.isActionable) return TouchTurnCloseConfirmation.FAILED                 
        if (!closeConfirmationWithinDeadline(bar, marketZoneId, nowEpochMillis, rules)) {
            return TouchTurnCloseConfirmation.EXPIRED
        }
        val passes = closeConfirmsTurn(bracket, bar, rules)
        return if (passes) TouchTurnCloseConfirmation.PASSED else TouchTurnCloseConfirmation.FAILED
    }

    /** True when [nowEpochMillis] is within one minute after the 15m bar close. */
    fun closeConfirmationWithinDeadline(
        candle: OhlcBar,
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Boolean {
        val time = candle.time ?: return false
        val barEnd = barEndEpochMillis(time, marketZoneId) ?: return false
        return nowEpochMillis >= barEnd &&
            nowEpochMillis <= barEnd + rules.closeConfirmationAfterCloseMs
    }

    fun closeConfirmationRemainingMillis(
        candle: OhlcBar?,
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Long? {
        val time = candle?.time ?: return null
        val barEnd = barEndEpochMillis(time, marketZoneId) ?: return null
        if (nowEpochMillis < barEnd) return null
        val deadline = barEnd + rules.closeConfirmationAfterCloseMs
        return (deadline - nowEpochMillis).coerceAtLeast(0)
    }

    /**
     * Green liquidity bar (short): close sufficiently below entry confirms the turn.
     * Red liquidity bar (long): close sufficiently above entry confirms the turn.
     */
    fun closeConfirmationMinDistanceFromEntry(
        setup: TouchTurnBracketSetup,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Double = setup.range * rules.closeConfirmationMinDistanceRatioOfRange

    /** Price level close must stay on the confirming side of the range buffer from entry. */
    fun closeConfirmationBufferPrice(
        setup: TouchTurnBracketSetup,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Double? {
        if (!setup.isActionable) return null
        val minDistance = closeConfirmationMinDistanceFromEntry(setup, rules)
        return when (setup.candleColor) {
            FirstCandleColor.GREEN -> setup.entry - minDistance
            FirstCandleColor.RED -> setup.entry + minDistance
            FirstCandleColor.DOJI -> null
        }
    }

    fun closeSeparatesFromEntry(
        setup: TouchTurnBracketSetup,
        close: Double,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Boolean {
        val minDistance = closeConfirmationMinDistanceFromEntry(setup, rules)
        return when (setup.candleColor) {
            FirstCandleColor.GREEN -> {
                val belowEntry = setup.entry - close
                belowEntry > 0.0 && belowEntry >= minDistance
            }
            FirstCandleColor.RED -> {
                val aboveEntry = close - setup.entry
                aboveEntry > 0.0 && aboveEntry >= minDistance
            }
            FirstCandleColor.DOJI -> false
        }
    }

    /**
     * Close must sit in the turn zone of the 15m range: lower third for shorts (green),
     * upper third for longs (red).
     */
    fun closePositionInTurnZone(
        setup: TouchTurnBracketSetup,
        bar: OhlcBar,
        price: Double,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Boolean {
        val ratio = closePositionRatioForPrice(bar, price) ?: return false
        return when (setup.candleColor) {
            FirstCandleColor.GREEN -> ratio <= rules.closePositionShortMax
            FirstCandleColor.RED -> ratio >= rules.closePositionLongMin
            FirstCandleColor.DOJI -> false
        }
    }

    fun closePositionRatioForPrice(bar: OhlcBar, price: Double): Double? {
        val range = bar.range
        if (range <= 0.0) return null
        return ((price - bar.low) / range).coerceIn(0.0, 1.0)
    }

    /** Turn confirmed when [price] is separated from entry and sits in the bar's turn zone. */
    fun confirmsTurnAtPrice(
        setup: TouchTurnBracketSetup,
        bar: OhlcBar,
        price: Double,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Boolean =
        closeSeparatesFromEntry(setup, price, rules) &&
            closePositionInTurnZone(setup, bar, price, rules)

    fun closeConfirmsTurn(
        setup: TouchTurnBracketSetup,
        bar: OhlcBar,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Boolean = confirmsTurnAtPrice(setup, bar, bar.close, rules)

    fun entryTouchBuffer(
        setup: TouchTurnBracketSetup,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Double =
        (setup.range * rules.entryTouchBufferRatioOfRange).coerceAtLeast(rules.minStopDistance)

    /**
     * True when a resting limit at [setup.entry] can still represent a touch fill (live price has not
     * already blown through the level beyond [entryTouchBuffer]).
     */
    fun liveEntryTouchable(
        setup: TouchTurnBracketSetup,
        bid: Double,
        ask: Double,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Boolean {
        val buffer = entryTouchBuffer(setup, rules)
        return when (setup.side) {
            TouchTurnTradeSide.LONG -> ask >= setup.entry - buffer
            TouchTurnTradeSide.SHORT -> bid <= setup.entry + buffer
        }
    }

    fun liveCloseConfirmsTurn(
        setup: TouchTurnBracketSetup,
        bar: OhlcBar,
        livePrice: Double,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Boolean = confirmsTurnAtPrice(setup, bar, livePrice, rules)

    /**
     * Single live price for close-confirmation gates: bid/ask mid when the spread is present,
     * otherwise [last]. Entry touch still uses bid/ask directly so limits are not marketable.
     */
    fun resolveLiveMid(bid: Double?, ask: Double?, last: Double?): Double? {
        if (bid != null && ask != null && bid > 0.0 && ask > 0.0) return (bid + ask) / 2.0
        return last?.takeIf { it > 0.0 }
    }

    /**
     * True when [liveMid] is within [TouchTurnDefaults.BAR_LIVE_DIVERGENCE_MAX_RATIO_OF_RANGE] of the
     * completed bar's close — rejects trading a turn when the live tape has already gapped from the bar.
     */
    fun barCloseAgreesWithLiveMid(
        bar: OhlcBar,
        liveMid: Double,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Boolean {
        val range = bar.range
        if (range <= 0.0) return false
        val maxGap = range * rules.barLiveDivergenceMaxRatioOfRange
        return kotlin.math.abs(bar.close - liveMid) <= maxGap
    }

    /** First blocking bar/volume outcome, or null when the opening bar qualifies for entry. */
    fun setupActionableForEntry(
        setup: TouchTurnBracketSetup,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Boolean = setup.isActionable || !rules.enables.notDoji

    fun barSetupBlockOutcome(
        setup: TouchTurnBracketSetup,
        volumeExhausted: Boolean,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): TouchTurnSessionOutcome? {
        val enables = rules.enables
        return when {
            enables.volumeExhaustion && volumeExhausted -> TouchTurnSessionOutcome.NO_TRADE_VOLUME_EXHAUSTION
            enables.liquidityRange && !setup.isLiquidityCandle -> TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY
            enables.notDoji && !setup.isActionable -> TouchTurnSessionOutcome.NO_TRADE_DOJI
            else -> null
        }
    }

    data class EntryGateResult(
        val entryOrdersPermitted: Boolean,
        val decisionOutcome: TouchTurnSessionOutcome?,
        val closeConfirmation: TouchTurnCloseConfirmation,
        val closeGatePassed: Boolean
    )

    /**
     * Consolidated go/no-go for bracket entry after the opening 15m bar closes.
     * Layers: bar qualifies → volume → turn confirmation (bar + optional live) → entry viability.
     */
    fun evaluateEntryGate(
        setup: TouchTurnBracketSetup,
        candle: OhlcBar,
        volumeSma20: Double,
        marketZoneId: String,
        nowEpochMillis: Long,
        sessionDateIso: String?,
        enforceCloseConfirmation: Boolean,
        liveBid: Double?,
        liveAsk: Double?,
        liveLast: Double?,
        requireLivePriceChecks: Boolean,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): EntryGateResult {
        val enables = rules.enables
        val volumeExhausted = enables.volumeExhaustion &&
            isVolumeExhaustion(candle.volume, volumeSma20, rules)
        val closeConfirmation = closeConfirmation(
            candle,
            setup,
            marketZoneId,
            nowEpochMillis,
            sessionDateIso,
            rules
        )
        barSetupBlockOutcome(setup, volumeExhausted, rules)?.let { outcome ->
            return EntryGateResult(
                entryOrdersPermitted = false,
                decisionOutcome = outcome,
                closeConfirmation = closeConfirmation,
                closeGatePassed = false
            )
        }

        val barCloseOk = !enables.barCloseTurn || !enforceCloseConfirmation ||
            closeConfirmation == TouchTurnCloseConfirmation.PASSED
        val liveGatePrice = resolveLiveMid(liveBid, liveAsk, liveLast)
        val liveQuoteOk = !enables.liveQuoteRequired || !requireLivePriceChecks ||
            (liveBid != null && liveAsk != null)
        val liveCloseOk = !enables.liveTurnConfirmation || !requireLivePriceChecks ||
            (liveGatePrice != null && confirmsTurnAtPrice(setup, candle, liveGatePrice, rules))
        val liveEntryOk = !enables.liveEntryTouchable || !requireLivePriceChecks ||
            (liveBid != null && liveAsk != null && liveEntryTouchable(setup, liveBid, liveAsk, rules))
        val closeGatePassed = barCloseOk && liveCloseOk
        val entryOrdersPermitted = setupActionableForEntry(setup, rules) &&
            !volumeExhausted &&
            closeGatePassed &&
            liveQuoteOk &&
            liveEntryOk
        val decisionOutcome = when {
            enables.entryWindow && enforceCloseConfirmation &&
                closeConfirmation == TouchTurnCloseConfirmation.EXPIRED ->
                TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED
            enables.barCloseTurn && enforceCloseConfirmation &&
                closeConfirmation == TouchTurnCloseConfirmation.FAILED ->
                TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED
            enables.liveQuoteRequired && requireLivePriceChecks && !liveQuoteOk ->
                TouchTurnSessionOutcome.NO_TRADE_LIVE_QUOTE_UNAVAILABLE
            enables.liveBarAgreement && requireLivePriceChecks && liveGatePrice != null &&
                !barCloseAgreesWithLiveMid(candle, liveGatePrice, rules) ->
                TouchTurnSessionOutcome.NO_TRADE_BAR_LIVE_DIVERGENCE
            enables.liveTurnConfirmation && requireLivePriceChecks && liveGatePrice != null && !liveCloseOk ->
                TouchTurnSessionOutcome.NO_TRADE_LIVE_CLOSE_CONFIRMATION_FAILED
            enables.liveEntryTouchable && requireLivePriceChecks && liveBid != null && liveAsk != null &&
                !liveEntryOk ->
                TouchTurnSessionOutcome.NO_TRADE_ENTRY_NOT_TOUCHABLE
            else -> null
        }
        return EntryGateResult(
            entryOrdersPermitted = entryOrdersPermitted,
            decisionOutcome = decisionOutcome,
            closeConfirmation = closeConfirmation,
            closeGatePassed = closeGatePassed
        )
    }

    fun closePositionRatio(bar: OhlcBar): Double? = closePositionRatioForPrice(bar, bar.close)

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
        nowEpochMillis: Long = System.currentTimeMillis(),
        sessionDateIso: String? = null
    ): LiquidityCandleEvaluation =
        liquidityCandleEvaluation(
            candle,
            candle?.time,
            marketZoneId,
            rangeThreshold,
            nowEpochMillis,
            sessionDateIso
        )

    fun liquidityCandleEvaluation(
        candle: OhlcBar?,
        barTime: String?,
        marketZoneId: String,
        rangeThreshold: Double,
        nowEpochMillis: Long = System.currentTimeMillis(),
        sessionDateIso: String? = null
    ): LiquidityCandleEvaluation {
        return when (firstCandleCloseStatus(barTime, marketZoneId, nowEpochMillis, sessionDateIso)) {
            FirstCandleCloseStatus.FORMING -> LiquidityCandleEvaluation.AWAITING_CLOSE
            FirstCandleCloseStatus.UNKNOWN -> LiquidityCandleEvaluation.UNKNOWN
            FirstCandleCloseStatus.CLOSED -> {
                val bar = candle ?: return LiquidityCandleEvaluation.AWAITING_CLOSE
                if (isLiquidityCandle(bar, rangeThreshold)) {
                    LiquidityCandleEvaluation.LIQUIDITY
                } else {
                    LiquidityCandleEvaluation.NOT_LIQUIDITY
                }
            }
        }
    }

    fun isLiquidityCandle(bar: OhlcBar, rangeThreshold: Double): Boolean = bar.range >= rangeThreshold

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

    fun liquidityRangeThreshold(
        adr14: Double,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Double = adr14 * rules.atrLiquidityRatio

    fun liquidityRangeThresholdFromAtr(
        atr14: Double,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Double = atr14 * rules.atrLiquidityRatio

    /**
     * Wilder-style ATR over the last [period] completed bars (needs [period] true ranges).
     */
    fun computeAtr14(bars: List<OhlcBar>, period: Int = TouchTurnDefaults.ATR_LOOKBACK_PERIODS): Result<Double> {
        val valid = bars.filter { it.high > 0.0 && it.low > 0.0 && it.high >= it.low }
        if (valid.size < period + 1) {
            return Result.failure(
                IllegalStateException("Need ${period + 1} 15m bars for ATR($period), got ${valid.size}")
            )
        }
        val slice = valid.takeLast(period + 1)
        val trueRanges = slice.zipWithNext { prev, curr ->
            maxOf(
                curr.high - curr.low,
                kotlin.math.abs(curr.high - prev.close),
                kotlin.math.abs(curr.low - prev.close)
            )
        }
        val atr = trueRanges.takeLast(period).average()
        return Result.success(atr)
    }

    fun computeVolumeSma20(
        bars: List<OhlcBar>,
        period: Int = TouchTurnDefaults.VOLUME_SMA_PERIODS
    ): Result<Double> {
        val withVolume = bars.filter { it.volume > 0.0 }
        if (withVolume.size < period) {
            return Result.failure(
                IllegalStateException(
                    "Need $period session-opening 15m bars with volume for SMA, got ${withVolume.size} " +
                        "usable (${bars.size} session days in history — request at least " +
                        "${TouchTurnDefaults.TOUCH_TURN_15M_HISTORY_DURATION} of 15m bars)"
                )
            )
        }
        return Result.success(withVolume.takeLast(period).map { it.volume }.average())
    }

    /**
     * One 15m bar per prior RTH session (scheduled open when present), sorted oldest-first.
     * Excludes [sessionDayYyyyMmdd].
     */
    fun priorSessionOpeningFifteenMinuteBars(
        bars: List<OhlcBar>,
        marketZoneId: String,
        sessionDayYyyyMmdd: String
    ): List<OhlcBar> =
        bars
            .mapNotNull { bar -> barDayKey(bar.time)?.let { day -> day to bar } }
            .filter { (day, _) -> day < sessionDayYyyyMmdd }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (day, dayBars) ->
                resolveSessionOpeningFifteenMinuteBar(dayBars, marketZoneId, day)
            }
            .sortedBy { barTimeSortKey(it.time) }

    /** High-conviction breakout: opening bar volume above [ratio] × volume SMA. */
    fun isVolumeExhaustion(
        candleVolume: Double,
        volumeSma20: Double,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Boolean = volumeSma20 > 0.0 && candleVolume > volumeSma20 * rules.volumeExhaustionRatio

    fun volumeExhaustionThreshold(
        volumeSma20: Double,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Double = volumeSma20 * rules.volumeExhaustionRatio

    /**
     * Derives signal inputs from 15-minute history.
     * When [allowMissingTodayOpeningBar] is true (pre-open Prepare), ATR and volume SMA are computed
     * from prior sessions; today's opening bar is a placeholder until RTH open.
     */
    fun deriveTouchTurnSignalContext(
        bars: List<OhlcBar>,
        marketZoneId: String,
        sessionDayYyyyMmdd: String,
        allowMissingTodayOpeningBar: Boolean = false,
        explicitFirstCandle: OhlcBar? = null,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Result<TouchTurnSignalContext> {
        val first = explicitFirstCandle
            ?: selectFirstFifteenMinuteBar(bars, marketZoneId, sessionDayYyyyMmdd)
        if (first == null && !allowMissingTodayOpeningBar) {
            val label = RthMarketSessions.forZoneId(marketZoneId).label
            return Result.failure(
                IllegalStateException(
                    "No opening 15m bar for $label session ($sessionDayYyyyMmdd)"
                )
            )
        }
        val priorForAtr = if (first != null) {
            val firstKey = barTimeSortKey(first.time)
            bars
                .filter { barTimeSortKey(it.time) < firstKey }
                .sortedBy { barTimeSortKey(it.time) }
        } else {
            bars
                .filter { bar ->
                    val day = barDayKey(bar.time) ?: return@filter false
                    day < sessionDayYyyyMmdd
                }
                .sortedBy { barTimeSortKey(it.time) }
        }
        val atrResult = computeAtr14(priorForAtr, rules.atrLookbackPeriods)
        val priorOpenings = priorSessionOpeningFifteenMinuteBars(bars, marketZoneId, sessionDayYyyyMmdd)
        val volumeResult = computeVolumeSma20(priorOpenings, rules.volumeSmaPeriods)
        if (atrResult.isFailure) return Result.failure(atrResult.exceptionOrNull()!!)
        if (volumeResult.isFailure) return Result.failure(volumeResult.exceptionOrNull()!!)
        val firstCandle = first ?: placeholderOpeningBar(sessionDayYyyyMmdd, marketZoneId)
        return Result.success(
            TouchTurnSignalContext(
                firstCandle = firstCandle,
                atr14 = atrResult.getOrThrow(),
                volumeSma20 = volumeResult.getOrThrow(),
                todayOpeningBarPending = first == null
            )
        )
    }

    /** Placeholder until IB has today's RTH opening 15m bar (pre-open Prepare). */
    fun placeholderOpeningBar(sessionDayYyyyMmdd: String, marketZoneId: String): OhlcBar {
        val sessionDateIso = runCatching {
            val y = sessionDayYyyyMmdd.substring(0, 4).toInt()
            val m = sessionDayYyyyMmdd.substring(4, 6).toInt()
            val d = sessionDayYyyyMmdd.substring(6, 8).toInt()
            "%04d-%02d-%02d".format(y, m, d)
        }.getOrNull()
        val time = sessionDateIso?.let { iso ->
            marketOpenEpochMillis(iso, marketZoneId, firstCandleBarTime = null)
                ?.let { formatIbBarOpenTime(it, marketZoneId) }
        }
        return OhlcBar(
            open = 0.0,
            high = 0.0,
            low = 0.0,
            close = 0.0,
            time = time,
            volume = 0.0
        )
    }

    fun barDayKey(barTime: String?): String? {
        val trimmed = barTime?.trim() ?: return null
        val match = Regex("""(\d{8})""").find(trimmed) ?: return null
        return match.groupValues[1]
    }

    /** True when [barTime] was produced by [formatIbBarOpenTime] (already in [marketZoneId] local time). */
    fun ibBarTimeLooksMarketLocal(barTime: String): Boolean = "  " in barTime

    private fun ibBarTimeHasSourceSuffix(barTime: String): Boolean {
        val suffix = IB_BAR_TIME_WITH_SUFFIX_REGEX.find(barTime.trim())?.destructured?.toList()?.getOrNull(6)
        return suffix?.isNotBlank() == true
    }

    /** Raw IB UK timestamps without suffix use MET wall clock (+1h vs London). */
    private fun ibBarTimeLikelyRawMetWallClock(barTime: String, marketZoneId: String): Boolean {
        if (marketZoneId != RthMarketSessions.EUR.zoneId) return false
        if (ibBarTimeHasSourceSuffix(barTime) || ibBarTimeLooksMarketLocal(barTime)) return false
        val hour = IB_BAR_TIME_WITH_SUFFIX_REGEX.find(barTime.trim())?.destructured?.toList()?.getOrNull(3)
            ?.toIntOrNull() ?: return false
        return hour > RthMarketSessions.EUR.openHour
    }

    private fun barTimeForMarketZoneParse(barTime: String, marketZoneId: String): String? = when {
        ibBarTimeHasSourceSuffix(barTime) -> normalizeIbBarTimeToMarketZone(barTime, marketZoneId)
        ibBarTimeLikelyRawMetWallClock(barTime, marketZoneId) ->
            normalizeIbBarTimeToMarketZone(barTime, marketZoneId)
        else -> barTime.trim()
    }

    fun barStartEpochMillis(barTime: String, marketZoneId: String): Long? {
        val localized = barTimeForMarketZoneParse(barTime, marketZoneId) ?: return null
        val match = IB_BAR_TIME_REGEX.find(localized.trim()) ?: return null
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
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): TouchTurnBracketSetup {
        val range = bar.range
        val color = firstCandleColor(bar)
        val liquidity = isLiquidityCandle(bar, rangeThreshold)
        val minStopDistance = rules.minStopDistance
        return when (color) {
            FirstCandleColor.GREEN -> {
                val tpDistance = range * rules.takeProfitFibRatioGreen
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
                val tpDistance = range * rules.takeProfitFibRatioRed
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
        FirstCandleColor.GREEN -> "38.2%"
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
    const val ATR_LOOKBACK_PERIODS = 14
    const val VOLUME_SMA_PERIODS = 20
    /**
     * IB [reqHistoricalData] duration for Touch Turn 15m history. Needs ~20 prior RTH session
     * opening bars plus today's bar and an ATR window (~35+ trading days); 1 M is often too short.
     */
    const val TOUCH_TURN_15M_HISTORY_DURATION = "2 M"
    /** Liquidity when first 15m range is at least this fraction of 14-period ATR. */
    const val ATR_LIQUIDITY_RATIO = 0.25
    @Deprecated("Use ATR_LIQUIDITY_RATIO", ReplaceWith("ATR_LIQUIDITY_RATIO"))
    const val ADR_LIQUIDITY_RATIO = ATR_LIQUIDITY_RATIO
    /** Opening-bar volume above this multiple of the 20 prior session-open volume SMA aborts entry. */
    const val VOLUME_EXHAUSTION_RATIO = 1.5
    /** Post-entry observation window before resting bracket is left working unchecked. */
    const val VOLUME_BUFFER_OBSERVATION_MS = 60_000L
    const val MIN_STOP_DISTANCE = 0.05
    /** Green (short) liquidity bar: take-profit distance as fraction of bar range. */
    const val TAKE_PROFIT_FIB_RATIO_GREEN = 0.382
    /** Red (long) liquidity bar: take-profit distance as fraction of bar range. */
    const val TAKE_PROFIT_FIB_RATIO_RED = 0.382
    /** Max time after 15m bar close to pass close confirmation and place entry orders. */
    const val CLOSE_CONFIRMATION_AFTER_CLOSE_MS = 60_000L
    /** Wait after 15m bar end before trusting IB historical refetch (bar-not-final race). */
    const val CLOSED_BAR_REFETCH_SETTLE_MS = 3_000L
    @Deprecated("Use CLOSE_CONFIRMATION_AFTER_CLOSE_MS", ReplaceWith("CLOSE_CONFIRMATION_AFTER_CLOSE_MS"))
    const val ENTRY_WINDOW_AFTER_CLOSE_MS = CLOSE_CONFIRMATION_AFTER_CLOSE_MS
    /**
     * Green liquidity bar (short): close below entry by at least this fraction of bar range.
     * Red liquidity bar (long): close above entry by the same margin — keeps entry resting so the
     * post-placement volume buffer can observe before a fill.
     */
    const val CLOSE_CONFIRMATION_MIN_DISTANCE_RATIO_OF_RANGE = 0.15
    /** Long: skip entry when ask is more than this fraction of bar range below entry (and vice versa for short). */
    const val ENTRY_TOUCH_BUFFER_RATIO_OF_RANGE = 0.05
    /** Max |bar.close − liveMid| as a fraction of bar range before hybrid mode rejects the setup. */
    const val BAR_LIVE_DIVERGENCE_MAX_RATIO_OF_RANGE = 0.25
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
            rules = effectiveTouchTurnRules(),
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

/**
 * Stores ADR/volume context after the initial history fetch. Opening-bar OHLC is **not** stored here;
 * [withClosedFirstFifteenMinuteCandle] applies the completed bar once wall-clock passes bar end.
 */
fun StrategyDeployment.withFirstFifteenMinuteCandle(
    sessionDate: String,
    candle: OhlcBar,
    atr14: Double,
    volumeSma20: Double,
    adr14: Double? = null,
    currencyCode: String = "USD",
    marketZoneId: String = "America/New_York"
): StrategyDeployment {
    if (strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return this
    val rules = effectiveTouchTurnRules()
    val threshold = TouchTurnLogic.liquidityRangeThresholdFromAtr(atr14, rules)
    val prior = touchTurnSession?.milestones
    val at = currentSessionTimestampIso()
    return copy(
        touchTurnSession = TouchTurnSessionContext(
            sessionDate = sessionDate,
            status = TouchTurnCandleStatus.READY,
            openingBarTime = candle.time,
            candle = null,
            currencyCode = currencyCode,
            marketZoneId = marketZoneId,
            adr14 = adr14 ?: atr14,
            atr14 = atr14,
            volumeSma20 = volumeSma20,
            rangeThreshold = threshold,
            rules = rules,
            milestones = TouchTurnMilestoneTimestamps(
                startingSessionAt = prior?.startingSessionAt ?: inProgressSession()?.startedAt ?: at,
                dataReadyAt = at
            )
        )
    )
}

/** Engine event: first 15m RTH bar has closed (OHLC refetch / liquidity eval may still be in progress). */
fun StrategyDeployment.withOpeningBarClosedMilestone(): StrategyDeployment {
    if (strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return this
    val session = touchTurnSession ?: return this
    if (session.milestones.barClosedAt != null) return this
    val at = currentSessionTimestampIso()
    return copy(
        touchTurnSession = session.copy(
            milestones = session.milestones.copy(barClosedAt = at)
        )
    )
}

/** Applies the completed first 15m bar OHLC from a post-close historical refetch. */
fun StrategyDeployment.withClosedFirstFifteenMinuteCandle(candle: OhlcBar): StrategyDeployment {
    if (strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return this
    val session = touchTurnSession ?: return this
    return copy(
        touchTurnSession = session.copy(
            candle = candle,
            openingBarTime = session.openingBarTime ?: candle.time
        )
    )
}

/** Persists bracket setup and liquidity flag once the first candle has closed. */
fun StrategyDeployment.withLiquidityEvaluatedIfClosed(
    enforceCloseConfirmation: Boolean = true,
    nowEpochMillis: Long = System.currentTimeMillis(),
    liveBid: Double? = null,
    liveAsk: Double? = null,
    liveLast: Double? = null,
    requireLivePriceChecks: Boolean = false
): StrategyDeployment {
    if (strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return this
    val session = touchTurnSession ?: return this
    val candle = session.candle ?: return this
    if (session.candleCloseStatus(nowEpochMillis) != FirstCandleCloseStatus.CLOSED) return this
    if (session.setup != null) return this
    val rules = effectiveTouchTurnRules()
    val setup = TouchTurnLogic.computeBracketSetup(candle, session.rangeThreshold, rules)
    val gate = TouchTurnLogic.evaluateEntryGate(
        setup = setup,
        candle = candle,
        volumeSma20 = session.volumeSma20 ?: 0.0,
        marketZoneId = session.marketZoneId,
        nowEpochMillis = nowEpochMillis,
        sessionDateIso = session.sessionDate,
        enforceCloseConfirmation = enforceCloseConfirmation,
        liveBid = liveBid,
        liveAsk = liveAsk,
        liveLast = liveLast,
        requireLivePriceChecks = requireLivePriceChecks,
        rules = rules
    )
    val closeConfirmation = gate.closeConfirmation
    val closeGatePassed = gate.closeGatePassed
    val entryOrdersPermitted = gate.entryOrdersPermitted
    val decisionOutcome = gate.decisionOutcome
    val at = currentSessionTimestampIso()
    val milestones = session.milestones.let { m ->
        m.copy(
            dataReadyAt = m.dataReadyAt ?: m.startingSessionAt,
            barClosedAt = m.barClosedAt ?: at,
            liquidityEvaluatedAt = at,
            closeConfirmedAt = m.closeConfirmedAt
                ?: if (closeGatePassed) at else null
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
    val prior = touchTurnSession
    val at = currentSessionTimestampIso()
    val milestones = (prior?.milestones ?: TouchTurnMilestoneTimestamps()).copy(
        startingSessionAt = prior?.milestones?.startingSessionAt
            ?: inProgressSession()?.startedAt
            ?: at,
        dataReadyAt = prior?.milestones?.dataReadyAt,
        barClosedAt = prior?.milestones?.barClosedAt,
        dataFailedAt = at
    )
    val failedSession = prior?.copy(
        sessionDate = sessionDate,
        status = TouchTurnCandleStatus.FAILED,
        errorMessage = message,
        decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
        milestones = milestones
    ) ?: TouchTurnSessionContext(
        sessionDate = sessionDate,
        status = TouchTurnCandleStatus.FAILED,
        errorMessage = message,
        decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
        milestones = milestones
    )
    return copy(touchTurnSession = failedSession)
}

/**
 * Most recent closed Touch Turn run for pipeline UI, recap, and close panel.
 * Picks the latest closed run by [StrategySession.stoppedAt] (then [StrategySession.startedAt]),
 * regardless of whether it had broker fills.
 */
fun StrategyDeployment.touchTurnPostStopSession(): StrategySession? {
    if (strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return null
    inProgressSession()?.takeIf { it.sessionTrades.isNotEmpty() }?.let { return it }
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
            TouchTurnSessionOutcome.NO_TRADE_LIVE_CLOSE_CONFIRMATION_FAILED,
            TouchTurnSessionOutcome.NO_TRADE_BAR_LIVE_DIVERGENCE,
            TouchTurnSessionOutcome.NO_TRADE_ENTRY_NOT_TOUCHABLE,
            TouchTurnSessionOutcome.NO_TRADE_LIVE_QUOTE_UNAVAILABLE,
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
