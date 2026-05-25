package daytrader.domain

import daytrader.gateway.AccountPosition
import daytrader.gateway.WorkingOrder
import daytrader.broker.SymbolMarkets

enum class SessionStopAction {
    /** Before stop-after-open deadline, or still within session with exposure. */
    CONTINUE,
    /** Bracket/trade cycle finished (win or loss known) — stop immediately. */
    STOP_TRADE_OUTCOME_KNOWN,
    /** Past deadline with no position and no open orders — stop the instance. */
    STOP_FLAT_AFTER_OPEN,
    /** Had exposure after deadline and RTH session has closed — stop the instance. */
    STOP_AT_MARKET_CLOSE
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

    fun sessionOpenEpochMillis(
        instance: StrategyInstance,
        sessionDateIso: String
    ): Long? {
        val zoneId = SymbolMarkets.zoneId(instance.symbol)
        val barTime = instance.touchTurnSession?.candle?.time
        return TouchTurnLogic.marketOpenEpochMillis(sessionDateIso, zoneId, barTime)
    }

    fun marketCloseEpochMillis(sessionDateIso: String, marketZoneId: String): Long? =
        TouchTurnLogic.marketCloseEpochMillis(sessionDateIso, marketZoneId)

    fun stopAfterOpenDeadlineEpochMillis(
        sessionOpenEpochMillis: Long,
        stopAfterMinOpen: Int
    ): Long = sessionOpenEpochMillis + stopAfterMinOpen * 60_000L

    fun evaluate(
        nowEpochMillis: Long,
        sessionOpenEpochMillis: Long,
        marketCloseEpochMillis: Long,
        stopAfterMinOpen: Int,
        hasOpenPosition: Boolean,
        hasOpenOrders: Boolean
    ): SessionStopAction {
        val stopDeadline = stopAfterOpenDeadlineEpochMillis(sessionOpenEpochMillis, stopAfterMinOpen)
        if (nowEpochMillis < stopDeadline) return SessionStopAction.CONTINUE
        if (!hasOpenPosition && !hasOpenOrders) return SessionStopAction.STOP_FLAT_AFTER_OPEN
        if (nowEpochMillis >= marketCloseEpochMillis) return SessionStopAction.STOP_AT_MARKET_CLOSE
        return SessionStopAction.CONTINUE
    }

    fun evaluateForInstance(
        instance: StrategyInstance,
        stopAfterMinOpen: Int,
        positions: List<AccountPosition>,
        openOrders: List<WorkingOrder>,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): SessionStopAction? {
        val sessionDate = sessionDateForRunningInstance(instance) ?: return null
        val open = sessionOpenEpochMillis(instance, sessionDate) ?: return null
        val close = marketCloseEpochMillis(sessionDate, SymbolMarkets.zoneId(instance.symbol)) ?: return null
        return evaluate(
            nowEpochMillis = nowEpochMillis,
            sessionOpenEpochMillis = open,
            marketCloseEpochMillis = close,
            stopAfterMinOpen = stopAfterMinOpen,
            hasOpenPosition = SymbolMarkets.hasOpenPosition(instance.symbol, positions),
            hasOpenOrders = SymbolMarkets.hasOpenOrders(instance.symbol, openOrders)
        )
    }

    fun millisUntilStopAfterOpen(
        sessionOpenEpochMillis: Long,
        stopAfterMinOpen: Int,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Long {
        val deadline = stopAfterOpenDeadlineEpochMillis(sessionOpenEpochMillis, stopAfterMinOpen)
        return (deadline - nowEpochMillis).coerceAtLeast(0)
    }

    fun pendingStopAfterOpenLabel(millisRemaining: Long, stopAfterMinOpen: Int): String {
        val minutes = (millisRemaining / 60_000).toInt()
        val seconds = ((millisRemaining % 60_000) / 1000).toInt()
        val timing = if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
        return "Auto-stop if flat in $timing (${stopAfterMinOpen}m after open)"
    }
}
