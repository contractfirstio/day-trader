package daytrader.presentation.strategies

import daytrader.domain.DeploymentSessionStopLogic
import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnSessionStopLogic
import daytrader.domain.effectiveTouchTurnRules

/** Presentation labels for Touch Turn UI sections (keeps domain logic out of Compose screens). */
object TouchTurnScreenLabels {
    data class MarketOpenTimers(
        val zoneAbbrev: String,
        val elapsedSinceOpen: String,
        val nextOpenAt: String,
        val countdownToNextOpen: String
    )

    fun marketOpenTimers(marketZoneId: String): MarketOpenTimers =
        MarketOpenTimers(
            zoneAbbrev = TouchTurnLogic.marketOpenZoneAbbrev(marketZoneId),
            elapsedSinceOpen = TouchTurnLogic.formatElapsedSinceMarketOpen(
                TouchTurnLogic.millisSinceLastMarketOpenWallClock(marketZoneId)
            ),
            nextOpenAt = TouchTurnLogic.nextMarketOpenLocalLabel(marketZoneId),
            countdownToNextOpen = TouchTurnLogic.formatCountdownToNextMarketOpen(
                TouchTurnLogic.millisUntilNextMarketOpen(marketZoneId)
            )
        )

    data class AutoStopStatus(
        val stopAfterMinOpen: Int,
        val remainingLabel: String?,
        val pastDeadline: Boolean
    )

    fun autoStopStatus(instance: StrategyDeployment): AutoStopStatus? {
        val rules = instance.effectiveTouchTurnRules()
        if (!rules.enables.openDeadline) return null
        val sessionDate = DeploymentSessionStopLogic.sessionDateForRunningInstance(instance) ?: return null
        val openEpoch = TouchTurnSessionStopLogic.sessionOpenEpochMillis(instance, sessionDate) ?: return null
        val remainingMs = TouchTurnSessionStopLogic.millisUntilStopAfterOpen(openEpoch, rules) ?: return null
        val pastDeadline = remainingMs == 0L
        val remainingLabel = if (pastDeadline) {
            null
        } else {
            TouchTurnSessionStopLogic.pendingStopAfterOpenLabel(remainingMs, rules.stopAfterOpenMinutes)
        }
        return AutoStopStatus(
            stopAfterMinOpen = rules.stopAfterOpenMinutes,
            remainingLabel = remainingLabel,
            pastDeadline = pastDeadline
        )
    }

    fun pastDeadlineLabel(stopAfterMinOpen: Int): String =
        "Past ${stopAfterMinOpen}m after open — session will stop and flatten broker orders/position."
}
