package daytrader.presentation

import daytrader.domain.CurrencyCodes

object Formatters {
    fun currency(amount: Double, showSign: Boolean = false): String =
        money(amount, "USD", showSign)

    /** Format an amount in the given ISO currency (e.g. GBP → £, USD → $). */
    fun money(amount: Double, currencyCode: String, showSign: Boolean = false): String {
        val sign = when {
            showSign && amount > 0 -> "+"
            amount < 0 -> "-"
            else -> ""
        }
        val code = normalizeDisplayCurrency(currencyCode)
        val symbol = currencySymbol(code)
        return "$sign$symbol${String.format("%,.2f", kotlin.math.abs(amount))}"
    }

    fun currencyPlain(amount: Double): String = moneyPlain(amount, "USD")

    fun moneyPlain(amount: Double, currencyCode: String): String {
        val code = normalizeDisplayCurrency(currencyCode)
        return "${currencySymbol(code)}${String.format("%.2f", amount)}"
    }

    fun normalizeDisplayCurrency(currencyCode: String): String =
        CurrencyCodes.displayCurrency(currencyCode)

    private fun currencySymbol(code: String): String = when (code) {
        "USD" -> "$"
        "GBP" -> "£"
        "EUR" -> "€"
        "JPY" -> "¥"
        "CHF" -> "CHF "
        "CAD" -> "C$"
        "AUD" -> "A$"
        else -> "$code "
    }

    fun percent(value: Double): String {
        val sign = if (value >= 0) "+" else ""
        return "$sign${String.format("%.2f", value)}%"
    }

    fun paramsSummary(symbol: String, maxDollars: Int): String =
        "$symbol · \$${String.format("%,d", maxDollars)} max"

    fun maxAtRisk(maxDollars: Int): String = "\$${String.format("%,d", maxDollars)}"

    fun sessionDateLabel(isoDate: String): String {
        val parts = isoDate.split("-")
        if (parts.size != 3) return isoDate
        val day = parts[2].toIntOrNull() ?: return isoDate
        val month = parts[1].toIntOrNull() ?: return isoDate
        val monthLabel = when (month) {
            1 -> "Jan"
            2 -> "Feb"
            3 -> "Mar"
            4 -> "Apr"
            5 -> "May"
            6 -> "Jun"
            7 -> "Jul"
            8 -> "Aug"
            9 -> "Sep"
            10 -> "Oct"
            11 -> "Nov"
            12 -> "Dec"
            else -> return isoDate
        }
        return "$day $monthLabel"
    }

    fun percentOfMax(pnl: Double, maxDollars: Int): String {
        if (maxDollars <= 0) return "—"
        val pct = (kotlin.math.abs(pnl) / maxDollars) * 100.0
        return "${String.format("%.0f", pct)}%"
    }

    fun winRate(winDays: Int, closedDays: Int): String {
        if (closedDays == 0) return "—"
        val pct = (winDays.toDouble() / closedDays) * 100.0
        return "${String.format("%.0f", pct)}%"
    }
}
