package daytrader.engine

import daytrader.data.DeploymentSessionStopEvaluator
import daytrader.data.MarketOpenAutoStartLogic
import daytrader.data.SessionStopOrderCleanup
import daytrader.data.StrategyDeploymentRepository
import daytrader.data.TouchTurnManualStopHandler
import daytrader.data.TouchTurnOrderLog
import daytrader.data.emulatorRequireCloseConfirmation
import daytrader.broker.SessionTradeMatcher
import daytrader.broker.SymbolMarkets
import daytrader.domain.DeploymentMarket
import daytrader.domain.DeploymentSessionStopLogic
import daytrader.domain.DeploymentStatus
import daytrader.domain.ClosedFirstCandleRefetchValidation
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.InstrumentIdentity
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCloseConfirmation
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnVolumeCheck
import daytrader.domain.TouchTurnVolumeCheckPhase
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnCandleLog
import daytrader.domain.TouchTurnDecisionLog
import daytrader.domain.inProgressSession
import daytrader.domain.beginTouchTurnSession
import daytrader.domain.onSessionStarted
import daytrader.domain.withClosedFirstFifteenMinuteCandle
import daytrader.domain.withOpeningBarClosedMilestone
import daytrader.domain.withFirstFifteenMinuteCandle
import daytrader.domain.withLiquidityEvaluatedIfClosed
import daytrader.domain.withOrdersPlacedForSession
import daytrader.domain.withTouchTurnCandleFailed
import daytrader.domain.withTouchTurnClosingMilestoneIfNeeded
import daytrader.domain.withTouchTurnDecisionOutcome
import daytrader.domain.withTouchTurnPositionOpenedIfNeeded
import daytrader.data.StrategyCatalog
import daytrader.domain.withClosedPosition
import daytrader.data.withDemoLiveExecutionOnStart
import daytrader.domain.withStopPrice
import daytrader.domain.withoutSessionHistoryEntry
import daytrader.diagnostics.SessionTrace
import daytrader.diagnostics.SessionHistoricalLog
import daytrader.diagnostics.TimestampedConsoleLog
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.LiveQuote
import daytrader.gateway.TouchTurnBracketAck
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnOrderRole
import daytrader.execution.ExecutionManager
import daytrader.marketdata.MarketDataProvider
import daytrader.engine.touchturn.VolumeExhaustionBufferMonitor
import daytrader.engine.touchturn.VolumeExhaustionSignalEngine
import java.util.concurrent.ConcurrentHashMap
import daytrader.gateway.WorkingOrder
import daytrader.presentation.strategies.StartBlockedAlertMapper
import daytrader.presentation.strategies.StrategyDetailTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

