package daytrader.presentation

import daytrader.domain.CurrencyCodes
import daytrader.domain.RunStatus

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

    fun price(value: Double?): String =
        value?.let { String.format("%.2f", it) } ?: "—"

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

    fun runSessionLabel(
        date: String,
        startedAt: String,
        stoppedAt: String,
        status: RunStatus
    ): String {
        val day = sessionDateLabel(date)
        val startTime = startedAt.runTimeFromIso()
        val stopTime = stoppedAt.runTimeFromIso()
        val timeRange = when {
            startTime != null && stopTime != null -> "$startTime–$stopTime"
            startTime != null -> "$startTime–…"
            else -> null
        }
        return when {
            status == RunStatus.IN_PROGRESS && timeRange != null ->
                "$day · $timeRange · In progress"
            status == RunStatus.IN_PROGRESS -> "$day · In progress"
            timeRange != null -> "$day · $timeRange"
            else -> day
        }
    }

    fun runStartTimeDisplay(startedAt: String): String =
        startedAt.runTimeFromIso() ?: "—"

    fun runStopTimeDisplay(stoppedAt: String, inProgress: Boolean): String = when {
        inProgress -> "—"
        else -> stoppedAt.runTimeFromIso() ?: "—"
    }

    private fun String.runTimeFromIso(): String? {
        val tIndex = indexOf('T')
        if (tIndex < 0 || length < tIndex + 6) return null
        return substring(tIndex + 1, tIndex + 6)
    }

    fun yesNo(value: Boolean?): String = when (value) {
        true -> "Yes"
        false -> "No"
        null -> "—"
    }

    /** Shows "Nothing" when there is no meaningful P&L for the run. */
    fun runPnLDisplay(pnl: Double, positionOpened: Boolean?): String {
        val hasPnL = kotlin.math.abs(pnl) >= 0.01
        return if (hasPnL) {
            currency(pnl, showSign = true)
        } else {
            "Nothing"
        }
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
