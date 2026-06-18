package daytrader.domain

/** Cross-strategy run-stop outcomes; strategy-specific rules live in per-type helpers (e.g. [TouchTurnSessionStopLogic]). */
enum class DeploymentSessionStopAction {
    /** Keep running (no stop rule matched). */
    CONTINUE,
    /** Bracket/trade cycle finished (win or loss known) — stop immediately. */
    STOP_TRADE_OUTCOME_KNOWN,
    /**
     * Touch Turn only: past [StrategyCatalog.stopAfterMinOpen] after session open — stop and
     * flatten (cancel working orders and close any open position for the symbol).
     */
    STOP_AFTER_OPEN_DEADLINE
}

object DeploymentSessionStopLogic {
    /**
     * Touch Turn: stop immediately once a no-trade decision is known for the active session.
     * This is market-zone agnostic because it keys off the resolved decision outcome only.
     */
    fun shouldStopAfterNoTradeDecision(instance: StrategyDeployment): Boolean {
        if (!instance.isTouchTurn) return false
        return when (instance.touchTurnSession?.decisionOutcome) {
            TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
            TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
            TouchTurnSessionOutcome.NO_TRADE_DOJI,
            TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
            TouchTurnSessionOutcome.NO_TRADE_BOUNCE_REJECTION_FAILED,
            TouchTurnSessionOutcome.NO_TRADE_BOUNCE_DATA_UNAVAILABLE,
            TouchTurnSessionOutcome.NO_TRADE_LIVE_CLOSE_CONFIRMATION_FAILED,
            TouchTurnSessionOutcome.NO_TRADE_BAR_LIVE_DIVERGENCE,
            TouchTurnSessionOutcome.NO_TRADE_ENTRY_NOT_TOUCHABLE,
            TouchTurnSessionOutcome.NO_TRADE_INVERT_ENTRY_MARKETABLE,
            TouchTurnSessionOutcome.NO_TRADE_INVERT_STOP_WOULD_TRIGGER,
            TouchTurnSessionOutcome.NO_TRADE_LIVE_QUOTE_UNAVAILABLE,
            TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED,
            TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED,
            TouchTurnSessionOutcome.NO_TRADE_VOLUME_EXHAUSTION,
            TouchTurnSessionOutcome.NO_TRADE_MACRO_TREND_MISALIGNED,
            TouchTurnSessionOutcome.NO_TRADE_MACRO_TREND_DATA_UNAVAILABLE,
            TouchTurnSessionOutcome.NO_TRADE_STOCK_TREND_MISALIGNED,
            TouchTurnSessionOutcome.NO_TRADE_STOCK_TREND_DATA_UNAVAILABLE -> true
            TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
            null -> false
        }
    }

    /**
     * True when this run had a completed round-trip (entry + exit fill), is flat, and
     * session P&L (win/loss) can be recorded on stop.
     */
    fun shouldStopAfterTradeOutcome(
        instance: StrategyDeployment,
        sessionTrades: List<SessionTrade>,
        hasOpenPosition: Boolean,
        hasOpenOrders: Boolean
    ): Boolean {
        if (hasOpenPosition || hasOpenOrders) return false
        if (!hadTradeActivity(instance, sessionTrades)) return false
        return tradeCycleComplete(sessionTrades)
    }

    fun hadTradeActivity(instance: StrategyDeployment, sessionTrades: List<SessionTrade>): Boolean =
        when (instance.strategyType) {
            StrategyType.TOUCH_AND_TURN_SCALPER ->
                instance.touchTurnSession?.sessionOrdersPlaced() == true
            StrategyType.QUICK_FLIP_SCALPER ->
                sessionTrades.isNotEmpty() || instance.live.state == ExecutionState.FILLED
        }

    fun tradeCycleComplete(sessionTrades: List<SessionTrade>): Boolean {
        val hasEntry = sessionTrades.any { it.parentOrderId == 0 }
        val hasExit = sessionTrades.any { it.parentOrderId != 0 }
        return hasEntry && hasExit
    }

    /**
     * Prefer Touch Turn live session date because it is anchored to the strategy market zone.
     * In-progress row date can be based on local machine date and drift for non-US sessions.
     */
    fun sessionDateForRunningInstance(instance: StrategyDeployment): String? =
        instance.touchTurnSession?.sessionDate
            ?: instance.inProgressSession()?.date
            ?: instance.lastAutoStartSessionDate

    /**
     * Strategy-specific deadline stop (e.g. Touch Turn 2h after open). Returns null when the
     * strategy has no open-deadline rule.
     */
    fun evaluateDeadlineForInstance(
        instance: StrategyDeployment,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): DeploymentSessionStopAction? = when (instance.strategyType) {
        StrategyType.TOUCH_AND_TURN_SCALPER ->
            TouchTurnSessionStopLogic.evaluateOpenDeadline(instance, nowEpochMillis)
        StrategyType.QUICK_FLIP_SCALPER -> null
    }
}
