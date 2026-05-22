package daytrader.data

import daytrader.domain.RthMarketSessions
import daytrader.domain.TouchTurnLogic
import daytrader.platform.CountdownAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays a 10-second spoken countdown ending with "[MARKET] Market Open"
 * for US, EUR, and HK RTH opens (once per session day per market).
 */
class MarketOpenCountdownWatcher(
    private val scope: CoroutineScope
) {
    private val countdownPlayedForSession = mutableMapOf<String, String>()

    fun start() {
        scope.launch {
            while (isActive) {
                delay(POLL_MS)
                checkCountdowns()
            }
        }
    }

    private fun checkCountdowns() {
        val now = System.currentTimeMillis()
        for (market in RthMarketSessions.all) {
            val sessionDate = TouchTurnLogic.sessionDateIsoInMarketZone(market.zoneId, now)
            if (countdownPlayedForSession[market.zoneId] == sessionDate) continue

            val open = TouchTurnLogic.marketOpenEpochMillisForZone(market.zoneId, now) ?: continue
            val millisUntilOpen = open - now
            if (millisUntilOpen in COUNTDOWN_START_WINDOW_MS) {
                countdownPlayedForSession[market.zoneId] = sessionDate
                scope.launch {
                    CountdownAudio.playTenSecondCountdown(market.label)
                }
            }
        }
    }

    private companion object {
        const val POLL_MS = 1_000L
        /** Fire once when between 9s and 10s before the open (1s poll resolution). */
        val COUNTDOWN_START_WINDOW_MS = 9_000L..10_999L
    }
}
