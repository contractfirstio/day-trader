package daytrader.broker

import daytrader.diagnostics.TimestampedConsoleLog
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * IBKR Pro Tiered per-fill commission model for emulated commission reports.
 *
 * Limit orders are treated as maker (commission only). Stop orders are treated as taker
 * (commission plus pass-through exchange fees).
 */
class TransactionCostCalculator {
    fun calculateCommission(shares: Int, orderType: String): BigDecimal {
        require(shares > 0) { "shares must be positive" }
        val perShareTotal = commissionPerShare(orderType)
        val raw = perShareTotal.multiply(BigDecimal(shares))
        return raw.max(MIN_COMMISSION_PER_ORDER).setScale(2, RoundingMode.HALF_UP)
    }

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

    private fun commissionPerShare(orderType: String): BigDecimal =
        if (isTakerOrderType(orderType)) {
            COMMISSION_PER_SHARE.add(TAKER_EXCHANGE_FEE_PER_SHARE)
        } else {
            COMMISSION_PER_SHARE
        }

    companion object {
        private val COMMISSION_PER_SHARE = BigDecimal("0.0035")
        private val MIN_COMMISSION_PER_ORDER = BigDecimal("0.35")
        private val TAKER_EXCHANGE_FEE_PER_SHARE = BigDecimal("0.0030")
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
