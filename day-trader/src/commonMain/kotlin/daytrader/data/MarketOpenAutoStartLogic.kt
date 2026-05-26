package daytrader.data

import daytrader.domain.TouchTurnLogic

/**
 * Wall-clock gate for [MarketOpenAutoStarter]: auto-start runs once local RTH open has passed
 * plus [AUTO_START_DELAY_AFTER_OPEN_MS] in the symbol's market zone (weekends/holidays not skipped).
 */
object MarketOpenAutoStartLogic {
    /** Delay after RTH open before [MarketOpenAutoStarter] may start deployments. */
    const val AUTO_START_DELAY_AFTER_OPEN_MS = 60_000L

    fun sessionDateIfMarketOpen(
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): String? {
        val todayOpen = TouchTurnLogic.marketOpenEpochMillisForZone(marketZoneId, nowEpochMillis)
            ?: return null
        if (nowEpochMillis < todayOpen + AUTO_START_DELAY_AFTER_OPEN_MS) return null
        return TouchTurnLogic.sessionDateIsoInMarketZone(marketZoneId, nowEpochMillis)
    }
}
