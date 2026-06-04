package daytrader.replay

import daytrader.marketdata.MarketQuoteBus
import daytrader.marketdata.QuoteSource

/**
 * Publishes captured [QuoteEvent]s up to a virtual timestamp onto [MarketQuoteBus] and [ReplayMarketDataGateway].
 */
class QuoteFeeder(
    private val bundle: SessionBundle,
    private val quoteBus: MarketQuoteBus?,
    private val marketDataGateway: ReplayMarketDataGateway
) {
    private var nextIndex = 0

    fun reset() {
        nextIndex = 0
    }

    /** Publishes all quote events with [QuoteEvent.epochMs] less than or equal to [epochMs]. */
    fun publishUpTo(epochMs: Long) {
        val events = bundle.quoteEvents
        while (nextIndex < events.size && events[nextIndex].epochMs <= epochMs) {
            val event = events[nextIndex++]
            marketDataGateway.updateQuote(event)
            quoteBus?.publish(
                symbol = event.symbol,
                quote = event.quote,
                priorClose = null,
                source = QuoteSource.EXTERNAL
            )
        }
    }
}
