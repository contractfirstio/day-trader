package daytrader.broker.emulator

import daytrader.gateway.LiveQuote
import kotlin.math.max

/**
 * Synthetic bid / ask / last for emulator fills.
 * Limits and stops use the aggressive side (buy at ask, sell at bid) like a real book.
 */
internal data class EmulatorMarketQuote(
    var last: Double,
    var bid: Double,
    var ask: Double,
    var halfSpread: Double,
    var lastTickVolume: Double = 0.0
) {
    fun toLiveQuote(symbol: String): LiveQuote = LiveQuote(
        symbol = symbol,
        bid = bid,
        ask = ask,
        last = last,
        tickVolume = lastTickVolume.takeIf { it > 0.0 }
    )

    fun setFromBarClose(close: Double, spread: Double) {
        halfSpread = max(spread / 2.0, close * 1e-6)
        last = close
        bid = close - halfSpread
        ask = close + halfSpread
    }

    fun setMid(mid: Double) {
        val previous = last
        last = mid.coerceAtLeast(0.01)
        bid = last - halfSpread
        ask = last + halfSpread
        val delta = kotlin.math.abs(last - previous)
        if (delta > 0.0) {
            lastTickVolume = delta * 50_000.0
        }
    }

    fun nudgeMid(delta: Double) {
        setMid((last + delta).coerceAtLeast(0.01))
    }

    /** Moves the side that would fill bracket exits (bid for long, ask for short) and keeps spread. */
    fun setAggressivePrice(price: Double, isLongPosition: Boolean) {
        val px = price.coerceAtLeast(0.01)
        if (isLongPosition) {
            bid = px
            ask = px + halfSpread * 2.0
        } else {
            ask = px
            bid = (px - halfSpread * 2.0).coerceAtLeast(0.01)
        }
        last = (bid + ask) / 2.0
    }

    fun aggressivePrice(isLongPosition: Boolean): Double = if (isLongPosition) bid else ask

    companion object {
        /**
         * Merges an IB [LiveQuote] into a book row. Returns null until both bid and ask are known
         * (required before hybrid limit/stop evaluation).
         */
        fun fromLiveQuote(incoming: LiveQuote, existing: EmulatorMarketQuote? = null): EmulatorMarketQuote? {
            val bid = incoming.bid?.takeIf { it > 0.0 } ?: existing?.bid ?: return null
            val ask = incoming.ask?.takeIf { it > 0.0 } ?: existing?.ask ?: return null
            val last = incoming.last?.takeIf { it > 0.0 }
                ?: existing?.last
                ?: (bid + ask) / 2.0
            val halfSpread = max((ask - bid) / 2.0, last * 1e-6)
            return EmulatorMarketQuote(last = last, bid = bid, ask = ask, halfSpread = halfSpread)
        }
    }
}

internal object EmulatorMarketQuoteBook {
    fun spreadForBracketRange(range: Double, referencePrice: Double, spreadPctOfRange: Double): Double =
        max(range * spreadPctOfRange, referencePrice * 1e-4)

    /** Buy limit: lift the offer — fill when ask is at or below the limit. */
    fun buyLimitFillable(ask: Double, limit: Double): Boolean = ask <= limit

    /** Sell limit: hit the bid — fill when bid is at or above the limit. */
    fun sellLimitFillable(bid: Double, limit: Double): Boolean = bid >= limit

    /** Sell stop (e.g. long protection): triggered when bid trades at or through the stop. */
    fun sellStopTriggered(bid: Double, stop: Double): Boolean = bid <= stop

    /** Buy stop (e.g. short protection): triggered when ask trades at or through the stop. */
    fun buyStopTriggered(ask: Double, stop: Double): Boolean = ask >= stop
}
