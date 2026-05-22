package daytrader.domain

/** Normalize IB contract currency codes for display and P&L (e.g. GBX → GBP). */
object CurrencyCodes {
    fun displayCurrency(currencyCode: String): String = when (currencyCode.uppercase()) {
        "", "USD" -> "USD"
        "GBX", "GBPENCE", "GBP" -> "GBP"
        else -> currencyCode.uppercase().ifEmpty { "USD" }
    }
}
