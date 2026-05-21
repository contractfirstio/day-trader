package daytrader.presentation

object Formatters {
    fun currency(amount: Double, showSign: Boolean = false): String {
        val sign = if (showSign && amount >= 0) "+" else if (amount < 0) "" else ""
        return "$sign$${String.format("%,.2f", amount)}"
    }

    fun currencyPlain(amount: Double): String = "$${String.format("%.2f", amount)}"

    fun percent(value: Double): String {
        val sign = if (value >= 0) "+" else ""
        return "$sign${String.format("%.2f", value)}%"
    }

    fun paramsSummary(symbol: String, timeframe: String, riskDollars: Int): String =
        "$symbol · $timeframe · \$$riskDollars risk"
}
