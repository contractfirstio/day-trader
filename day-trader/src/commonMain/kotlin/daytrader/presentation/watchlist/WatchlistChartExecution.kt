package daytrader.presentation.watchlist

import daytrader.broker.SessionTradeMatcher
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.WatchlistEntry
import daytrader.domain.WatchlistTradePlan
import daytrader.domain.effectivePnL
import daytrader.domain.sessionDisplayPnL
import daytrader.gateway.BrokerFill
import daytrader.presentation.strategies.TouchTurnExecutedBracketLegs
import daytrader.presentation.strategies.TouchTurnOrderLevelKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object WatchlistChartExecution {
    fun executedLevels(
        symbol: String,
        entry: WatchlistEntry?,
        fills: List<BrokerFill>,
        bracketDraft: WatchlistBracketOrderUi? = null,
        planEditors: List<WatchlistPlanEditorUi> = emptyList()
    ): Set<TouchTurnOrderLevelKind> {
        val plan = WatchlistChartLevels.activePlacedPlan(entry, bracketDraft) ?: return emptySet()
        val bracket = WatchlistChartLevels.plannedBracketForEntry(entry, bracketDraft, planEditors)
            ?: return emptySet()
        val trades = sessionTradesForPlan(symbol, plan, fills)
        val sessionPnl = trades.sessionDisplayPnL().takeIf { trades.isNotEmpty() }
        return TouchTurnExecutedBracketLegs.resolve(
            trades = trades,
            plannedBracket = bracket,
            bracketSetup = null,
            sessionPnl = sessionPnl,
            persistedLegs = plan.executedBracketLegs
        )
    }

    fun mergeDetectedExecutedLegs(
        plan: WatchlistTradePlan,
        detected: Set<TouchTurnOrderLevelKind>
    ): List<TouchTurnOrderRole> {
        if (detected.isEmpty()) return plan.executedBracketLegs
        val merged = plan.executedBracketLegs.toMutableSet()
        detected.mapNotNull { kind ->
            when (kind) {
                TouchTurnOrderLevelKind.ENTRY -> TouchTurnOrderRole.ENTRY
                TouchTurnOrderLevelKind.TAKE_PROFIT -> TouchTurnOrderRole.TAKE_PROFIT
                TouchTurnOrderLevelKind.STOP_LOSS -> TouchTurnOrderRole.STOP_LOSS
                TouchTurnOrderLevelKind.TRAIL_TRIGGER -> null
                TouchTurnOrderLevelKind.OTHER -> null
            }
        }.forEach { merged.add(it) }
        return merged.toList()
    }

    private fun sessionTradesForPlan(
        symbol: String,
        plan: WatchlistTradePlan,
        fills: List<BrokerFill>
    ) = SessionTradeMatcher.toSessionTrades(
        fillsForPlacedPlan(symbol, plan, fills)
    )

    private fun fillsForPlacedPlan(
        symbol: String,
        plan: WatchlistTradePlan,
        fills: List<BrokerFill>
    ): List<BrokerFill> {
        val placedAt = plan.orderPlacedAtEpochMs ?: return emptyList()
        val startedAt = Instant.ofEpochMilli(placedAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        var matched = SessionTradeMatcher.fillsForSession(
            symbol = symbol,
            startedAt = startedAt,
            stoppedAt = null,
            fills = fills
        )
        if (plan.placedOrderIds.isNotEmpty()) {
            val ids = plan.placedOrderIds.toSet()
            val scoped = matched.filter { fill ->
                fill.orderId in ids || fill.parentOrderId in ids
            }
            if (scoped.isNotEmpty()) matched = scoped
        }
        return matched
    }
}
