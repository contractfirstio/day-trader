package daytrader.broker.emulator

import daytrader.broker.SymbolMarkets
import daytrader.gateway.LiveQuote

/**
 * Bid/ask/last book used only to decide when emulator limits and stops fill.
 *
 * Decoupled from how prices are produced: [EmulatorPricingSource.SYNTHETIC] mutates quotes
 * internally; [EmulatorPricingSource.LIVE_EXCHANGE] accepts [ingestExternal] updates only.
 */
internal class EmulatorQuoteBook(
    private val pricingSource: EmulatorPricingSource
) {
    private val quotes = mutableMapOf<String, EmulatorMarketQuote>()
    /** Symbols with a complete external bid+ask (required before fills in live-exchange mode). */
    private val externalFeedReady = mutableSetOf<String>()

    val isEmpty: Boolean get() = quotes.isEmpty()

    fun clear() {
        quotes.clear()
        externalFeedReady.clear()
    }

    fun canTriggerFills(symbol: String): Boolean {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        if (norm !in quotes) return false
        return when (pricingSource) {
            EmulatorPricingSource.SYNTHETIC -> true
            EmulatorPricingSource.LIVE_EXCHANGE -> norm in externalFeedReady
        }
    }

    fun quoteOrNull(symbol: String): EmulatorMarketQuote? =
        quotes[SymbolMarkets.normalizeSymbol(symbol)]

    fun hasCompleteBidAsk(symbol: String): Boolean {
        val quote = quoteOrNull(symbol) ?: return false
        return quote.bid > 0.0 && quote.ask > 0.0 && quote.ask >= quote.bid
    }

    fun quoteFor(symbol: String, createDefault: () -> EmulatorMarketQuote): EmulatorMarketQuote {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        return quotes.getOrPut(norm, createDefault)
    }

    fun mid(symbol: String): Double? = quoteOrNull(symbol)?.last

    /**
     * Applies a tick from a real exchange feed. No-op when [pricingSource] is [EmulatorPricingSource.SYNTHETIC].
     * Returns the merged book row when bid and ask are both known.
     */
    fun ingestExternal(symbol: String, incoming: LiveQuote): EmulatorMarketQuote? {
        if (pricingSource != EmulatorPricingSource.LIVE_EXCHANGE) return null
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        val merged = EmulatorMarketQuote.fromLiveQuote(incoming, quotes[norm]) ?: return null
        quotes[norm] = merged
        externalFeedReady.add(norm)
        return merged
    }

    fun seedSymbol(symbol: String, quote: EmulatorMarketQuote) {
        quotes[SymbolMarkets.normalizeSymbol(symbol)] = quote
    }

    fun removeSymbol(symbol: String) {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        quotes.remove(norm)
        externalFeedReady.remove(norm)
    }

    fun symbols(): Set<String> = quotes.keys.toSet()

    fun mutate(symbol: String, block: (EmulatorMarketQuote) -> Unit) {
        quoteOrNull(symbol)?.let(block)
    }

    fun toLiveQuoteSnapshot(): Map<String, LiveQuote> =
        quotes.mapValues { (sym, q) -> q.toLiveQuote(sym) }

    fun lastPricesBySymbol(): Map<String, Double> =
        quotes.mapValues { it.value.last }
}
