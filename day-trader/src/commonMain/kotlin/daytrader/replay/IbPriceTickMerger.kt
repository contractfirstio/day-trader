package daytrader.replay

import daytrader.broker.SymbolMarkets
import daytrader.gateway.LiveQuote
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Merges per-field IB disk ticks into a timeline of [QuoteEvent]s suitable for replay.
 *
 * Input format matches [daytrader.broker.IbPriceDiskLog] JSONL lines.
 */
object IbPriceTickMerger {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun mergeJsonl(
        jsonl: String,
        symbolFilter: String? = null
    ): List<QuoteEvent> {
        val ticks = parseTicks(jsonl)
        return merge(ticks, symbolFilter)
    }

    fun merge(
        ticks: List<IbPriceTickEvent>,
        symbolFilter: String? = null
    ): List<QuoteEvent> {
        val normalizedFilter = symbolFilter?.let { SymbolMarkets.normalizeSymbol(it) }
        val sorted = ticks
            .asSequence()
            .filter { tick ->
                normalizedFilter == null ||
                    SymbolMarkets.normalizeSymbol(tick.symbol) == normalizedFilter
            }
            .sortedBy { it.epochMs }
            .toList()
        if (sorted.isEmpty()) return emptyList()

        val events = mutableListOf<QuoteEvent>()
        val accumulators = mutableMapOf<String, QuoteAccumulator>()
        var previousBySymbol = mutableMapOf<String, LiveQuote>()

        for (tick in sorted) {
            val symbol = SymbolMarkets.normalizeSymbol(tick.symbol)
            val acc = accumulators.getOrPut(symbol) { QuoteAccumulator(symbol) }
            acc.apply(tick)
            val quote = acc.toLiveQuote(tick.epochMs) ?: continue
            val prior = previousBySymbol[symbol]
            if (prior != null && quotesEqual(prior, quote)) continue
            previousBySymbol[symbol] = quote
            events.add(QuoteEvent(epochMs = tick.epochMs, symbol = symbol, quote = quote))
        }
        return events
    }

    internal fun parseTicks(jsonl: String): List<IbPriceTickEvent> =
        jsonl.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                runCatching {
                    json.decodeFromString(IbPriceTickEvent.serializer(), line)
                }.getOrNull()
            }
            .toList()

    private fun quotesEqual(a: LiveQuote, b: LiveQuote): Boolean =
        a.bid == b.bid && a.ask == b.ask && a.last == b.last && a.tickVolume == b.tickVolume

    private class QuoteAccumulator(private val symbol: String) {
        private var bid: Double? = null
        private var ask: Double? = null
        private var last: Double? = null

        fun apply(tick: IbPriceTickEvent) {
            tick.bid?.takeIf { it > 0.0 }?.let { bid = it }
            tick.ask?.takeIf { it > 0.0 }?.let { ask = it }
            tick.last?.takeIf { it > 0.0 }?.let { last = it }
            when (tick.fieldName.uppercase()) {
                "BID" -> if (tick.price > 0.0) bid = tick.price
                "ASK" -> if (tick.price > 0.0) ask = tick.price
                "LAST" -> if (tick.price > 0.0) last = tick.price
            }
        }

        fun toLiveQuote(epochMs: Long): LiveQuote? {
            val bidPx = bid ?: return null
            val askPx = ask ?: return null
            if (bidPx <= 0.0 || askPx <= 0.0) return null
            val lastPx = last?.takeIf { it > 0.0 } ?: (bidPx + askPx) / 2.0
            return LiveQuote(
                symbol = symbol,
                bid = bidPx,
                ask = askPx,
                last = lastPx,
                quoteEpochMillis = epochMs
            )
        }
    }
}

@Serializable
data class IbPriceTickEvent(
    val at: String,
    val epochMs: Long,
    val symbol: String,
    val field: Int = 0,
    val fieldName: String,
    val price: Double,
    val bid: Double? = null,
    val ask: Double? = null,
    val last: Double? = null,
    val marketDataKey: String? = null
)
