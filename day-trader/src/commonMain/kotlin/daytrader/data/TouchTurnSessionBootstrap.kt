package daytrader.data

import daytrader.broker.IbGatewayConnection
import daytrader.broker.SymbolMarkets
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.InstanceStatus
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnNoPositionCancelOutcome
import daytrader.domain.withFirstFifteenMinuteCandle
import daytrader.domain.withLiquidityEvaluatedIfClosed
import daytrader.domain.withNoPositionBracketCancelEvaluated
import daytrader.domain.withTouchTurnCandleFailed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * When a Touch Turn instance is started, loads 14-day ADR and the first 15-minute RTH candle from IB,
 * then evaluates liquidity once that bar has closed (range > 25% of ADR).
 * Watches for the 90-minute no-position bracket cancel rule (log-only).
 */
class TouchTurnSessionBootstrap(
    private val gateway: IbGatewayConnection,
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

            val candleResult = gateway.fetch(symbol)
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
                if (session.setup != null) {
                    if (session.entryOrdersPermitted == true &&
                        session.noPositionBracketCancelOutcome == TouchTurnNoPositionCancelOutcome.PENDING
                    ) {
                        watchForNoPositionBracketCancel(instanceId)
                    }
                    return@launch
                }
                if (session.candleCloseStatus() != FirstCandleCloseStatus.CLOSED) continue

                val evaluatedAt = System.currentTimeMillis()
                var startCancelWatch = false
                repository.update(instanceId) { current ->
                    val updated = current.withLiquidityEvaluatedIfClosed(evaluatedAt)
                    val session = updated.touchTurnSession
                    TouchTurnOrderLog.logAfterLiquidityEvaluation(
                        instanceId = updated.id,
                        symbol = updated.symbol,
                        sessionDate = session?.sessionDate ?: sessionDate,
                        maxDollars = updated.maxDollars,
                        currencyCode = session?.currencyCode ?: SymbolMarkets.currencyCode(updated.symbol),
                        candle = session?.candle,
                        marketZoneId = session?.marketZoneId ?: SymbolMarkets.zoneId(updated.symbol),
                        setup = session?.setup,
                        nowEpochMillis = evaluatedAt
                    )
                    startCancelWatch = session?.entryOrdersPermitted == true
                    updated
                }
                if (startCancelWatch) {
                    watchForNoPositionBracketCancel(instanceId)
                }
                return@launch
            }
        }
    }

    private fun watchForNoPositionBracketCancel(instanceId: String) {
        scope.launch {
            while (isActive) {
                delay(NO_POSITION_CANCEL_POLL_MS)
                val instance = repository.instances.value.find { it.id == instanceId } ?: return@launch
                if (instance.status != InstanceStatus.RUNNING) return@launch
                val session = instance.touchTurnSession ?: return@launch
                if (session.entryOrdersPermitted != true) return@launch
                when (session.noPositionBracketCancelOutcome) {
                    TouchTurnNoPositionCancelOutcome.WOULD_CANCEL_LOGGED,
                    TouchTurnNoPositionCancelOutcome.KEPT_HAS_POSITION -> return@launch
                    TouchTurnNoPositionCancelOutcome.PENDING,
                    null -> Unit
                }

                val now = System.currentTimeMillis()
                if (!TouchTurnLogic.isPastNoPositionCancelDeadline(
                        session.sessionDate,
                        session.marketZoneId,
                        session.candle?.time,
                        now
                    )
                ) {
                    continue
                }

                val setup = session.setup?.takeIf { it.isLiquidityCandle && it.isActionable } ?: return@launch
                val hasPosition = SymbolMarkets.hasOpenPosition(instance.symbol, gateway.positions.value)
                val outcome = if (hasPosition) {
                    TouchTurnNoPositionCancelOutcome.KEPT_HAS_POSITION
                } else {
                    TouchTurnNoPositionCancelOutcome.WOULD_CANCEL_LOGGED
                }
                repository.update(instanceId) { current ->
                    val updated = current.withNoPositionBracketCancelEvaluated(outcome)
                    if (outcome == TouchTurnNoPositionCancelOutcome.WOULD_CANCEL_LOGGED) {
                        val s = updated.touchTurnSession
                        TouchTurnOrderLog.logWouldCancelBrackets(
                            instanceId = updated.id,
                            symbol = updated.symbol,
                            sessionDate = s?.sessionDate ?: session.sessionDate,
                            marketZoneId = s?.marketZoneId ?: session.marketZoneId,
                            maxDollars = updated.maxDollars,
                            currencyCode = s?.currencyCode ?: SymbolMarkets.currencyCode(updated.symbol),
                            setup = setup
                        )
                    }
                    updated
                }
                return@launch
            }
        }
    }

    private companion object {
        const val LIQUIDITY_POLL_MS = 5_000L
        const val NO_POSITION_CANCEL_POLL_MS = 30_000L
    }
}
