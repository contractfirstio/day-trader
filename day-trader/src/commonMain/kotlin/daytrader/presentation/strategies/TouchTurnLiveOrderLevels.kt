package daytrader.presentation.strategies

import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnPlannedBracket
import daytrader.gateway.WorkingOrder
import kotlin.math.abs
import kotlin.math.max

enum class TouchTurnOrderLevelKind {
    ENTRY,
    TAKE_PROFIT,
    STOP_LOSS,
    OTHER
}

data class TouchTurnOrderLevelUi(
    val price: Double,
    val label: String,
    val kind: TouchTurnOrderLevelKind
)

object TouchTurnLiveOrderLevels {
    fun chartLevels(
        openOrders: List<WorkingOrder>,
        plannedBracket: TouchTurnPlannedBracket?,
        bracketSetup: TouchTurnBracketSetup?
    ): List<TouchTurnOrderLevelUi> {
        plannedBracket?.let { return levelsFromPlannedBracket(it) }
        return fromWorkingOrders(openOrders, plannedBracket, bracketSetup)
    }

    fun levelsFromPlannedBracket(bracket: TouchTurnPlannedBracket): List<TouchTurnOrderLevelUi> =
        listOf(
            TouchTurnOrderLevelUi(bracket.entry, "Entry", TouchTurnOrderLevelKind.ENTRY),
            TouchTurnOrderLevelUi(bracket.takeProfit, "Take profit", TouchTurnOrderLevelKind.TAKE_PROFIT),
            TouchTurnOrderLevelUi(bracket.stopLoss, "Stop loss", TouchTurnOrderLevelKind.STOP_LOSS)
        )

    fun fromWorkingOrders(
        openOrders: List<WorkingOrder>,
        plannedBracket: TouchTurnPlannedBracket?,
        bracketSetup: TouchTurnBracketSetup?
    ): List<TouchTurnOrderLevelUi> =
        openOrders.mapNotNull { order -> levelFor(order, plannedBracket, bracketSetup) }

    private fun levelFor(
        order: WorkingOrder,
        plannedBracket: TouchTurnPlannedBracket?,
        bracketSetup: TouchTurnBracketSetup?
    ): TouchTurnOrderLevelUi? {
        val price = order.limitPrice?.takeIf { it > 0.0 }
            ?: order.stopPrice?.takeIf { it > 0.0 }
            ?: return null
        val kind = classify(price, order, plannedBracket, bracketSetup)
        val label = labelFor(kind, order)
        return TouchTurnOrderLevelUi(price = price, label = label, kind = kind)
    }

    private fun classify(
        price: Double,
        order: WorkingOrder,
        plannedBracket: TouchTurnPlannedBracket?,
        bracketSetup: TouchTurnBracketSetup?
    ): TouchTurnOrderLevelKind {
        plannedBracket?.let { bracket ->
            when {
                pricesNear(price, bracket.entry) -> return TouchTurnOrderLevelKind.ENTRY
                pricesNear(price, bracket.takeProfit) -> return TouchTurnOrderLevelKind.TAKE_PROFIT
                pricesNear(price, bracket.stopLoss) -> return TouchTurnOrderLevelKind.STOP_LOSS
            }
        }
        bracketSetup?.let { setup ->
            when {
                pricesNear(price, setup.entry) -> return TouchTurnOrderLevelKind.ENTRY
                pricesNear(price, setup.takeProfit) -> return TouchTurnOrderLevelKind.TAKE_PROFIT
                pricesNear(price, setup.stopLoss) -> return TouchTurnOrderLevelKind.STOP_LOSS
            }
        }
        return when {
            order.orderType.equals("STP", ignoreCase = true) -> TouchTurnOrderLevelKind.STOP_LOSS
            order.parentOrderId == 0 -> TouchTurnOrderLevelKind.ENTRY
            else -> TouchTurnOrderLevelKind.TAKE_PROFIT
        }
    }

    private fun labelFor(kind: TouchTurnOrderLevelKind, order: WorkingOrder): String = when (kind) {
        TouchTurnOrderLevelKind.ENTRY -> "Entry"
        TouchTurnOrderLevelKind.TAKE_PROFIT -> "Take profit"
        TouchTurnOrderLevelKind.STOP_LOSS -> "Stop loss"
        TouchTurnOrderLevelKind.OTHER -> "${order.action} ${order.orderType}"
    }

    private fun pricesNear(a: Double, b: Double): Boolean {
        val tolerance = max(abs(b) * 1e-4, 0.001)
        return abs(a - b) <= tolerance
    }
}
