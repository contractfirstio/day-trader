package daytrader.presentation.strategies

import daytrader.broker.BrokerDeploymentIndex
import daytrader.broker.SessionTradeMatcher
import daytrader.broker.SymbolMarkets
import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionTrade
import daytrader.domain.StrategyDeployment
import daytrader.domain.isTouchTurn
import daytrader.domain.StrategyType
import daytrader.domain.inProgressSession
import daytrader.domain.touchTurnRecapRun
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import daytrader.gateway.WorkingOrder

/**
 * Resolves Touch Turn pipeline graph inputs from the same broker + session signals
 * the engine uses ([daytrader.data.DeploymentSessionStopEvaluator]), so the live
 * pipeline graph reflects the true run state.
 */
object TouchTurnPipelineUiMapper {
    data class LiveContext(
        val hasOpenPosition: Boolean,
        val hasOpenOrders: Boolean,
        val sessionTrades: List<SessionTrade>,
        val orderLifecycle: TouchTurnOrderLifecycleUi,
        val nowEpochMillis: Long
    )

    fun liveContext(
        instance: StrategyDeployment,
        brokerPositions: List<AccountPosition>,
        brokerOpenOrders: List<WorkingOrder>,
        brokerFills: List<BrokerFill>,
        inActiveTrade: Boolean = false,
        sessionEnded: Boolean = false,
        nowEpochMillis: Long = System.currentTimeMillis(),
        brokerIndex: BrokerDeploymentIndex? = null,
    ): LiveContext {
        val hasOpenPosition = brokerIndex?.hasOpenPosition(instance)
            ?: SymbolMarkets.hasOpenPosition(instance, brokerPositions)
        val hasOpenOrders = brokerIndex?.hasOpenOrders(instance)
            ?: SymbolMarkets.hasOpenOrders(instance, brokerOpenOrders)
        val sessionTrades = liveSessionTrades(instance, brokerFills)
        return LiveContext(
            hasOpenPosition = hasOpenPosition,
            hasOpenOrders = hasOpenOrders,
            sessionTrades = sessionTrades,
            orderLifecycle = TouchTurnOrderLifecycleResolver.resolve(
                session = instance.touchTurnSession,
                hasOpenPosition = hasOpenPosition,
                hasOpenOrders = hasOpenOrders,
                inActiveTrade = inActiveTrade,
                sessionEnded = sessionEnded,
                hasSessionTrades = sessionTrades.isNotEmpty()
            ),
            nowEpochMillis = nowEpochMillis
        )
    }

    fun liveSessionTrades(
        instance: StrategyDeployment,
        brokerFills: List<BrokerFill>
    ): List<SessionTrade> {
        val run = instance.inProgressSession() ?: return emptyList()
        return SessionTradeMatcher.toSessionTrades(
            SessionTradeMatcher.fillsForSession(
                symbol = instance.symbol,
                startedAt = run.startedAt,
                stoppedAt = null,
                fills = brokerFills
            )
        )
    }

    fun graphForDeployment(
        instance: StrategyDeployment,
        brokerPositions: List<AccountPosition>,
        brokerOpenOrders: List<WorkingOrder>,
        brokerFills: List<BrokerFill>,
        showSessionRecap: Boolean,
        recapRunId: String? = null,
        nowEpochMillis: Long = System.currentTimeMillis(),
        brokerIndex: BrokerDeploymentIndex? = null,
    ): TouchTurnPipelineGraph? {
        if (!instance.isTouchTurn) return null
        return when {
            instance.status == DeploymentStatus.RUNNING -> {
                val ctx = liveContext(
                    instance = instance,
                    brokerPositions = brokerPositions,
                    brokerOpenOrders = brokerOpenOrders,
                    brokerFills = brokerFills,
                    nowEpochMillis = nowEpochMillis,
                    brokerIndex = brokerIndex,
                )
                TouchTurnStatusBreadcrumbMapper.graph(
                    instance = instance,
                    hasOpenPosition = ctx.hasOpenPosition,
                    hasOpenOrders = ctx.hasOpenOrders,
                    sessionTrades = ctx.sessionTrades,
                    nowEpochMillis = ctx.nowEpochMillis
                )
            }
            showSessionRecap -> {
                val run = instance.touchTurnRecapRun(recapRunId) ?: return null
                TouchTurnStatusBreadcrumbMapper.graphForSession(instance, run)
            }
            else ->
                TouchTurnStatusBreadcrumbMapper.graph(
                    instance = instance,
                    hasOpenPosition = false,
                    hasOpenOrders = false,
                    sessionTrades = emptyList(),
                    nowEpochMillis = nowEpochMillis
                )
        }
    }
}
