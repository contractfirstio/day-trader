package daytrader.presentation.strategies

import daytrader.broker.SymbolMarkets
import daytrader.gateway.AccountPosition
import daytrader.gateway.LiveQuote

/** Resolves the last-traded price for charting (prefer quote last, then position last/mark). */
object LiveMarkPriceResolver {
    fun resolve(
        symbol: String,
        positions: List<AccountPosition>,
        quotes: Map<String, LiveQuote>
    ): Double? {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        val quote = quotes[norm]
        val position = positions.firstOrNull { SymbolMarkets.symbolsMatch(symbol, it.symbol) }
        return quote?.last?.takeIf { it > 0.0 }
            ?: position?.lastTradePrice?.takeIf { it > 0.0 }
            ?: position?.marketPrice?.takeIf { it > 0.0 }
    }
}
