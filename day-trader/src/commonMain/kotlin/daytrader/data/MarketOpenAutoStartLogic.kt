package daytrader.data

import daytrader.domain.TouchTurnLogic

/**
 * Wall-clock gate for [MarketOpenAutoStarter]: session is open once local RTH 09:30 has passed
 * in the symbol's market zone (calendar days only; weekends/holidays not skipped).
 */
object MarketOpenAutoStartLogic {
    fun sessionDateIfMarketOpen(
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): String? {
        val todayOpen = TouchTurnLogic.marketOpenEpochMillisForZone(marketZoneId, nowEpochMillis)
            ?: return null
        if (nowEpochMillis < todayOpen) return null
        return TouchTurnLogic.sessionDateIsoInMarketZone(marketZoneId, nowEpochMillis)
    }
}
