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
    TRAIL_TRIGGER,
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
        val entry = plannedBracket?.entry ?: bracketSetup?.entry
        val takeProfit = plannedBracket?.takeProfit ?: bracketSetup?.takeProfit
        val initialStop = plannedBracket?.stopLoss ?: bracketSetup?.stopLoss
        if (entry != null && takeProfit != null && initialStop != null) {
            return mergedBracketLevels(
                openOrders = openOrders,
                entry = entry,
                takeProfit = takeProfit,
                initialStop = initialStop,
                trailTrigger = plannedBracket?.trailTriggerPrice
                    ?: openOrders.firstOrNull { !it.isTrailAdjustment && it.trailTriggerPrice != null }
                        ?.trailTriggerPrice
            )
        }
        return fromWorkingOrders(openOrders, plannedBracket, bracketSetup)
    }

    private fun mergedBracketLevels(
        openOrders: List<WorkingOrder>,
        entry: Double,
        takeProfit: Double,
        initialStop: Double,
        trailTrigger: Double?
    ): List<TouchTurnOrderLevelUi> {
        val stopOrder = openOrders.firstOrNull { order ->
            !order.isTrailAdjustment &&
                (order.orderType.equals("STP", ignoreCase = true) ||
                    order.orderType.equals("TRAIL", ignoreCase = true))
        }
        val liveStop = stopOrder?.stopPrice?.takeIf { it > 0.0 }
        val stopPrice = liveStop ?: initialStop
        val stopLabel = if (stopOrder?.orderType.equals("TRAIL", ignoreCase = true)) {
            "Trailing stop"
        } else {
            "Stop loss"
        }
        val levels = mutableListOf(
            TouchTurnOrderLevelUi(entry, "Entry", TouchTurnOrderLevelKind.ENTRY),
            TouchTurnOrderLevelUi(takeProfit, "Take profit", TouchTurnOrderLevelKind.TAKE_PROFIT),
            TouchTurnOrderLevelUi(stopPrice, stopLabel, TouchTurnOrderLevelKind.STOP_LOSS)
        )
        trailTrigger?.let { trigger ->
            levels.add(
                TouchTurnOrderLevelUi(
                    price = trigger,
                    label = "Trail trigger",
                    kind = TouchTurnOrderLevelKind.TRAIL_TRIGGER
                )
            )
        }
        return levels
    }

    fun levelsFromPlannedBracket(bracket: TouchTurnPlannedBracket): List<TouchTurnOrderLevelUi> =
        mergedBracketLevels(
            openOrders = emptyList(),
            entry = bracket.entry,
            takeProfit = bracket.takeProfit,
            initialStop = bracket.stopLoss,
            trailTrigger = bracket.trailTriggerPrice
        )

    fun fromWorkingOrders(
        openOrders: List<WorkingOrder>,
        plannedBracket: TouchTurnPlannedBracket?,
        bracketSetup: TouchTurnBracketSetup?
    ): List<TouchTurnOrderLevelUi> =
        openOrders
            .filter { !it.isTrailAdjustment }
            .mapNotNull { order -> levelFor(order, plannedBracket, bracketSetup) }

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
        plannedBracket?.trailTriggerPrice?.let { trigger ->
            if (pricesNear(price, trigger)) return TouchTurnOrderLevelKind.TRAIL_TRIGGER
        }
        order.trailTriggerPrice?.let { trigger ->
            if (pricesNear(price, trigger)) return TouchTurnOrderLevelKind.TRAIL_TRIGGER
        }
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
            order.orderType.equals("TRAIL", ignoreCase = true) -> TouchTurnOrderLevelKind.STOP_LOSS
            order.orderType.equals("STP", ignoreCase = true) -> TouchTurnOrderLevelKind.STOP_LOSS
            order.parentOrderId == 0 -> TouchTurnOrderLevelKind.ENTRY
            else -> TouchTurnOrderLevelKind.TAKE_PROFIT
        }
    }

    private fun labelFor(kind: TouchTurnOrderLevelKind, order: WorkingOrder): String = when (kind) {
        TouchTurnOrderLevelKind.ENTRY -> "Entry"
        TouchTurnOrderLevelKind.TAKE_PROFIT -> "Take profit"
        TouchTurnOrderLevelKind.STOP_LOSS ->
            if (order.orderType.equals("TRAIL", ignoreCase = true)) "Trailing stop" else "Stop loss"
        TouchTurnOrderLevelKind.TRAIL_TRIGGER -> "Trail trigger"
        TouchTurnOrderLevelKind.OTHER -> "${order.action} ${order.orderType}"
    }

    private fun pricesNear(a: Double, b: Double): Boolean {
        val tolerance = max(abs(b) * 1e-4, 0.001)
        return abs(a - b) <= tolerance
    }
}
