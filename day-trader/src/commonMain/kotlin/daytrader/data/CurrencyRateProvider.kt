package daytrader.data

import daytrader.domain.CurrencyCodes

/**
 * Provides FX rates for normalizing trade ledger totals.
 *
 * Each returned rate is how many [toCurrency] units equal one unit of the source currency.
 */
interface CurrencyRateProvider {
    suspend fun ratesToTarget(fromCurrencies: Set<String>, toCurrency: String): Result<Map<String, Double>>
}

/** Fixed rates for tests and offline fallback when live FX is unavailable. */
class StubCurrencyRateProvider(
    private val ratesToHkd: Map<String, Double> = defaultRatesToHkd(),
) : CurrencyRateProvider {
    override suspend fun ratesToTarget(fromCurrencies: Set<String>, toCurrency: String): Result<Map<String, Double>> {
        val target = CurrencyCodes.displayCurrency(toCurrency)
        val rates = fromCurrencies.associate { raw ->
            val from = CurrencyCodes.displayCurrency(raw)
            from to when {
                from == target -> 1.0
                target == "HKD" -> ratesToHkd[from]
                    ?: error("StubCurrencyRateProvider has no $from→HKD rate")
                else -> error("StubCurrencyRateProvider only supports normalization to HKD")
            }
        }
        return Result.success(rates)
    }

    companion object {
        fun defaultRatesToHkd(): Map<String, Double> = mapOf(
            "USD" to 7.80,
            "GBP" to 9.90,
            "EUR" to 8.50,
            "JPY" to 0.052,
            "CAD" to 5.70,
            "AUD" to 5.10,
            "CHF" to 8.90,
        )
    }
}
