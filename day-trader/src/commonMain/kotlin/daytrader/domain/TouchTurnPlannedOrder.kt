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
    /** Stop price when trailing arms; null when trailing disabled. */
    val trailArmStopPrice: Double? = null
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

sealed interface TouchTurnOrderSizingResult {
    data class Ok(val quantity: Int, val rawQuantity: Int) : TouchTurnOrderSizingResult
    data class BelowMinimum(
        val rawQuantity: Int,
        val minimumLot: Int,
        val minimumNotional: Double,
    ) : TouchTurnOrderSizingResult

    data object InvalidInputs : TouchTurnOrderSizingResult
}

object TouchTurnOrderPlanner {
    /** Reversal mode rests a limit at the bar extreme; invert/continuation uses a stop entry on breakout. */
    fun entryOrderType(rules: TouchTurnRuleConfig): String =
        if (rules.invertTradeSide) "STP" else "LMT"

    /** Sizes an order from [maxDollars] and [entryPrice], snapped down to [orderSizeRules]. */
    fun sizeQuantity(
        maxDollars: Int,
        entryPrice: Double,
        orderSizeRules: InstrumentOrderSizeRules = InstrumentOrderSizeRules.DEFAULT
    ): TouchTurnOrderSizingResult {
        if (maxDollars <= 0 || entryPrice <= 0.0) return TouchTurnOrderSizingResult.InvalidInputs
        val raw = max(1, (maxDollars / entryPrice).toInt())
        return when (val snap = orderSizeRules.snapQuantityDown(raw)) {
            is SnapOrderSizeResult.Ok -> TouchTurnOrderSizingResult.Ok(snap.quantity, raw)
            is SnapOrderSizeResult.BelowMinimum -> TouchTurnOrderSizingResult.BelowMinimum(
                rawQuantity = raw,
                minimumLot = snap.minimum,
                minimumNotional = snap.minimum * entryPrice,
            )
        }
    }

    fun insufficientFundsDetailMessage(
        maxDollars: Int,
        currencyCode: String,
        entryPrice: Double,
        sizing: TouchTurnOrderSizingResult.BelowMinimum,
    ): String {
        val currency = currencyCode.trim().ifEmpty { "USD" }
        return buildString {
            append("Max at risk of $maxDollars $currency covers about ${sizing.rawQuantity} shares ")
            append("at entry ${formatPrice(entryPrice)}, but the minimum board lot is ")
            append("${sizing.minimumLot} shares (~${formatPrice(sizing.minimumNotional)} $currency). ")
            append("Increase max at risk or choose a symbol with a smaller minimum lot.")
        }
    }

    /**
     * Suggested share count from [maxDollars] and entry price, snapped down to [orderSizeRules].
     * Returns null when the budget cannot cover the minimum board lot.
     */
    fun suggestedQuantity(
        maxDollars: Int,
        entryPrice: Double,
        orderSizeRules: InstrumentOrderSizeRules = InstrumentOrderSizeRules.DEFAULT
    ): Int? = when (val sizing = sizeQuantity(maxDollars, entryPrice, orderSizeRules)) {
        is TouchTurnOrderSizingResult.Ok -> sizing.quantity
        else -> null
    }

    private fun formatPrice(price: Double): String =
        if (price == price.toLong().toDouble()) price.toLong().toString() else "%.4f".format(price)

    /**
     * Returns a three-leg bracket (entry LMT or STP when inverted, take-profit LMT, stop STP)
     * when [setup] is actionable.
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
        val orderSizeRules = instrument?.orderSizeRules() ?: InstrumentOrderSizeRules.DEFAULT
        val quantity = suggestedQuantity(maxDollars, setup.entry, orderSizeRules) ?: return null
        val exitAction = when (setup.side) {
            TouchTurnTradeSide.SHORT -> "BUY"
            TouchTurnTradeSide.LONG -> "SELL"
        }
        val entryAction = when (setup.side) {
            TouchTurnTradeSide.SHORT -> "SELL"
            TouchTurnTradeSide.LONG -> "BUY"
        }
        val adjustableStop = rules.computeAdjustableStop(
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
                    orderType = entryOrderType(rules),
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
                    trailArmStopPrice = adjustableStop?.armStopPrice
                )
            )
        )
    }

    /**
     * Bracket for the 5-minute hammer confirmation path: parent MKT entry, stop at hammer extreme,
     * take-profit at minimum 2:1 reward:risk from entry close to stop.
     */
    fun buildHammerConfirmationOrderPlan(
        symbol: String,
        hammerBar: OhlcBar,
        side: TouchTurnTradeSide,
        maxDollars: Int,
        currencyCode: String = "USD",
        instrument: InstrumentIdentity? = null,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): TouchTurnOrderPlan? {
        val setup = FiveMinuteConfirmationLogic.computeHammerBracketSetup(hammerBar, side)
        if (!TouchTurnLogic.setupActionableForEntry(setup, rules)) return null
        val orderSizeRules = instrument?.orderSizeRules() ?: InstrumentOrderSizeRules.DEFAULT
        val quantity = suggestedQuantity(maxDollars, setup.entry, orderSizeRules) ?: return null
        val exitAction = when (setup.side) {
            TouchTurnTradeSide.SHORT -> "BUY"
            TouchTurnTradeSide.LONG -> "SELL"
        }
        val entryAction = when (setup.side) {
            TouchTurnTradeSide.SHORT -> "SELL"
            TouchTurnTradeSide.LONG -> "BUY"
        }
        val adjustableStop = rules.computeAdjustableStop(
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
            openingBarClose = hammerBar.close,
            orders = listOf(
                TouchTurnPlannedOrder(
                    role = TouchTurnOrderRole.ENTRY,
                    action = entryAction,
                    orderType = "MKT",
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
                    trailArmStopPrice = adjustableStop?.armStopPrice
                )
            )
        )
    }
}
