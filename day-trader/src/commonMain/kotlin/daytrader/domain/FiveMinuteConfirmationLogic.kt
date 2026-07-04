package daytrader.domain

import kotlin.math.abs
import kotlin.math.max
import kotlinx.serialization.Serializable

/** Lifecycle of the post-sweep 5-minute hammer confirmation window. */
@Serializable
enum class FiveMinuteConfirmationStatus {
    /** Sweep active; awaiting a qualifying 5m hammer within TTL. */
    AWAITING,
    /** Valid hammer closed; bracket submitted or in progress. */
    CONFIRMED,
    /** Hammer close outside the 15m sweep range. */
    INVALIDATED,
    /** No qualifying hammer within three 5m bars (15 minutes). */
    EXPIRED,
    /** Valid hammer but projected gross profit to 15m TP below minimum. */
    REJECTED_INSUFFICIENT_GROSS_PROFIT
}

@Serializable
data class FiveMinuteConfirmationState(
    val status: FiveMinuteConfirmationStatus = FiveMinuteConfirmationStatus.AWAITING,
    /** Extreme of the 15m liquidity sweep (low for longs, high for shorts). */
    val sweepPrice: Double,
    val sweepActiveStartedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    /** IB bar times for 5m bars already evaluated (dedupe on poll). */
    val processedBarTimes: List<String> = emptyList(),
    /** Closed 5m bars evaluated during the confirmation window (for post-session recap). */
    val evaluatedBars: List<OhlcBar> = emptyList(),
    /** Last validated hammer bar, set when [status] becomes [FiveMinuteConfirmationStatus.CONFIRMED]. */
    val confirmedHammerBar: OhlcBar? = null
)

object FiveMinuteConfirmationLogic {
    const val BAR_DURATION_MS = 5 * 60 * 1000L
    const val TTL_MS = 3 * BAR_DURATION_MS
    const val MAX_BARS = 3

    fun stateAfterBarEvaluated(state: FiveMinuteConfirmationState, bar: OhlcBar): FiveMinuteConfirmationState {
        val barTime = bar.time ?: return state
        if (barTime in state.processedBarTimes) return state
        return state.copy(
            processedBarTimes = state.processedBarTimes + barTime,
            evaluatedBars = state.evaluatedBars + bar
        )
    }

    /** Bars to display in recap UI — prefers full [evaluatedBars], falls back to confirmed hammer only. */
    fun displayEvaluatedBars(state: FiveMinuteConfirmationState): List<OhlcBar> =
        state.evaluatedBars.ifEmpty {
            state.confirmedHammerBar?.let(::listOf).orEmpty()
        }

    /** Module runs only when enabled for reversal mode; ignored when invert/continuation is on. */
    fun shouldUseModule(rules: TouchTurnRuleConfig): Boolean =
        TouchTurnRuleConfig.isFiveMinuteConfirmationEffective(rules)

    fun shouldBypass(rules: TouchTurnRuleConfig): Boolean = !shouldUseModule(rules)

    /** Sweep level from the completed 15m liquidity bar. */
    fun sweepPrice(candle: OhlcBar, side: TouchTurnTradeSide): Double = when (side) {
        TouchTurnTradeSide.LONG -> candle.low
        TouchTurnTradeSide.SHORT -> candle.high
    }

    fun initialState(
        candle: OhlcBar,
        side: TouchTurnTradeSide,
        nowEpochMillis: Long
    ): FiveMinuteConfirmationState {
        val startedAt = nowEpochMillis
        return FiveMinuteConfirmationState(
            status = FiveMinuteConfirmationStatus.AWAITING,
            sweepPrice = sweepPrice(candle, side),
            sweepActiveStartedAtEpochMs = startedAt,
            expiresAtEpochMs = startedAt + TTL_MS
        )
    }

    fun isExpired(state: FiveMinuteConfirmationState, nowEpochMillis: Long): Boolean =
        nowEpochMillis >= state.expiresAtEpochMs

    data class HammerEvaluation(
        val isHammer: Boolean,
        val closeInsideSweepRange: Boolean,
        val invalidatesSetup: Boolean
    )

