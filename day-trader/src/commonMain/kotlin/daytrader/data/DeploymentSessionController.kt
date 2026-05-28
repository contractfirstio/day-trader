package daytrader.data

import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.domain.beginTouchTurnSession
import daytrader.data.StrategyCatalog
import daytrader.data.withDemoLiveExecutionOnStart
import daytrader.diagnostics.SessionTrace
import daytrader.domain.inProgressSession
import daytrader.domain.onSessionStarted

/**
 * Shared start helpers for manual runs and market-open auto-start.
 * Touch Turn bootstrap is handled by [daytrader.engine.TouchTurnEngine].
 */
object DeploymentSessionController {
    fun start(
        instance: StrategyDeployment,
        sessionDate: String,
        markAutoStarted: Boolean = false,
        onTouchTurnSessionStarted: ((instanceId: String, sessionDate: String) -> Unit)? = null
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
                onTouchTurnSessionStarted?.invoke(started.id, sessionDate)
                started
            }
            StrategyType.QUICK_FLIP_SCALPER ->
                started.withDemoLiveExecutionOnStart(sessionDate)
        }
        val result = withSession.copy(lastAutoStartSessionDate = sessionDate)
        result.inProgressSession()?.let { SessionTrace.sessionStarted(result, it) }
        return result
    }
}