class TouchTurnEngine(
    private val marketData: MarketDataProvider,
    private val execution: ExecutionManager,
    private val repository: StrategyDeploymentRepository,
    private val scope: CoroutineScope,
    private val brokerKind: BrokerKind = BrokerKind.EMULATOR,
    private val uiEffects: TouchTurnUiEffects = NoOpTouchTurnUiEffects,
    private val isGlobalAutoStartEnabled: () -> Boolean = { true },
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    /** @deprecated Use [marketData] / [execution]; kept for broker connection state subscription. */
    private val sessionGateway: BrokerGateway? = null,
    private val executionGateway: BrokerGateway? = null
) : TouchTurnEnginePort {
    private val commandQueue = Channel<TouchTurnCommand>(Channel.UNLIMITED)
    private val eventChannel = Channel<TouchTurnEvent>(Channel.UNLIMITED)
    override val events: Flow<TouchTurnEvent> = eventChannel.receiveAsFlow()

    private val stuckFormingLogged = mutableSetOf<String>()
    private val liquidityJobs = mutableMapOf<String, Job>()
    private val closedBarRefetchJobs = mutableMapOf<String, Job>()
    private val loadJobs = mutableMapOf<String, Job>()
    private val tracedFillExecIdsByInstance = mutableMapOf<String, MutableSet<String>>()
    private val pendingBracketPlacements = ConcurrentHashMap<String, PendingBracketPlacement>()
    private val bufferMonitor = VolumeExhaustionBufferMonitor(
        marketData = marketData,
        execution = execution,
        scope = scope,
        nowEpochMillis = nowEpochMillis,
        delayMillis = delayMillis
    )

    private data class PendingBracketPlacement(
        val plan: TouchTurnOrderPlan,
        val sessionId: String?,
        val evaluatedAt: Long,
        val enforceCloseConfirmation: Boolean
    )

    private val brokerPositions = MutableStateFlow<List<AccountPosition>>(emptyList())
    private val brokerOpenOrders = MutableStateFlow<List<daytrader.gateway.WorkingOrder>>(emptyList())
    private val brokerFills = MutableStateFlow<List<daytrader.gateway.BrokerFill>>(emptyList())
    private var globalAutoStartEnabled = true

    override fun dispatch(command: TouchTurnCommand) {
        commandQueue.trySend(command)
    }

    override fun updateGlobalAutoStartEnabled(enabled: Boolean) {
        globalAutoStartEnabled = enabled
    }

    override fun start() {
        scope.launch {
            for (command in commandQueue) {
                if (TouchTurnEngineConfig.shadowLogEnabled()) {
                    TimestampedConsoleLog.line("TouchTurnEngine", "command=$command")
                }
                runCatching { handle(command) }.onFailure { error ->
                    val instanceId = (command as? TouchTurnCommand.StartSession)?.instanceId
                        ?: (command as? TouchTurnCommand.StopSession)?.instanceId
                    SessionTrace.log(
                        type = "orchestrator_error",
                        deploymentId = instanceId,
                        details = mapOf(
                            "command" to command::class.simpleName.orEmpty(),
                            "message" to (error.message ?: "unknown")
                        )
                    )
                    emit(TouchTurnEvent.OrchestratorError(instanceId, error.message ?: "unknown"))
                }
            }
        }
        subscribeBrokerFlows()
        startTimers()
    }

    private fun subscribeBrokerFlows() {
        val gw = executionGateway ?: sessionGateway ?: return
        scope.launch {
            gw.positions.collect { positions ->
                brokerPositions.value = positions
                dispatch(
                    TouchTurnCommand.BrokerSnapshot(
                        positions = positions,
                        openOrders = brokerOpenOrders.value,
                        fills = brokerFills.value
                    )
                )
            }
        }
        scope.launch {
            gw.openOrders.collect { orders ->
                brokerOpenOrders.value = orders
                dispatch(
                    TouchTurnCommand.BrokerSnapshot(
                        positions = brokerPositions.value,
                        openOrders = orders,
                        fills = brokerFills.value
                    )
                )
            }
        }
        scope.launch {
            gw.fills.collect { fills ->
                brokerFills.value = fills
                dispatch(
                    TouchTurnCommand.BrokerSnapshot(
                        positions = brokerPositions.value,
                        openOrders = brokerOpenOrders.value,
                        fills = fills
                    )
                )
            }
        }
        sessionGateway?.let { gateway ->
            scope.launch {
                var previous: GatewayConnectionState? = null
                gateway.connectionState.collect { connection ->
                    if (connection == GatewayConnectionState.Connected &&
                        previous != GatewayConnectionState.Connected
                    ) {
                        dispatch(TouchTurnCommand.BrokerConnected)
                    }
                    previous = connection
                }
            }
        }
        scope.launch {
            gw.touchTurnBracketPlacements.collect { ack -> handleBracketAck(ack) }
        }
    }

    private fun handleBracketAck(ack: TouchTurnBracketAck) {
        val ackLatencyMs = pendingBracketPlacements.values.firstOrNull { pending ->
            SymbolMarkets.symbolsMatch(pending.plan.symbol, ack.symbol)
        }?.let { nowEpochMillis() - it.evaluatedAt }
        val match = pendingBracketPlacements.entries.firstOrNull { (_, pending) ->
            SymbolMarkets.symbolsMatch(pending.plan.symbol, ack.symbol)
        } ?: run {
            SessionTrace.bracketAckOrphan(
                symbol = ack.symbol,
                ack = ack,
                pendingBracketCount = pendingBracketPlacements.size
            )
            return
        }
        val (instanceId, pending) = match
        pendingBracketPlacements.remove(instanceId)
        val openOrders = brokerOpenOrders.value
        val openForSymbol = SymbolMarkets.openOrdersForSymbol(pending.plan.symbol, openOrders)
        val openSummary = openForSymbol.joinToString(";") { "${it.orderId}:${it.status}" }.ifEmpty { "none" }
        if (ack.result.isFailure) {
            SessionTrace.bracketAcknowledged(
                deploymentId = instanceId,
                sessionId = pending.sessionId,
                symbol = pending.plan.symbol,
                ack = ack,
                ackLatencyMs = ackLatencyMs ?: 0L,
                openOrdersForSymbol = openForSymbol.size,
                openOrdersTotal = openOrders.size,
                openOrderSummary = openSummary
            )
            repository.update(instanceId) { current ->
                current.withTouchTurnDecisionOutcome(TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED)
            }
            val instance = repository.deployments.value.find { it.id == instanceId } ?: return
            logLiquidityPollOutcome(
                instance = instance,
                sessionId = pending.sessionId,
                ordersPlaced = false,
                submittedPlan = null,
                enforceCloseConfirmation = pending.enforceCloseConfirmation,
                evaluatedAt = pending.evaluatedAt
            )
            notifyNoTradeDecisionIfNeeded(instanceId)
            return
        }
        val plan = ack.plan ?: pending.plan
        SessionTrace.bracketAcknowledged(
            deploymentId = instanceId,
            sessionId = pending.sessionId,
            symbol = plan.symbol,
            ack = ack,
            ackLatencyMs = ackLatencyMs ?: 0L,
            openOrdersForSymbol = openForSymbol.size,
            openOrdersTotal = openOrders.size,
            openOrderSummary = openSummary
        )
        repository.update(instanceId) { it.withOrdersPlacedForSession(plan) }
        val instance = repository.deployments.value.find { it.id == instanceId } ?: return
        val entryOrderId = ack.orderIds.firstOrNull()
            ?: openForSymbol.firstOrNull { it.orderId in ack.orderIds }?.orderId
            ?: openForSymbol.firstOrNull { order ->
                plan.orders.any { leg ->
                    leg.role == TouchTurnOrderRole.ENTRY && order.limitPrice == leg.price
                }
            }?.orderId
        val volumeThreshold = instance.touchTurnSession?.volumeSma20?.let {
            VolumeExhaustionSignalEngine.bufferVolumeThreshold(it)
        } ?: 0.0
        bufferMonitor.start(
            instanceId = instanceId,
            symbol = plan.symbol,
            entryOrderId = entryOrderId,
            volumeThreshold = volumeThreshold
        )
        logLiquidityPollOutcome(
            instance = instance,
            sessionId = pending.sessionId,
            ordersPlaced = true,
            submittedPlan = plan,
            enforceCloseConfirmation = pending.enforceCloseConfirmation,
            evaluatedAt = pending.evaluatedAt,
            brokerAckOrderIds = ack.orderIds
        )
    }

    private fun startTimers() {
        scope.launch {
            while (isActive) {
                delayMillis(TouchTurnEngineConfig.STOP_RULES_POLL_MS)
                dispatch(TouchTurnCommand.PollStopRules)
            }
        }
        scope.launch {
            while (isActive) {
                delayMillis(TouchTurnEngineConfig.AUTO_START_POLL_MS)
                dispatch(TouchTurnCommand.EvaluateAutoStart)
            }
        }
    }

    private suspend fun handle(command: TouchTurnCommand) {
        when (command) {
            is TouchTurnCommand.StartSession -> handleStartSession(command)
            is TouchTurnCommand.StopSession -> handleStopSession(command)
            is TouchTurnCommand.AdjustStop -> handleAdjustStop(command)
            is TouchTurnCommand.ClosePosition -> handleClosePosition(command)
            is TouchTurnCommand.DeleteSessionHistory -> handleDeleteSessionHistory(command)
            is TouchTurnCommand.BrokerSnapshot -> handleBrokerSnapshot(command)
            TouchTurnCommand.BrokerConnected -> handleBrokerConnected()
            is TouchTurnCommand.PollLiquidity -> handlePollLiquidity(command.instanceId)
            TouchTurnCommand.PollStopRules -> handlePollStopRules()
            TouchTurnCommand.EvaluateAutoStart -> handleEvaluateAutoStart()
            is TouchTurnCommand.RetryBootstrap -> handleLoadFirstCandle(command.instanceId, command.sessionDate)
            is TouchTurnCommand.LoadFirstCandle -> handleLoadFirstCandle(command.instanceId, command.sessionDate)
        }
    }

    private fun handleStartSession(command: TouchTurnCommand.StartSession) {
        val existing = repository.deployments.value.find { it.id == command.instanceId } ?: return
        if (existing.status == DeploymentStatus.RUNNING) return
        val blockingPosition = SymbolMarkets.findOpenPosition(existing, brokerPositions.value)
        if (blockingPosition != null) {
            val alert = StartBlockedAlertMapper.from(existing, blockingPosition)
            SessionTrace.log(
                type = "session_start_blocked",
                deploymentId = existing.id,
                symbol = existing.symbol,
                details = mapOf(
                    "reason" to "open_position",
                    "positionQty" to blockingPosition.quantity.toString(),
                    "positionSymbol" to blockingPosition.symbol
                )
            )
            emit(TouchTurnEvent.StartBlocked(alert))
            uiEffects.showStartBlockedAlert(alert)
            return
        }
        repository.update(command.instanceId) { current ->
            val started = current
                .onSessionStarted(command.sessionDate, touchTurnStartedBy = command.startedBy)
                .beginTouchTurnSession(command.sessionDate)
                .copy(lastAutoStartSessionDate = command.sessionDate)
            started.inProgressSession()?.let { SessionTrace.sessionStarted(started, it) }
            started
        }
        tracedFillExecIdsByInstance.remove(command.instanceId)
        repository.flushPersistence()
        val updated = repository.deployments.value.find { it.id == command.instanceId } ?: return
        updated.inProgressSession()?.let { session ->
            emit(
                TouchTurnEvent.SessionStarted(
                    instanceId = command.instanceId,
                    sessionId = session.id,
                    sessionDate = command.sessionDate,
                    startedBy = command.startedBy
                )
            )
            val tab = if (command.startedBy == TouchTurnSessionStartedBy.AUTO_MARKET_OPEN) {
                StrategyDetailTab.LIVE
            } else {
                StrategyDetailTab.LIVE
            }
            emit(TouchTurnEvent.UiNavigate(command.instanceId, tab))
            uiEffects.selectDeployment(command.instanceId, tab)
        }
        if (updated.strategyType == StrategyType.TOUCH_AND_TURN_SCALPER) {
            dispatch(TouchTurnCommand.LoadFirstCandle(command.instanceId, command.sessionDate))
        } else if (updated.strategyType == StrategyType.QUICK_FLIP_SCALPER) {
            repository.update(command.instanceId) { it.withDemoLiveExecutionOnStart(command.sessionDate) }
        }
    }

    private fun handleStopSession(command: TouchTurnCommand.StopSession) {
        val instance = repository.deployments.value.find { it.id == command.instanceId } ?: return
        if (instance.status != DeploymentStatus.RUNNING) return
        TouchTurnDecisionLog.sessionStopping(
            instanceId = command.instanceId,
            symbol = instance.symbol,
            trigger = command.trigger.name,
            session = instance.touchTurnSession
        )
        liquidityJobs.remove(command.instanceId)?.cancel()
        closedBarRefetchJobs.remove(command.instanceId)?.cancel()
        loadJobs.remove(command.instanceId)?.cancel()
        bufferMonitor.stop(command.instanceId)
        val gateway = executionGateway ?: sessionGateway
        val fillsForStop = command.brokerFillsAtDecision ?: brokerFills.value
        val result = TouchTurnManualStopHandler.stop(
            input = TouchTurnManualStopHandler.Input(
                instance = instance,
                brokerPositions = brokerPositions.value,
                brokerOpenOrders = brokerOpenOrders.value,
                brokerFills = fillsForStop,
                brokerKind = brokerKind
            ),
            gateway = gateway,
            explicitTrigger = command.trigger
        )
        val stopped = result.stoppedDeployment
        repository.update(command.instanceId) { stopped }
        repository.flushPersistence()
        maybeReleaseLiveMarketData(stopped)
        val sessionId = instance.inProgressSession()?.id
        emit(TouchTurnEvent.SessionStopped(command.instanceId, sessionId, command.trigger))
        emit(TouchTurnEvent.UiNavigate(command.instanceId, StrategyDetailTab.SESSION_HISTORY))
        uiEffects.selectDeployment(command.instanceId, StrategyDetailTab.SESSION_HISTORY)
        tracedFillExecIdsByInstance.remove(command.instanceId)
    }

    private fun handleAdjustStop(command: TouchTurnCommand.AdjustStop) {
        repository.update(command.instanceId) { instance ->
            val updated = instance.live.withStopPrice(
                newStop = command.stopPrice,
                rewardMultiple = StrategyCatalog.rewardMultiple(instance.strategyType)
            ) ?: return@update instance
            instance.copy(live = updated)
        }
    }

    private fun handleClosePosition(command: TouchTurnCommand.ClosePosition) {
        repository.update(command.instanceId) { it.withClosedPosition(command.sessionDate) }
    }

    private fun handleDeleteSessionHistory(command: TouchTurnCommand.DeleteSessionHistory) {
        repository.update(command.instanceId) { it.withoutSessionHistoryEntry(command.runId) }
        repository.flushPersistence()
    }

    private fun handleBrokerSnapshot(command: TouchTurnCommand.BrokerSnapshot) {
        brokerPositions.value = command.positions
        brokerOpenOrders.value = command.openOrders
        brokerFills.value = command.fills
        traceNewSessionFills(command.fills, command.positions)
        recordTouchTurnPositionMilestones(command.positions)
        handlePollStopRules(snapshot = command)
    }

    private fun recordTouchTurnPositionMilestones(positions: List<daytrader.gateway.AccountPosition>) {
        for (instance in repository.deployments.value) {
            if (instance.status != DeploymentStatus.RUNNING) continue
            if (instance.strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) continue
            if (!SymbolMarkets.hasOpenPosition(instance.symbol, positions)) continue
            if (instance.touchTurnSession?.milestones?.positionOpenedAt != null) continue
            repository.update(instance.id) { it.withTouchTurnPositionOpenedIfNeeded() }
            val updated = repository.deployments.value.find { it.id == instance.id } ?: continue
            val session = updated.inProgressSession() ?: continue
            val milestoneAt = updated.touchTurnSession?.milestones?.positionOpenedAt ?: continue
            SessionTrace.milestone(
                deploymentId = updated.id,
                sessionId = session.id,
                symbol = updated.symbol,
                name = "position_opened",
                at = milestoneAt
            )
            emit(TouchTurnEvent.PositionOpened(updated.id, milestoneAt))
            quoteForSymbol(updated.symbol)?.let { quote ->
                SessionTrace.quoteAtMilestone(
                    deploymentId = updated.id,
                    sessionId = session.id,
                    symbol = updated.symbol,
                    milestone = "position_opened",
                    quote = quote
                )
            }
        }
    }

    private fun handlePollStopRules(snapshot: TouchTurnCommand.BrokerSnapshot? = null) {
        val positions = snapshot?.positions ?: brokerPositions.value
        val openOrders = snapshot?.openOrders ?: brokerOpenOrders.value
        val fills = snapshot?.fills ?: brokerFills.value
        logAutoStopChecks(
            deployments = repository.deployments.value,
            positions = positions,
            openOrders = openOrders,
            fills = fills
        )
        val candidates = DeploymentSessionStopEvaluator.evaluate(
            deployments = repository.deployments.value,
            positions = positions,
            openOrders = openOrders,
            fills = fills
        )
        for (candidate in candidates) {
            val instance = repository.deployments.value.find { it.id == candidate.instanceId } ?: continue
            if (instance.status != DeploymentStatus.RUNNING) continue
            repository.update(candidate.instanceId) { it.withTouchTurnClosingMilestoneIfNeeded() }
            dispatch(
                TouchTurnCommand.StopSession(
                    instanceId = candidate.instanceId,
                    trigger = candidate.trigger,
                    brokerFillsAtDecision = fills
                )
            )
        }
    }

    private fun handleBrokerConnected() {
        repository.deployments.value
            .asSequence()
            .filter { it.status == DeploymentStatus.RUNNING }
            .filter { it.strategyType == StrategyType.TOUCH_AND_TURN_SCALPER }
            .forEach { instance ->
                val session = instance.touchTurnSession ?: return@forEach
                val sessionDate = instance.inProgressSession()?.date ?: session.sessionDate
                when (session.status) {
                    TouchTurnCandleStatus.LOADING,
                    TouchTurnCandleStatus.FAILED -> {
                        SessionTrace.touchTurnData(
                            deploymentId = instance.id,
                            sessionId = instance.inProgressSession()?.id,
                            symbol = instance.symbol,
                            event = "bootstrap_retry",
                            message = "status=${session.status}"
                        )
                        repository.update(instance.id) { it.beginTouchTurnSession(sessionDate) }
                        dispatch(TouchTurnCommand.LoadFirstCandle(instance.id, sessionDate))
                    }
                    else -> Unit
                }
            }
    }

    private fun handleLoadFirstCandle(instanceId: String, sessionDate: String) {
        loadJobs[instanceId]?.cancel()
        loadJobs[instanceId] = scope.launch {
            val instance = repository.deployments.value.find { it.id == instanceId } ?: return@launch
            if (instance.strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return@launch
            val symbol = instance.symbol
            val instrument = DeploymentMarket.effectiveInstrument(instance)
            marketData.ensureStreaming(symbol, instrument)
            val sessionId = instance.inProgressSession()?.id
            val signalResult = marketData.fetchTouchTurnSignalContext(symbol, instrument, isClosedBarRefetch = false)
            val zoneId = DeploymentMarket.effectiveZoneId(instance)
            val currency = DeploymentMarket.effectiveCurrencyCode(instance)
            repository.update(instanceId) { current ->
                signalResult.fold(
                    onSuccess = { context ->
                        VolumeExhaustionSignalEngine.logSignalContext(instanceId, symbol, context)
                        current.withFirstFifteenMinuteCandle(
                            sessionDate = sessionDate,
                            candle = context.firstCandle,
                            atr14 = context.atr14,
                            volumeSma20 = context.volumeSma20,
                            adr14 = context.atr14,
                            currencyCode = currency,
                            marketZoneId = zoneId
                        )
                    },
                    onFailure = { error ->
                        current.withTouchTurnCandleFailed(
                            sessionDate,
                            error.message ?: "Failed to load Touch Turn signal context"
                        )
                    }
                )
            }
            if (signalResult.isFailure) {
                val message = signalResult.exceptionOrNull()?.message
                    ?: "Failed to load Touch Turn signal context"
                SessionTrace.touchTurnData(
                    deploymentId = instanceId,
                    sessionId = sessionId,
                    symbol = symbol,
                    event = "signal_context_failed",
                    message = message
                )
                notifyNoTradeDecisionIfNeeded(instanceId)
                return@launch
            }
            val loaded = repository.deployments.value.find { it.id == instanceId }
            val loadedSession = loaded?.touchTurnSession
            if (loaded != null && loadedSession != null) {
                SessionTrace.touchTurnData(
                    deploymentId = instanceId,
                    sessionId = sessionId,
                    symbol = symbol,
                    event = "data_ready",
                    adr14 = loadedSession.atr14,
                    atr14 = loadedSession.atr14,
                    volumeSma20 = loadedSession.volumeSma20,
                    barTime = loadedSession.openingBarTime ?: loadedSession.candle?.time
                )
                signalResult.getOrNull()?.let { context ->
                    SessionHistoricalLog.recordSignalContext(
                        deploymentId = instanceId,
                        sessionId = sessionId,
                        symbol = symbol,
                        context = context,
                        isClosedBarRefetch = false
                    )
                    TouchTurnVolumeCheck.build(
                        phase = TouchTurnVolumeCheckPhase.SIGNAL_CONTEXT,
                        candleVolume = context.firstCandle.volume,
                        volumeSma20 = context.volumeSma20,
                        barTime = context.firstCandle.time
                    )?.let { check ->
                        SessionTrace.touchTurnVolumeCheck(
                            deploymentId = instanceId,
                            sessionId = sessionId,
                            symbol = symbol,
                            check = check,
                            atr14 = context.atr14
                        )
                    }
                }
                TouchTurnCandleLog.candleLoaded(
                    instanceId = instanceId,
                    symbol = symbol,
                    sessionDate = sessionDate,
                    deploymentMarketZoneId = zoneId,
                    session = loadedSession
                )
            }
            watchLiquidity(instanceId, sessionDate)
        }
    }

    private fun liquidityPollIntervalMs(): Long =
        if (brokerKind == BrokerKind.EMULATOR || brokerKind == BrokerKind.REPLAY) {
            TouchTurnEngineConfig.LIQUIDITY_POLL_EMULATOR_MS
        } else {
            TouchTurnEngineConfig.LIQUIDITY_POLL_MS
        }

    private fun watchLiquidity(instanceId: String, sessionDate: String) {
        liquidityJobs[instanceId]?.cancel()
        liquidityJobs[instanceId] = scope.launch {
            dispatch(TouchTurnCommand.PollLiquidity(instanceId))
            while (isActive) {
                delayMillis(liquidityPollIntervalMs())
                dispatch(TouchTurnCommand.PollLiquidity(instanceId))
                val instance = repository.deployments.value.find { it.id == instanceId } ?: return@launch
                if (instance.touchTurnSession?.setup != null) return@launch
                if (instance.touchTurnSession?.decisionOutcome != null &&
                    DeploymentSessionStopLogic.shouldStopAfterNoTradeDecision(instance)
                ) {
                    return@launch
                }
            }
        }
    }

    private fun handlePollLiquidity(instanceId: String) {
        val instance = repository.deployments.value.find { it.id == instanceId } ?: return
        if (instance.status != DeploymentStatus.RUNNING) return
        val session = instance.touchTurnSession ?: return
        if (session.setup != null) return
        if (session.candleCloseStatus(nowEpochMillis()) != FirstCandleCloseStatus.CLOSED) {
            TouchTurnDecisionLog.watchPollTick(
                instanceId = instanceId,
                symbol = instance.symbol,
                closeStatus = session.candleCloseStatus(nowEpochMillis()),
                hasSetup = session.setup != null,
                nowEpochMillis = nowEpochMillis()
            )
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
            return
        }
        TouchTurnCandleLog.candleClosed(instanceId, instance.symbol, session)
        val barClosedJustSet = session.milestones.barClosedAt == null
        repository.update(instanceId) { current ->
            current.withOpeningBarClosedMilestone()
        }
        val afterBarClosed = repository.deployments.value.find { it.id == instanceId } ?: return
        val sessionAfterBarClosed = afterBarClosed.touchTurnSession ?: return
        if (sessionAfterBarClosed.candle == null) {
            scheduleClosedBarRefetch(instanceId)
            return
        }
        if (barClosedJustSet && sessionAfterBarClosed.milestones.liquidityEvaluatedAt == null) {
            scope.launch {
                yield()
                evaluateLiquidityAfterClosedBar(instanceId)
            }
            return
        }
        evaluateLiquidityAfterClosedBar(instanceId)
    }

    private fun scheduleClosedBarRefetch(instanceId: String) {
        if (closedBarRefetchJobs[instanceId]?.isActive == true) return
        closedBarRefetchJobs[instanceId] = scope.launch {
            val instance = repository.deployments.value.find { it.id == instanceId } ?: return@launch
            if (instance.status != DeploymentStatus.RUNNING) return@launch
            val session = instance.touchTurnSession ?: return@launch
            if (session.candle != null) {
                closedBarRefetchJobs.remove(instanceId)
                dispatch(TouchTurnCommand.PollLiquidity(instanceId))
                return@launch
            }
            val symbol = instance.symbol
            val sessionId = instance.inProgressSession()?.id
            val instrument = DeploymentMarket.effectiveInstrument(instance)
            val zoneId = session.marketZoneId
            val openingBarTime = session.resolvedOpeningBarTime()
            val sessionDate = session.sessionDate
            SessionTrace.closedBarRefetch(
                deploymentId = instanceId,
                sessionId = sessionId,
                symbol = symbol,
                event = "started",
                openingBarTime = openingBarTime,
                maxAttempts = TouchTurnEngineConfig.CLOSED_BAR_REFETCH_MAX_ATTEMPTS
            )
            marketData.ensureStreaming(symbol, instrument)
            awaitClosedBarRefetchSettle(
                instanceId = instanceId,
                sessionId = sessionId,
                symbol = symbol,
                openingBarTime = openingBarTime,
                marketZoneId = zoneId
            )
            var refetchFailed = false
            var attempt = 0
            while (isActive && attempt < TouchTurnEngineConfig.CLOSED_BAR_REFETCH_MAX_ATTEMPTS) {
                attempt++
                val refetchResult = marketData.fetchTouchTurnSignalContext(
                    symbol,
                    instrument,
                    isClosedBarRefetch = true
                )
                if (refetchResult.isFailure) {
                    refetchFailed = true
                    val message = refetchResult.exceptionOrNull()?.message
                        ?: "Failed to load closed 15-minute bar"
                    SessionTrace.closedBarRefetch(
                        deploymentId = instanceId,
                        sessionId = sessionId,
                        symbol = symbol,
                        event = "failed",
                        openingBarTime = openingBarTime,
                        attempt = attempt,
                        maxAttempts = TouchTurnEngineConfig.CLOSED_BAR_REFETCH_MAX_ATTEMPTS,
                        reason = message
                    )
                    repository.update(instanceId) { current ->
                        current.withTouchTurnCandleFailed(sessionDate, message)
                    }
                    break
                }
                val context = refetchResult.getOrThrow()
                val now = nowEpochMillis()
                val (validation, reason) = TouchTurnLogic.validateClosedFirstCandleRefetch(
                    candle = context.firstCandle,
                    openingBarTime = openingBarTime,
                    marketZoneId = zoneId,
                    sessionDateIso = sessionDate,
                    nowEpochMillis = now
                )
                SessionHistoricalLog.recordSignalContext(
                    deploymentId = instanceId,
                    sessionId = sessionId,
                    symbol = symbol,
                    context = context,
                    isClosedBarRefetch = true,
                    attempt = attempt,
                    validation = validation.name
                )
                when (validation) {
                    ClosedFirstCandleRefetchValidation.READY -> {
                        val volumeSma = session.volumeSma20
                        SessionTrace.closedBarRefetch(
                            deploymentId = instanceId,
                            sessionId = sessionId,
                            symbol = symbol,
                            event = "loaded",
                            openingBarTime = openingBarTime,
                            attempt = attempt,
                            refetchedBarTime = context.firstCandle.time,
                            validation = validation.name,
                            openingBarVolume = context.firstCandle.volume,
                            volumeSma20 = volumeSma
                        )
                        TouchTurnVolumeCheck.build(
                            phase = TouchTurnVolumeCheckPhase.CLOSED_BAR_LOADED,
                            candleVolume = context.firstCandle.volume,
                            volumeSma20 = volumeSma,
                            barTime = context.firstCandle.time
                        )?.let { check ->
                            SessionTrace.touchTurnVolumeCheck(
                                deploymentId = instanceId,
                                sessionId = sessionId,
                                symbol = symbol,
                                check = check,
                                atr14 = session.atr14
                            )
                        }
                        repository.update(instanceId) { current ->
                            TouchTurnCandleLog.closedBarLoaded(
                                instanceId = instanceId,
                                symbol = symbol,
                                barTime = context.firstCandle.time,
                                candle = context.firstCandle
                            )
                            current.withClosedFirstFifteenMinuteCandle(context.firstCandle)
                        }
                        break
                    }
                    ClosedFirstCandleRefetchValidation.NOT_YET_FINAL -> {
                        val retryReason = reason ?: "not yet final"
                        TouchTurnCandleLog.closedBarRefetchRetry(
                            instanceId = instanceId,
                            symbol = symbol,
                            attempt = attempt,
                            reason = retryReason
                        )
                        SessionTrace.closedBarRefetch(
                            deploymentId = instanceId,
                            sessionId = sessionId,
                            symbol = symbol,
                            event = "retry",
                            openingBarTime = openingBarTime,
                            attempt = attempt,
                            maxAttempts = TouchTurnEngineConfig.CLOSED_BAR_REFETCH_MAX_ATTEMPTS,
                            refetchedBarTime = context.firstCandle.time,
                            validation = validation.name,
                            reason = retryReason
                        )
                        if (attempt >= TouchTurnEngineConfig.CLOSED_BAR_REFETCH_MAX_ATTEMPTS) {
                            refetchFailed = true
                            val failMessage =
                                "Closed 15-minute bar not final after $attempt refetches: $retryReason"
                            SessionTrace.closedBarRefetch(
                                deploymentId = instanceId,
                                sessionId = sessionId,
                                symbol = symbol,
                                event = "failed",
                                openingBarTime = openingBarTime,
                                attempt = attempt,
                                maxAttempts = TouchTurnEngineConfig.CLOSED_BAR_REFETCH_MAX_ATTEMPTS,
                                validation = validation.name,
                                reason = failMessage
                            )
                            repository.update(instanceId) { current ->
                                current.withTouchTurnCandleFailed(sessionDate, failMessage)
                            }
                        } else {
                            delayMillis(TouchTurnEngineConfig.CLOSED_BAR_REFETCH_RETRY_DELAY_MS)
                        }
                    }
                    ClosedFirstCandleRefetchValidation.REJECTED -> {
                        refetchFailed = true
                        val rejectMessage = reason ?: "Closed 15-minute bar rejected"
                        SessionTrace.closedBarRefetch(
                            deploymentId = instanceId,
                            sessionId = sessionId,
                            symbol = symbol,
                            event = "failed",
                            openingBarTime = openingBarTime,
                            attempt = attempt,
                            validation = validation.name,
                            reason = rejectMessage
                        )
                        repository.update(instanceId) { current ->
                            current.withTouchTurnCandleFailed(sessionDate, rejectMessage)
                        }
                        break
                    }
                }
            }
            closedBarRefetchJobs.remove(instanceId)
            if (refetchFailed) {
                notifyNoTradeDecisionIfNeeded(instanceId)
                liquidityJobs.remove(instanceId)?.cancel()
                return@launch
            }
            val after = repository.deployments.value.find { it.id == instanceId }
            if (after?.touchTurnSession?.candle == null) {
                notifyNoTradeDecisionIfNeeded(instanceId)
                liquidityJobs.remove(instanceId)?.cancel()
                return@launch
            }
            dispatch(TouchTurnCommand.PollLiquidity(instanceId))
        }
    }

    private suspend fun awaitClosedBarRefetchSettle(
        instanceId: String,
        sessionId: String?,
        symbol: String,
        openingBarTime: String?,
        marketZoneId: String
    ) {
        val waitMs = TouchTurnLogic.millisUntilClosedBarRefetchReady(
            openingBarTime = openingBarTime,
            marketZoneId = marketZoneId,
            nowEpochMillis = nowEpochMillis()
        )
        if (waitMs <= 0L) return
        TouchTurnCandleLog.closedBarRefetchWaiting(
            instanceId = instanceId,
            symbol = symbol,
            openingBarTime = openingBarTime,
            waitMs = waitMs
        )
        SessionTrace.closedBarRefetch(
            deploymentId = instanceId,
            sessionId = sessionId,
            symbol = symbol,
            event = "settle_wait",
            openingBarTime = openingBarTime,
            waitMs = waitMs
        )
        delayMillis(waitMs)
    }

    private fun evaluateLiquidityAfterClosedBar(instanceId: String) {
        val instance = repository.deployments.value.find { it.id == instanceId } ?: return
        if (instance.status != DeploymentStatus.RUNNING) return
        val session = instance.touchTurnSession ?: return
        if (session.setup != null) return
        val candle = session.candle ?: run {
            scheduleClosedBarRefetch(instanceId)
            return
        }
        val evaluatedAt = nowEpochMillis()
        val enforceCloseConfirmation = brokerKind.usesLiveIbMarketData ||
            emulatorRequireCloseConfirmation()
        val requireLivePriceChecks = brokerKind.usesLiveIbMarketData
        val liveQuote = if (requireLivePriceChecks) quoteForSymbol(instance.symbol) else null
        val executionGw = executionGateway ?: sessionGateway
        TouchTurnDecisionLog.bootstrapCandleClosed(
            instanceId = instanceId,
            symbol = instance.symbol,
            session = session,
            enforceCloseConfirmation = enforceCloseConfirmation,
            nowEpochMillis = evaluatedAt
        )
        repository.update(instanceId) { current ->
            current.withLiquidityEvaluatedIfClosed(
                enforceCloseConfirmation = enforceCloseConfirmation,
                nowEpochMillis = evaluatedAt,
                liveBid = liveQuote?.bid,
                liveAsk = liveQuote?.ask,
                liveLast = liveQuote?.last,
                requireLivePriceChecks = requireLivePriceChecks
            )
        }
        val afterEval = repository.deployments.value.find { it.id == instanceId } ?: return
        val afterSession = afterEval.touchTurnSession ?: return
        afterSession.candle?.let { candle ->
            TouchTurnVolumeCheck.build(
                phase = TouchTurnVolumeCheckPhase.LIQUIDITY_EVALUATED,
                candleVolume = candle.volume,
                volumeSma20 = afterSession.volumeSma20,
                barTime = candle.time
            )?.let { check ->
                SessionTrace.touchTurnVolumeCheck(
                    deploymentId = instanceId,
                    sessionId = afterEval.inProgressSession()?.id,
                    symbol = afterEval.symbol,
                    check = check,
                    atr14 = afterSession.atr14,
                    decisionOutcome = afterSession.decisionOutcome?.name
                )
            }
        }
        if (afterSession.decisionOutcome in liquidityEvalNoBracketOutcomes) {
            finishLiquidityPoll(instanceId, afterEval, evaluatedAt, enforceCloseConfirmation)
            return
        }
        val setup = afterSession.setup
        if (setup == null || !setup.isLiquidityCandle || !setup.isActionable ||
            afterSession.entryOrdersPermitted != true
        ) {
            finishLiquidityPoll(instanceId, afterEval, evaluatedAt, enforceCloseConfirmation)
            return
        }
        scope.launch {
            yield()
            val bracketSubmitRequested = requestBracketAfterLiquidityEvaluation(
                instanceId = instanceId,
                evaluatedAt = evaluatedAt,
                enforceCloseConfirmation = enforceCloseConfirmation,
                executionGw = executionGw
            )
            val after = repository.deployments.value.find { it.id == instanceId } ?: return@launch
            if (!bracketSubmitRequested) {
                logLiquidityPollOutcome(
                    instance = after,
                    sessionId = after.inProgressSession()?.id,
                    ordersPlaced = false,
                    submittedPlan = null,
                    enforceCloseConfirmation = enforceCloseConfirmation,
                    evaluatedAt = evaluatedAt
                )
            }
            finishLiquidityPoll(instanceId, after, evaluatedAt, enforceCloseConfirmation)
        }
    }

    private val liquidityEvalNoBracketOutcomes = setOf(
        TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
        TouchTurnSessionOutcome.NO_TRADE_DOJI,
        TouchTurnSessionOutcome.NO_TRADE_VOLUME_EXHAUSTION,
        TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
        TouchTurnSessionOutcome.NO_TRADE_LIVE_CLOSE_CONFIRMATION_FAILED,
        TouchTurnSessionOutcome.NO_TRADE_BAR_LIVE_DIVERGENCE,
        TouchTurnSessionOutcome.NO_TRADE_ENTRY_NOT_TOUCHABLE,
        TouchTurnSessionOutcome.NO_TRADE_LIVE_QUOTE_UNAVAILABLE,
        TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED
    )

    private fun finishLiquidityPoll(
        instanceId: String,
        instance: StrategyDeployment,
        evaluatedAt: Long,
        enforceCloseConfirmation: Boolean
    ) {
        notifyNoTradeDecisionIfNeeded(instanceId)
        TouchTurnDecisionLog.watchPollExit(instanceId, instance.symbol, "liquidity_poll_complete")
        liquidityJobs.remove(instanceId)?.cancel()
        closedBarRefetchJobs.remove(instanceId)?.cancel()
    }

    private fun requestBracketAfterLiquidityEvaluation(
        instanceId: String,
        evaluatedAt: Long,
        enforceCloseConfirmation: Boolean,
        executionGw: BrokerGateway?
    ): Boolean {
        val instance = repository.deployments.value.find { it.id == instanceId } ?: return false
        val session = instance.touchTurnSession ?: return false
        val setup = session.setup ?: return false
        if (!setup.isLiquidityCandle || !setup.isActionable || session.entryOrdersPermitted != true) {
            return false
        }
        val deploymentInstrument = DeploymentMarket.effectiveInstrument(instance)
        val plan = TouchTurnOrderPlanner.buildOrderPlan(
            symbol = instance.symbol,
            setup = setup,
            maxDollars = instance.maxDollars,
            currencyCode = session.currencyCode,
            instrument = deploymentInstrument
        ) ?: return false
        if (executionGw == null) {
            repository.update(instanceId) { current ->
                if (setup.isActionable) {
                    current.withTouchTurnDecisionOutcome(TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED)
                } else {
                    current
                }
            }
            return false
        }
        pendingBracketPlacements[instance.id] = PendingBracketPlacement(
            plan = plan,
            sessionId = instance.inProgressSession()?.id,
            evaluatedAt = evaluatedAt,
            enforceCloseConfirmation = enforceCloseConfirmation
        )
        val bracketSubmitRequested = TouchTurnOrderLog.logAfterLiquidityEvaluation(
            instanceId = instance.id,
            symbol = instance.symbol,
            sessionDate = session.sessionDate,
            maxDollars = instance.maxDollars,
            currencyCode = session.currencyCode,
            instrument = deploymentInstrument,
            setup = setup,
            openingBarClose = session.candle?.close,
            brokerGateway = executionGw
        )
        if (bracketSubmitRequested) {
            SessionTrace.bracketSubmitRequested(
                deploymentId = instance.id,
                sessionId = instance.inProgressSession()?.id,
                symbol = instance.symbol,
                orderCount = plan.orders.size,
                entryPrice = setup.entry,
                currencyCode = session.currencyCode,
                pendingBracketCount = pendingBracketPlacements.size
            )
        } else {
            pendingBracketPlacements.remove(instance.id)
            repository.update(instanceId) { current ->
                current.withTouchTurnDecisionOutcome(TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED)
            }
        }
        return bracketSubmitRequested
    }

    private fun logLiquidityPollOutcome(
        instance: StrategyDeployment,
        sessionId: String?,
        ordersPlaced: Boolean,
        submittedPlan: daytrader.domain.TouchTurnOrderPlan?,
        enforceCloseConfirmation: Boolean,
        evaluatedAt: Long,
        brokerAckOrderIds: List<Int> = emptyList()
    ) {
        val session = instance.touchTurnSession ?: return
        val prePlacement = VolumeExhaustionSignalEngine.evaluateAtBarClose(session)
        prePlacement?.let { VolumeExhaustionSignalEngine.logPrePlacement(instance.id, instance.symbol, it) }
        when (session.decisionOutcome) {
            TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
            TouchTurnSessionOutcome.NO_TRADE_DOJI,
            TouchTurnSessionOutcome.NO_TRADE_VOLUME_EXHAUSTION,
            TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
            TouchTurnSessionOutcome.NO_TRADE_LIVE_CLOSE_CONFIRMATION_FAILED,
            TouchTurnSessionOutcome.NO_TRADE_BAR_LIVE_DIVERGENCE,
            TouchTurnSessionOutcome.NO_TRADE_ENTRY_NOT_TOUCHABLE,
            TouchTurnSessionOutcome.NO_TRADE_LIVE_QUOTE_UNAVAILABLE,
            TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED,
            TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED -> {
                TouchTurnDecisionLog.bootstrapBranch(
                    instanceId = instance.id,
                    symbol = instance.symbol,
                    branch = "no_trade_${session.decisionOutcome?.name?.lowercase()}",
                    session = session,
                    ordersPlaced = false,
                    nowEpochMillis = evaluatedAt
                )
            }
            else -> Unit
        }
        if (ordersPlaced && submittedPlan != null) {
            emit(TouchTurnEvent.BracketSubmitted(instance.id, submittedPlan))
            TouchTurnDecisionLog.bootstrapBranch(
                instanceId = instance.id,
                symbol = instance.symbol,
                branch = "orders_placed",
                session = session,
                ordersPlaced = true,
                nowEpochMillis = evaluatedAt
            )
            SessionTrace.log(
                type = "bracket_submitted",
                deploymentId = instance.id,
                sessionId = sessionId,
                symbol = instance.symbol,
                details = buildMap {
                    put("orderCount", submittedPlan.orders.size.toString())
                    put("entrySide", session.setup?.side?.name ?: "unknown")
                    if (brokerAckOrderIds.isNotEmpty()) {
                        put("brokerAckOrderIds", brokerAckOrderIds.joinToString(","))
                    }
                    put(
                        "brokerHasOpenOrders",
                        SymbolMarkets.hasOpenOrders(instance, brokerOpenOrders.value).toString()
                    )
                    put("submitToAckMs", (nowEpochMillis() - evaluatedAt).toString())
                }
            )
            quoteForSymbol(instance.symbol)?.let { quote ->
                SessionTrace.quoteAtMilestone(
                    deploymentId = instance.id,
                    sessionId = sessionId,
                    symbol = instance.symbol,
                    milestone = "bracket_submitted",
                    quote = quote
                )
            }
            return
        }
        val setup = session.setup
        when {
            setup == null ->
                TouchTurnDecisionLog.ordersSkipped(instance.id, instance.symbol, "setup_null", session, evaluatedAt)
            !setup.isLiquidityCandle ->
                TouchTurnDecisionLog.ordersSkipped(instance.id, instance.symbol, "not_liquidity_candle", session, evaluatedAt)
            !setup.isActionable ->
                TouchTurnDecisionLog.ordersSkipped(instance.id, instance.symbol, "not_actionable", session, evaluatedAt)
            session.entryOrdersPermitted != true ->
                TouchTurnDecisionLog.ordersSkipped(
                    instance.id,
                    instance.symbol,
                    if (enforceCloseConfirmation) "close_confirmation_pending" else "entry_not_permitted",
                    session,
                    evaluatedAt
                )
            session.decisionOutcome == null && setup.isActionable ->
                TouchTurnDecisionLog.ordersSkipped(instance.id, instance.symbol, "plan_not_submitted", session, evaluatedAt)
        }
    }

    private fun notifyNoTradeDecisionIfNeeded(instanceId: String) {
        val instance = repository.deployments.value.find { it.id == instanceId } ?: return
        if (!DeploymentSessionStopLogic.shouldStopAfterNoTradeDecision(instance)) return
        instance.touchTurnSession?.decisionOutcome?.let { outcome ->
            TouchTurnDecisionLog.noTradeStopCheck(
                instanceId = instanceId,
                symbol = instance.symbol,
                wouldStop = true,
                decisionOutcome = outcome,
                source = "liquidity_or_bootstrap"
            )
            emit(TouchTurnEvent.NoTradeDecision(instanceId, outcome))
        }
        dispatch(TouchTurnCommand.StopSession(instanceId, TouchTurnSessionStopTrigger.NO_TRADE_DECISION))
    }

    private fun handleEvaluateAutoStart() {
        if (!isGlobalAutoStartEnabled() || !globalAutoStartEnabled) return
        val now = nowEpochMillis()
        val zones = repository.deployments.value
            .map { DeploymentMarket.effectiveZoneId(it) }
            .toSet()
        for (zone in zones) {
            val sessionDate = MarketOpenAutoStartLogic.sessionDateIfMarketOpen(zone, now) ?: continue
            val candidates = repository.deployments.value.filter { instance ->
                DeploymentMarket.effectiveZoneId(instance) == zone &&
                    instance.autoStartOnMarketOpen &&
                    instance.status == DeploymentStatus.STOPPED &&
                    instance.lastAutoStartSessionDate != sessionDate &&
                    !SymbolMarkets.hasOpenPosition(instance.symbol, brokerPositions.value)
            }
            for (instance in candidates) {
                SessionTrace.log(
                    type = "auto_start_dispatched",
                    deploymentId = instance.id,
                    symbol = instance.symbol,
                    details = mapOf(
                        "sessionDate" to sessionDate,
                        "zoneId" to zone
                    )
                )
                dispatch(
                    TouchTurnCommand.StartSession(
                        instanceId = instance.id,
                        sessionDate = sessionDate,
                        startedBy = TouchTurnSessionStartedBy.AUTO_MARKET_OPEN,
                        markAutoStarted = true
                    )
                )
            }
        }
    }

    private fun maybeReleaseLiveMarketData(deployment: StrategyDeployment) {
        val symbol = deployment.symbol
        val instrument = DeploymentMarket.effectiveInstrument(deployment)
        val stillNeeded = repository.deployments.value.any {
            it.status == DeploymentStatus.RUNNING &&
                SymbolMarkets.symbolsMatch(it.symbol, symbol)
        }
        if (!stillNeeded) marketData.releaseStreaming(symbol, instrument)
    }

    private fun traceNewSessionFills(fills: List<BrokerFill>, positions: List<AccountPosition>) {
        for (instance in repository.deployments.value) {
            if (instance.status != DeploymentStatus.RUNNING) continue
            val session = instance.inProgressSession() ?: continue
            val traced = tracedFillExecIdsByInstance.getOrPut(instance.id) { mutableSetOf() }
            val sessionFills = SessionTradeMatcher.fillsForSession(
                symbol = instance.symbol,
                startedAt = session.startedAt,
                stoppedAt = null,
                fills = fills
            )
            val positionQty = positions.find {
                SymbolMarkets.symbolsMatch(it.symbol, instance.symbol)
            }?.quantity
            for (fill in sessionFills) {
                if (!traced.add(fill.execId)) continue
                SessionTrace.fillRecorded(
                    deploymentId = instance.id,
                    sessionId = session.id,
                    symbol = instance.symbol,
                    fill = fill,
                    positionQtyAfter = positionQty
                )
            }
        }
    }

    private fun logAutoStopChecks(
        deployments: List<StrategyDeployment>,
        positions: List<AccountPosition>,
        openOrders: List<WorkingOrder>,
        fills: List<BrokerFill>
    ) {
        val candidates = DeploymentSessionStopEvaluator.evaluate(
            deployments = deployments,
            positions = positions,
            openOrders = openOrders,
            fills = fills
        )
        val candidateIds = candidates.map { it.instanceId }.toSet()
        for (instance in deployments) {
            if (instance.status != DeploymentStatus.RUNNING) continue
            val session = instance.inProgressSession() ?: continue
            val hasOpenPosition = SymbolMarkets.hasOpenPosition(instance, positions)
            val hasOpenOrders = SymbolMarkets.hasOpenOrders(instance, openOrders)
            val sessionTrades = SessionTradeMatcher.toSessionTrades(
                SessionTradeMatcher.fillsForSession(
                    symbol = instance.symbol,
                    startedAt = session.startedAt,
                    stoppedAt = null,
                    fills = fills
                )
            )
            val tradeCycleComplete = DeploymentSessionStopLogic.shouldStopAfterTradeOutcome(
                instance = instance,
                sessionTrades = sessionTrades,
                hasOpenPosition = hasOpenPosition,
                hasOpenOrders = hasOpenOrders
            )
            SessionTrace.autoStopCheck(
                deploymentId = instance.id,
                symbol = instance.symbol,
                sessionId = session.id,
                wouldStop = instance.id in candidateIds,
                hasOpenPosition = hasOpenPosition,
                hasOpenOrders = hasOpenOrders,
                tradeCycleComplete = tradeCycleComplete
            )
        }
    }

    private fun quoteForSymbol(symbol: String): LiveQuote? {
        val gateway = if (brokerKind.usesLiveIbMarketData) {
            sessionGateway ?: executionGateway
        } else {
            executionGateway ?: sessionGateway
        } ?: return null
        val normalized = SymbolMarkets.normalizeSymbol(symbol)
        return gateway.quotes.value[normalized]
    }

    private fun emit(event: TouchTurnEvent) {
        eventChannel.trySend(event)
    }
}
