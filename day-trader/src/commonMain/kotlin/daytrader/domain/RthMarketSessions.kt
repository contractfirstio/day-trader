package daytrader.domain

/**
 * Regular-hours cash sessions used for market-open countdown audio (09:30 local in each zone).
 */
data class RthMarketSession(
    val label: String,
    val zoneId: String,
    val closeHour: Int,
    val closeMinute: Int = 0
)

object RthMarketSessions {
    val US = RthMarketSession(label = "US", zoneId = "America/New_York", closeHour = 16)
    val EUR = RthMarketSession(label = "EUR", zoneId = "Europe/Berlin", closeHour = 17, closeMinute = 30)
    val HK = RthMarketSession(label = "HK", zoneId = "Asia/Hong_Kong", closeHour = 16)

    val all: List<RthMarketSession> = listOf(US, EUR, HK)

    fun forZoneId(zoneId: String): RthMarketSession = when (zoneId) {
        US.zoneId -> US
        EUR.zoneId -> EUR
        HK.zoneId -> HK
        else -> US
    }
}
