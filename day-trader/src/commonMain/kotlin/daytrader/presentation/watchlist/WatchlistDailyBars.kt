package daytrader.presentation.watchlist

import daytrader.domain.OhlcBar

object WatchlistDailyBars {
    const val TRADING_DAYS_ONE_MONTH = 22

    fun fromDailyCloses(closes: List<Double>): List<OhlcBar> {
        val valid = closes.filter { it.isFinite() && it > 0.0 }
        if (valid.isEmpty()) return emptyList()
        return valid.mapIndexed { index, close ->
            val open = if (index == 0) close else valid[index - 1]
            val bodyHigh = maxOf(open, close)
            val bodyLow = minOf(open, close)
            val wickPad = (bodyHigh - bodyLow).coerceAtLeast(close * 0.002)
            OhlcBar(
                open = open,
                high = bodyHigh + wickPad * 0.35,
                low = bodyLow - wickPad * 0.35,
                close = close,
                time = ""
            )
        }
    }
}
