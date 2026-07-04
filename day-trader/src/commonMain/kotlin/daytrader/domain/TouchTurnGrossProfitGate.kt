package daytrader.domain

import kotlin.math.abs

/** Pre-execution check that projected gross profit to take-profit meets [TouchTurnRuleConfig.minGrossProfit]. */
object TouchTurnGrossProfitGate {
    const val INSUFFICIENT_GROSS_PROFIT_MESSAGE = "Rejected: Insufficient Gross Profit Potential"

    /** Gross profit if take-profit fills at [takeProfitPrice] after entry at [entryPrice]. */
    fun projectedGrossProfit(
        takeProfitPrice: Double,
        entryPrice: Double,
        quantity: Int
    ): Double = abs(takeProfitPrice - entryPrice) * quantity

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
            quantity = quantity
        ) >= minGrossProfit
    }
}
