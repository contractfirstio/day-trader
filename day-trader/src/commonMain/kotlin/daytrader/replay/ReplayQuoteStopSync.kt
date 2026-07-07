package daytrader.replay

import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnSessionStopLogic
import daytrader.domain.effectiveTouchTurnRules

/** OPEN_DEADLINE virtual-time helpers for replay quote drip. */
object ReplayQuoteStopSync {
    fun openDeadlineEpochMs(deployment: StrategyDeployment, sessionDate: String): Long? {
        val rules = deployment.effectiveTouchTurnRules()
        if (!rules.enables.openDeadline) return null
        val open = TouchTurnSessionStopLogic.sessionOpenEpochMillis(deployment, sessionDate) ?: return null
        return open + rules.stopAfterOpenMinutes * 60_000L
    }

    /** True when replay has no further quotes to publish for fill evaluation on this symbol. */
    fun quoteTimelineEnded(nextQuoteEpochMs: Long?, deadlineEpochMs: Long?): Boolean {
        if (nextQuoteEpochMs == null) return true
        return deadlineEpochMs != null && nextQuoteEpochMs > deadlineEpochMs
    }
}
