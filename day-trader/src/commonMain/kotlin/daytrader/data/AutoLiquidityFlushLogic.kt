package daytrader.data

import daytrader.domain.AUTO_LIQUIDITY_FLUSH_OFFSET_MS
import daytrader.domain.TouchTurnLogic

/**
 * Wall-clock gate for auto liquidity flush: runs once local RTH open has passed
 * plus [AUTO_LIQUIDITY_FLUSH_OFFSET_MS] in the symbol's market zone.
 */
object AutoLiquidityFlushLogic {
    fun sessionDateIfFlushDue(
        marketZoneId: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): String? {
        val todayOpen = TouchTurnLogic.marketOpenEpochMillisForZone(marketZoneId, nowEpochMillis)
            ?: return null
        if (nowEpochMillis < todayOpen + AUTO_LIQUIDITY_FLUSH_OFFSET_MS) return null
        return TouchTurnLogic.sessionDateIsoInMarketZone(marketZoneId, nowEpochMillis)
    }

    fun flushKey(zoneId: String, sessionDate: String): String = "$zoneId:$sessionDate"
}