    /**
     * Validates hammer geometry and that [bar.close] remains inside the 15m bar range
     * anchored at [sweepPrice] ([fifteenMinuteBar.low] .. [fifteenMinuteBar.high]).
     */
    fun evaluateHammer(
        bar: OhlcBar,
        side: TouchTurnTradeSide,
        fifteenMinuteBar: OhlcBar
    ): HammerEvaluation {
        val closeInside = bar.close in fifteenMinuteBar.low..fifteenMinuteBar.high
        if (!closeInside) {
            return HammerEvaluation(isHammer = false, closeInsideSweepRange = false, invalidatesSetup = true)
        }
        val isHammer = isHammerPattern(bar, side)
        return HammerEvaluation(
            isHammer = isHammer,
            closeInsideSweepRange = true,
            invalidatesSetup = false
        )
    }

    fun isHammerPattern(bar: OhlcBar, side: TouchTurnTradeSide): Boolean {
        val range = bar.range
        if (range <= 0.0) return false
        val body = abs(bar.close - bar.open)
        if (body / range > TouchTurnDefaults.FIVE_MIN_HAMMER_MAX_BODY_RATIO) return false
        val upperShadow = bar.high - max(bar.open, bar.close)
        val lowerShadow = minOf(bar.open, bar.close) - bar.low
        val rejectionShadow = when (side) {
            TouchTurnTradeSide.LONG -> lowerShadow
            TouchTurnTradeSide.SHORT -> upperShadow
        }
        val oppositeShadow = when (side) {
            TouchTurnTradeSide.LONG -> upperShadow
            TouchTurnTradeSide.SHORT -> lowerShadow
        }
        if (body <= 0.0) return false
        if (rejectionShadow < body * TouchTurnDefaults.FIVE_MIN_HAMMER_MIN_REJECTION_BODY_RATIO) return false
        if (oppositeShadow / range > TouchTurnDefaults.FIVE_MIN_HAMMER_MAX_OPPOSITE_SHADOW_RATIO) return false
        return true
    }

    /**
     * Bracket for the 5m confirmation path: MKT entry at [marketEntry], fixed 15m take-profit,
     * stop recomputed from market entry using [TouchTurnRuleConfig.takeProfitToStopLossRatio].
     */
    fun buildConfirmationSetup(
        fifteenMinuteSetup: TouchTurnBracketSetup,
        marketEntry: Double,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): TouchTurnBracketSetup {
        val takeProfit = fifteenMinuteSetup.takeProfit
        val tpDistance = when (fifteenMinuteSetup.side) {
            TouchTurnTradeSide.LONG -> takeProfit - marketEntry
            TouchTurnTradeSide.SHORT -> marketEntry - takeProfit
        }
        val stopDistance = tpDistance / rules.takeProfitToStopLossRatio
        val stopLoss = when (fifteenMinuteSetup.side) {
            TouchTurnTradeSide.LONG -> marketEntry - stopDistance
            TouchTurnTradeSide.SHORT -> marketEntry + stopDistance
        }
        return fifteenMinuteSetup.copy(
            entry = marketEntry,
            stopLoss = stopLoss,
            takeProfit = takeProfit
        )
    }

    fun applyMarketEntryToFifteenMinuteSetup(
        fifteenMinuteSetup: TouchTurnBracketSetup,
        hammerBar: OhlcBar,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): TouchTurnBracketSetup =
        buildConfirmationSetup(fifteenMinuteSetup, hammerBar.close, rules)

    fun passesGrossProfitGate(
        fifteenMinuteSetup: TouchTurnBracketSetup,
        hammerBar: OhlcBar,
        quantity: Int,
        minGrossProfit: Double
    ): Boolean = TouchTurnGrossProfitGate.passes(
        setup = fifteenMinuteSetup,
        entryPrice = hammerBar.close,
        quantity = quantity,
        minGrossProfit = minGrossProfit
    )

    /** @deprecated Use [TouchTurnGrossProfitGate.INSUFFICIENT_GROSS_PROFIT_MESSAGE] */
    const val INSUFFICIENT_GROSS_PROFIT_MESSAGE = TouchTurnGrossProfitGate.INSUFFICIENT_GROSS_PROFIT_MESSAGE

    /** True when the bar's open time is at or after [afterBarOpenEpochMs] and the bar has closed. */
    fun isClosedBarAfter(
        bar: OhlcBar,
        marketZoneId: String,
        afterBarOpenEpochMs: Long,
        nowEpochMillis: Long
    ): Boolean {
        val barOpen = TouchTurnLogic.barStartEpochMillis(bar.time ?: return false, marketZoneId) ?: return false
        if (barOpen < afterBarOpenEpochMs) return false
        val barEnd = barOpen + BAR_DURATION_MS
        return nowEpochMillis >= barEnd
    }
}
