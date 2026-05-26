package daytrader.presentation.strategies

import daytrader.domain.TouchTurnBracketExecution
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnPlannedBracket
import daytrader.domain.SessionTrade

/** Maps domain bracket execution to chart level kinds for the Orders recap preview. */
object TouchTurnExecutedBracketLegs {
    fun resolve(
        trades: List<SessionTrade>,
        plannedBracket: TouchTurnPlannedBracket?,
        bracketSetup: TouchTurnBracketSetup?,
        sessionPnl: Double? = null,
        persistedLegs: List<TouchTurnOrderRole> = emptyList()
    ): Set<TouchTurnOrderLevelKind> {
        if (persistedLegs.isNotEmpty()) return persistedLegs.toLevelKinds()
        return TouchTurnBracketExecution.resolveFromTrades(
            trades = trades,
            plannedBracket = plannedBracket,
            bracketSetup = bracketSetup,
            sessionPnl = sessionPnl
        ).toLevelKinds()
    }
}

fun TouchTurnOrderRole.toOrderLevelKind(): TouchTurnOrderLevelKind = when (this) {
    TouchTurnOrderRole.ENTRY -> TouchTurnOrderLevelKind.ENTRY
    TouchTurnOrderRole.TAKE_PROFIT -> TouchTurnOrderLevelKind.TAKE_PROFIT
    TouchTurnOrderRole.STOP_LOSS -> TouchTurnOrderLevelKind.STOP_LOSS
}

fun List<TouchTurnOrderRole>.toLevelKinds(): Set<TouchTurnOrderLevelKind> =
    map { it.toOrderLevelKind() }.toSet()
