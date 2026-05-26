package daytrader.presentation.strategies

import daytrader.broker.SymbolMarkets
import daytrader.gateway.AccountPosition
import daytrader.gateway.LiveQuote

/** Resolves the best numeric live mark for charting (last trade, then market, then quote last). */
object LiveMarkPriceResolver {
    fun resolve(
        symbol: String,
        positions: List<AccountPosition>,
        quotes: Map<String, LiveQuote>
    ): Double? {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        val quote = quotes[norm]
        val position = positions.firstOrNull { SymbolMarkets.symbolsMatch(symbol, it.symbol) }
        return position?.lastTradePrice?.takeIf { it > 0.0 }
            ?: position?.marketPrice?.takeIf { it > 0.0 }
            ?: quote?.last?.takeIf { it > 0.0 }
    }
}
