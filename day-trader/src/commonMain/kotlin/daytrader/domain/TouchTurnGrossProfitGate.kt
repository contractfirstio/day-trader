package daytrader.domain

import kotlin.math.max

/** Pre-execution check that projected gross profit to take-profit meets [TouchTurnRuleConfig.minGrossProfit]. */
object TouchTurnGrossProfitGate {
    const val INSUFFICIENT_GROSS_PROFIT_MESSAGE = "Rejected: Insufficient Gross Profit Potential"

    /** Gross profit if take-profit fills at [takeProfitPrice] after entry at [entryPrice] for [side]. */
    fun projectedGrossProfit(
        takeProfitPrice: Double,
        entryPrice: Double,
        quantity: Int,
        side: TouchTurnTradeSide
    ): Double {
        val perShare = when (side) {
            TouchTurnTradeSide.LONG -> takeProfitPrice - entryPrice
            TouchTurnTradeSide.SHORT -> entryPrice - takeProfitPrice
        }
        return max(0.0, perShare) * quantity
    }

    fun passes(
        setup: TouchTurnBracketSetup,
        entryPrice: Double,
        quantity: Int,
        minGrossProfit: Double
    ): Boolean {
        if (minGrossProfit <= 0.0) return true
        return projectedGrossProfit(
            takeProfitPrice = setup.takeProfit,
            entryPrice = entryPrice,
            quantity = quantity,
            side = setup.side
        ) >= minGrossProfit
    }
}
