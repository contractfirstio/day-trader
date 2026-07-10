package daytrader.presentation.trades

import daytrader.domain.CurrencyCodes
import daytrader.gateway.BrokerFill

object TradeLedgerFx {
    const val AGGREGATE_CURRENCY = "HKD"

    fun convertAmount(
        amount: Double,
        fromCurrency: String,
        toCurrency: String,
        ratesToTarget: Map<String, Double>,
    ): Double? {
        val from = CurrencyCodes.displayCurrency(fromCurrency)
        val to = CurrencyCodes.displayCurrency(toCurrency)
        if (from == to) return amount
        val rate = ratesToTarget[from] ?: return null
        return amount * rate
    }

    fun currenciesNeedingRates(fills: List<BrokerFill>, targetCurrency: String): Set<String> =
        fills.map { CurrencyCodes.displayCurrency(TradeMarketResolver.settlementCurrency(it)) }
            .filter { it.isNotBlank() && it != CurrencyCodes.displayCurrency(targetCurrency) }
            .toSet()

    fun summarizeNormalized(
        fills: List<BrokerFill>,
        targetCurrency: String,
        ratesToTarget: Map<String, Double>,
    ): TradeLedgerSummary {
        var realizedTotal = 0.0
        var hasRealized = false
        var commissionTotal = 0.0
        var hasCommission = false
        var conversionComplete = true
        val sourceCurrencies = mutableSetOf<String>()

        fills.forEach { fill ->
            val fillCurrency = CurrencyCodes.displayCurrency(TradeMarketResolver.settlementCurrency(fill))
            sourceCurrencies += fillCurrency
            fill.realizedPnL?.let { realized ->
                val converted = convertAmount(realized, fillCurrency, targetCurrency, ratesToTarget)
                if (converted == null) {
                    conversionComplete = false
                } else {
                    realizedTotal += converted
                    hasRealized = true
                }
            }
            fill.commission?.let { commission ->
                val converted = convertAmount(commission, fillCurrency, targetCurrency, ratesToTarget)
                if (converted == null) {
                    conversionComplete = false
                } else {
                    commissionTotal += converted
                    hasCommission = true
                }
            }
        }

        return TradeLedgerSummary(
            tradeCount = fills.size,
            realizedPnL = realizedTotal.takeIf { hasRealized },
            commission = commissionTotal.takeIf { hasCommission },
            currencies = setOf(CurrencyCodes.displayCurrency(targetCurrency)),
            sourceCurrencies = sourceCurrencies,
            normalizedToCurrency = CurrencyCodes.displayCurrency(targetCurrency),
            fxConversionComplete = conversionComplete,
        )
    }
}
