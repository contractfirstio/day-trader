package daytrader.domain

import daytrader.domain.DeploymentMarket
import daytrader.data.StrategyCatalog

/**
 * Touch Turn–specific run lifecycle rules (e.g. configurable RTH open deadline auto-stop).
 */
object TouchTurnSessionStopLogic {
    fun sessionOpenEpochMillis(instance: StrategyDeployment, sessionDateIso: String): Long? {
        if (!instance.isTouchTurn) return null
        val zoneId = instance.touchTurnSession?.marketZoneId ?: DeploymentMarket.effectiveZoneId(instance)
        val barTime = instance.touchTurnSession?.resolvedOpeningBarTime()
        return TouchTurnLogic.marketOpenEpochMillis(sessionDateIso, zoneId, barTime)
    }

    fun evaluateOpenDeadline(
        instance: StrategyDeployment,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): DeploymentSessionStopAction? {
        if (StrategyCatalog.stopAfterMinOpen(instance.strategyType) == null) return null
        val rules = instance.effectiveTouchTurnRules()
        if (!rules.enables.openDeadline) return DeploymentSessionStopAction.CONTINUE
        val sessionDate = DeploymentSessionStopLogic.sessionDateForRunningInstance(instance) ?: return null
        val open = sessionOpenEpochMillis(instance, sessionDate) ?: return null
        val stopDeadline = open + rules.stopAfterOpenMinutes * 60_000L
        return if (nowEpochMillis < stopDeadline) {
            DeploymentSessionStopAction.CONTINUE
        } else {
            DeploymentSessionStopAction.STOP_AFTER_OPEN_DEADLINE
        }
    }

    fun millisUntilStopAfterOpen(
        sessionOpenEpochMillis: Long,
        rules: TouchTurnRuleConfig,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Long? {
        if (!rules.enables.openDeadline) return null
        val deadline = sessionOpenEpochMillis + rules.stopAfterOpenMinutes * 60_000L
        return (deadline - nowEpochMillis).coerceAtLeast(0)
    }

    fun pendingStopAfterOpenLabel(millisRemaining: Long, stopAfterOpenMinutes: Int): String {
        val minutes = (millisRemaining / 60_000).toInt()
        val seconds = ((millisRemaining % 60_000) / 1000).toInt()
        val timing = if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
        return "Auto-stop in $timing (${stopAfterOpenMinutes}m after open)"
    }
}
