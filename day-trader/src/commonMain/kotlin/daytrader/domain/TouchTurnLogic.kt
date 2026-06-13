package daytrader.domain

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

    /**
     * Legacy entry-window helpers retained for pipeline UI labels on closed sessions.
     */
    fun entryWindowDeadlineEpochMillis(
        barTime: String,
        marketZoneId: String
    ): Long? = barEndEpochMillis(barTime, marketZoneId)?.plus(60_000L)

    fun entryWindowStatus(
        barTime: String?,
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): TouchTurnEntryWindowStatus {
        val time = barTime ?: return TouchTurnEntryWindowStatus.UNKNOWN
        val barEnd = barEndEpochMillis(time, marketZoneId) ?: return TouchTurnEntryWindowStatus.UNKNOWN
        if (nowEpochMillis < barEnd) return TouchTurnEntryWindowStatus.AWAITING_BAR_CLOSE
        val deadline = entryWindowDeadlineEpochMillis(time, marketZoneId)
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
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Long? {
        val time = barTime ?: return null
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

    /** Volume and live-quote gates removed — liquidity evaluation is never deferred. */
    fun deferLiquidityEvaluationForLiveQuotes(
        requireLivePriceChecks: Boolean,
        liveBid: Double?,
        liveAsk: Double?,
        entryWindowStatus: TouchTurnEntryWindowStatus,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Boolean = false

    fun closeConfirmation(
        candle: OhlcBar?,
        setup: TouchTurnBracketSetup?,
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
        sessionDateIso: String? = null,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT,
        openingBarPriceSamples: List<TouchTurnOpeningBarPriceSample> = emptyList()
    ): TouchTurnCloseConfirmation {
        val bar = candle ?: return TouchTurnCloseConfirmation.UNKNOWN
        if (firstCandleCloseStatus(bar, marketZoneId, nowEpochMillis, sessionDateIso) !=
            FirstCandleCloseStatus.CLOSED
        ) {
            return TouchTurnCloseConfirmation.AWAITING_LIQUIDITY
        }
        val bracket = setup ?: return TouchTurnCloseConfirmation.AWAITING_LIQUIDITY
        if (rules.enables.requiresLiquidityRange() && !bracket.isLiquidityCandle) {
            return TouchTurnCloseConfirmation.FAILED
        }
        if (rules.enables.bounceRejection) {
            val bounce = TouchTurnExtremeBounceEvaluator.evaluate(
                setup = bracket,
                bar = bar,
                samples = openingBarPriceSamples,
                rules = rules
            )
            if (!bounce.passed) {
                return TouchTurnCloseConfirmation.FAILED
            }
        }
        return TouchTurnCloseConfirmation.PASSED
    }

    /** Legacy helper retained for pipeline UI on closed sessions. */
    fun closeConfirmationWithinDeadline(
        candle: OhlcBar,
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val time = candle.time ?: return false
        val barEnd = barEndEpochMillis(time, marketZoneId) ?: return false
        return nowEpochMillis >= barEnd && nowEpochMillis <= barEnd + 60_000L
    }

    fun closeConfirmationRemainingMillis(
        candle: OhlcBar?,
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Long? {
        val time = candle?.time ?: return null
        val barEnd = barEndEpochMillis(time, marketZoneId) ?: return null
        if (nowEpochMillis < barEnd) return null
        val deadline = barEnd + 60_000L
        return (deadline - nowEpochMillis).coerceAtLeast(0)
    }

    /**
     * Legacy turn-zone helpers retained for pipeline UI on closed sessions.
     */
    fun closePositionInTurnZone(
        setup: TouchTurnBracketSetup,
        bar: OhlcBar,
        price: Double,
        closePositionShortMax: Double = 0.45,
        closePositionLongMin: Double = 0.55
    ): Boolean {
        val ratio = closePositionRatioForPrice(bar, price) ?: return false
        return when (setup.candleColor) {
            FirstCandleColor.GREEN -> ratio <= closePositionShortMax
            FirstCandleColor.RED -> ratio >= closePositionLongMin
            FirstCandleColor.DOJI -> false
        }
    }

    fun closePositionRatioForPrice(bar: OhlcBar, price: Double): Double? {
        val range = bar.range
        if (range <= 0.0) return null
        return ((price - bar.low) / range).coerceIn(0.0, 1.0)
    }

    /** Legacy helper retained for pipeline UI on closed sessions. */
    fun confirmsTurnAtPrice(
        setup: TouchTurnBracketSetup,
        bar: OhlcBar,
        price: Double
    ): Boolean = closePositionInTurnZone(setup, bar, price)

    fun closeConfirmsTurn(
        setup: TouchTurnBracketSetup,
        bar: OhlcBar
    ): Boolean = confirmsTurnAtPrice(setup, bar, bar.close)

    /** Legacy helper retained for liquidity allocator UI. */
    fun liveEntryTouchable(
        setup: TouchTurnBracketSetup,
        bid: Double,
        ask: Double
    ): Boolean = when (setup.side) {
        TouchTurnTradeSide.LONG -> ask >= setup.entry
        TouchTurnTradeSide.SHORT -> bid <= setup.entry
    }

    fun liveCloseConfirmsTurn(
        setup: TouchTurnBracketSetup,
        bar: OhlcBar,
        livePrice: Double
    ): Boolean = confirmsTurnAtPrice(setup, bar, livePrice)

    /**
     * Single live price for close-confirmation gates: bid/ask mid when the spread is present,
     * otherwise [last]. Entry touch still uses bid/ask directly so limits are not marketable.
     */
    fun resolveLiveMid(bid: Double?, ask: Double?, last: Double?): Double? {
        if (bid != null && ask != null && bid > 0.0 && ask > 0.0) return (bid + ask) / 2.0
        return last?.takeIf { it > 0.0 }
    }

    /** Legacy helper retained for pipeline UI on closed sessions. */
    fun barCloseAgreesWithLiveMid(
        bar: OhlcBar,
        liveMid: Double,
        maxDivergenceRatioOfRange: Double = 0.25
    ): Boolean {
        val range = bar.range
        if (range <= 0.0) return false
        val maxGap = range * maxDivergenceRatioOfRange
        return kotlin.math.abs(bar.close - liveMid) <= maxGap
    }

    /** True when the opening bar qualifies for bracket entry under [rules]. */
    fun setupActionableForEntry(
        setup: TouchTurnBracketSetup,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Boolean = setup.isLiquidityCandle || !rules.enables.requiresLiquidityRange()

    fun barSetupBlockOutcome(
        setup: TouchTurnBracketSetup,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): TouchTurnSessionOutcome? {
        if (rules.enables.requiresLiquidityRange() && !setup.isLiquidityCandle) {
            return TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY
        }
        return null
    }

    data class EntryGateResult(
        val entryOrdersPermitted: Boolean,
        val decisionOutcome: TouchTurnSessionOutcome?,
        val closeConfirmation: TouchTurnCloseConfirmation,
        val closeGatePassed: Boolean
    )

    /** Consolidated go/no-go for bracket entry after the opening 15m bar closes. */
    fun evaluateEntryGate(
        setup: TouchTurnBracketSetup,
        candle: OhlcBar,
        marketZoneId: String,
        nowEpochMillis: Long,
        sessionDateIso: String?,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT,
        openingBarPriceSamples: List<TouchTurnOpeningBarPriceSample> = emptyList()
    ): EntryGateResult {
        val closeConfirmation = closeConfirmation(
            candle,
            setup,
            marketZoneId,
            nowEpochMillis,
            sessionDateIso,
            rules,
            openingBarPriceSamples
        )
        barSetupBlockOutcome(setup, rules)?.let { outcome ->
            return EntryGateResult(
                entryOrdersPermitted = false,
                decisionOutcome = outcome,
                closeConfirmation = closeConfirmation,
                closeGatePassed = false
            )
        }
        val closeGatePassed = closeConfirmation == TouchTurnCloseConfirmation.PASSED
        val entryOrdersPermitted = setupActionableForEntry(setup, rules) && closeGatePassed
        val decisionOutcome = when {
            closeConfirmation == TouchTurnCloseConfirmation.FAILED ->
                closeConfirmationFailureOutcome(setup, candle, rules, openingBarPriceSamples)
            else -> null
        }
        return EntryGateResult(
            entryOrdersPermitted = entryOrdersPermitted,
            decisionOutcome = decisionOutcome,
            closeConfirmation = closeConfirmation,
            closeGatePassed = closeGatePassed
        )
    }

    /** Maps a failed [closeConfirmation] to the appropriate no-trade outcome. */
    fun closeConfirmationFailureOutcome(
        setup: TouchTurnBracketSetup,
        candle: OhlcBar,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT,
        openingBarPriceSamples: List<TouchTurnOpeningBarPriceSample> = emptyList()
    ): TouchTurnSessionOutcome = when {
        setup.candleColor == FirstCandleColor.DOJI -> TouchTurnSessionOutcome.NO_TRADE_DOJI
        rules.enables.requiresLiquidityRange() && !setup.isLiquidityCandle ->
            TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY
        rules.enables.bounceRejection -> {
            val bounce = TouchTurnExtremeBounceEvaluator.evaluate(setup, candle, openingBarPriceSamples, rules)
            when {
                !bounce.dataAvailable -> TouchTurnSessionOutcome.NO_TRADE_BOUNCE_DATA_UNAVAILABLE
                !bounce.passed -> TouchTurnSessionOutcome.NO_TRADE_BOUNCE_REJECTION_FAILED
                else -> TouchTurnSessionOutcome.NO_TRADE_BOUNCE_REJECTION_FAILED
            }
        }
        else -> TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED
    }

    fun extremeBounceEvaluation(
        setup: TouchTurnBracketSetup,
        candle: OhlcBar,
        openingBarPriceSamples: List<TouchTurnOpeningBarPriceSample>,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): TouchTurnExtremeBounceEvaluator.Result =
        TouchTurnExtremeBounceEvaluator.evaluate(setup, candle, openingBarPriceSamples, rules)

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
            TouchTurnLiquidityThresholds(thresholdDailyAtr = rangeThreshold),
            TouchTurnRuleConfig.DEFAULT.copy(
                enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
            ),
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
    ): LiquidityCandleEvaluation =
        liquidityCandleEvaluation(
            candle,
            barTime,
            marketZoneId,
            TouchTurnLiquidityThresholds(thresholdDailyAtr = rangeThreshold),
            TouchTurnRuleConfig.DEFAULT.copy(
                enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
            ),
            nowEpochMillis,
            sessionDateIso
        )

    fun liquidityCandleEvaluation(
        candle: OhlcBar?,
        barTime: String?,
        marketZoneId: String,
        liquidityThresholds: TouchTurnLiquidityThresholds,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT,
        nowEpochMillis: Long = System.currentTimeMillis(),
        sessionDateIso: String? = null
    ): LiquidityCandleEvaluation {
        return when (firstCandleCloseStatus(barTime, marketZoneId, nowEpochMillis, sessionDateIso)) {
            FirstCandleCloseStatus.FORMING -> LiquidityCandleEvaluation.AWAITING_CLOSE
            FirstCandleCloseStatus.UNKNOWN -> LiquidityCandleEvaluation.UNKNOWN
            FirstCandleCloseStatus.CLOSED -> {
                val bar = candle ?: return LiquidityCandleEvaluation.AWAITING_CLOSE
                if (evaluatesLiquidityCandle(bar, liquidityThresholds, rules)) {
                    LiquidityCandleEvaluation.LIQUIDITY
                } else {
                    LiquidityCandleEvaluation.NOT_LIQUIDITY
                }
            }
        }
    }

    fun isLiquidityCandle(bar: OhlcBar, rangeThreshold: Double): Boolean = bar.range >= rangeThreshold

    fun resolveLiquidityThresholds(
        dailyAtr14: Double?,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): TouchTurnLiquidityThresholds = TouchTurnLiquidityThresholds(
        thresholdDailyAtr = if (rules.enables.liquidityRangeDailyAtr) {
            dailyAtr14?.takeIf { it > 0.0 }?.let { liquidityRangeThresholdFromDailyAtr(it, rules) }
        } else {
            null
        }
    )

    /**
     * True when the opening bar passes every enabled liquidity gate (AND). When neither gate is
     * enabled, returns true.
     */
    fun evaluatesLiquidityCandle(
        bar: OhlcBar,
        liquidityThresholds: TouchTurnLiquidityThresholds,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Boolean {
        val enables = rules.enables
        if (!enables.requiresLiquidityRange()) return true
        if (enables.liquidityRangeDailyAtr) {
            val threshold = liquidityThresholds.thresholdDailyAtr ?: return false
            if (bar.range < threshold) return false
        }
        return true
    }

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

    fun liquidityRangeThresholdFromDailyAtr(
        dailyAtr14: Double,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Double = dailyAtr14 * rules.atrLiquidityRatio

    /** Diagnoses which IB historical legs were still pending when composite bootstrap timed out. */
    fun describeSignalContextBootstrapPendingLegs(
        bars15mReady: Boolean,
        bars15mCount: Int = 0,
        dailyBarsRequired: Boolean,
        dailyBarsReady: Boolean,
        dailyFetchFailed: String? = null
    ): String {
        val legs = buildList {
            if (bars15mReady) {
                add("15m_bars_ready(count=$bars15mCount)")
            } else {
                add("15m_opening_bar")
            }
            if (dailyBarsRequired) {
                when {
                    dailyFetchFailed != null -> add("daily_bars_failed=$dailyFetchFailed")
                    dailyBarsReady -> add("daily_bars_ready")
                    else -> add("daily_bars")
                }
            }
        }
        return legs.joinToString(",")
    }

    /**
     * Wilder-style daily ATR over the last [period] completed sessions (today excluded when set).
     */
    fun computeDailyAtr14(
        dailyBars: List<OhlcBar>,
        period: Int = TouchTurnDefaults.DAILY_ATR_LOOKBACK_PERIODS,
        excludeSessionDayYyyyMmdd: String? = null
    ): Result<Double> {
        val valid = dailyBars
            .filter { it.high > 0.0 && it.low > 0.0 && it.high >= it.low }
            .filter { bar ->
                val day = barDayKey(bar.time) ?: return@filter false
                excludeSessionDayYyyyMmdd == null || day != excludeSessionDayYyyyMmdd
            }
            .sortedBy { barDayKey(it.time).orEmpty() }
        return computeAtr14(valid, period)
    }

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
        dailyBars: List<OhlcBar>? = null,
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
        val dailyAtrResult = dailyBars?.let {
            computeDailyAtr14(it, rules.dailyAtrLookbackPeriods, excludeSessionDayYyyyMmdd = sessionDayYyyyMmdd)
        }
        if (rules.enables.liquidityRangeDailyAtr) {
            if (dailyBars == null) {
                return Result.failure(
                    IllegalStateException("Daily bars required for daily ATR liquidity gate")
                )
            }
            if (dailyAtrResult?.isFailure == true) {
                return Result.failure(dailyAtrResult.exceptionOrNull()!!)
            }
        }
        val firstCandle = first ?: placeholderOpeningBar(sessionDayYyyyMmdd, marketZoneId)
        return Result.success(
            TouchTurnSignalContext(
                firstCandle = firstCandle,
                atr14 = 0.0,
                dailyAtr14 = dailyAtrResult?.getOrNull(),
                volumeSma20 = 0.0,
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

    /** @param rangeThreshold Legacy single threshold (treated as daily ATR gate). */
    fun computeBracketSetup(
        bar: OhlcBar,
        rangeThreshold: Double,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): TouchTurnBracketSetup = computeBracketSetup(
        bar,
        TouchTurnLiquidityThresholds(thresholdDailyAtr = rangeThreshold),
        rules.copy(enables = rules.enables.copy(liquidityRangeDailyAtr = true))
    )

    fun computeBracketSetup(
        bar: OhlcBar,
        liquidityThresholds: TouchTurnLiquidityThresholds,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): TouchTurnBracketSetup {
        val range = bar.range
        val color = firstCandleColor(bar)
        val liquidity = evaluatesLiquidityCandle(bar, liquidityThresholds, rules)
        val rangeThreshold = liquidityThresholds.primary
        val entryInwardOffset = range * rules.entryInwardOffsetRatioOfRange
        return when (color) {
            FirstCandleColor.GREEN -> {
                val entry = bar.high - entryInwardOffset
                val takeProfit = bar.low + range * rules.takeProfitFibRatioGreen
                val tpDistance = entry - takeProfit
                val stopDistance = tpDistance / rules.takeProfitToStopLossRatio
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
                val entry = bar.low + entryInwardOffset
                val takeProfit = entry + tpDistance
                val stopDistance = tpDistance / rules.takeProfitToStopLossRatio
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
        val stopDesc = stopLossDistanceDescription(setup)
        return when (setup.candleColor) {
            FirstCandleColor.GREEN ->
                "Green liquidity bar → $action below bar high (inward offset), take profit at $fibPct fib " +
                    "retracement level, $stopDesc above entry."
            FirstCandleColor.RED ->
                "Red liquidity bar → $action above bar low (inward offset), take profit at $fibPct of range " +
                    "above entry, $stopDesc below entry."
            FirstCandleColor.DOJI -> "Flat candle (open = close) — no directional bracket."
        }
    }

    private fun stopLossDistanceDescription(setup: TouchTurnBracketSetup): String {
        val tpDistance = kotlin.math.abs(setup.takeProfit - setup.entry)
        val slDistance = kotlin.math.abs(setup.stopLoss - setup.entry)
        if (tpDistance <= 0.0 || slDistance <= 0.0) return "stop at entry"
        val ratio = tpDistance / slDistance
        val ratioLabel = if (ratio == ratio.toLong().toDouble()) {
            "${ratio.toLong()}:1"
        } else {
            "%.1f:1".format(ratio)
        }
        return "stop at $ratioLabel take-profit-to-stop-loss (entry-to-target distance ÷ ${formatRatio(ratio)})"
    }

    private fun formatRatio(ratio: Double): String =
        if (ratio == ratio.toLong().toDouble()) ratio.toLong().toString() else "%.2g".format(ratio)
}
