package daytrader.replay

import daytrader.marketdata.MarketQuoteBus
import daytrader.marketdata.QuoteSource

/**
 * Publishes captured [QuoteEvent]s up to a virtual timestamp onto [MarketQuoteBus] and [ReplayMarketDataGateway].
 */
class QuoteFeeder(
    private var bundle: SessionBundle,
    private val quoteBus: MarketQuoteBus?,
    private val marketDataGateway: ReplayMarketDataGateway
) {
    private var nextIndex = 0

    /**
     * When set, captured quotes are published under this symbol so the active deployment's
     * live-price UI resolves them (capture symbol may differ from deployment symbol).
     */
    @Volatile
    var publishSymbolOverride: String? = null

    val totalQuoteCount: Int
        get() = bundle.quoteEvents.size

    val publishedQuoteCount: Int
        get() = nextIndex

    fun reset() {
        nextIndex = 0
        publishSymbolOverride = null
    }

    fun replaceBundle(newBundle: SessionBundle) {
        bundle = newBundle
        reset()
    }

    fun peekNext(): QuoteEvent? = bundle.quoteEvents.getOrNull(nextIndex)

    /** Publishes the next captured quote, if any. */
    fun publishNext(): QuoteEvent? {
        val event = peekNext() ?: return null
        nextIndex++
        publishCapturedQuote(event)
        return event
    }

    /** Publishes all quote events with [QuoteEvent.epochMs] less than or equal to [epochMs]. */
    fun publishUpTo(epochMs: Long) {
        val events = bundle.quoteEvents
        while (nextIndex < events.size && events[nextIndex].epochMs <= epochMs) {
            publishCapturedQuote(events[nextIndex++])
        }
    }

    private fun publishCapturedQuote(event: QuoteEvent) {
        val symbol = publishSymbolOverride ?: event.symbol
        val quote = event.quote.copy(symbol = symbol)
        marketDataGateway.updateQuote(event.copy(symbol = symbol, quote = quote))
        quoteBus?.publish(
            symbol = symbol,
            quote = quote,
            priorClose = null,
            source = QuoteSource.EXTERNAL
        )
    }
}
