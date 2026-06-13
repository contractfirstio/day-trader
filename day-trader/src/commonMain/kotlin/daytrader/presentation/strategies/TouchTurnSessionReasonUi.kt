package daytrader.presentation.strategies

import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.LiquidityCandleEvaluation
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnCloseConfirmation
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStopTrigger

enum class TouchTurnReasonSeverity {
    Info,
    Warning,
    Error
}

/** User-visible explanation for why a Touch Turn session did or did not trade. */
data class TouchTurnSessionStatusUi(
    val headline: String,
    val detail: String? = null,
    val severity: TouchTurnReasonSeverity = TouchTurnReasonSeverity.Info
)

/**
 * Plain-language reasons for no trade, order skips, cancellations, and session stop.
 * Used by pipeline captions, detail banners, and session history.
 */
object TouchTurnSessionReasonUi {

    fun forDecisionOutcome(
        outcome: TouchTurnSessionOutcome,
        session: TouchTurnSessionContext? = null
    ): TouchTurnSessionStatusUi = when (outcome) {
        TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED -> {
            val liquidityRefetch = session?.failedDuringLiquidityRefetch() == true
            TouchTurnSessionStatusUi(
                headline = if (liquidityRefetch) {
                    "No trade — closed bar data unavailable"
                } else {
                    "No trade — market data failed"
                },
                detail = session?.errorMessage?.takeIf { it.isNotBlank() }
                    ?: if (liquidityRefetch) {
                        "The opening bar closed but final 15-minute OHLC could not be loaded from the broker after refetch. Check connectivity and symbol history, then start a new session."
                    } else {
                        "Could not load opening 15-minute bar and ATR(14) from the broker. Check connectivity and symbol history, then start a new session."
                    },
                severity = TouchTurnReasonSeverity.Error
            )
        }
        TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY -> TouchTurnSessionStatusUi(
            headline = "No trade — bar not liquid",
            detail = "Opening 15-minute range did not exceed 25% of 14-day ADR. Bracket orders were not placed.",
            severity = TouchTurnReasonSeverity.Warning
        )
        TouchTurnSessionOutcome.NO_TRADE_VOLUME_EXHAUSTION -> TouchTurnSessionStatusUi(
            headline = "No trade — volume exhaustion",
            detail = "Opening bar volume exceeded the exhaustion threshold (high-conviction breakout filter). Bracket orders were not placed.",
            severity = TouchTurnReasonSeverity.Warning
        )
        TouchTurnSessionOutcome.NO_TRADE_MACRO_TREND_MISALIGNED -> TouchTurnSessionStatusUi(
            headline = "No trade — macro trend misaligned",
            detail = "Macro trend alignment blocked entry on a prior strategy version. Bracket orders were not placed.",
            severity = TouchTurnReasonSeverity.Warning
        )
        TouchTurnSessionOutcome.NO_TRADE_MACRO_TREND_DATA_UNAVAILABLE -> {
            val benchmark = session?.macroBenchmarkLabel ?: "home market index"
            TouchTurnSessionStatusUi(
                headline = "No trade — macro trend unavailable",
                detail = "Macro trend alignment is enabled but $benchmark regime data could not be gathered from IB " +
                    "(index history or live quote). Bracket orders were not placed.",
                severity = TouchTurnReasonSeverity.Warning
            )
        }
        TouchTurnSessionOutcome.NO_TRADE_STOCK_TREND_MISALIGNED -> TouchTurnSessionStatusUi(
            headline = "No trade — stock trend misaligned",
            detail = "Stock trend alignment blocked entry on a prior strategy version. Bracket orders were not placed.",
            severity = TouchTurnReasonSeverity.Warning
        )
        TouchTurnSessionOutcome.NO_TRADE_STOCK_TREND_DATA_UNAVAILABLE -> TouchTurnSessionStatusUi(
            headline = "No trade — stock trend unavailable",
            detail = "Stock trend alignment is enabled but daily trend data could not be gathered from IB. " +
                "Bracket orders were not placed.",
            severity = TouchTurnReasonSeverity.Warning
        )
        TouchTurnSessionOutcome.NO_TRADE_DOJI -> TouchTurnSessionStatusUi(
            headline = "No trade — non-actionable bar",
            detail = "Opening candle is a doji or flat body — no directional entry. Bracket orders were not placed.",
            severity = TouchTurnReasonSeverity.Warning
        )
        TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED -> TouchTurnSessionStatusUi(
            headline = "No trade — close did not confirm turn",
            detail = "After the bar closed, price did not confirm the touch-and-turn entry within the allowed close-position band.",
            severity = TouchTurnReasonSeverity.Warning
        )
        TouchTurnSessionOutcome.NO_TRADE_LIVE_CLOSE_CONFIRMATION_FAILED -> TouchTurnSessionStatusUi(
            headline = "No trade — live price did not confirm turn",
            detail = "The completed bar passed close confirmation, but the current live price is no longer on the confirming side of the entry level.",
            severity = TouchTurnReasonSeverity.Warning
        )
        TouchTurnSessionOutcome.NO_TRADE_BAR_LIVE_DIVERGENCE -> TouchTurnSessionStatusUi(
            headline = "No trade — bar and live price disagree",
            detail = "The completed 15-minute bar close and the live bid/ask mid differ by more than 25% of the bar range. The tape has already moved away from the candle the strategy used.",
            severity = TouchTurnReasonSeverity.Warning
        )
        TouchTurnSessionOutcome.NO_TRADE_ENTRY_NOT_TOUCHABLE -> TouchTurnSessionStatusUi(
            headline = "No trade — entry not touchable",
            detail = "Live price has already moved through the entry level; a resting limit would fill immediately above or below the market.",
            severity = TouchTurnReasonSeverity.Warning
        )
        TouchTurnSessionOutcome.NO_TRADE_LIVE_QUOTE_UNAVAILABLE -> TouchTurnSessionStatusUi(
            headline = "No trade — live quote unavailable",
            detail = "Live bid/ask were required to validate entry and close confirmation but were not available from the broker feed.",
            severity = TouchTurnReasonSeverity.Warning
        )
        TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED -> TouchTurnSessionStatusUi(
            headline = "No trade — confirmation window expired",
            detail = "Close confirmation must pass within one minute after the opening 15-minute bar closes. The window expired before orders could be sent.",
            severity = TouchTurnReasonSeverity.Warning
        )
        TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED -> TouchTurnSessionStatusUi(
            headline = "No trade — orders not sent",
            detail = "Liquidity and confirmation passed but the bracket was not submitted (broker unavailable, plan rejected, or gateway error). Check logs for bracket_submit / ordersSkipped.",
            severity = TouchTurnReasonSeverity.Error
        )
        TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED -> TouchTurnSessionStatusUi(
            headline = "Bracket orders submitted",
            detail = "Entry, stop, and take-profit were sent to the broker. Session continues until filled, flat, or auto-stop.",
            severity = TouchTurnReasonSeverity.Info
        )
    }

