package daytrader.data

import daytrader.domain.StrategyInstance
import daytrader.domain.StrategyType
import daytrader.domain.beginTouchTurnSession
import daytrader.domain.onRunStarted

/**
 * Shared start/stop helpers for manual runs and market-open auto-start.
 */
object InstanceRunController {
    fun start(
        instance: StrategyInstance,
        sessionDate: String,
        touchTurnBootstrap: TouchTurnSessionBootstrap?,
        markAutoStarted: Boolean = false
    ): StrategyInstance {
        val started = instance.onRunStarted(sessionDate).beginTouchTurnSession(sessionDate)
        val withSession = when (instance.strategyType) {
            StrategyType.TOUCH_AND_TURN_SCALPER -> {
                touchTurnBootstrap?.loadFirstCandle(started.id, sessionDate)
                started
            }
            StrategyType.QUICK_FLIP_SCALPER ->
                started.withDemoLiveExecutionOnStart(sessionDate)
        }
        // Record session date on any start so market-open auto-start does not run again the same day.
        return withSession.copy(lastAutoStartSessionDate = sessionDate)
    }
}
