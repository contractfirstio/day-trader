package daytrader.data

import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.domain.beginTouchTurnSession
import daytrader.diagnostics.SessionTrace
import daytrader.domain.inProgressSession
import daytrader.domain.onSessionStarted

/**
 * Shared start/stop helpers for manual runs and market-open auto-start.
 */
object DeploymentSessionController {
    fun start(
        instance: StrategyDeployment,
        sessionDate: String,
        touchTurnBootstrap: TouchTurnSessionBootstrap?,
        markAutoStarted: Boolean = false
    ): StrategyDeployment {
        val startedBy = if (markAutoStarted) {
            TouchTurnSessionStartedBy.AUTO_MARKET_OPEN
        } else {
            TouchTurnSessionStartedBy.MANUAL
        }
        val started = instance
            .onSessionStarted(sessionDate, touchTurnStartedBy = startedBy)
            .beginTouchTurnSession(sessionDate)
        val withSession = when (instance.strategyType) {
            StrategyType.TOUCH_AND_TURN_SCALPER -> {
                touchTurnBootstrap?.loadFirstCandle(started.id, sessionDate)
                started
            }
            StrategyType.QUICK_FLIP_SCALPER ->
                started.withDemoLiveExecutionOnStart(sessionDate)
        }
        // Record session date on any start so market-open auto-start does not run again the same day.
        val result = withSession.copy(lastAutoStartSessionDate = sessionDate)
        result.inProgressSession()?.let { SessionTrace.sessionStarted(result, it) }
        return result
    }
}
