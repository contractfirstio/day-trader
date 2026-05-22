package daytrader.presentation.markets

import daytrader.domain.RthMarketSession
import daytrader.domain.RthMarketSessions
import daytrader.domain.TouchTurnLogic

data class MarketSessionStatusUi(
    val label: String,
    val zoneId: String,
    val zoneAbbrev: String,
    val isOpen: Boolean,
    val headline: String,
    /** Always populated — fixed-height subline slot (countdown or session elapsed). */
    val subline: String
)

object MarketSessionStatusUiMapper {
    fun all(nowEpochMillis: Long = System.currentTimeMillis()): List<MarketSessionStatusUi> =
        RthMarketSessions.all.map { toUi(it, nowEpochMillis) }

    fun toUi(session: RthMarketSession, nowEpochMillis: Long = System.currentTimeMillis()): MarketSessionStatusUi {
        val zoneAbbrev = TouchTurnLogic.marketOpenZoneAbbrev(session.zoneId)
        val isOpen = TouchTurnLogic.isRthMarketOpen(session, nowEpochMillis)
        return if (isOpen) {
            val elapsed = TouchTurnLogic.millisSinceLastMarketOpenWallClock(session.zoneId, nowEpochMillis)
            MarketSessionStatusUi(
                label = session.label,
                zoneId = session.zoneId,
                zoneAbbrev = zoneAbbrev,
                isOpen = true,
                headline = "LIVE",
                subline = TouchTurnLogic.formatElapsedSinceMarketOpen(elapsed)
            )
        } else {
            val remaining = TouchTurnLogic.millisUntilNextMarketOpen(session.zoneId, nowEpochMillis)
            MarketSessionStatusUi(
                label = session.label,
                zoneId = session.zoneId,
                zoneAbbrev = zoneAbbrev,
                isOpen = false,
                headline = "CLOSED",
                subline = TouchTurnLogic.formatCountdownToNextMarketOpen(remaining)
            )
        }
    }
}
