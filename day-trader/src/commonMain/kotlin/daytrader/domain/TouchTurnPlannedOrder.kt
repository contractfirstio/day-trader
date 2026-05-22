package daytrader.domain

import kotlin.math.max
import kotlinx.serialization.Serializable

@Serializable
enum class TouchTurnOrderRole {
    ENTRY,
    TAKE_PROFIT,
    STOP_LOSS
}

/**
 * One leg of the Touch Turn bracket that would be sent to IB.
 * Built for logging only until live order placement is implemented.
 */
@Serializable
data class TouchTurnPlannedOrder(
    val role: TouchTurnOrderRole,
    val action: String,
    val orderType: String,
    val quantity: Int,
    val price: Double
)

@Serializable
data class TouchTurnOrderPlan(
    val symbol: String,
    val currencyCode: String,
    val side: TouchTurnTradeSide,
    val quantity: Int,
    val orders: List<TouchTurnPlannedOrder>
)

object TouchTurnOrderPlanner {
    /**
     * Suggested share count from [maxDollars] and entry price (minimum 1).
     */
    fun suggestedQuantity(maxDollars: Int, entryPrice: Double): Int {
        if (maxDollars <= 0 || entryPrice <= 0.0) return 1
        return max(1, (maxDollars / entryPrice).toInt())
    }

    /**
     * Returns a three-leg bracket (entry LMT, take-profit LMT, stop STP) when [setup] is actionable.
     */
    fun buildOrderPlan(
        symbol: String,
        setup: TouchTurnBracketSetup,
        maxDollars: Int,
        currencyCode: String = "USD"
    ): TouchTurnOrderPlan? {
        if (!setup.isActionable) return null
        val quantity = suggestedQuantity(maxDollars, setup.entry)
        val exitAction = when (setup.side) {
            TouchTurnTradeSide.SHORT -> "BUY"
            TouchTurnTradeSide.LONG -> "SELL"
        }
        val entryAction = when (setup.side) {
            TouchTurnTradeSide.SHORT -> "SELL"
            TouchTurnTradeSide.LONG -> "BUY"
        }
        return TouchTurnOrderPlan(
            symbol = symbol,
            currencyCode = currencyCode,
            side = setup.side,
            quantity = quantity,
            orders = listOf(
                TouchTurnPlannedOrder(
                    role = TouchTurnOrderRole.ENTRY,
                    action = entryAction,
                    orderType = "LMT",
                    quantity = quantity,
                    price = setup.entry
                ),
                TouchTurnPlannedOrder(
                    role = TouchTurnOrderRole.TAKE_PROFIT,
                    action = exitAction,
                    orderType = "LMT",
                    quantity = quantity,
                    price = setup.takeProfit
                ),
                TouchTurnPlannedOrder(
                    role = TouchTurnOrderRole.STOP_LOSS,
                    action = exitAction,
                    orderType = "STP",
                    quantity = quantity,
                    price = setup.stopLoss
                )
            )
        )
    }
}
