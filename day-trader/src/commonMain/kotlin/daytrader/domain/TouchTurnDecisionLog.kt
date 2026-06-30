package daytrader.domain

import daytrader.diagnostics.TimestampedConsoleLog
import daytrader.presentation.Formatters

/**
 * Console diagnostics for Touch Turn liquidity, close confirmation, order gating, bootstrap, and stop.
 * Enabled by default; set `DAY_TRADER_TOUCH_TURN_CANDLE_LOGS=false` to disable (shared with [TouchTurnCandleLog]).
 */
object TouchTurnDecisionLog {
    private val enabled: Boolean
        get() = System.getenv("DAY_TRADER_TOUCH_TURN_CANDLE_LOGS")
            ?.equals("false", ignoreCase = true) != true

    fun deferLiquidityForLiveQuotes(
        instanceId: String,
        symbol: String,
        sessionDate: String,
        entryWindowRemainingMs: Long?,
        nowEpochMillis: Long
    ) {
        if (!enabled) return
        line(
            "liquidity eval deferred instance=$instanceId symbol=$symbol session=$sessionDate " +
                "reason=live_bid_ask_missing entryWindowRemaining=${
                    entryWindowRemainingMs?.let { TouchTurnCandleLogDuration.format(it) } ?: "n/a"
                }"
        )
        detail("  will retry on liquidity poll until entry window closes or quotes arrive")
    }

    fun liquidityEvaluated(
        instanceId: String,
        symbol: String,
        session: TouchTurnSessionContext,
        setup: TouchTurnBracketSetup,
        enforceCloseConfirmation: Boolean,
        closeConfirmation: TouchTurnCloseConfirmation,
        entryOrdersPermitted: Boolean,
        decisionOutcome: TouchTurnSessionOutcome?,
        nowEpochMillis: Long
    ) {
        if (!enabled) return
        val candle = session.candle ?: return
        val currency = session.currencyCode
        val liquidityEval = session.liquidityEvaluation(nowEpochMillis)
        val closeConfirmationRemaining = TouchTurnLogic.closeConfirmationRemainingMillis(
            candle,
            session.marketZoneId,
            nowEpochMillis
        )
        line(
            "liquidity evaluated instance=$instanceId symbol=$symbol session=${session.sessionDate} " +
                "evaluation=${liquidityEval.name}"
        )
        detail(
            "  bar O=${fmt(candle.open, currency)} H=${fmt(candle.high, currency)} " +
                "L=${fmt(candle.low, currency)} C=${fmt(candle.close, currency)} " +
                "range=${fmt(candle.range, currency)} threshold=${fmt(session.rangeThreshold, currency)} " +
                "adr14=${session.adr14?.let { fmt(it, currency) } ?: "null"}"
        )
        detail(
            "  bracket ${setup.candleColor.name} ${TouchTurnLogic.tradeSideLabel(setup.side)} " +
                "entry=${fmt(setup.entry, currency)} stop=${fmt(setup.stopLoss, currency)} " +
                "tp=${fmt(setup.takeProfit, currency)} liquidityCandle=${setup.isLiquidityCandle} " +
                "actionable=${setup.isActionable}"
        )
        closeConfirmationDetail(
            instanceId = instanceId,
            symbol = symbol,
            session = session,
            setup = setup,
            enforceCloseConfirmation = enforceCloseConfirmation,
            closeConfirmation = closeConfirmation,
            nowEpochMillis = nowEpochMillis,
            context = "liquidity_evaluated"
        )
        detail(
            "  entryOrdersPermitted=$entryOrdersPermitted " +
                "closeConfirmationRemaining=${
                    closeConfirmationRemaining?.let { TouchTurnCandleLogDuration.format(it) } ?: "n/a"
                } decisionOutcome=${decisionOutcome?.name ?: "null"}"
        )
        decisionHint(decisionOutcome)
    }

    fun closeConfirmationDetail(
        instanceId: String,
        symbol: String,
        session: TouchTurnSessionContext,
        setup: TouchTurnBracketSetup?,
        enforceCloseConfirmation: Boolean,
        closeConfirmation: TouchTurnCloseConfirmation,
        nowEpochMillis: Long,
        context: String
    ) {
        if (!enabled) return
        val candle = session.candle
        val barTime = candle?.time ?: "null"
        val barEnd = candle?.time?.let { TouchTurnLogic.barEndEpochMillis(it, session.marketZoneId) }
        val withinDeadline = candle?.let {
            TouchTurnLogic.closeConfirmationWithinDeadline(it, session.marketZoneId, nowEpochMillis)
        }
        val confirmsTurn = setup?.let { s ->
            candle?.let { TouchTurnLogic.closeConfirmsTurn(s, it) }
        }
        val millisAfterBarEnd = barEnd?.let { (nowEpochMillis - it).coerceAtLeast(0) }
        line(
            "close confirmation context=$context instance=$instanceId symbol=$symbol " +
                "result=$closeConfirmation enforce=$enforceCloseConfirmation"
        )
        detail(
            "  barTime=$barTime barEndEpoch=${barEnd ?: "null"} " +
                "nowEpoch=$nowEpochMillis millisAfterBarEnd=${millisAfterBarEnd ?: "n/a"} " +
                "within1mDeadline=$withinDeadline"
        )
        setup?.let { s ->
            val close = candle?.close ?: 0.0
            detail(
                "  ${closeConfirmationRule(s, close)} confirmsTurn=$confirmsTurn " +
                    "close=${fmt(close, session.currencyCode)} entry=${fmt(s.entry, session.currencyCode)}"
            )
        }
        detail(
            "  persisted closeConfirmedAt=${session.milestones.closeConfirmedAt != null} " +
                "decisionOutcome=${session.decisionOutcome?.name ?: "null"}"
        )
    }

    fun bootstrapCandleClosed(
        instanceId: String,
        symbol: String,
        session: TouchTurnSessionContext,
        enforceCloseConfirmation: Boolean,
        nowEpochMillis: Long
    ) {
        if (!enabled) return
        line(
            "bootstrap candle closed instance=$instanceId symbol=$symbol " +
                "session=${session.sessionDate} enforceCloseConfirmation=$enforceCloseConfirmation"
        )
        session.setup?.let { setup ->
            closeConfirmationDetail(
                instanceId = instanceId,
                symbol = symbol,
                session = session,
                setup = setup,
                enforceCloseConfirmation = enforceCloseConfirmation,
                closeConfirmation = session.closeConfirmation(nowEpochMillis),
                nowEpochMillis = nowEpochMillis,
                context = "bootstrap_pre_eval"
            )
        } ?: detail("  setup=null (will compute on withLiquidityEvaluatedIfClosed)")
    }

    fun bootstrapBranch(
        instanceId: String,
        symbol: String,
        branch: String,
        session: TouchTurnSessionContext?,
        ordersPlaced: Boolean = false,
        nowEpochMillis: Long = System.currentTimeMillis()
    ) {
        if (!enabled) return
        line(
            "bootstrap branch=$branch instance=$instanceId symbol=$symbol " +
                "ordersPlaced=$ordersPlaced"
        )
        session?.let {
            detail(
                "  decisionOutcome=${it.decisionOutcome?.name ?: "null"} " +
                    "closeConfirmation=${it.closeConfirmation(nowEpochMillis)} " +
                    "entryOrdersPermitted=${it.entryOrdersPermitted} " +
                    "ordersPlacedForSession=${it.ordersPlacedForSession}"
            )
            milestonesSummary(it.milestones)
        }
    }

    fun ordersSkipped(
        instanceId: String,
        symbol: String,
        reason: String,
        session: TouchTurnSessionContext? = null,
        nowEpochMillis: Long = System.currentTimeMillis()
    ) {
        if (!enabled) return
        line("orders skipped instance=$instanceId symbol=$symbol reason=$reason")
        session?.let {
            detail(
                "  decisionOutcome=${it.decisionOutcome?.name ?: "null"} " +
                    "closeConfirmation=${it.closeConfirmation(nowEpochMillis)} " +
                    "entryOrdersPermitted=${it.entryOrdersPermitted} ordersPlaced=${it.ordersPlacedForSession}"
            )
        }
    }

    fun noTradeStopCheck(
        instanceId: String,
        symbol: String,
        wouldStop: Boolean,
        decisionOutcome: TouchTurnSessionOutcome?,
        source: String
    ) {
        if (!enabled) return
        line(
            "no-trade stop check source=$source instance=$instanceId symbol=$symbol " +
                "wouldStop=$wouldStop decisionOutcome=${decisionOutcome?.name ?: "null"}"
        )
        if (wouldStop) {
            detail("  → requesting immediate session stop (NO_TRADE_DECISION)")
        }
    }

    fun sessionStopping(
        instanceId: String,
        symbol: String,
        trigger: String,
        session: TouchTurnSessionContext?,
        nowEpochMillis: Long = System.currentTimeMillis()
    ) {
        if (!enabled) return
        line(
            "session stopping instance=$instanceId symbol=$symbol trigger=$trigger " +
                "decisionOutcome=${session?.decisionOutcome?.name ?: "null"}"
        )
        session?.let {
            detail(
                "  closeConfirmation=${it.closeConfirmation(nowEpochMillis)} " +
                    "entryOrdersPermitted=${it.entryOrdersPermitted} " +
                    "ordersPlaced=${it.ordersPlacedForSession}"
            )
            milestonesSummary(it.milestones)
        }
    }

    fun watchPollExit(
        instanceId: String,
        symbol: String,
        reason: String
    ) {
        if (!enabled) return
        line("liquidity watch ended instance=$instanceId symbol=$symbol reason=$reason")
    }

    fun watchPollTick(
        instanceId: String,
        symbol: String,
        closeStatus: FirstCandleCloseStatus,
        hasSetup: Boolean,
        nowEpochMillis: Long
    ) {
        if (!enabled) return
        if (closeStatus == FirstCandleCloseStatus.CLOSED && !hasSetup) return
        line(
            "liquidity watch poll instance=$instanceId symbol=$symbol " +
                "closeStatus=$closeStatus hasSetup=$hasSetup"
        )
    }

    private fun milestonesSummary(milestones: TouchTurnMilestoneTimestamps) {
        detail(
            "  milestones starting=${milestones.startingSessionAt != null} " +
                "dataReady=${milestones.dataReadyAt != null} dataFailed=${milestones.dataFailedAt != null} " +
                "barClosed=${milestones.barClosedAt != null} liquidity=${milestones.liquidityEvaluatedAt != null} " +
                "closeConfirmed=${milestones.closeConfirmedAt != null} orders=${milestones.ordersPlacedAt != null} " +
                "position=${milestones.positionOpenedAt != null} closing=${milestones.closingSessionAt != null}"
        )
    }

    private fun decisionHint(decisionOutcome: TouchTurnSessionOutcome?) {
        when (decisionOutcome) {
            TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED ->
                detail(
                    "  HINT: close confirmation must run within 1 minute of 15m bar close — " +
                        "start the session before bar close + 1m"
                )
            TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED ->
                detail("  HINT: close did not confirm the turn vs entry (see closeConfirmation rule above)")
            TouchTurnSessionOutcome.NO_TRADE_BOUNCE_REJECTION_FAILED,
            TouchTurnSessionOutcome.NO_TRADE_BOUNCE_DATA_UNAVAILABLE ->
                detail("  HINT: legacy bounce-rejection outcome (rule removed)")
            TouchTurnSessionOutcome.NO_TRADE_LIVE_CLOSE_CONFIRMATION_FAILED ->
                detail(
                    "  HINT: completed bar passed but live bid/ask mid is no longer on the confirming side of entry"
                )
            TouchTurnSessionOutcome.NO_TRADE_BAR_LIVE_DIVERGENCE ->
                detail(
                    "  HINT: bar close and live bid/ask mid differ by more than " +
                        "${(TouchTurnDefaults.BAR_LIVE_DIVERGENCE_MAX_RATIO_OF_RANGE * 100).toInt()}% of bar range"
                )
            TouchTurnSessionOutcome.NO_TRADE_ENTRY_NOT_TOUCHABLE ->
                detail("  HINT: live bid/ask already through entry — resting limit would fill as marketable")
            TouchTurnSessionOutcome.NO_TRADE_INVERT_ENTRY_MARKETABLE ->
                detail(
                    "  HINT: legacy invert gate — entry marketable without stop trigger (no longer blocks placement)"
                )
            TouchTurnSessionOutcome.NO_TRADE_INVERT_STOP_WOULD_TRIGGER ->
                detail(
                    "  HINT: invert trade side — stop entry and protective stop would " +
                        "trigger on the same quote; bracket not placed"
                )
            TouchTurnSessionOutcome.NO_TRADE_LIVE_QUOTE_UNAVAILABLE ->
                detail(
                    "  HINT: live bid/ask required for hybrid mode but still missing when entry window closed"
                )
            TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY ->
                detail("  HINT: bar range must exceed 25% of 14-day ADR")
            TouchTurnSessionOutcome.NO_TRADE_DOJI ->
                detail("  HINT: DOJI / non-actionable candle — no bracket")
            TouchTurnSessionOutcome.NO_TRADE_MACRO_TREND_MISALIGNED ->
                detail("  HINT: macro trend alignment — green short needs home-market bear, red long needs bull")
            TouchTurnSessionOutcome.NO_TRADE_MACRO_TREND_DATA_UNAVAILABLE ->
                detail("  HINT: home-market index regime data unavailable — check IB index subscription / contract")
            TouchTurnSessionOutcome.NO_TRADE_STOCK_TREND_MISALIGNED ->
                detail("  HINT: stock trend alignment — green short needs downtrend, red long needs uptrend")
            TouchTurnSessionOutcome.NO_TRADE_STOCK_TREND_DATA_UNAVAILABLE ->
                detail("  HINT: symbol daily trend data unavailable — check IB historical request")
            TouchTurnSessionOutcome.NO_TRADE_INSUFFICIENT_MAX_DOLLARS_FOR_MIN_LOT ->
                detail(
                    "  HINT: max at risk cannot cover one minimum board lot at entry — " +
                        "increase max dollars or trade a cheaper symbol / smaller lot"
                )
            else -> Unit
        }
    }

    private fun closeConfirmationRule(setup: TouchTurnBracketSetup, close: Double): String {
        val zoneRule = when (setup.candleColor) {
            FirstCandleColor.GREEN ->
                "close in lower ≤${(TouchTurnDefaults.CLOSE_POSITION_SHORT_MAX * 100).toInt()}% of bar range"
            FirstCandleColor.RED ->
                "close in upper ≥${(TouchTurnDefaults.CLOSE_POSITION_LONG_MIN * 100).toInt()}% of bar range"
            FirstCandleColor.DOJI -> "DOJI not actionable"
        }
        return when (setup.candleColor) {
            FirstCandleColor.RED,
            FirstCandleColor.GREEN ->
                "(rule: $zoneRule; close=$close, entry=${setup.entry})"
            FirstCandleColor.DOJI -> "(rule: DOJI not actionable)"
        }
    }

    private fun fmt(price: Double, currency: String): String = Formatters.moneyPlain(price, currency)

    private fun line(message: String) {
        TimestampedConsoleLog.line("TouchTurn", message)
    }

    private fun detail(message: String) {
        TimestampedConsoleLog.line("TouchTurn", message)
    }
}

/** Shared duration formatting for decision logs (avoids exposing [TouchTurnCandleLog] internals). */
internal object TouchTurnCandleLogDuration {
    fun format(millis: Long): String {
        val totalSec = millis / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m}m ${s}s"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
}
