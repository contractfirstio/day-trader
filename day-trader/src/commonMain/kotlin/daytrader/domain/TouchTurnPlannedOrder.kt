package daytrader.domain

import kotlin.math.max
import kotlinx.serialization.Serializable

@Serializable
enum class TouchTurnOrderRole {
    ENTRY,
    TAKE_PROFIT,
    STOP_LOSS
}

/** Touch Turn orders are always session/day orders — never GTC or other multi-day TIF. */
object TouchTurnOrderDefaults {
    const val TIME_IN_FORCE = "DAY"
}

/** One leg of the Touch Turn bracket sent to IB or the broker emulator. */
@Serializable
data class TouchTurnPlannedOrder(
    val role: TouchTurnOrderRole,
    val action: String,
    val orderType: String,
    val quantity: Int,
    val price: Double,
    val timeInForce: String = TouchTurnOrderDefaults.TIME_IN_FORCE,
    /** Price at which IB converts the stop to TRAIL (adjustable stop, Option A). */
    val trailTriggerPrice: Double? = null,
    /** Nominal trail distance for IB adjustable trailing stop (amount unit). */
    val trailAmount: Double? = null
)

@Serializable
data class TouchTurnOrderPlan(
    val symbol: String,
    val currencyCode: String,
    val instrument: InstrumentIdentity? = null,
    val side: TouchTurnTradeSide,
    val quantity: Int,
    val orders: List<TouchTurnPlannedOrder>,
    /** First 15m bar close — emulator seeds live price here before walking to the entry limit. */
    val openingBarClose: Double? = null
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
        currencyCode: String = "USD",
        instrument: InstrumentIdentity? = null,
        openingBarClose: Double? = null,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): TouchTurnOrderPlan? {
        if (!TouchTurnLogic.setupActionableForEntry(setup, rules)) return null
        val quantity = suggestedQuantity(maxDollars, setup.entry)
        val exitAction = when (setup.side) {
            TouchTurnTradeSide.SHORT -> "BUY"
            TouchTurnTradeSide.LONG -> "SELL"
        }
        val entryAction = when (setup.side) {
            TouchTurnTradeSide.SHORT -> "SELL"
            TouchTurnTradeSide.LONG -> "BUY"
        }
        val adjustableStop = TouchTurnAdjustableStop.compute(
            entry = setup.entry,
            stopLoss = setup.stopLoss,
            takeProfit = setup.takeProfit
        )
        return TouchTurnOrderPlan(
            symbol = symbol,
            currencyCode = currencyCode,
            instrument = instrument,
            side = setup.side,
            quantity = quantity,
            openingBarClose = openingBarClose?.takeIf { it > 0.0 },
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
                    price = setup.stopLoss,
                    trailTriggerPrice = adjustableStop?.triggerPrice,
                    trailAmount = adjustableStop?.trailAmount
                )
            )
        )
    }
}
