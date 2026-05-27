package daytrader.marketdata

import daytrader.gateway.LiveQuote

/** Origin of a quote tick fed into [MarketQuoteBus]. */
enum class QuoteSource {
    /** Live IB (or other external) bid/ask/last. */
    EXTERNAL,
    /** Emulator synthetic walk / jitter. */
    SYNTHETIC
}

/**
 * Ordered market-data event published to [MarketQuoteBus].
 * Each subscriber receives a copy on its own FIFO channel.
 */
data class QuoteUpdate(
    val symbol: String,
    val quote: LiveQuote,
    val priorClose: Double? = null,
    val source: QuoteSource = QuoteSource.EXTERNAL,
    val sequence: Long = 0L
)
