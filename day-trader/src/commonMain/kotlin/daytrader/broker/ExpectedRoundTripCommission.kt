package daytrader.broker

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil

/**
 * Ballpark pre-trade round-trip commission for max-profit / stop-outcome / profit-gate math.
 *
 * - **SEHK / HKD:** IB Tiered stocks (0.05% of trade value, min HKD 18) plus published
 *   third-party fees (exchange, clearing, SFC, FRC, stamp duty).
 * - **US / USD:** IBKR Pro Tiered share model (same rates as [TransactionCostCalculator]).
 * - Other markets: `0.0` (no estimate).
 */
object ExpectedRoundTripCommission {
    fun estimateRoundTrip(
        quantity: Int,
        entryPrice: Double,
        exitPrice: Double,
        currency: String,
        primaryExch: String? = null,
        exchange: String? = null,
        entryOrderType: String = "LMT",
        exitOrderType: String = "LMT",
    ): Double {
        if (quantity <= 0 || entryPrice <= 0.0 || exitPrice <= 0.0) return 0.0
        return when {
            isSehk(currency, primaryExch, exchange) -> {
                val entry = estimateSehkLeg(quantity, entryPrice)
                val exit = estimateSehkLeg(quantity, exitPrice)
                (entry + exit).setScale(2, RoundingMode.HALF_UP).toDouble()
            }
            isUsEquity(currency, primaryExch, exchange) -> {
                val entry = estimateUsLeg(quantity, entryOrderType)
                val exit = estimateUsLeg(quantity, exitOrderType)
                (entry + exit).setScale(2, RoundingMode.HALF_UP).toDouble()
            }
            else -> 0.0
        }
    }

    /** Single-leg US IBKR Pro Tiered commission (maker or taker). */
    fun estimateUsLeg(shares: Int, orderType: String): BigDecimal {
        require(shares > 0) { "shares must be positive" }
        val perShare = if (TransactionCostCalculator.isTakerOrderType(orderType)) {
            US_COMMISSION_PER_SHARE.add(US_TAKER_EXCHANGE_FEE_PER_SHARE)
        } else {
            US_COMMISSION_PER_SHARE
        }
        return perShare.multiply(BigDecimal(shares))
            .max(US_MIN_COMMISSION_PER_ORDER)
            .setScale(2, RoundingMode.HALF_UP)
    }

    private fun estimateSehkLeg(quantity: Int, price: Double): BigDecimal {
        val notional = BigDecimal.valueOf(quantity.toLong())
            .multiply(BigDecimal.valueOf(price))
            .setScale(8, RoundingMode.HALF_UP)
        val ib = notional.multiply(SEHK_IB_TIERED_RATE)
            .max(SEHK_IB_MIN_PER_ORDER)
            .setScale(2, RoundingMode.HALF_UP)
        val levies = notional.multiply(SEHK_THIRD_PARTY_LEVY_RATE)
            .setScale(2, RoundingMode.HALF_UP)
        val stamp = BigDecimal.valueOf(ceil(notional.toDouble() * SEHK_STAMP_DUTY_RATE))
            .setScale(2, RoundingMode.HALF_UP)
        return ib.add(levies).add(stamp)
    }

    private fun isSehk(currency: String, primaryExch: String?, exchange: String?): Boolean {
        val exch = listOfNotNull(primaryExch, exchange).joinToString(" ").uppercase()
        if (exch.contains("SEHK")) return true
        return currency.trim().uppercase() == "HKD"
    }

    private fun isUsEquity(currency: String, primaryExch: String?, exchange: String?): Boolean {
        if (isSehk(currency, primaryExch, exchange)) return false
        return currency.trim().uppercase() == "USD"
    }

    private val US_COMMISSION_PER_SHARE = BigDecimal("0.0035")
    private val US_MIN_COMMISSION_PER_ORDER = BigDecimal("0.35")
    private val US_TAKER_EXCHANGE_FEE_PER_SHARE = BigDecimal("0.0030")

    private val SEHK_IB_TIERED_RATE = BigDecimal("0.0005")
    private val SEHK_IB_MIN_PER_ORDER = BigDecimal("18.00")
    /** Exchange + clearing + SFC + FRC (excludes stamp). */
    private val SEHK_THIRD_PARTY_LEVY_RATE = BigDecimal("0.00012715")
    private const val SEHK_STAMP_DUTY_RATE = 0.001
}
