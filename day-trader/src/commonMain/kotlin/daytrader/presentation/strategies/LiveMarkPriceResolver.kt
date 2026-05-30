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

    fun quoteForSymbol(symbol: String, quotes: Map<String, LiveQuote>): LiveQuote? =
        quotes[SymbolMarkets.normalizeSymbol(symbol)]

    fun isFillReady(quote: LiveQuote?): Boolean =
        quote?.bid?.let { it > 0.0 } == true && quote.ask?.let { it > 0.0 } == true

    /** When paper fills need bid/ask but the UI already has a last price. */
    fun fillReadinessHint(quote: LiveQuote?, requiresBidAskForFills: Boolean): String? {
        if (!requiresBidAskForFills) return null
        if (quote == null) return null
        if (isFillReady(quote)) return null
        if (quote.last?.let { it > 0.0 } == true ||
            quote.bid?.let { it > 0.0 } == true ||
            quote.ask?.let { it > 0.0 } == true
        ) {
            return "Waiting for bid/ask before paper fills"
        }
        return null
    }
}
