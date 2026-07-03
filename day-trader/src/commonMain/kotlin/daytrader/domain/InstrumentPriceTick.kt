package daytrader.domain

import daytrader.broker.SymbolMarkets
import kotlin.math.round

/**
 * Rounds listing prices to IB minimum price variation before order submission.
 */
object InstrumentPriceTick {
    fun roundToMinTick(price: Double, minTick: Double): Double {
        if (!price.isFinite() || minTick <= 0.0 || !minTick.isFinite()) return price
        return round(price / minTick) * minTick
    }

    fun resolveMinTick(instrument: InstrumentIdentity?, symbol: String): Double =
        instrument?.minPriceTick?.takeIf { it > 0.0 } ?: defaultMinTick(symbol, instrument)

    private fun defaultMinTick(symbol: String, instrument: InstrumentIdentity?): Double {
        if (SymbolMarkets.isHongKong(symbol)) return 0.01
        if (instrument != null &&
            InstrumentPriceScale.quotesInMinorUnits(
                instrument.currency,
                instrument.primaryExch,
                instrument.exchange
            )
        ) {
            return 1.0
        }
        return 0.01
    }
}
