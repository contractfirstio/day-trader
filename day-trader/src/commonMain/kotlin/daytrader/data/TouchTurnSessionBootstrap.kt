package daytrader.data

import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerId
import daytrader.domain.DeploymentMarket
import daytrader.domain.InstrumentIdentity
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnEntryWindowStatus
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.beginTouchTurnSession
import daytrader.domain.inProgressSession
import daytrader.domain.withFirstFifteenMinuteCandle
import daytrader.domain.withLiquidityEvaluatedIfClosed
import daytrader.domain.withOrdersPlacedForSession
import daytrader.domain.withTouchTurnCandleFailed
import daytrader.diagnostics.SessionTrace
import daytrader.domain.TouchTurnCandleLog
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnCloseConfirmation
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
    private val ensureLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null
) {
    private val stuckFormingLogged = mutableSetOf<String>()
    private val loadJobsByInstanceId = mutableMapOf<String, kotlinx.coroutines.Job>()

    /**
     * Re-attempts ADR + first 15m bar fetch when the broker reconnects after a transient IB outage.
     */
    fun retryStuckLoadsWhenConnected() {
        scope.launch {
            repository.deployments.value
                .asSequence()
                .filter { it.status == DeploymentStatus.RUNNING }
                .filter { it.strategyType == StrategyType.TOUCH_AND_TURN_SCALPER }
                .mapNotNull { instance ->
                    val session = instance.touchTurnSession ?: return@mapNotNull null
                    val sessionDate = instance.inProgressSession()?.date ?: session.sessionDate
                    when (session.status) {
                        TouchTurnCandleStatus.LOADING,
                        TouchTurnCandleStatus.FAILED -> instance.id to sessionDate
                        else -> null
                    }
                }
                .forEach { (instanceId, sessionDate) ->
                    repository.update(instanceId) { current ->
                        current.beginTouchTurnSession(sessionDate)
                    }
                    loadFirstCandle(instanceId, sessionDate)
                }
        }
    }

    fun loadFirstCandle(instanceId: String, sessionDate: String) {
        loadJobsByInstanceId[instanceId]?.cancel()
        loadJobsByInstanceId[instanceId] = scope.launch {
            val instance = repository.deployments.value.find { it.id == instanceId } ?: return@launch
            if (instance.strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return@launch

            val symbol = instance.symbol
            val instrument = DeploymentMarket.effectiveInstrument(instance)
            ensureLiveMarketData?.invoke(symbol, instrument)
            val sessionId = instance.inProgressSession()?.id
            val adrResult = sessionGateway.fetchFourteenDayAdr(symbol, instrument)
            val adr14 = adrResult.getOrElse { error ->
                val message = error.message ?: "Failed to load 14-day ADR"
                SessionTrace.touchTurnData(
                    deploymentId = instanceId,
                    sessionId = sessionId,
                    symbol = symbol,
                    event = "adr_failed",
                    message = message
                )
                repository.update(instanceId) { current ->
                    current.withTouchTurnCandleFailed(
                        sessionDate,
                        message
                    )
                }
                return@launch
            }

            val candleResult = sessionGateway.fetchFirstFifteenMinuteCandle(symbol, instrument)
            val zoneId = DeploymentMarket.effectiveZoneId(instance)
            val currency = DeploymentMarket.effectiveCurrencyCode(instance)
            repository.update(instanceId) { current ->
                candleResult.fold(
                    onSuccess = { bar ->
                        SessionTrace.touchTurnData(
                            deploymentId = instanceId,
                            sessionId = current.inProgressSession()?.id,
                            symbol = symbol,
                            event = "data_ready",
                            adr14 = adr14,
                            barTime = bar.time
                        )
                        val updated = current.withFirstFifteenMinuteCandle(
                            sessionDate = sessionDate,
                            candle = bar,
                            adr14 = adr14,
                            currencyCode = currency,
                            marketZoneId = zoneId
                        )
                        updated.touchTurnSession?.let { session ->
                            TouchTurnCandleLog.candleLoaded(
                                instanceId = instanceId,
                                symbol = symbol,
                                sessionDate = sessionDate,
                                deploymentMarketZoneId = zoneId,
                                session = session
                            )
                        }
                        updated
                    },
                    onFailure = { error ->
                        val message = error.message ?: "Failed to load first 15-minute candle"
                        SessionTrace.touchTurnData(
                            deploymentId = instanceId,
                            sessionId = current.inProgressSession()?.id,
                            symbol = symbol,
                            event = "candle_failed",
                            message = message
                        )
                        current.withTouchTurnCandleFailed(
                            sessionDate,
                            message
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
                if (session.candleCloseStatus() != FirstCandleCloseStatus.CLOSED) {
                    val elapsedRth = session.millisSinceLastMarketOpen(session.marketZoneId)
                    if (elapsedRth > TouchTurnLogic.FIRST_CANDLE_BAR_DURATION_MS &&
                        stuckFormingLogged.add(instanceId)
                    ) {
                        TouchTurnCandleLog.stuckFormingAfterRthOpen(
                            instanceId = instanceId,
                            symbol = instance.symbol,
                            deploymentMarketZoneId = DeploymentMarket.effectiveZoneId(instance),
                            session = session
                        )
                    }
                    continue
                }

                TouchTurnCandleLog.candleClosed(
                    instanceId = instanceId,
                    symbol = instance.symbol,
                    session = session
                )
                val evaluatedAt = System.currentTimeMillis()
                var ordersPlaced = false
                val enforceCloseConfirmation = executionGateway.brokerId != BrokerId.EMULATOR ||
                    emulatorRequireCloseConfirmation()
                repository.update(instanceId) { current ->
                    val updated = current.withLiquidityEvaluatedIfClosed(
                        enforceCloseConfirmation = enforceCloseConfirmation,
                        nowEpochMillis = evaluatedAt
                    )
                    val session = updated.touchTurnSession ?: return@update updated
                    when (session.decisionOutcome) {
                        TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
                        TouchTurnSessionOutcome.NO_TRADE_DOJI,
                        TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
                        TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED -> return@update updated
                        else -> Unit
                    }
                    if (enforceCloseConfirmation &&
                        session.closeConfirmation(evaluatedAt) != TouchTurnCloseConfirmation.PASSED
                    ) {
                        return@update updated.withTouchTurnDecisionOutcome(
                            TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED
                        )
                    }
                    if (session.entryWindowStatus(evaluatedAt) == TouchTurnEntryWindowStatus.EXPIRED) {
                        return@update updated.withTouchTurnDecisionOutcome(
                            TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED
                        )
                    }
                    val setup = session.setup
                    val deploymentInstrument = DeploymentMarket.effectiveInstrument(updated)
                    val plan = setup?.let { s ->
                        TouchTurnOrderPlanner.buildOrderPlan(
                            symbol = updated.symbol,
                            setup = s,
                            maxDollars = updated.maxDollars,
                            currencyCode = session.currencyCode,
                            instrument = deploymentInstrument
                        )
                    }
                    ordersPlaced = TouchTurnOrderLog.logAfterLiquidityEvaluation(
                        instanceId = updated.id,
                        symbol = updated.symbol,
                        sessionDate = session.sessionDate,
                        maxDollars = updated.maxDollars,
                        currencyCode = session.currencyCode,
                        instrument = deploymentInstrument,
                        setup = setup,
                        openingBarClose = session.candle?.close,
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

expect fun emulatorRequireCloseConfirmationEnv(): String?

private fun emulatorRequireCloseConfirmation(): Boolean =
    when (emulatorRequireCloseConfirmationEnv()?.trim()?.lowercase()) {
        "true", "1", "on", "yes" -> true
        else -> false
    }
