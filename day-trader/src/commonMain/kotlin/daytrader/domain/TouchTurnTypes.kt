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

/** Whether orders may be placed within [TouchTurnDefaults.CLOSE_CONFIRMATION_AFTER_CLOSE_MS] of bar close. */
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

/** Liquidity range thresholds derived from daily ATR(14). */
@Serializable
data class TouchTurnLiquidityThresholds(
    val thresholdDailyAtr: Double? = null,
) {
    val primary: Double get() = thresholdDailyAtr ?: 0.0
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
 * Stop is entry-to-TP distance ÷ [TouchTurnRuleConfig.takeProfitToStopLossRatio] beyond entry.
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
    /** Legacy 15m ATR metric from persisted runs (no longer used for liquidity gates). */
    val atr14: Double? = null,
    /** Wilder daily ATR(14) on completed daily bars (ProReal-style liquidity input). */
    val dailyAtr14: Double? = null,
    /** 20-period SMA of prior session-opening 15m bar volume (apples-to-apples vs today's open). */
    val volumeSma20: Double? = null,
    /** Daily ATR liquidity threshold = [dailyAtr14] × ratio when daily gate is enabled. */
    val rangeThreshold: Double = 0.0,
    val rangeThresholdDailyAtr: Double? = null,
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
    /** Broker order ids after bracket submit (parent, take-profit, stop, optional adjustable stop). */
    val bracketOrderIds: TouchTurnBracketOrderIds? = null,
    /** Filled legs for a closed run (from persisted run record or derived from fills). */
    val executedBracketLegs: List<TouchTurnOrderRole> = emptyList(),
    /** Rule thresholds snapshotted from deployment at session start. */
    val rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT,
    /** Pre-flight checks captured when this session was started. */
    val prepareSnapshot: TouchTurnPrepareSnapshot? = null,
    /** Home-market macro trend at liquidity evaluation (when macro trend alignment rule is enabled). */
    val macroTrendAtEntry: MacroTrendState? = null,
    val macroBenchmarkSymbol: String? = null,
    val macroBenchmarkLabel: String? = null,
    /** Symbol daily trend at liquidity evaluation (when stock trend alignment rule is enabled). */
    val stockTrendAtEntry: StockTrendState? = null,
    /** Live/replay marks collected during the opening 15m bar for bounce rejection. */
    val openingBarPriceSamples: List<TouchTurnOpeningBarPriceSample> = emptyList(),
    /** Qualified extreme bounces counted at liquidity evaluation when bounce rejection ran. */
    val extremeBounceCount: Int? = null
) {
    fun sessionOrdersPlaced(): Boolean = ordersPlacedForSession || entryOrdersPermitted == true

    /** Bar-close milestone was set but closed-bar OHLC refetch never succeeded. */
    fun failedDuringLiquidityRefetch(): Boolean =
        status == TouchTurnCandleStatus.FAILED &&
            decisionOutcome == TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED &&
            milestones.barClosedAt != null &&
            milestones.liquidityEvaluatedAt == null
    val liquidityThresholds: TouchTurnLiquidityThresholds
        get() {
            val resolved = TouchTurnLogic.resolveLiquidityThresholds(dailyAtr14, rules)
            if (resolved.primary > 0.0) return resolved
            return TouchTurnLiquidityThresholds(
                thresholdDailyAtr = rangeThresholdDailyAtr ?: rangeThreshold.takeIf { it > 0.0 }
            )
        }

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
            liquidityThresholds,
            rules,
            nowEpochMillis,
            sessionDate
        )

    fun firstCandleColor(): FirstCandleColor? = candle?.let { TouchTurnLogic.firstCandleColor(it) }

    fun entryWindowStatus(nowEpochMillis: Long = System.currentTimeMillis()): TouchTurnEntryWindowStatus =
        TouchTurnLogic.entryWindowStatus(resolvedOpeningBarTime(), marketZoneId, nowEpochMillis)

    fun closeConfirmation(nowEpochMillis: Long = System.currentTimeMillis()): TouchTurnCloseConfirmation =
        TouchTurnLogic.closeConfirmation(
            candle,
            setup,
            marketZoneId,
            nowEpochMillis,
            sessionDate,
            rules,
            openingBarPriceSamples
        )

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
                TouchTurnSessionOutcome.NO_TRADE_BOUNCE_REJECTION_FAILED,
                TouchTurnSessionOutcome.NO_TRADE_BOUNCE_DATA_UNAVAILABLE,
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

