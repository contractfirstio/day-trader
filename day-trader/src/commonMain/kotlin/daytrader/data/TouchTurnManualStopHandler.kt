package daytrader.data

import daytrader.broker.SessionTradeMatcher
import daytrader.broker.SymbolMarkets
import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.currentSessionTimestampIso
import daytrader.domain.inferTouchTurnStopTrigger
import daytrader.domain.onSessionStopped
import daytrader.domain.resolveStopSnapshot
import daytrader.domain.SessionStopParams
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerKind
import daytrader.gateway.WorkingOrder

/**
 * Manual session stop: flatten broker state and apply [onSessionStopped] to the deployment.
 * Extracted from [daytrader.presentation.strategies.StrategiesViewModel] for testability.
 */
object TouchTurnManualStopHandler {
    data class Input(
        val instance: StrategyDeployment,
        val brokerPositions: List<AccountPosition>,
        val brokerOpenOrders: List<WorkingOrder>,
        val brokerFills: List<BrokerFill>,
        val flattenOnBroker: Boolean = true,
        val brokerKind: BrokerKind? = null
    )

    data class Result(
        val stoppedDeployment: StrategyDeployment,
        val stopTrigger: TouchTurnSessionStopTrigger
    )

    fun stop(
        input: Input,
        gateway: BrokerGateway?,
        explicitTrigger: TouchTurnSessionStopTrigger? = null
    ): Result {
        val instance = input.instance
        val brokerPosition = SymbolMarkets.findOpenPosition(instance, input.brokerPositions)
        val hadOpenPosition = brokerPosition != null
        val hasOpenOrders = SymbolMarkets.hasOpenOrders(instance, input.brokerOpenOrders)
        if (input.flattenOnBroker) {
            gateway?.let { SessionStopOrderCleanup.flattenSymbolForSession(it, instance.symbol) }
        }
        val stoppedAt = currentSessionTimestampIso()
        val sessionTrades = SessionTradeMatcher.captureForSessionStop(
            instance = instance,
            fills = input.brokerFills
        )
        val stopTrigger = explicitTrigger ?: inferTouchTurnStopTrigger(
            instance = instance,
            sessionTrades = sessionTrades,
            hasOpenPosition = hadOpenPosition,
            hasOpenOrders = hasOpenOrders
        )
        val snapshot = instance.resolveStopSnapshot(
            hadOpenBrokerPosition = hadOpenPosition,
            brokerUnrealizedPnL = brokerPosition?.totalUnrealizedPnL,
            sessionTrades = sessionTrades
        )
        val stopped = instance.onSessionStopped(
            stoppedAt = stoppedAt,
            snapshot = snapshot,
            stopParams = gateway?.let {
                SessionStopParams(
                    stopTrigger = stopTrigger,
                    brokerId = it.brokerId,
                    brokerKind = input.brokerKind,
                    stopErrorMessage = instance.touchTurnSession?.errorMessage,
                    brokerUnrealizedPnLAtStop = brokerPosition?.totalUnrealizedPnL,
                    hasOpenPosition = hadOpenPosition,
                    hasOpenOrders = hasOpenOrders
                )
            }
        )
        return Result(stoppedDeployment = stopped, stopTrigger = stopTrigger)
    }

    fun manualStopTrigger(): TouchTurnSessionStopTrigger = TouchTurnSessionStopTrigger.MANUAL
}
