package daytrader.engine

import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.presentation.strategies.StartBlockedByPositionAlert
import daytrader.presentation.strategies.StrategyDetailTab

sealed interface TouchTurnEvent {
    data class SessionStarted(
        val instanceId: String,
        val sessionId: String,
        val sessionDate: String,
        val startedBy: TouchTurnSessionStartedBy
    ) : TouchTurnEvent

    data class SessionStopped(
        val instanceId: String,
        val sessionId: String?,
        val trigger: TouchTurnSessionStopTrigger
    ) : TouchTurnEvent

    data class NoTradeDecision(
        val instanceId: String,
        val outcome: TouchTurnSessionOutcome
    ) : TouchTurnEvent

    data class BracketSubmitted(
        val instanceId: String,
        val plan: TouchTurnOrderPlan
    ) : TouchTurnEvent

    data class PositionOpened(val instanceId: String, val milestoneAt: String) : TouchTurnEvent

    data class OrchestratorError(val instanceId: String?, val message: String) : TouchTurnEvent

    data class UiNavigate(
        val instanceId: String,
        val tab: StrategyDetailTab
    ) : TouchTurnEvent

    data class StartBlocked(val alert: StartBlockedByPositionAlert) : TouchTurnEvent
}
