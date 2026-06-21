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

    /** When set (replay backtest), each captured quote is ingested synchronously for fill evaluation. */
    @Volatile
    var onCapturedQuotePublished: ((QuoteEvent) -> Unit)? = null

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

    /**
     * Headless backtest: publish in order and invoke [onQuote] on the caller coroutine (no quote-bus
     * fan-out, no runBlocking emulator ingest).
     */
    suspend fun publishUpToForBacktest(epochMs: Long, onQuote: suspend (QuoteEvent) -> Unit) {
        val events = bundle.quoteEvents
        while (nextIndex < events.size && events[nextIndex].epochMs <= epochMs) {
            val event = events[nextIndex++]
            val published = preparedQuoteEvent(event)
            marketDataGateway.updateQuote(published)
            onQuote(published)
        }
    }

    /**
     * Seeds [ReplayMarketDataGateway] through [epochMs] without advancing [nextIndex] or ingesting
     * into the emulator. Used when fast-forwarding the opening bar for headless backtest so the
     * post-bracket drive still has the full quote timeline for fill evaluation.
     */
    fun seedGatewayQuotesUpTo(epochMs: Long) {
        val events = bundle.quoteEvents
        for (index in events.indices) {
            if (events[index].epochMs > epochMs) break
            marketDataGateway.updateQuote(preparedQuoteEvent(events[index]))
        }
    }

    fun seekToFirstQuoteAfter(epochMs: Long) {
        nextIndex = indexOfFirstQuoteAfter(epochMs)
    }

    fun indexOfFirstQuoteAfter(epochMs: Long): Int {
        val events = bundle.quoteEvents
        val index = events.indexOfFirst { it.epochMs > epochMs }
        return if (index < 0) events.size else index
    }

    /** Max-speed drip: one tick, suspend emulator ingest, no quote-bus fan-out. */
    suspend fun publishNextForBacktest(onQuote: suspend (QuoteEvent) -> Unit): QuoteEvent? {
        val event = peekNext() ?: return null
        nextIndex++
        val published = preparedQuoteEvent(event)
        marketDataGateway.updateQuote(published)
        onQuote(published)
        return published
    }

    private fun publishCapturedQuote(event: QuoteEvent) {
        val published = preparedQuoteEvent(event)
        marketDataGateway.updateQuote(published)
        quoteBus?.publish(
            symbol = published.symbol,
            quote = published.quote,
            priorClose = null,
            source = QuoteSource.EXTERNAL
        )
        onCapturedQuotePublished?.invoke(published)
    }

    private fun preparedQuoteEvent(event: QuoteEvent): QuoteEvent {
        val symbol = publishSymbolOverride ?: event.symbol
        val quote = event.quote.copy(symbol = symbol)
        return event.copy(symbol = symbol, quote = quote)
    }
}