    fun forStopTrigger(
        trigger: TouchTurnSessionStopTrigger,
        stopErrorMessage: String? = null,
        decisionOutcome: TouchTurnSessionOutcome? = null
    ): TouchTurnSessionStatusUi = when (trigger) {
        TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN -> TouchTurnSessionStatusUi(
            headline = "Session stopped — trade cycle complete",
            detail = "Position is flat and entry/exit fills are recorded (or working orders were cleared).",
            severity = TouchTurnReasonSeverity.Info
        )
        TouchTurnSessionStopTrigger.NO_TRADE_DECISION -> decisionOutcome?.let { forDecisionOutcome(it) }
            ?: TouchTurnSessionStatusUi(
                headline = "Session stopped — no trade",
                detail = "Liquidity, confirmation, or data gates resolved without placing bracket orders.",
                severity = TouchTurnReasonSeverity.Warning
            )
        TouchTurnSessionStopTrigger.OPEN_DEADLINE -> TouchTurnSessionStatusUi(
            headline = "Session stopped — open deadline",
            detail = "Maximum time after RTH open elapsed. Working orders were cancelled and any open position was flattened.",
            severity = TouchTurnReasonSeverity.Warning
        )
        TouchTurnSessionStopTrigger.PRE_MARKET_CLOSE -> TouchTurnSessionStatusUi(
            headline = "Session stopped — before market close",
            detail = "Deployment stopped ahead of the scheduled pre-close window.",
            severity = TouchTurnReasonSeverity.Warning
        )
        TouchTurnSessionStopTrigger.MANUAL -> TouchTurnSessionStatusUi(
            headline = "Session stopped — manual",
            detail = "You stopped the deployment. Working orders were cancelled when possible.",
            severity = TouchTurnReasonSeverity.Info
        )
        TouchTurnSessionStopTrigger.ERROR -> TouchTurnSessionStatusUi(
            headline = "Session stopped — error",
            detail = stopErrorMessage?.takeIf { it.isNotBlank() }
                ?: "Session ended due to a data or engine error.",
            severity = TouchTurnReasonSeverity.Error
        )
        TouchTurnSessionStopTrigger.APPLICATION_SHUTDOWN -> TouchTurnSessionStatusUi(
            headline = "Session stopped — app closed",
            detail = "The application exited or restarted while this session was in progress.",
            severity = TouchTurnReasonSeverity.Warning
        )
    }

