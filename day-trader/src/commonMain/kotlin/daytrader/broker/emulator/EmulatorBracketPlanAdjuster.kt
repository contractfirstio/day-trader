package daytrader.broker.emulator

import daytrader.domain.TouchTurnAdjustableStop
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnPlannedOrder
import kotlin.math.abs

/**
 * Emulator-only adjustments to bracket exit prices (wider stop/TP spread than strategy math).
 */
internal object EmulatorBracketPlanAdjuster {
    fun widenExits(plan: TouchTurnOrderPlan, spreadWidenFactor: Double): TouchTurnOrderPlan {
        if (spreadWidenFactor <= 1.0) return plan
        val entry = plan.orders.firstOrNull { it.role == TouchTurnOrderRole.ENTRY }?.price ?: return plan
        val takeProfit = plan.orders.firstOrNull { it.role == TouchTurnOrderRole.TAKE_PROFIT }?.price
        return plan.copy(
            orders = plan.orders.map { leg ->
                when (leg.role) {
                    TouchTurnOrderRole.TAKE_PROFIT ->
                        leg.copy(price = widenFromEntry(entry, leg.price, spreadWidenFactor))
                    TouchTurnOrderRole.STOP_LOSS -> {
                        val widenedStop = widenFromEntry(entry, leg.price, spreadWidenFactor)
                        val widenedTp = takeProfit?.let { widenFromEntry(entry, it, spreadWidenFactor) }
                        val trail = when {
                            leg.trailTriggerPrice == null || leg.trailAmount == null -> null
                            widenedTp == null || takeProfit == null -> null
                            else -> {
                                val triggerFraction = inferTriggerFraction(
                                    entry = entry,
                                    takeProfit = takeProfit,
                                    trailTriggerPrice = leg.trailTriggerPrice
                                )
                                val trailFraction = inferTrailFraction(
                                    entry = entry,
                                    stopLoss = leg.price,
                                    trailAmount = leg.trailAmount
                                )
                                if (triggerFraction == null || trailFraction == null) {
                                    null
                                } else {
                                    TouchTurnAdjustableStop.compute(
                                        entry = entry,
                                        stopLoss = widenedStop,
                                        takeProfit = widenedTp,
                                        triggerFraction = triggerFraction,
                                        trailFraction = trailFraction
                                    )
                                }
                            }
                        }
                        leg.copy(
                            price = widenedStop,
                            trailTriggerPrice = trail?.triggerPrice,
                            trailAmount = trail?.trailAmount
                        )
                    }
                    else -> leg
                }
            }
        )
    }

    /** Moves [price] further from [entry] by [factor] (>1 widens the bracket). */
    fun widenFromEntry(entry: Double, price: Double, factor: Double): Double {
        val delta = price - entry
        if (delta == 0.0) return price
        return entry + delta * factor
    }

    fun takeProfitPrice(plan: TouchTurnOrderPlan): Double? =
        plan.orders.firstOrNull { it.role == TouchTurnOrderRole.TAKE_PROFIT }?.price

    fun stopLossPrice(plan: TouchTurnOrderPlan): Double? =
        plan.orders.firstOrNull { it.role == TouchTurnOrderRole.STOP_LOSS }?.price

    private fun inferTriggerFraction(
        entry: Double,
        takeProfit: Double,
        trailTriggerPrice: Double
    ): Double? {
        val entryToTp = takeProfit - entry
        if (abs(entryToTp) < 1e-9) return null
        return (trailTriggerPrice - entry) / entryToTp
    }

    private fun inferTrailFraction(entry: Double, stopLoss: Double, trailAmount: Double): Double? {
        val entryToStop = stopLoss - entry
        if (abs(entryToStop) < 1e-9) return null
        return trailAmount / abs(entryToStop)
    }

    /** +1 when price must rise to reach take-profit, -1 when it must fall. */
    fun towardTakeProfitDirection(plan: TouchTurnOrderPlan): Int {
        val tp = takeProfitPrice(plan) ?: return 1
        val sl = stopLossPrice(plan) ?: return 1
        return if (tp > sl) 1 else -1
    }
}
