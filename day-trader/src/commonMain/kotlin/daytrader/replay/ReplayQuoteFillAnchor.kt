package daytrader.replay

import daytrader.platform.MutableTradingClock

/**
 * Skips captured quotes at or before bracket placement so replay cannot fill on ticks paper
 * never saw while the entry stop was still working.
 */
object ReplayQuoteFillAnchor {
    fun ordersPlacedAnchorEpochMs(bundle: SessionBundle): Long? =
        bundle.timeline.milestones?.ordersPlacedAt?.let(::parseIsoToEpochMillis)

    fun alignAfterBracketPlaced(
        quoteFeeder: MultiSymbolQuoteFeeder,
        clock: MutableTradingClock,
        symbol: String,
        anchorEpochMs: Long
    ) {
        val feeder = quoteFeeder.feederForSymbol(symbol) ?: return
        val next = feeder.peekNext() ?: return
        if (next.epochMs <= anchorEpochMs) {
            quoteFeeder.seekToFirstQuoteAfter(symbol, anchorEpochMs)
            quoteFeeder.feederForSymbol(symbol)?.peekNext()?.let { clock.advanceTo(it.epochMs) }
        }
    }

    private fun parseIsoToEpochMillis(iso: String): Long? = runCatching {
        java.time.LocalDateTime.parse(iso, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
}