    fun bracketEntryFilled(session: TouchTurnSessionContext?, hasOpenPosition: Boolean): Boolean =
        hasOpenPosition || session?.milestones?.positionOpenedAt != null

    fun liveStatus(
        session: TouchTurnSessionContext?,
        hasOpenPosition: Boolean,
        hasOpenOrders: Boolean,
        closing: Boolean,
        nowEpochMillis: Long,
        deploymentRunning: Boolean = false
    ): TouchTurnSessionStatusUi? {
        if (session == null) return null

        session.decisionOutcome?.let { outcome ->
            if (outcome != TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED) {
                return forDecisionOutcome(outcome, session)
            }
        }

        when (session.status) {
            TouchTurnCandleStatus.LOADING -> return TouchTurnSessionStatusUi(
                headline = "Loading session data…",
                detail = "Fetching 14-day ATR and the opening 15-minute RTH bar from the broker.",
                severity = TouchTurnReasonSeverity.Info
            )
            TouchTurnCandleStatus.FAILED -> return forDecisionOutcome(
                TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
                session
            )
            TouchTurnCandleStatus.READY -> Unit
        }

        if (hasOpenPosition) {
            return inPositionStatus(hasOpenOrders)
        }

        if (hasOpenOrders && !session.ordersPlacedForSession) {
            return TouchTurnSessionStatusUi(
                headline = "Broker orders on symbol — not from this session",
                detail = "Open orders at the broker are not tied to this session's bracket. Cancel stale orders in TWS or use a clean paper account before trading.",
                severity = TouchTurnReasonSeverity.Warning
            )
        }

        if (session.ordersPlacedForSession || session.decisionOutcome == TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED) {
            val entryFilled = bracketEntryFilled(session, hasOpenPosition)
            return when {
                hasOpenPosition -> inPositionStatus(hasOpenOrders)
                hasOpenOrders -> TouchTurnSessionStatusUi(
                    headline = "Waiting for entry fill",
                    detail = "Bracket is at the broker. Entry, stop, and take-profit remain working until price touches entry or orders are cancelled.",
                    severity = TouchTurnReasonSeverity.Info
                )
                entryFilled -> TouchTurnSessionStatusUi(
                    headline = "Entry filled — completing trade cycle",
                    detail = "Entry fill was recorded. The session stops once the round trip is flat and working orders are cleared.",
                    severity = TouchTurnReasonSeverity.Info
                )
                closing && deploymentRunning -> TouchTurnSessionStatusUi(
                    headline = "Stopping session — open deadline",
                    detail = "The open deadline was reached. Working bracket orders are being cancelled.",
                    severity = TouchTurnReasonSeverity.Warning
                )
                closing -> TouchTurnSessionStatusUi(
                    headline = "Bracket closed without fill",
                    detail = "Orders were submitted but no entry fill was recorded before the session ended.",
                    severity = TouchTurnReasonSeverity.Warning
                )
                else -> TouchTurnSessionStatusUi(
                    headline = "Bracket submitted — awaiting broker",
                    detail = "Orders were sent; waiting for open-order visibility at the broker.",
                    severity = TouchTurnReasonSeverity.Info
                )
            }
        }

        val milestones = session.milestones
        if (milestones.barClosedAt == null) {
            return TouchTurnSessionStatusUi(
                headline = "Waiting for opening bar to close",
                detail = "Liquidity and close confirmation run after the first 15-minute RTH candle completes.",
                severity = TouchTurnReasonSeverity.Info
            )
        }
        if (milestones.liquidityEvaluatedAt == null) {
            return TouchTurnSessionStatusUi(
                headline = "Evaluating liquidity…",
                detail = if (session.candle == null) {
                    "Loading final bar OHLC, then comparing range to 25% of 14-day ATR."
                } else {
                    "Comparing opening bar range to 25% of 14-day ATR."
                },
                severity = TouchTurnReasonSeverity.Info
            )
        }

        session.decisionOutcome?.let { outcome ->
            if (outcome != TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED) {
                return forDecisionOutcome(outcome, session)
            }
        }

        if (session.entryOrdersPermitted == false) {
            return TouchTurnSessionStatusUi(
                headline = "Entry not permitted",
                detail = pendingEntryBlockDetail(session, nowEpochMillis),
                severity = TouchTurnReasonSeverity.Warning
            )
        }

        if (session.entryOrdersPermitted == true && !session.ordersPlacedForSession) {
            return TouchTurnSessionStatusUi(
                headline = "Placing bracket orders…",
                detail = "Liquidity and close confirmation passed; submitting entry, stop, and take-profit to the broker.",
                severity = TouchTurnReasonSeverity.Info
            )
        }

        when (session.pipelineCloseConfirmation(nowEpochMillis)) {
            TouchTurnCloseConfirmation.FAILED ->
                return forDecisionOutcome(TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED, session)
            TouchTurnCloseConfirmation.EXPIRED ->
                return forDecisionOutcome(TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED, session)
            TouchTurnCloseConfirmation.AWAITING_LIQUIDITY,
            TouchTurnCloseConfirmation.UNKNOWN -> return TouchTurnSessionStatusUi(
                headline = "Close confirmation pending",
                detail = "Checking whether the bar close confirms the touch-and-turn entry (1 minute after bar close).",
                severity = TouchTurnReasonSeverity.Info
            )
            TouchTurnCloseConfirmation.PASSED -> Unit
        }

        return null
    }

