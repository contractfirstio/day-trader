package daytrader.domain

/**
 * Regular-hours cash sessions used for market-open countdown audio and auto-start gates.
 * Open/close are local wall-clock times in [zoneId] (weekends skipped; holidays not excluded).
 */
data class RthMarketSession(
    val label: String,
    val zoneId: String,
    val openHour: Int = 9,
    val openMinute: Int = 30,
    val closeHour: Int,
    val closeMinute: Int = 0
)

object RthMarketSessions {
    val US = RthMarketSession(label = "US", zoneId = "America/New_York", closeHour = 16)
    /** LSE continuous trading — London timezone, 08:00–16:30 local. */
    val EUR = RthMarketSession(
        label = "EUR",
        zoneId = "Europe/London",
        openHour = 8,
        openMinute = 0,
        closeHour = 16,
        closeMinute = 30
    )
    val HK = RthMarketSession(label = "HK", zoneId = "Asia/Hong_Kong", closeHour = 16)

    val all: List<RthMarketSession> = listOf(US, EUR, HK)

    fun forZoneId(zoneId: String): RthMarketSession = when (zoneId) {
        US.zoneId -> US
        EUR.zoneId -> EUR
        HK.zoneId -> HK
        "Europe/Berlin" -> EUR
        else -> US
    }
}
