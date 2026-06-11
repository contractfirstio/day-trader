package daytrader.broker.emulator

import daytrader.domain.TouchTurnAdjustableStop
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnPlannedOrder

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
                        val trail = widenedTp?.let {
                            TouchTurnAdjustableStop.compute(entry, widenedStop, it)
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

    /** +1 when price must rise to reach take-profit, -1 when it must fall. */
    fun towardTakeProfitDirection(plan: TouchTurnOrderPlan): Int {
        val tp = takeProfitPrice(plan) ?: return 1
        val sl = stopLossPrice(plan) ?: return 1
        return if (tp > sl) 1 else -1
    }
}
