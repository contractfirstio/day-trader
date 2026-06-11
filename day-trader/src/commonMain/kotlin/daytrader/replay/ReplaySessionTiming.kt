package daytrader.replay

import daytrader.domain.DeploymentMarket
import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnLogic
import daytrader.platform.MutableTradingClock

/**
 * Anchors virtual replay time to the replayed session's RTH open so pipeline and stop rules
 * evaluate against session date — not the wall clock when the user clicked Start.
 */
object ReplaySessionTiming {
    fun sessionOpenEpochMillis(instance: StrategyDeployment, sessionDate: String): Long? =
        TouchTurnLogic.marketOpenEpochMillis(
            sessionDateIso = sessionDate,
            marketZoneId = DeploymentMarket.effectiveZoneId(instance),
            firstCandleBarTime = null
        )

    fun alignClockToSessionOpen(
        clock: MutableTradingClock,
        instance: StrategyDeployment,
        sessionDate: String
    ): Long? {
        val open = sessionOpenEpochMillis(instance, sessionDate) ?: return null
        clock.reset(open)
        return open
    }
}