    fun pendingEntryBlockDetail(session: TouchTurnSessionContext, nowEpochMillis: Long): String {
        val setup = session.setup
        return when {
            setup == null -> "Bracket setup is not ready yet."
            !setup.isLiquidityCandle -> "Bar failed the liquidity range check."
            session.closeConfirmation(nowEpochMillis) == TouchTurnCloseConfirmation.FAILED ->
                "Close confirmation failed — entry band not satisfied."
            session.closeConfirmation(nowEpochMillis) == TouchTurnCloseConfirmation.EXPIRED ->
                "Close confirmation window expired."
            else -> "A gate blocked entry (liquidity or confirmation still pending)."
        }
    }

    fun orderLifecycleMessage(
        phase: TouchTurnOrderLifecyclePhase,
        session: TouchTurnSessionContext?
    ): String? = when (phase) {
        TouchTurnOrderLifecyclePhase.NOT_PLACED -> session?.decisionOutcome
            ?.takeIf { it != TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED }
            ?.let { forDecisionOutcome(it, session).headline }
        TouchTurnOrderLifecyclePhase.SUBMITTED_PENDING_BROKER_VISIBILITY ->
            "Bracket queued with broker; awaiting acknowledgment and open-order visibility."
        TouchTurnOrderLifecyclePhase.AWAITING_ENTRY ->
            "Entry order working — waiting for price to touch the planned entry level."
        TouchTurnOrderLifecyclePhase.IN_POSITION ->
            "Position open — take-profit and stop-loss orders should be working at the broker."
        TouchTurnOrderLifecyclePhase.CLOSED_NO_FILL ->
            "Bracket ended without an entry fill (cancelled, expired, or session stopped)."
        TouchTurnOrderLifecyclePhase.CLOSED -> null
    }

    private fun inPositionStatus(hasOpenOrders: Boolean): TouchTurnSessionStatusUi =
        if (hasOpenOrders) {
            TouchTurnSessionStatusUi(
                headline = "In position — TP / SL working",
                detail = "Manage the trade on the chart or via broker orders. Session auto-stops when flat after a completed cycle.",
                severity = TouchTurnReasonSeverity.Info
            )
        } else {
            TouchTurnSessionStatusUi(
                headline = "In position — no protective orders",
                detail = "Open position with no working stop or take-profit at the broker. Flatten manually or stop the session.",
                severity = TouchTurnReasonSeverity.Warning
            )
        }
}
