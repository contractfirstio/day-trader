package daytrader.presentation.strategies

import daytrader.broker.SymbolMarkets
import daytrader.domain.TouchTurnTradeSide
import daytrader.gateway.AccountPosition
import daytrader.gateway.LiveQuote

/** Resolves display prices for charts and marks (last by default; bid/ask when fills matter). */
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

    /**
     * Touch Turn chart price after bracket submit: bid/ask leg used by the paper emulator
     * (entry → [TouchTurnQuoteStripUiMapper.fillPriceForGap], in position → exit side).
     * Falls back to [resolve] when orders are not yet placed or bid/ask are missing.
     */
    fun resolveForTouchTurnChart(
        symbol: String,
        positions: List<AccountPosition>,
        quotes: Map<String, LiveQuote>,
        entrySide: TouchTurnTradeSide?,
        ordersPlaced: Boolean,
        inPosition: Boolean
    ): Double? {
        if (ordersPlaced) {
            val quote = quoteForSymbol(symbol, quotes)
            TouchTurnQuoteStripUiMapper.chartPrice(
                entrySide = entrySide,
                bid = quote?.bid,
                ask = quote?.ask,
                inPosition = inPosition
            )?.let { return it }
        }
        return resolve(symbol, positions, quotes)
    }

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
