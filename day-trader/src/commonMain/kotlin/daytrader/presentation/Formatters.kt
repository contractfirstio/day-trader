package daytrader.presentation

object Formatters {
    fun currency(amount: Double, showSign: Boolean = false): String {
        val sign = when {
            showSign && amount > 0 -> "+"
            amount < 0 -> "-"
            else -> ""
        }
        return "$sign$${String.format("%,.2f", kotlin.math.abs(amount))}"
    }

    fun currencyPlain(amount: Double): String = "$${String.format("%.2f", amount)}"

    fun percent(value: Double): String {
        val sign = if (value >= 0) "+" else ""
        return "$sign${String.format("%.2f", value)}%"
    }

    fun paramsSummary(symbol: String, maxDollars: Int): String =
        "$symbol · \$${String.format("%,d", maxDollars)} max"

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
