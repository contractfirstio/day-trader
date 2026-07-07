package daytrader.domain

import kotlin.math.abs
import kotlin.math.max

/** Determines which Touch Turn bracket legs filled from broker session trades. */
object TouchTurnBracketExecution {
    fun resolveFromTrades(
        trades: List<SessionTrade>,
        plannedBracket: TouchTurnPlannedBracket?,
        bracketSetup: TouchTurnBracketSetup?,
        sessionPnl: Double? = null
    ): List<TouchTurnOrderRole> {
        if (trades.isEmpty()) return emptyList()

        val bracket = plannedBracket
            ?: bracketSetup?.toPlannedBracket()
            ?: return entryOnlyIfPresent(trades)

        val legs = mutableListOf<TouchTurnOrderRole>()
        val entryFill = trades.firstOrNull { it.parentOrderId == 0 }
            ?: trades.firstOrNull { pricesNear(it.price, bracket.entry) }
        if (entryFill != null) {
            legs.add(TouchTurnOrderRole.ENTRY)
        }

        val entryOrderId = entryFill?.takeIf { it.parentOrderId == 0 }?.orderId
        val exitFill = findClosingExitFill(trades, entryOrderId)
        if (exitFill != null) {
            classifyExitFill(exitFill, bracket, bracketSetup, sessionPnl)?.let { legs.add(it) }
        }

        return legs.distinct()
    }

    private fun entryOnlyIfPresent(trades: List<SessionTrade>): List<TouchTurnOrderRole> =
        if (trades.any { it.parentOrderId == 0 }) listOf(TouchTurnOrderRole.ENTRY) else emptyList()

    private fun findClosingExitFill(
        trades: List<SessionTrade>,
        entryOrderId: Int?
    ): SessionTrade? {
        val bracketScoped = if (entryOrderId != null) {
            trades.filter { it.orderId == entryOrderId || it.parentOrderId == entryOrderId }
        } else {
            trades
        }
        val exitCandidates = bracketScoped.filter { it.parentOrderId != 0 && it.orderId != entryOrderId }
            .ifEmpty { bracketScoped.filter { it.parentOrderId != 0 } }
        return exitCandidates.filter { it.realizedPnL != null }.lastOrNull()
            ?: exitCandidates.lastOrNull()
            ?: trades.filter { it.realizedPnL != null && it.orderId != entryOrderId }.lastOrNull()
    }

    private fun classifyExitFill(
        exit: SessionTrade,
        bracket: TouchTurnPlannedBracket,
        bracketSetup: TouchTurnBracketSetup?,
        sessionPnl: Double?
    ): TouchTurnOrderRole? {
        exit.realizedPnL?.let { fillPnl ->
            when {
                fillPnl > 0.0 -> return TouchTurnOrderRole.TAKE_PROFIT
                fillPnl < 0.0 -> return TouchTurnOrderRole.STOP_LOSS
            }
        }

        legByPrice(exit.price, bracket.takeProfit, bracket.stopLoss)?.let { return it }

        bracketSetup?.let { setup ->
            legByPrice(exit.price, setup.takeProfit, setup.stopLoss)?.let { return it }
        }

        sessionPnl?.let { pnl ->
            when {
                pnl > 0.0 -> return TouchTurnOrderRole.TAKE_PROFIT
                pnl < 0.0 -> return TouchTurnOrderRole.STOP_LOSS
            }
        }

        return legByNearest(exit.price, bracket.takeProfit, bracket.stopLoss)
    }

    private fun legByPrice(
        price: Double,
        takeProfit: Double,
        stopLoss: Double
    ): TouchTurnOrderRole? = when {
        pricesNear(price, takeProfit) -> TouchTurnOrderRole.TAKE_PROFIT
        pricesNear(price, stopLoss) -> TouchTurnOrderRole.STOP_LOSS
        else -> null
    }

    private fun legByNearest(
        price: Double,
        takeProfit: Double,
        stopLoss: Double
    ): TouchTurnOrderRole {
        val distTp = abs(price - takeProfit)
        val distSl = abs(price - stopLoss)
        return if (distTp <= distSl) TouchTurnOrderRole.TAKE_PROFIT else TouchTurnOrderRole.STOP_LOSS
    }

    private fun TouchTurnBracketSetup.toPlannedBracket(): TouchTurnPlannedBracket =
        TouchTurnPlannedBracket(
            side = side,
            entry = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit
        )

    private fun pricesNear(a: Double, b: Double): Boolean {
        val tolerance = max(abs(b) * 1e-4, 0.01)
        return abs(a - b) <= tolerance
    }
}

fun TouchTurnPlannedBracket.applyToChartSetup(setup: TouchTurnBracketSetup): TouchTurnBracketSetup =
    setup.copy(
        side = side,
        entry = entry,
        stopLoss = stopLoss,
        takeProfit = takeProfit
    )
