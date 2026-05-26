package daytrader.presentation.strategies

import daytrader.domain.SessionTrade
import daytrader.domain.SessionTradeDetailsBuilder
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnPlannedBracket
import kotlin.math.abs
import kotlin.math.max

/** Which bracket legs actually filled during a closed Touch Turn session. */
object TouchTurnExecutedBracketLegs {
    fun resolve(
        trades: List<SessionTrade>,
        plannedBracket: TouchTurnPlannedBracket?,
        bracketSetup: TouchTurnBracketSetup?
    ): Set<TouchTurnOrderLevelKind> {
        if (trades.isEmpty()) return emptySet()
        val tpRef = plannedBracket?.takeProfit ?: bracketSetup?.takeProfit
        val slRef = plannedBracket?.stopLoss ?: bracketSetup?.stopLoss
        if (tpRef == null && slRef == null && trades.none { it.parentOrderId == 0 }) return emptySet()

        val executed = mutableSetOf<TouchTurnOrderLevelKind>()
        val details = SessionTradeDetailsBuilder.build(trades)

        val hasEntryFill = trades.any { it.parentOrderId == 0 } ||
            details?.entryFill != null ||
            SessionTradeDetailsBuilder.fillDisplays(trades).any { it.roleLabel == "Entry" }
        if (hasEntryFill) {
            executed.add(TouchTurnOrderLevelKind.ENTRY)
        }

        val exitPrices = buildList {
            details?.exitFills?.forEach { add(it.price) }
            details?.exitPrice?.let { add(it) }
            SessionTradeDetailsBuilder.fillDisplays(trades)
                .filter { it.roleLabel == "Exit" }
                .forEach { add(it.price) }
            trades.filter { it.realizedPnL != null }.forEach { add(it.price) }
        }.distinct()

        for (exitPrice in exitPrices) {
            executed.add(classifyExit(exitPrice, tpRef, slRef))
        }

        return executed
    }

    private fun classifyExit(
        exitPrice: Double,
        tpRef: Double?,
        slRef: Double?
    ): TouchTurnOrderLevelKind = when {
        tpRef != null && slRef != null -> {
            if (abs(exitPrice - tpRef) <= abs(exitPrice - slRef)) {
                TouchTurnOrderLevelKind.TAKE_PROFIT
            } else {
                TouchTurnOrderLevelKind.STOP_LOSS
            }
        }
        tpRef != null && pricesNear(exitPrice, tpRef) -> TouchTurnOrderLevelKind.TAKE_PROFIT
        slRef != null && pricesNear(exitPrice, slRef) -> TouchTurnOrderLevelKind.STOP_LOSS
        tpRef != null -> TouchTurnOrderLevelKind.TAKE_PROFIT
        slRef != null -> TouchTurnOrderLevelKind.STOP_LOSS
        else -> TouchTurnOrderLevelKind.OTHER
    }

    private fun pricesNear(a: Double, b: Double): Boolean {
        val tolerance = max(abs(b) * 1e-4, 0.01)
        return abs(a - b) <= tolerance
    }
}
