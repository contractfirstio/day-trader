package daytrader.broker

import com.ib.client.Contract
import daytrader.domain.CurrencyCodes

/**
 * IB often quotes UK (and some other) instruments in minor units (pence) while
 * account P&L is in major currency (pounds). [ContractDetails.priceMagnifier]
 * is the divisor to convert API prices into major currency (e.g. 100 for LSE).
 */
internal object IbPriceScale {
    fun defaultMagnifier(contract: Contract): Int {
        val currency = contract.currency().orEmpty().uppercase()
        if (currency in MINOR_UNIT_CURRENCIES) return 100
        val primary = contract.primaryExch().orEmpty().uppercase()
        val exchange = contract.exchange().orEmpty().uppercase()
        val isUk = currency == "GBP" && UK_EXCHANGES.any { uk ->
            primary.contains(uk) || exchange.contains(uk)
        }
        if (isUk) return 100
        return 1
    }

    fun resolveMagnifier(contractDetailsMagnifier: Int, contract: Contract): Int =
        if (contractDetailsMagnifier > 1) contractDetailsMagnifier else defaultMagnifier(contract)

    /** Convert an API price (often pence) to major currency units (pounds) for display/PnL. */
    fun toMajorCurrency(rawPrice: Double, magnifier: Int): Double =
        if (magnifier > 1) rawPrice / magnifier else rawPrice

    /**
     * IB sometimes mixes units: [position.avgCost] in pounds (12.041) while ticks/historical
     * are in pence (1208.5). Apply [contractMagnifier] only where needed.
     */
    fun resolvePriceMagnifiers(
        avgCostRaw: Double,
        marketPriceRaw: Double,
        contractMagnifier: Int
    ): PriceMagnifiers {
        if (contractMagnifier <= 1) return PriceMagnifiers(1, 1)
        if (avgCostRaw <= 0.0 || marketPriceRaw <= 0.0) {
            return PriceMagnifiers(contractMagnifier, contractMagnifier)
        }
        val tolerance = kotlin.math.max(kotlin.math.abs(marketPriceRaw) * 0.02, 0.01)
        if (kotlin.math.abs(avgCostRaw - marketPriceRaw) <= tolerance) {
            return PriceMagnifiers(contractMagnifier, contractMagnifier)
        }
        val marketMajorIfPence = marketPriceRaw / contractMagnifier
        val avgTolerance = kotlin.math.max(marketMajorIfPence * 0.02, 0.01)
        if (kotlin.math.abs(avgCostRaw - marketMajorIfPence) <= avgTolerance) {
            return PriceMagnifiers(avgMagnifier = 1, marketMagnifier = contractMagnifier)
        }
        if (avgCostRaw > marketMajorIfPence * 10.0) {
            return PriceMagnifiers(contractMagnifier, contractMagnifier)
        }
        return PriceMagnifiers(contractMagnifier, contractMagnifier)
    }

    /**
     * Unrealized P&L in the position's major currency (pounds for LSE), using scaled prices.
     */
    fun unrealizedPnLInPositionCurrency(
        quantity: Int,
        avgCostRaw: Double,
        marketPriceRaw: Double,
        magnifiers: PriceMagnifiers
    ): Double {
        val avgMajor = toMajorCurrency(avgCostRaw, magnifiers.avgMagnifier)
        val marketMajor = toMajorCurrency(marketPriceRaw, magnifiers.marketMagnifier)
        return (marketMajor - avgMajor) * quantity
    }

    fun unrealizedPnLInPositionCurrency(
        quantity: Int,
        avgCostRaw: Double,
        marketPriceRaw: Double,
        magnifier: Int
    ): Double = unrealizedPnLInPositionCurrency(
        quantity,
        avgCostRaw,
        marketPriceRaw,
        PriceMagnifiers(magnifier, magnifier)
    )

    internal data class PriceMagnifiers(
        val avgMagnifier: Int,
        val marketMagnifier: Int
    )

    fun displayCurrency(contractCurrency: String): String =
        CurrencyCodes.displayCurrency(contractCurrency.ifBlank { "USD" })

    private val MINOR_UNIT_CURRENCIES = setOf("GBX", "GBPENCE")

    /** LSE, LSEETF, etc. — UK listings quoted in pence when magnifier is 100. */
    private val UK_EXCHANGES = listOf("LSE", "LSEETF", "IOB", "CHIX")
}
