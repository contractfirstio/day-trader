package daytrader.data

import daytrader.gateway.BrokerGateway
import daytrader.domain.DeploymentMarket
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnEntryWindowStatus
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.withFirstFifteenMinuteCandle
import daytrader.domain.withLiquidityEvaluatedIfClosed
import daytrader.domain.withOrdersPlacedForSession
import daytrader.domain.withTouchTurnCandleFailed
import daytrader.domain.withTouchTurnDecisionOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * When a Touch Turn instance is started, loads 14-day ADR and the first 15-minute RTH candle from IB,
 * then evaluates liquidity once that bar has closed (range > 25% of ADR).
 */
class TouchTurnSessionBootstrap(
    private val sessionGateway: BrokerGateway,
    private val executionGateway: BrokerGateway = sessionGateway,
    private val repository: StrategyDeploymentRepository,
    private val scope: CoroutineScope,
    private val ensureLiveMarketData: ((String) -> Unit)? = null
) {
    fun loadFirstCandle(instanceId: String, sessionDate: String) {
        scope.launch {
            val instance = repository.deployments.value.find { it.id == instanceId } ?: return@launch
            if (instance.strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return@launch

            val symbol = instance.symbol
            ensureLiveMarketData?.invoke(symbol)
            val adrResult = sessionGateway.fetchFourteenDayAdr(symbol)
            val adr14 = adrResult.getOrElse { error ->
                repository.update(instanceId) { current ->
                    current.withTouchTurnCandleFailed(
                        sessionDate,
                        error.message ?: "Failed to load 14-day ADR"
                    )
                }
                return@launch
            }

            val candleResult = sessionGateway.fetchFirstFifteenMinuteCandle(symbol)
            val zoneId = DeploymentMarket.effectiveZoneId(instance)
            val currency = DeploymentMarket.effectiveCurrencyCode(instance)
            repository.update(instanceId) { current ->
                candleResult.fold(
                    onSuccess = { bar ->
                        current.withFirstFifteenMinuteCandle(
                            sessionDate = sessionDate,
                            candle = bar,
                            adr14 = adr14,
                            currencyCode = currency,
                            marketZoneId = zoneId
                        )
                    },
                    onFailure = { error ->
                        current.withTouchTurnCandleFailed(
                            sessionDate,
                            error.message ?: "Failed to load first 15-minute candle"
                        )
                    }
                )
            }

            if (candleResult.isSuccess) {
                watchForLiquidityEvaluation(instanceId, sessionDate)
            }
        }
    }

    private fun watchForLiquidityEvaluation(instanceId: String, sessionDate: String) {
        scope.launch {
            while (isActive) {
                delay(LIQUIDITY_POLL_MS)
                val instance = repository.deployments.value.find { it.id == instanceId } ?: return@launch
                if (instance.status != DeploymentStatus.RUNNING) return@launch
                val session = instance.touchTurnSession ?: return@launch
                if (session.setup != null) return@launch
                if (session.candleCloseStatus() != FirstCandleCloseStatus.CLOSED) continue

                val evaluatedAt = System.currentTimeMillis()
                var ordersPlaced = false
                repository.update(instanceId) { current ->
                    val updated = current.withLiquidityEvaluatedIfClosed(evaluatedAt)
                    val session = updated.touchTurnSession ?: return@update updated
                    when (session.decisionOutcome) {
                        TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
                        TouchTurnSessionOutcome.NO_TRADE_DOJI,
                        TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED -> return@update updated
                        else -> Unit
                    }
                    if (session.entryWindowStatus(evaluatedAt) == TouchTurnEntryWindowStatus.EXPIRED) {
                        return@update updated.withTouchTurnDecisionOutcome(
                            TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED
                        )
                    }
                    val setup = session.setup
                    val plan = setup?.let { s ->
                        TouchTurnOrderPlanner.buildOrderPlan(
                            symbol = updated.symbol,
                            setup = s,
                            maxDollars = updated.maxDollars,
                            currencyCode = session.currencyCode
                        )
                    }
                    ordersPlaced = TouchTurnOrderLog.logAfterLiquidityEvaluation(
                        instanceId = updated.id,
                        symbol = updated.symbol,
                        sessionDate = session.sessionDate,
                        maxDollars = updated.maxDollars,
                        currencyCode = session.currencyCode,
                        setup = setup,
                        brokerGateway = executionGateway
                    )
                    when {
                        ordersPlaced && plan != null -> updated.withOrdersPlacedForSession(plan)
                        setup?.isActionable == true ->
                            updated.withTouchTurnDecisionOutcome(TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED)
                        else -> updated
                    }
                }
                return@launch
            }
        }
    }

    private companion object {
        const val LIQUIDITY_POLL_MS = 5_000L
    }
}
