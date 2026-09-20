package daytrader.broker

import daytrader.diagnostics.TimestampedConsoleLog
import java.math.BigDecimal

/**
 * IBKR Pro Tiered per-fill commission model for emulated commission reports.
 *
 * Limit orders are treated as maker (commission only). Stop orders are treated as taker
 * (commission plus pass-through exchange fees). Delegates rates to [ExpectedRoundTripCommission].
 */
class TransactionCostCalculator {
    fun calculateCommission(shares: Int, orderType: String): BigDecimal =
        ExpectedRoundTripCommission.estimateUsLeg(shares, orderType)

    fun validateEdge(grossRealizedPnL: BigDecimal, totalCommission: BigDecimal) {
        if (totalCommission <= BigDecimal.ZERO) return
        val threshold = totalCommission.multiply(EDGE_MULTIPLIER)
        if (grossRealizedPnL < threshold) {
            TimestampedConsoleLog.line(
                tag = "transaction-cost",
                message = "Trade rejected: insufficient edge " +
                    "(grossRealizedPnL=$grossRealizedPnL totalCommission=$totalCommission threshold=$threshold)",
            )
        }
    }

    companion object {
        private val EDGE_MULTIPLIER = BigDecimal("3")

        fun normalizeOrderType(raw: String): String = when (raw.uppercase()) {
            "LMT", "LIMIT" -> "LIMIT"
            "STP", "STP LMT", "STOP", "TRAIL", "MKT" -> "STOP"
            else -> "LIMIT"
        }

        fun isTakerOrderType(orderType: String): Boolean =
            normalizeOrderType(orderType) == "STOP"
    }
}
