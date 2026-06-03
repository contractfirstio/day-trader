package daytrader.domain

import daytrader.domain.DeploymentMarket
import daytrader.data.StrategyCatalog

/**
 * Touch Turn–specific run lifecycle rules (e.g. auto-stop [StrategyCatalog.stopAfterMinOpen]
 * minutes after RTH open using the first 15-minute candle anchor).
 */
object TouchTurnSessionStopLogic {
    fun sessionOpenEpochMillis(instance: StrategyDeployment, sessionDateIso: String): Long? {
        if (instance.strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return null
        val zoneId = instance.touchTurnSession?.marketZoneId ?: DeploymentMarket.effectiveZoneId(instance)
        val barTime = instance.touchTurnSession?.resolvedOpeningBarTime()
        return TouchTurnLogic.marketOpenEpochMillis(sessionDateIso, zoneId, barTime)
    }

    fun evaluateOpenDeadline(
        instance: StrategyDeployment,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): DeploymentSessionStopAction? {
        val stopAfterMinOpen = StrategyCatalog.stopAfterMinOpen(instance.strategyType) ?: return null
        val sessionDate = DeploymentSessionStopLogic.sessionDateForRunningInstance(instance) ?: return null
        val open = sessionOpenEpochMillis(instance, sessionDate) ?: return null
        val stopDeadline = open + stopAfterMinOpen * 60_000L
        return if (nowEpochMillis < stopDeadline) {
            DeploymentSessionStopAction.CONTINUE
        } else {
            DeploymentSessionStopAction.STOP_AFTER_OPEN_DEADLINE
        }
    }

    fun millisUntilStopAfterOpen(
        sessionOpenEpochMillis: Long,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Long {
        val stopAfterMinOpen = StrategyCatalog.stopAfterMinOpen(StrategyType.TOUCH_AND_TURN_SCALPER)!!
        val deadline = sessionOpenEpochMillis + stopAfterMinOpen * 60_000L
        return (deadline - nowEpochMillis).coerceAtLeast(0)
    }

    fun pendingStopAfterOpenLabel(millisRemaining: Long): String {
        val stopAfterMinOpen = StrategyCatalog.stopAfterMinOpen(StrategyType.TOUCH_AND_TURN_SCALPER)!!
        val minutes = (millisRemaining / 60_000).toInt()
        val seconds = ((millisRemaining % 60_000) / 1000).toInt()
        val timing = if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
        return "Auto-stop in $timing (${stopAfterMinOpen}m after open)"
    }
}
