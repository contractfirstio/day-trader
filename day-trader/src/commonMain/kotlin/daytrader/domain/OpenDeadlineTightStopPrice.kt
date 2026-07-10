package daytrader.domain

import daytrader.gateway.AccountPosition
import daytrader.gateway.LiveQuote

/**
 * Computes a protective stop price very near the market for OPEN_DEADLINE exit.
 * Long positions: sell stop slightly below bid. Short positions: buy stop slightly above ask.
 */
object OpenDeadlineTightStopPrice {
    /** Whole ticks away from the touchable quote side (0 = at bid/ask). */
    const val TICKS_FROM_MARKET = 1

    fun compute(
        position: AccountPosition,
        quote: LiveQuote?,
        instrument: InstrumentIdentity?,
        symbol: String
    ): Double? {
        if (position.quantity == 0) return null
        val minTick = InstrumentPriceTick.resolveMinTick(instrument, symbol)
        val offset = TICKS_FROM_MARKET * minTick
        val raw = when {
            position.quantity > 0 -> {
                val bid = quote?.bid?.takeIf { it.isFinite() && it > 0.0 }
                    ?: position.marketPrice.takeIf { it.isFinite() && it > 0.0 }
                    ?: return null
                bid - offset
            }
            else -> {
                val ask = quote?.ask?.takeIf { it.isFinite() && it > 0.0 }
                    ?: position.marketPrice.takeIf { it.isFinite() && it > 0.0 }
                    ?: return null
                ask + offset
            }
        }
        return InstrumentPriceTick.roundForInstrument(raw, instrument, symbol)
    }

    /** True when [stopPrice] should fill immediately at the current quote (emulator / tests). */
    fun isImmediatelyMarketable(
        position: AccountPosition,
        stopPrice: Double,
        quote: LiveQuote?
    ): Boolean {
        if (!stopPrice.isFinite() || stopPrice <= 0.0) return false
        val bid = quote?.bid?.takeIf { it.isFinite() && it > 0.0 } ?: position.marketPrice
        val ask = quote?.ask?.takeIf { it.isFinite() && it > 0.0 } ?: position.marketPrice
        return when {
            position.quantity > 0 -> bid <= stopPrice
            position.quantity < 0 -> ask >= stopPrice
            else -> false
        }
    }
}
