package daytrader.domain

import daytrader.domain.TouchTurnOrderRole.ENTRY
import daytrader.domain.TouchTurnOrderRole.STOP_LOSS
import daytrader.domain.TouchTurnOrderRole.TAKE_PROFIT

object WatchlistBracketOrderPlanner {
    data class BracketOrderOptions(
        val stopEntry: Boolean = false,
        val adjustableTrailingStop: Boolean = true,
        val trailingStopTriggerFractionOfEntryToTp: Double =
            TouchTurnDefaults.TRAILING_STOP_TRIGGER_FRACTION_OF_ENTRY_TO_TP,
        val trailingStopArmFractionOfEntryToStop: Double =
            TouchTurnDefaults.TRAILING_STOP_ARM_FRACTION_OF_ENTRY_TO_STOP
    ) {
        fun entryOrderType(): String = TouchTurnOrderPlanner.entryOrderType(
            TouchTurnRuleConfig.DEFAULT.copy(invertTradeSide = stopEntry)
        )
    }

    fun optionsFromPlan(plan: WatchlistTradePlan): BracketOrderOptions =
        BracketOrderOptions(
            stopEntry = plan.stopEntry,
            adjustableTrailingStop = plan.adjustableTrailingStop
        )

    fun bracketOrderSummary(options: BracketOrderOptions): String {
        val stopLabel = if (options.adjustableTrailingStop) {
            "adjustable trailing stop"
        } else {
            "fixed stop"
        }
        return "DAY entry with GTC take-profit and $stopLabel."
    }

    fun buildTouchTurnPlan(
        symbol: String,
        currencyCode: String,
        instrument: InstrumentIdentity?,
        side: TradeSide,
        entryPrice: Double,
        stopPrice: Double,
        targetPrice: Double,
        quantity: Int,
        options: BracketOrderOptions = BracketOrderOptions()
    ): Result<TouchTurnOrderPlan> {
        if (quantity <= 0) return Result.failure(IllegalArgumentException("Quantity must be at least 1"))
        val orderSizeRules = instrument?.orderSizeRules() ?: InstrumentOrderSizeRules.DEFAULT
        orderSizeRules.validateQuantity(quantity)?.let { message ->
            return Result.failure(IllegalArgumentException(message))
        }
        val draft = WatchlistTradePlan(
            id = "draft",
            label = "Bracket",
            side = side,
            entryPrice = entryPrice,
            stopPrice = stopPrice,
            targetPrice = targetPrice,
            investmentAmount = entryPrice * quantity,
            stopEntry = options.stopEntry,
            adjustableTrailingStop = options.adjustableTrailingStop
        )
        val outcome = WatchlistTradePlanCalculator.compute(draft, orderSizeRules)
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
        val adjustableStop = computeAdjustableStop(
            entry = entryPrice,
            stopLoss = stopPrice,
            takeProfit = targetPrice,
            options = options
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
                        orderType = options.entryOrderType(),
                        quantity = quantity,
                        price = entryPrice,
                        timeInForce = TouchTurnOrderDefaults.timeInForceFor(ENTRY)
                    ),
                    TouchTurnPlannedOrder(
                        role = TAKE_PROFIT,
                        action = exitAction,
                        orderType = "LMT",
                        quantity = quantity,
                        price = targetPrice,
                        timeInForce = TouchTurnOrderDefaults.timeInForceFor(TAKE_PROFIT)
                    ),
                    TouchTurnPlannedOrder(
                        role = STOP_LOSS,
                        action = exitAction,
                        orderType = "STP",
                        quantity = quantity,
                        price = stopPrice,
                        timeInForce = TouchTurnOrderDefaults.timeInForceFor(STOP_LOSS),
                        trailTriggerPrice = adjustableStop?.triggerPrice,
                        trailArmStopPrice = adjustableStop?.armStopPrice,
                        attachAdjustableAtPlacement = adjustableStop != null
                    )
                )
            )
        )
    }

    fun fromWatchlistPlan(
        entry: WatchlistEntry,
        plan: WatchlistTradePlan
    ): Result<TouchTurnOrderPlan> {
        val orderSizeRules = entry.instrument?.orderSizeRules() ?: InstrumentOrderSizeRules.DEFAULT
        val outcome = WatchlistTradePlanCalculator.compute(plan, orderSizeRules)
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
            quantity = outcome.quantity!!,
            options = optionsFromPlan(plan)
        )
    }

    private fun computeAdjustableStop(
        entry: Double,
        stopLoss: Double,
        takeProfit: Double,
        options: BracketOrderOptions
    ): TouchTurnAdjustableStopParams? {
        if (!options.adjustableTrailingStop) return null
        return TouchTurnAdjustableStop.compute(
            entry = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            triggerFraction = options.trailingStopTriggerFractionOfEntryToTp,
            armFractionOfEntryToStop = options.trailingStopArmFractionOfEntryToStop
        )
    }
}
