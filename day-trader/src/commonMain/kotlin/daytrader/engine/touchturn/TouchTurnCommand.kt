package daytrader.engine

import daytrader.domain.InstrumentIdentity
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import daytrader.gateway.WorkingOrder

sealed interface TouchTurnCommand {
    data class StartSession(
        val instanceId: String,
        val sessionDate: String,
        val startedBy: TouchTurnSessionStartedBy = TouchTurnSessionStartedBy.MANUAL,
        val markAutoStarted: Boolean = false
    ) : TouchTurnCommand

    data class StopSession(
        val instanceId: String,
        val trigger: TouchTurnSessionStopTrigger = TouchTurnSessionStopTrigger.MANUAL
    ) : TouchTurnCommand

    data class AdjustStop(val instanceId: String, val stopPrice: Double) : TouchTurnCommand

    data class ClosePosition(val instanceId: String, val sessionDate: String) : TouchTurnCommand

    data class DeleteSessionHistory(val instanceId: String, val runId: String) : TouchTurnCommand

    data class BrokerSnapshot(
        val positions: List<AccountPosition>,
        val openOrders: List<WorkingOrder>,
        val fills: List<BrokerFill>
    ) : TouchTurnCommand

    data object BrokerConnected : TouchTurnCommand

    data class PollLiquidity(val instanceId: String) : TouchTurnCommand

    data object PollStopRules : TouchTurnCommand

    data object EvaluateAutoStart : TouchTurnCommand

    data class RetryBootstrap(val instanceId: String, val sessionDate: String) : TouchTurnCommand

    data class LoadFirstCandle(val instanceId: String, val sessionDate: String) : TouchTurnCommand
}
