package daytrader.presentation.trades

import daytrader.broker.SymbolMarkets
import daytrader.gateway.BrokerFill

data class TradeDateRange(
    val from: java.time.LocalDate? = null,
    val to: java.time.LocalDate? = null,
) {
    fun isOpen(): Boolean = from == null && to == null
}

data class TradeLedgerSummary(
    val tradeCount: Int,
    val realizedPnL: Double?,
    val commission: Double?,
    val currencies: Set<String>,
    val sourceCurrencies: Set<String> = currencies,
    val normalizedToCurrency: String? = null,
    val fxConversionComplete: Boolean = true,
)

object TradeLedgerFilter {
    fun filter(
        fills: List<BrokerFill>,
        range: TradeDateRange,
        columnFilters: TradeColumnFilters = TradeColumnFilters(),
    ): List<BrokerFill> =
        filterByDate(fills, range).filter(columnFilters::apply)

    fun filter(
        fills: List<BrokerFill>,
        range: TradeDateRange,
        symbol: String?,
    ): List<BrokerFill> {
        val dateFiltered = filterByDate(fills, range)
        return filterBySymbol(dateFiltered, symbol)
    }

    fun filterByDate(fills: List<BrokerFill>, range: TradeDateRange): List<BrokerFill> {
        if (range.isOpen()) return fills
        return fills.filter { fill ->
            val tradeDate = TradeUiMapper.parseFillDate(fill.time) ?: return@filter false
            range.from?.let { if (tradeDate.isBefore(it)) return@filter false }
            range.to?.let { if (tradeDate.isAfter(it)) return@filter false }
            true
        }
    }

    fun filterBySymbol(fills: List<BrokerFill>, symbol: String?): List<BrokerFill> {
        if (symbol.isNullOrBlank()) return fills
        return fills.filter { SymbolMarkets.symbolsMatch(symbol, it.symbol) }
    }

    fun distinctSymbols(fills: List<BrokerFill>): List<String> =
        fills.map { it.symbol }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })

    fun summarize(fills: List<BrokerFill>): TradeLedgerSummary {
        var realizedTotal = 0.0
        var hasRealized = false
        var commissionTotal = 0.0
        var hasCommission = false
        val currencies = mutableSetOf<String>()
        fills.forEach { fill ->
            currencies += TradeMarketResolver.settlementCurrency(fill)
            fill.realizedPnL?.let {
                realizedTotal += it
                hasRealized = true
            }
            fill.commission?.let {
                commissionTotal += it
                hasCommission = true
            }
        }
        return TradeLedgerSummary(
            tradeCount = fills.size,
            realizedPnL = realizedTotal.takeIf { hasRealized },
            commission = commissionTotal.takeIf { hasCommission },
            currencies = currencies,
            sourceCurrencies = currencies,
        )
    }

    fun summarizeNormalized(
        fills: List<BrokerFill>,
        targetCurrency: String,
        ratesToTarget: Map<String, Double>,
    ): TradeLedgerSummary = TradeLedgerFx.summarizeNormalized(fills, targetCurrency, ratesToTarget)
}
