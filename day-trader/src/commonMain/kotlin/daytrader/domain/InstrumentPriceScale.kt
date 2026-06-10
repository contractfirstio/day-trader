package daytrader.domain

/**
 * LSE listings quote in pence (minor units) while account P&L is in pounds.
 * Converts listing/API prices into major currency for P&L.
 * Mirrors [daytrader.broker.IbPriceScale] for emulator and shared domain math.
 */
object InstrumentPriceScale {
    val UK_EXCHANGES = listOf("LSE", "LSEETF", "IOB", "CHIX")

    fun isUkListed(currency: String, primaryExch: String?, exchange: String? = null): Boolean {
        if (currency.uppercase() != "GBP") return false
        val primary = primaryExch.orEmpty().uppercase()
        val exch = exchange.orEmpty().uppercase()
        return UK_EXCHANGES.any { uk -> primary.contains(uk) || exch.contains(uk) }
    }

    /** True when market ticks, bars, and bracket prices are in minor units (pence for LSE). */
    fun quotesInMinorUnits(currency: String, primaryExch: String? = null, exchange: String? = null): Boolean =
        when (currency.uppercase()) {
            "GBX", "GBPENCE" -> true
            "GBP" -> isUkListed(currency, primaryExch, exchange)
            else -> false
        }

    fun defaultMagnifierForCurrency(currency: String): Int =
        defaultMagnifier(currency, primaryExch = null, exchange = null)

    fun defaultMagnifier(currency: String, primaryExch: String?, exchange: String? = null): Int =
        if (quotesInMinorUnits(currency, primaryExch, exchange)) 100 else 1

    fun defaultMagnifier(instrument: InstrumentIdentity): Int =
        defaultMagnifier(instrument.currency, instrument.primaryExch, instrument.exchange)

    /** LSE primary exchange for UK session rows when IB metadata is missing. */
    fun resolvedListingExch(currency: String, marketZoneId: String?, primaryExch: String?): String? =
        primaryExch?.takeIf { it.isNotBlank() }
            ?: if (currency.equals("GBP", ignoreCase = true) &&
                marketZoneId == RthMarketSessions.EUR.zoneId
            ) {
                "LSE"
            } else {
                null
            }

    fun toMajorCurrency(rawPrice: Double, magnifier: Int): Double =
        if (magnifier > 1) rawPrice / magnifier else rawPrice

    fun toMajorCurrency(rawPrice: Double, currency: String): Double =
        toMajorCurrency(rawPrice, defaultMagnifierForCurrency(currency))

    fun toMajorCurrency(rawPrice: Double, instrument: InstrumentIdentity): Double =
        toMajorCurrency(rawPrice, defaultMagnifier(instrument))

    fun unrealizedPnL(
        quantity: Int,
        avgPriceRaw: Double,
        marketPriceRaw: Double,
        currency: String
    ): Double = unrealizedPnL(quantity, avgPriceRaw, marketPriceRaw, currency, primaryExch = null)

    fun unrealizedPnL(
        quantity: Int,
        avgPriceRaw: Double,
        marketPriceRaw: Double,
        currency: String,
        primaryExch: String?,
        exchange: String? = null
    ): Double {
        val magnifier = defaultMagnifier(currency, primaryExch, exchange)
        val avgMajor = toMajorCurrency(avgPriceRaw, magnifier)
        val marketMajor = toMajorCurrency(marketPriceRaw, magnifier)
        return (marketMajor - avgMajor) * quantity
    }

    fun realizedPnLOnClose(
        closeQty: Int,
        avgPriceRaw: Double,
        exitPriceRaw: Double,
        currency: String,
        isLong: Boolean
    ): Double = realizedPnLOnClose(closeQty, avgPriceRaw, exitPriceRaw, currency, isLong, primaryExch = null)

    fun realizedPnLOnClose(
        closeQty: Int,
        avgPriceRaw: Double,
        exitPriceRaw: Double,
        currency: String,
        isLong: Boolean,
        primaryExch: String?,
        exchange: String? = null
    ): Double {
        val magnifier = defaultMagnifier(currency, primaryExch, exchange)
        val avgMajor = toMajorCurrency(avgPriceRaw, magnifier)
        val exitMajor = toMajorCurrency(exitPriceRaw, magnifier)
        return if (isLong) {
            (exitMajor - avgMajor) * closeQty
        } else {
            (avgMajor - exitMajor) * closeQty
        }
    }
}
