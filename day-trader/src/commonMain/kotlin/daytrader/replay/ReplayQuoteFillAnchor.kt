package daytrader.replay

import daytrader.platform.MutableTradingClock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Skips captured quotes at or before bracket placement so replay cannot fill on ticks paper
 * never saw while the entry stop was still working.
 */
object ReplayQuoteFillAnchor {
    fun ordersPlacedAnchorEpochMs(bundle: SessionBundle): Long? =
        bundle.timeline.ordersPlacedAnchorEpochMs
            ?: bundle.timeline.milestones?.ordersPlacedAt?.let { iso ->
                parseIsoToEpochMillis(iso, marketZoneId(bundle))
            }

    fun alignAfterBracketPlaced(
        quoteFeeder: MultiSymbolQuoteFeeder,
        clock: MutableTradingClock,
        symbol: String,
        anchorEpochMs: Long
    ) {
        quoteFeeder.seekToFirstQuoteAfter(symbol, anchorEpochMs)
        quoteFeeder.feederForSymbol(symbol)?.peekNext()?.let { clock.advanceTo(it.epochMs) }
    }

    private fun marketZoneId(bundle: SessionBundle): ZoneId =
        bundle.groundTruth?.runRecord?.marketInputs?.marketZoneId
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault()

    private fun parseIsoToEpochMillis(iso: String, zoneId: ZoneId): Long? = runCatching {
        LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
}
