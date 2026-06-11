package daytrader.domain

import daytrader.domain.TouchTurnOrderRole.ENTRY
import daytrader.domain.TouchTurnOrderRole.STOP_LOSS
import daytrader.domain.TouchTurnOrderRole.TAKE_PROFIT

object WatchlistBracketOrderPlanner {
    fun buildTouchTurnPlan(
        symbol: String,
        currencyCode: String,
        instrument: InstrumentIdentity?,
        side: TradeSide,
        entryPrice: Double,
        stopPrice: Double,
        targetPrice: Double,
        quantity: Int
    ): Result<TouchTurnOrderPlan> {
        if (quantity <= 0) return Result.failure(IllegalArgumentException("Quantity must be at least 1"))
        val draft = WatchlistTradePlan(
            id = "draft",
            label = "Bracket",
            side = side,
            entryPrice = entryPrice,
            stopPrice = stopPrice,
            targetPrice = targetPrice,
            investmentAmount = entryPrice * quantity
        )
        val outcome = WatchlistTradePlanCalculator.compute(draft)
        if (outcome.errors.isNotEmpty()) {
            return Result.failure(IllegalArgumentException(outcome.errors.joinToString("; ")))
        }
        val touchSide = when (side) {
            TradeSide.LONG -> TouchTurnTradeSide.LONG
            TradeSide.SHORT -> TouchTurnTradeSide.SHORT
        }
        val exitAction = when (side) {
            TradeSide.LONG -> "SELL"
            TradeSide.SHORT -> "BUY"
        }
        val entryAction = when (side) {
            TradeSide.LONG -> "BUY"
            TradeSide.SHORT -> "SELL"
        }
        val adjustableStop = TouchTurnAdjustableStop.compute(
            entry = entryPrice,
            stopLoss = stopPrice,
            takeProfit = targetPrice
        )
        return Result.success(
            TouchTurnOrderPlan(
                symbol = symbol.trim().uppercase(),
                currencyCode = currencyCode,
                instrument = instrument,
                side = touchSide,
                quantity = quantity,
                orders = listOf(
                    TouchTurnPlannedOrder(
                        role = ENTRY,
                        action = entryAction,
                        orderType = "LMT",
                        quantity = quantity,
                        price = entryPrice
                    ),
                    TouchTurnPlannedOrder(
                        role = TAKE_PROFIT,
                        action = exitAction,
                        orderType = "LMT",
                        quantity = quantity,
                        price = targetPrice
                    ),
                    TouchTurnPlannedOrder(
                        role = STOP_LOSS,
                        action = exitAction,
                        orderType = "STP",
                        quantity = quantity,
                        price = stopPrice,
                        trailTriggerPrice = adjustableStop?.triggerPrice,
                        trailAmount = adjustableStop?.trailAmount
                    )
                )
            )
        )
    }

    fun fromWatchlistPlan(
        entry: WatchlistEntry,
        plan: WatchlistTradePlan
    ): Result<TouchTurnOrderPlan> {
        val outcome = WatchlistTradePlanCalculator.compute(plan)
        if (!outcome.isComplete) {
            return Result.failure(IllegalArgumentException(outcome.errors.joinToString("; ")))
        }
        return buildTouchTurnPlan(
            symbol = entry.symbol,
            currencyCode = entry.currencyCode,
            instrument = entry.instrument,
            side = plan.side,
            entryPrice = plan.entryPrice!!,
            stopPrice = plan.stopPrice!!,
            targetPrice = plan.targetPrice!!,
            quantity = outcome.quantity!!
        )
    }
}
