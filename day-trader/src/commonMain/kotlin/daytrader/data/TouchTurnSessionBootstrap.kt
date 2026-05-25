package daytrader.data

import daytrader.gateway.BrokerGateway
import daytrader.broker.SymbolMarkets
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.InstanceStatus
import daytrader.domain.StrategyType
import daytrader.domain.withFirstFifteenMinuteCandle
import daytrader.domain.withLiquidityEvaluatedIfClosed
import daytrader.domain.withOrdersPlacedForSession
import daytrader.domain.withTouchTurnCandleFailed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * When a Touch Turn instance is started, loads 14-day ADR and the first 15-minute RTH candle from IB,
 * then evaluates liquidity once that bar has closed (range > 25% of ADR).
 */
class TouchTurnSessionBootstrap(
    private val gateway: BrokerGateway,
    private val repository: StrategyInstanceRepository,
    private val scope: CoroutineScope
) {
    fun loadFirstCandle(instanceId: String, sessionDate: String) {
        scope.launch {
            val instance = repository.instances.value.find { it.id == instanceId } ?: return@launch
            if (instance.strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return@launch

            val symbol = instance.symbol
            val adrResult = gateway.fetchFourteenDayAdr(symbol)
            val adr14 = adrResult.getOrElse { error ->
                repository.update(instanceId) { current ->
                    current.withTouchTurnCandleFailed(
                        sessionDate,
                        error.message ?: "Failed to load 14-day ADR"
                    )
                }
                return@launch
            }

            val candleResult = gateway.fetchFirstFifteenMinuteCandle(symbol)
            val currency = SymbolMarkets.currencyCode(symbol)
            val zoneId = SymbolMarkets.zoneId(symbol)
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
                val instance = repository.instances.value.find { it.id == instanceId } ?: return@launch
                if (instance.status != InstanceStatus.RUNNING) return@launch
                val session = instance.touchTurnSession ?: return@launch
                if (session.setup != null) return@launch
                if (session.candleCloseStatus() != FirstCandleCloseStatus.CLOSED) continue

                val evaluatedAt = System.currentTimeMillis()
                var ordersPlaced = false
                repository.update(instanceId) { current ->
                    val updated = current.withLiquidityEvaluatedIfClosed(evaluatedAt)
                    val session = updated.touchTurnSession
                    ordersPlaced = TouchTurnOrderLog.logAfterLiquidityEvaluation(
                        instanceId = updated.id,
                        symbol = updated.symbol,
                        sessionDate = session?.sessionDate ?: sessionDate,
                        maxDollars = updated.maxDollars,
                        currencyCode = session?.currencyCode ?: SymbolMarkets.currencyCode(updated.symbol),
                        setup = session?.setup,
                        brokerGateway = gateway
                    )
                    if (ordersPlaced) updated.withOrdersPlacedForSession() else updated
                }
                return@launch
            }
        }
    }

    private companion object {
        const val LIQUIDITY_POLL_MS = 5_000L
    }
}
