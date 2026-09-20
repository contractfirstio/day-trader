package daytrader.domain

import daytrader.broker.ExpectedRoundTripCommission
import kotlin.math.max

/**
 * Pre-execution check that projected net max profit / max loss meets
 * [TouchTurnRuleConfig.minProfitToLossRatio] (0 disables).
 */
object TouchTurnGrossProfitGate {
    const val INSUFFICIENT_PROFIT_TO_LOSS_RATIO_MESSAGE =
        "Rejected: Insufficient Profit/Loss Ratio"

    /** @deprecated Use [INSUFFICIENT_PROFIT_TO_LOSS_RATIO_MESSAGE] */
    const val INSUFFICIENT_GROSS_PROFIT_MESSAGE = INSUFFICIENT_PROFIT_TO_LOSS_RATIO_MESSAGE

    private const val MAX_LOSS_EPSILON = 1e-9

    /**
     * Projected P&L if take-profit fills at [takeProfitPrice] after entry at [entryPrice],
     * minus expected round-trip commission for the market.
     */
    fun projectedMaxProfit(
        takeProfitPrice: Double,
        entryPrice: Double,
        quantity: Int,
        side: TouchTurnTradeSide,
        currency: String = "USD",
        primaryExch: String? = null,
        exchange: String? = null,
    ): Double {
        val perShare = when (side) {
            TouchTurnTradeSide.LONG -> takeProfitPrice - entryPrice
            TouchTurnTradeSide.SHORT -> entryPrice - takeProfitPrice
        }
        val gross = max(0.0, perShare) * quantity
        if (gross <= 0.0) return 0.0
        val commission = ExpectedRoundTripCommission.estimateRoundTrip(
            quantity = quantity,
            entryPrice = entryPrice,
            exitPrice = takeProfitPrice,
            currency = currency,
            primaryExch = primaryExch,
            exchange = exchange,
            entryOrderType = "LMT",
            exitOrderType = "LMT",
        )
        return max(0.0, gross - commission)
    }

    /** @deprecated Use [projectedMaxProfit] */
    fun projectedGrossProfit(
        takeProfitPrice: Double,
        entryPrice: Double,
        quantity: Int,
        side: TouchTurnTradeSide,
        currency: String = "USD",
        primaryExch: String? = null,
        exchange: String? = null,
    ): Double = projectedMaxProfit(
        takeProfitPrice = takeProfitPrice,
        entryPrice = entryPrice,
        quantity = quantity,
        side = side,
        currency = currency,
        primaryExch = primaryExch,
        exchange = exchange,
    )

    /**
     * Absolute downside if stop fills: `-min(0, stopOutcome)` where stopOutcome is signed
     * stop P&L minus expected RT commission (entry LMT, exit STP). Min-win / flat → 0.
     */
    fun projectedMaxLoss(
        stopLossPrice: Double,
        entryPrice: Double,
        quantity: Int,
        side: TouchTurnTradeSide,
        currency: String = "USD",
        primaryExch: String? = null,
        exchange: String? = null,
    ): Double {
        if (quantity <= 0) return 0.0
        val perShare = when (side) {
            TouchTurnTradeSide.LONG -> stopLossPrice - entryPrice
            TouchTurnTradeSide.SHORT -> entryPrice - stopLossPrice
        }
        val gross = perShare * quantity
        val commission = ExpectedRoundTripCommission.estimateRoundTrip(
            quantity = quantity,
            entryPrice = entryPrice,
            exitPrice = stopLossPrice,
            currency = currency,
            primaryExch = primaryExch,
            exchange = exchange,
            entryOrderType = "LMT",
            exitOrderType = "STP",
        )
        val stopOutcome = gross - commission
        return if (stopOutcome < 0.0) -stopOutcome else 0.0
    }

    /** Net maxProfit / maxLoss, or null when there is no meaningful downside. */
    fun projectedRatio(
        setup: TouchTurnBracketSetup,
        entryPrice: Double,
        quantity: Int,
        currency: String = "USD",
        primaryExch: String? = null,
        exchange: String? = null,
    ): Double? {
        val maxLoss = projectedMaxLoss(
            stopLossPrice = setup.stopLoss,
            entryPrice = entryPrice,
            quantity = quantity,
            side = setup.side,
            currency = currency,
            primaryExch = primaryExch,
            exchange = exchange,
        )
        if (maxLoss <= MAX_LOSS_EPSILON) return null
        val maxProfit = projectedMaxProfit(
            takeProfitPrice = setup.takeProfit,
            entryPrice = entryPrice,
            quantity = quantity,
            side = setup.side,
            currency = currency,
            primaryExch = primaryExch,
            exchange = exchange,
        )
        return maxProfit / maxLoss
    }

    fun passes(
        setup: TouchTurnBracketSetup,
        entryPrice: Double,
        quantity: Int,
        minProfitToLossRatio: Double,
        currency: String = "USD",
        primaryExch: String? = null,
        exchange: String? = null,
    ): Boolean {
        if (minProfitToLossRatio <= 0.0) return true
        val ratio = projectedRatio(
            setup = setup,
            entryPrice = entryPrice,
            quantity = quantity,
            currency = currency,
            primaryExch = primaryExch,
            exchange = exchange,
        ) ?: return false
        return ratio >= minProfitToLossRatio
    }
}
