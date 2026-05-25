package daytrader.domain

/** Cross-strategy run-stop outcomes; strategy-specific rules live in per-type helpers (e.g. [TouchTurnRunStopLogic]). */
enum class InstanceRunStopAction {
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

object InstanceRunStopLogic {
    /**
     * True when this run had a completed round-trip (entry + exit fill), is flat, and
     * session P&L (win/loss) can be recorded on stop.
     */
    fun shouldStopAfterTradeOutcome(
        instance: StrategyInstance,
        sessionTrades: List<SessionTrade>,
        hasOpenPosition: Boolean,
        hasOpenOrders: Boolean
    ): Boolean {
        if (hasOpenPosition || hasOpenOrders) return false
        if (!hadTradeActivity(instance, sessionTrades)) return false
        return tradeCycleComplete(sessionTrades)
    }

    fun hadTradeActivity(instance: StrategyInstance, sessionTrades: List<SessionTrade>): Boolean =
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

    fun sessionDateForRunningInstance(instance: StrategyInstance): String? =
        instance.inProgressRun()?.date
            ?: instance.touchTurnSession?.sessionDate
            ?: instance.lastAutoStartSessionDate

    /**
     * Strategy-specific deadline stop (e.g. Touch Turn 90m after open). Returns null when the
     * strategy has no open-deadline rule.
     */
    fun evaluateDeadlineForInstance(
        instance: StrategyInstance,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): InstanceRunStopAction? = when (instance.strategyType) {
        StrategyType.TOUCH_AND_TURN_SCALPER ->
            TouchTurnRunStopLogic.evaluateOpenDeadline(instance, nowEpochMillis)
        StrategyType.QUICK_FLIP_SCALPER -> null
    }
}
