package daytrader.data

import daytrader.broker.SymbolMarkets

/** Ensures protective stops are never cancelled while a broker position remains open. */
object OpenDeadlineProtectiveStopGuard {
    fun mayCancelProtectiveStops(symbol: String, positions: List<daytrader.gateway.AccountPosition>): Boolean =
        !SymbolMarkets.hasOpenPosition(symbol, positions)
}
