package daytrader.engine

import daytrader.data.DeploymentSessionStopEvaluator
import daytrader.data.LiquidityBucketRepository
import daytrader.data.MarketOpenAutoStartLogic
import daytrader.data.RunningBrokerReconciliation
import daytrader.data.SessionStopOrderCleanup
import daytrader.data.StrategyDeploymentRepository
import daytrader.data.TouchTurnManualStopHandler
import daytrader.data.TouchTurnOrderLog
import daytrader.broker.SessionTradeMatcher
import daytrader.broker.SymbolMarkets
import daytrader.domain.DeploymentMarket
import daytrader.domain.DeploymentSessionStopLogic
import daytrader.domain.DeploymentStatus
import daytrader.domain.ClosedFirstCandleRefetchValidation
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.InstrumentIdentity
import daytrader.domain.InstrumentOrderSizeRules
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCloseConfirmation
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnBracketOrderIds
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnOrderSizingResult
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnCandleLog
import daytrader.domain.TouchTurnDecisionLog
import daytrader.domain.orderSizeRules
import daytrader.domain.inProgressSession
import daytrader.domain.beginTouchTurnSession
import daytrader.domain.isTouchTurn
import daytrader.domain.beginTouchTurnSession
import daytrader.domain.withClosedFirstFifteenMinuteCandle
import daytrader.domain.withOpeningBarClosedMilestone
import daytrader.domain.withFirstFifteenMinuteCandle
import daytrader.domain.withTouchTurnPrepare
import daytrader.domain.TouchTurnSessionPrepare
import daytrader.domain.TouchTurnPrepareOverallStatus
import daytrader.domain.TouchTurnSignalContext
import daytrader.domain.effectiveTouchTurnRules
import daytrader.domain.requiresLiquidityRange
import daytrader.domain.withLiquidityEvaluatedIfClosed
import daytrader.domain.withOrdersPlacedForSession
import daytrader.domain.FiveMinuteConfirmationLogic
import daytrader.domain.withFiveMinuteConfirmationStarted
import daytrader.engine.touchturn.FiveMinuteConfirmationRunner
import daytrader.domain.withTouchTurnCandleFailed
import daytrader.domain.withTouchTurnClosingMilestoneIfNeeded
import daytrader.domain.withTouchTurnDecisionOutcome
import daytrader.domain.withTouchTurnPositionOpenedIfNeeded
import daytrader.data.StrategyCatalog
import daytrader.domain.withClosedPosition
import daytrader.data.DeploymentSessionController
import daytrader.data.LiveMarketDataLifecycle
import daytrader.data.SessionMarketDataCapture
import daytrader.domain.withStopPrice
import daytrader.domain.withoutClosedSessionHistory
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
import daytrader.gateway.QueuedBrokerGateway
import daytrader.gateway.TouchTurnBracketAck
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnOrderRole
import daytrader.execution.ExecutionManager
import daytrader.marketdata.MarketDataProvider
import daytrader.engine.touchturn.TouchTurnPrepareRunner
import daytrader.engine.touchturn.AutoStopCheckSnapshot
import daytrader.engine.BrokerSnapshotSource
import daytrader.engine.touchturn.BrokerSnapshotMerger
import daytrader.engine.touchturn.BrokerSnapshotStopScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import daytrader.gateway.WorkingOrder
import daytrader.presentation.strategies.StartBlockedAlertMapper
import daytrader.presentation.strategies.StrategyDetailTab
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private val onReplaySessionStarting: ((StrategyDeployment, String) -> Unit)? = null,
    /** Returns null when no hybrid capture exists; otherwise the captured session ISO date. */
    private val activateReplayCapture: ((StrategyDeployment) -> String?)? = null,
    /** Replay: true once playback fast-forward has published opening-bar quotes for [symbol]. */
    private val isReplayOpeningBarQuotesReady: ((String) -> Boolean)? = null,
    /** @deprecated Use [marketData] / [execution]; kept for broker connection state subscription. */
    private val sessionGateway: BrokerGateway? = null,
    private val executionGateway: BrokerGateway? = null,
    private val liquidityBucketRepository: LiquidityBucketRepository? = null,
    private val replayPrepareSessionStop: ((String) -> Unit)? = null,
    private val replayDrainBroker: (suspend () -> Unit)? = null
) : TouchTurnEnginePort {
    private val commandQueue = Channel<TouchTurnCommand>(Channel.UNLIMITED)
    private val eventFlow = MutableSharedFlow<TouchTurnEvent>(extraBufferCapacity = 64)
    override val events: Flow<TouchTurnEvent> = eventFlow.asSharedFlow()

    private val stuckFormingLogged = mutableSetOf<String>()
    private val liquidityJobs = ConcurrentHashMap<String, Job>()
    private val liquidityEvalJobs = mutableMapOf<String, Job>()
    private val fiveMinuteConfirmationJobs = ConcurrentHashMap<String, Job>()
    private val closedBarRefetchJobs = ConcurrentHashMap<String, Job>()
    private val loadJobs = ConcurrentHashMap<String, Job>()
    private val prepareJobs = ConcurrentHashMap<String, Job>()
    private val replayLiquidityRetryJobs = ConcurrentHashMap<String, Job>()
    private val tracedFillExecIdsByInstance = mutableMapOf<String, MutableSet<String>>()
    private val lastLoggedAutoStopCheck = mutableMapOf<String, AutoStopCheckSnapshot>()
    private val pendingBracketPlacements = ConcurrentHashMap<String, PendingBracketPlacement>()

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
    private val shutdownRequested = AtomicBoolean(false)
    private val backtestSyncCommands = AtomicBoolean(false)
    private var commandLoopJob: Job? = null
    private var stopRulesPollJob: Job? = null
    private var autoStartPollJob: Job? = null

    override fun setBacktestSyncCommands(enabled: Boolean) {
        backtestSyncCommands.set(enabled)
    }

    override suspend fun dispatchAndAwait(command: TouchTurnCommand, idleSpins: Int) {
        if (shutdownRequested.get()) return
        if (backtestSyncCommands.get()) {
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
            drainUntilIdle(idleSpins)
            return
        }
        dispatch(command)
        drainUntilIdle(idleSpins)
    }

    override fun dispatch(command: TouchTurnCommand) {
        if (shutdownRequested.get()) return
        if (backtestSyncCommands.get()) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
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
            return
        }
        commandQueue.trySend(command)
    }

    override fun updateGlobalAutoStartEnabled(enabled: Boolean) {
        globalAutoStartEnabled = enabled
    }

    override fun start() {
        if (shutdownRequested.get()) return
        commandLoopJob = scope.launch {
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

    override fun shutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) return
        cancelPerInstanceJobs()
        stopRulesPollJob?.cancel()
        autoStartPollJob?.cancel()
        commandLoopJob?.cancel()
        commandQueue.close()
    }

    override fun resetSessionMemory(instanceId: String?) {
        if (instanceId != null) {
            clearInstanceTracking(instanceId)
            cancelJobsForInstance(instanceId)
        } else {
            stuckFormingLogged.clear()
            pendingBracketPlacements.clear()
            tracedFillExecIdsByInstance.clear()
            lastLoggedAutoStopCheck.clear()
            cancelPerInstanceJobs()
        }
        clearBrokerSnapshotsIfIdle()
    }

    override suspend fun drainUntilIdle(maxSpins: Int) {
        var idleStreak = 0
        repeat(maxSpins) {
            yield()
            if (hasActiveInstanceJobs()) {
                idleStreak = 0
            } else {
                idleStreak++
                if (idleStreak >= 4) return
            }
        }
    }

    private fun hasActiveInstanceJobs(): Boolean =
        liquidityJobs.values.any { it.isActive } ||
            liquidityEvalJobs.values.any { it.isActive } ||
            closedBarRefetchJobs.values.any { it.isActive } ||
            loadJobs.values.any { it.isActive } ||
            prepareJobs.values.any { it.isActive } ||
            replayLiquidityRetryJobs.values.any { it.isActive }

    private fun clearInstanceTracking(instanceId: String) {
        stuckFormingLogged.remove(instanceId)
        pendingBracketPlacements.remove(instanceId)
        tracedFillExecIdsByInstance.remove(instanceId)
        lastLoggedAutoStopCheck.remove(instanceId)
    }

    private fun clearBrokerSnapshotsIfIdle() {
        if (repository.deployments.value.any { it.status == DeploymentStatus.RUNNING }) return
        brokerPositions.value = emptyList()
        brokerOpenOrders.value = emptyList()
        brokerFills.value = emptyList()
        if (brokerKind.usesEmulatorExecution) {
            (executionGateway as? QueuedBrokerGateway)?.requestSessionReset()
        }
    }

    private fun cancelJobsForInstance(instanceId: String) {
        liquidityJobs.remove(instanceId)?.cancel()
        liquidityEvalJobs.remove(instanceId)?.cancel()
        closedBarRefetchJobs.remove(instanceId)?.cancel()
        loadJobs.remove(instanceId)?.cancel()
        prepareJobs.remove(instanceId)?.cancel()
        replayLiquidityRetryJobs.remove(instanceId)?.cancel()
    }

    private fun replayOpeningBarQuotesReady(symbol: String): Boolean =
        brokerKind != BrokerKind.REPLAY || isReplayOpeningBarQuotesReady?.invoke(symbol) == true

    private fun scheduleReplayLiquidityRetry(instanceId: String) {
        if (brokerKind != BrokerKind.REPLAY) return
        if (replayLiquidityRetryJobs[instanceId]?.isActive == true) return
        replayLiquidityRetryJobs[instanceId] = scope.launch {
            try {
                repeat(400) {
                    val instance = repository.deployments.value.find { it.id == instanceId } ?: return@launch
                    if (instance.status != DeploymentStatus.RUNNING) return@launch
                    if (replayOpeningBarQuotesReady(instance.symbol)) {
                        dispatch(TouchTurnCommand.PollLiquidity(instanceId))
                        return@launch
                    }
                    delay(15L)
                }
            } finally {
                replayLiquidityRetryJobs.remove(instanceId)
            }
        }
    }

    private fun subscribeBrokerFlows() {
        val gw = executionGateway ?: sessionGateway ?: return
        scope.launch {
            gw.positions.collect { positions ->
                brokerPositions.value = positions
                dispatch(
                    TouchTurnCommand.BrokerSnapshot(
                        source = BrokerSnapshotSource.POSITIONS,
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
                        source = BrokerSnapshotSource.OPEN_ORDERS,
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
                        source = BrokerSnapshotSource.FILLS,
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
                current.withTouchTurnDecisionOutcome(
                    TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED,
                    detailMessage = ack.result.exceptionOrNull()?.message
                )
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
        val bracketOrderIds = TouchTurnBracketOrderIds.fromAckOrderIds(ack.orderIds)
        repository.update(instanceId) {
            it.withOrdersPlacedForSession(plan = plan, bracketOrderIds = bracketOrderIds)
        }
        repository.flushPersistenceBlocking()
        val instance = repository.deployments.value.find { it.id == instanceId } ?: return
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
        stopRulesPollJob = scope.launch {
            while (isActive) {
                delayPollLoop(TouchTurnEngineConfig.STOP_RULES_POLL_MS)
                dispatch(TouchTurnCommand.PollStopRules)
            }
        }
        autoStartPollJob = scope.launch {
            while (isActive) {
                delayPollLoop(TouchTurnEngineConfig.AUTO_START_POLL_MS)
                dispatch(TouchTurnCommand.EvaluateAutoStart)
            }
        }
    }

    private fun cancelPerInstanceJobs() {
        liquidityJobs.values.forEach { it.cancel() }
        liquidityJobs.clear()
        liquidityEvalJobs.values.forEach { it.cancel() }
        liquidityEvalJobs.clear()
        closedBarRefetchJobs.values.forEach { it.cancel() }
        closedBarRefetchJobs.clear()
        loadJobs.values.forEach { it.cancel() }
        loadJobs.clear()
        prepareJobs.values.forEach { it.cancel() }
        prepareJobs.clear()
    }

    private suspend fun handle(command: TouchTurnCommand) {
        when (command) {
            is TouchTurnCommand.StartSession -> handleStartSession(command)
            is TouchTurnCommand.StopSession -> handleStopSession(command)
            is TouchTurnCommand.AdjustStop -> handleAdjustStop(command)
            is TouchTurnCommand.ClosePosition -> handleClosePosition(command)
            is TouchTurnCommand.DeleteSessionHistory -> handleDeleteSessionHistory(command)
            is TouchTurnCommand.DeleteAllSessionHistory -> handleDeleteAllSessionHistory(command)
            is TouchTurnCommand.BrokerSnapshot -> handleBrokerSnapshot(command)
            TouchTurnCommand.BrokerConnected -> handleBrokerConnected()
            is TouchTurnCommand.PollLiquidity -> handlePollLiquidity(command.instanceId)
            TouchTurnCommand.PollStopRules -> handlePollStopRules()
            TouchTurnCommand.EvaluateAutoStart -> handleEvaluateAutoStart()
            is TouchTurnCommand.RetryBootstrap -> handleLoadFirstCandle(command.instanceId, command.sessionDate)
            is TouchTurnCommand.LoadFirstCandle -> handleLoadFirstCandle(command.instanceId, command.sessionDate)
            is TouchTurnCommand.PrepareSession -> handlePrepareSession(command.instanceId)
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
        val sessionDate = if (brokerKind == BrokerKind.REPLAY) {
            val replaySessionDate = activateReplayCapture?.invoke(existing)
            if (replaySessionDate == null) {
                val alert = StartBlockedAlertMapper.fromReplayCaptureNotFound(existing)
                SessionTrace.log(
                    type = "session_start_blocked",
                    deploymentId = existing.id,
                    symbol = existing.symbol,
                    details = mapOf(
                        "reason" to "replay_capture_not_found",
                        "deploymentSymbol" to SymbolMarkets.normalizeSymbol(existing.symbol)
                    )
                )
                emit(TouchTurnEvent.StartBlocked(alert))
                uiEffects.showStartBlockedAlert(alert)
                return
            }
            onReplaySessionStarting?.invoke(existing, replaySessionDate)
            replaySessionDate
        } else {
            command.sessionDate
        }
        repository.update(command.instanceId) { current ->
            DeploymentSessionController.start(
                instance = current,
                sessionDate = sessionDate,
                markAutoStarted = command.startedBy == TouchTurnSessionStartedBy.AUTO_MARKET_OPEN,
            )
        }
        tracedFillExecIdsByInstance.remove(command.instanceId)
        val updated = repository.deployments.value.find { it.id == command.instanceId } ?: return
        startSessionMarketDataCapture(updated)
        updated.inProgressSession()?.let { session ->
            emit(
                TouchTurnEvent.SessionStarted(
                    instanceId = command.instanceId,
                    sessionId = session.id,
                    sessionDate = sessionDate,
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
        if (updated.isTouchTurn) {
            dispatch(TouchTurnCommand.LoadFirstCandle(command.instanceId, sessionDate))
        }
    }

    private suspend fun handleStopSession(command: TouchTurnCommand.StopSession) {
        val instance = repository.deployments.value.find { it.id == command.instanceId } ?: return
        if (instance.status != DeploymentStatus.RUNNING) {
            maybeReleaseLiveMarketData(instance)
            return
        }
        TouchTurnDecisionLog.sessionStopping(
            instanceId = command.instanceId,
            symbol = instance.symbol,
            trigger = command.trigger.name,
            session = instance.touchTurnSession
        )
        cancelJobsForInstance(command.instanceId)
        clearInstanceTracking(command.instanceId)
        val gateway = executionGateway ?: sessionGateway
        val replayFlattenHooks = brokerKind == BrokerKind.REPLAY && replayPrepareSessionStop != null
        if (replayFlattenHooks) {
            replayPrepareSessionStop?.invoke(instance.symbol)
            gateway?.flattenSymbolForSymbol(instance.symbol)
            replayDrainBroker?.invoke()
        }
        val fillsForStop = when {
            replayFlattenHooks && gateway is QueuedBrokerGateway -> gateway.fills.value
            command.brokerFillsAtDecision != null -> command.brokerFillsAtDecision
            else -> brokerFills.value
        }
        val result = TouchTurnManualStopHandler.stop(
            input = TouchTurnManualStopHandler.Input(
                instance = instance,
                brokerPositions = brokerPositions.value,
                brokerOpenOrders = brokerOpenOrders.value,
                brokerFills = fillsForStop,
                brokerKind = brokerKind,
                flattenOnBroker = !replayFlattenHooks
            ),
            gateway = gateway,
            explicitTrigger = command.trigger
        )
        instance.inProgressSession()?.let { sessionRow ->
            liquidityBucketRepository?.creditNoTradeSession(
                sessionId = sessionRow.id,
                deploymentId = instance.id,
                symbol = instance.symbol,
                currencyCode = instance.currencyCode,
                sessionDate = instance.touchTurnSession?.sessionDate ?: sessionRow.date,
                maxDollars = instance.maxDollars,
                touchTurn = instance.touchTurnSession
            )
        }
        val stopped = result.stoppedDeployment
        repository.update(command.instanceId) { stopped }
        repository.flushPersistenceBlocking()
        maybeReleaseLiveMarketData(stopped)
        maybePruneSymbolBrokerState(stopped)
        val sessionId = instance.inProgressSession()?.id
        emit(TouchTurnEvent.SessionStopped(command.instanceId, sessionId, command.trigger))
        emit(TouchTurnEvent.UiNavigate(command.instanceId, StrategyDetailTab.SESSION_HISTORY))
        uiEffects.selectDeployment(command.instanceId, StrategyDetailTab.SESSION_HISTORY)
        clearBrokerSnapshotsIfIdle()
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
    }

    private fun handleDeleteAllSessionHistory(command: TouchTurnCommand.DeleteAllSessionHistory) {
        repository.update(command.instanceId) { it.withoutClosedSessionHistory() }
    }

    private fun handleBrokerSnapshot(command: TouchTurnCommand.BrokerSnapshot) {
        val previousPositions = brokerPositions.value
        val previousOpenOrders = brokerOpenOrders.value
        val previousFills = brokerFills.value
        val (nextPositions, nextOpenOrders, nextFills) = BrokerSnapshotMerger.apply(
            source = command.source,
            command = command,
            currentPositions = previousPositions,
            currentOpenOrders = previousOpenOrders,
            currentFills = previousFills
        )
        brokerPositions.value = nextPositions
        brokerOpenOrders.value = nextOpenOrders
        brokerFills.value = nextFills
        val affectedSymbols = BrokerSnapshotStopScope.affectedSymbols(
            previousPositions = previousPositions,
            previousOpenOrders = previousOpenOrders,
            previousFills = previousFills,
            positions = nextPositions,
            openOrders = nextOpenOrders,
            fills = nextFills
        )
        traceNewSessionFills(nextFills, nextPositions)
        recordTouchTurnPositionMilestones(nextPositions)
        if (affectedSymbols.isNotEmpty()) {
            reconcileRunningBrokerState()
            handlePollStopRules(
                snapshot = null,
                logChecks = false,
                affectedSymbols = affectedSymbols
            )
        }
    }

    private fun reconcileRunningBrokerState() {
        val findings = RunningBrokerReconciliation.evaluate(
            deployments = repository.deployments.value,
            positions = brokerPositions.value,
            openOrders = brokerOpenOrders.value
        )
        for (finding in findings) {
            SessionTrace.log(
                type = "broker_reconciliation",
                deploymentId = finding.deploymentId,
                symbol = finding.symbol,
                details = mapOf(
                    "kind" to finding.kind.name,
                    "detail" to finding.detail
                )
            )
        }
    }

    private fun recordTouchTurnPositionMilestones(positions: List<daytrader.gateway.AccountPosition>) {
        for (instance in repository.deployments.value) {
            if (instance.status != DeploymentStatus.RUNNING) continue
            if (!instance.isTouchTurn) continue
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

    private fun handlePollStopRules(
        snapshot: TouchTurnCommand.BrokerSnapshot? = null,
        logChecks: Boolean = true,
        affectedSymbols: Set<String>? = null
    ) {
        val positions = snapshot?.positions ?: brokerPositions.value
        val openOrders = snapshot?.openOrders ?: brokerOpenOrders.value
        val fills = snapshot?.fills ?: brokerFills.value
        val deployments = repository.deployments.value
        val scopedDeployments = affectedSymbols?.let { symbols ->
            deployments.filter { SymbolMarkets.normalizeSymbol(it.symbol) in symbols }
        } ?: deployments
        if (logChecks) {
            logAutoStopChecks(
                deployments = deployments,
                positions = positions,
                openOrders = openOrders,
                fills = fills
            )
        }
        val candidates = DeploymentSessionStopEvaluator.evaluate(
            deployments = scopedDeployments,
            positions = positions,
            openOrders = openOrders,
            fills = fills,
            nowEpochMillis = nowEpochMillis()
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
        reconcileRunningBrokerState()
        handlePollStopRules(logChecks = false)
        repository.deployments.value
            .asSequence()
            .filter { it.status == DeploymentStatus.RUNNING }
            .filter { it.isTouchTurn }
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

    private fun handlePrepareSession(instanceId: String) {
        prepareJobs[instanceId]?.cancel()
        prepareJobs[instanceId] = scope.launch {
            val instance = repository.deployments.value.find { it.id == instanceId } ?: return@launch
            if (!instance.isTouchTurn) return@launch
            if (instance.status == DeploymentStatus.RUNNING) return@launch
            emit(TouchTurnEvent.PrepareStarted(instanceId))
            val sessionDate = DeploymentMarket.sessionDateIso(instance)
            val quotes = (sessionGateway ?: executionGateway)?.quotes?.value.orEmpty()
            val gateway = sessionGateway ?: executionGateway
            val prepare = TouchTurnPrepareRunner.run(
                deployment = instance,
                sessionDateIso = sessionDate,
                marketData = marketData,
                quotes = quotes,
                brokerPositions = brokerPositions.value,
                marketGateway = gateway,
                brokerKind = brokerKind,
                nowEpochMillis = nowEpochMillis()
            )
            repository.update(instanceId) { it.withTouchTurnPrepare(prepare) }
            SessionTrace.log(
                type = "session_prepare",
                deploymentId = instanceId,
                symbol = instance.symbol,
                details = mapOf(
                    "overallStatus" to prepare.overallStatus,
                    "sessionDate" to sessionDate,
                    "checkCount" to prepare.checks.size.toString()
                )
            )
            if (prepare.overall() != TouchTurnPrepareOverallStatus.FAIL) {
                val ctx = prepare.signalContext
                SessionTrace.touchTurnData(
                    deploymentId = instanceId,
                    sessionId = null,
                    symbol = instance.symbol,
                    event = "prepare_bootstrap",
                    atr14 = ctx.atr14,
                    volumeSma20 = ctx.volumeSma20,
                    barTime = ctx.firstCandle.time
                )
            }
            emit(TouchTurnEvent.PrepareFinished(instanceId, prepare.overall()))
            prepareJobs.remove(instanceId)
        }
    }

    private fun handleLoadFirstCandle(instanceId: String, sessionDate: String) {
        loadJobs[instanceId]?.cancel()
        loadJobs[instanceId] = scope.launch {
            val activeJob = coroutineContext[Job] ?: return@launch
            fun stillActiveLoad(): Boolean = loadJobs[instanceId] === activeJob
            val instance = repository.deployments.value.find { it.id == instanceId } ?: return@launch
            if (!instance.isTouchTurn) return@launch
            val symbol = instance.symbol
            val zoneId = DeploymentMarket.effectiveZoneId(instance)
            val instrument = DeploymentMarket.effectiveInstrument(instance)
            val sessionId = instance.inProgressSession()?.id
            val currency = DeploymentMarket.effectiveCurrencyCode(instance)
            val prepared = instance.touchTurnPrepare
            val reusePrepare = brokerKind != BrokerKind.EMULATOR &&
                TouchTurnSessionPrepare.canReuseBootstrapOnStart(
                    prepare = prepared,
                    deployment = instance,
                    sessionDateIso = sessionDate,
                    nowEpochMillis = nowEpochMillis()
                )
            val signalResult: Result<TouchTurnSignalContext> = if (reusePrepare && prepared != null) {
                if (instance.status == DeploymentStatus.RUNNING) {
                    marketData.ensureStreaming(symbol, instrument)
                }
                if (!stillActiveLoad()) return@launch
                val ctx = prepared.signalContext
                repository.update(instanceId) { current ->
                    current.withFirstFifteenMinuteCandle(
                        sessionDate = sessionDate,
                        candle = ctx.firstCandle,
                        atr14 = ctx.atr14,
                        dailyAtr14 = ctx.dailyAtr14,
                        volumeSma20 = ctx.volumeSma20,
                        adr14 = ctx.atr14,
                        currencyCode = currency,
                        marketZoneId = zoneId,
                        bootstrapReusedFromPrepare = true
                    )
                }
                SessionTrace.touchTurnData(
                    deploymentId = instanceId,
                    sessionId = sessionId,
                    symbol = symbol,
                    event = "prepare_reused_bootstrap",
                    atr14 = prepared.signalContext.atr14,
                    volumeSma20 = prepared.signalContext.volumeSma20,
                    barTime = prepared.signalContext.firstCandle.time
                )
                Result.success(prepared.signalContext)
            } else {
                if (instance.status == DeploymentStatus.RUNNING) {
                    marketData.ensureStreaming(symbol, instrument)
                }
                val fetched = marketData.fetchTouchTurnSignalContext(
                    symbol = symbol,
                    instrument = instrument,
                    isClosedBarRefetch = false,
                    marketZoneId = zoneId,
                    rules = instance.effectiveTouchTurnRules()
                )
                if (!stillActiveLoad()) return@launch
                repository.update(instanceId) { current ->
                    fetched.fold(
                        onSuccess = { context ->
                            current.withFirstFifteenMinuteCandle(
                                sessionDate = sessionDate,
                                candle = context.firstCandle,
                                atr14 = context.atr14,
                                dailyAtr14 = context.dailyAtr14,
                                volumeSma20 = context.volumeSma20,
                                adr14 = context.atr14,
                                currencyCode = currency,
                                marketZoneId = zoneId,
                                bootstrapReusedFromPrepare = false
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
                fetched
            }
            if (!stillActiveLoad()) return@launch
            if (signalResult.isFailure) {
                val cause = signalResult.exceptionOrNull()
                if (cause is CancellationException) return@launch
                val message = cause?.message ?: "Failed to load Touch Turn signal context"
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
                ensureEmulatorQuotesAfterDataReady(loaded, loadedSession)
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
                }
                TouchTurnCandleLog.candleLoaded(
                    instanceId = instanceId,
                    symbol = symbol,
                    sessionDate = sessionDate,
                    deploymentMarketZoneId = zoneId,
                    session = loadedSession
                )
            }
            repository.deployments.value.find { it.id == instanceId }?.let { current ->
                if (current.status != DeploymentStatus.RUNNING) {
                    maybeReleaseLiveMarketData(current)
                }
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

    /** Replay poll loops use wall time so virtual clock stays under orchestrator control. */
    private suspend fun delayPollLoop(intervalMs: Long) {
        if (brokerKind == BrokerKind.REPLAY) {
            delay(intervalMs)
        } else {
            delayMillis(intervalMs)
        }
    }

    private fun watchLiquidity(instanceId: String, sessionDate: String) {
        liquidityJobs[instanceId]?.cancel()
        liquidityJobs[instanceId] = scope.launch {
            dispatch(TouchTurnCommand.PollLiquidity(instanceId))
            while (isActive) {
                delayPollLoop(liquidityPollIntervalMs())
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
        liquidityEvalJobs[instanceId]?.cancel()
        liquidityEvalJobs[instanceId] = scope.launch {
            if (barClosedJustSet && sessionAfterBarClosed.milestones.liquidityEvaluatedAt == null) {
                yield()
            }
            try {
                evaluateLiquidityAfterClosedBar(instanceId)
            } finally {
                liquidityEvalJobs.remove(instanceId)
            }
        }
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
                marketZoneId = zoneId,
                rules = session.rules
            )
            var refetchFailed = false
            var attempt = 0
            while (isActive && attempt < TouchTurnEngineConfig.CLOSED_BAR_REFETCH_MAX_ATTEMPTS) {
                attempt++
                val refetchResult = marketData.fetchTouchTurnSignalContext(
                    symbol = symbol,
                    instrument = instrument,
                    isClosedBarRefetch = true,
                    marketZoneId = zoneId,
                    rules = session.rules
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
                    nowEpochMillis = now,
                    settleMs = session.rules.closedBarRefetchSettleMs
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
        marketZoneId: String,
        rules: daytrader.domain.TouchTurnRuleConfig
    ) {
        val waitMs = TouchTurnLogic.millisUntilClosedBarRefetchReady(
            openingBarTime = openingBarTime,
            marketZoneId = marketZoneId,
            nowEpochMillis = nowEpochMillis(),
            settleMs = rules.closedBarRefetchSettleMs
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

    private suspend fun evaluateLiquidityAfterClosedBar(instanceId: String) {
        val instance = repository.deployments.value.find { it.id == instanceId } ?: return
        if (instance.status != DeploymentStatus.RUNNING) return
        val session = instance.touchTurnSession ?: return
        if (session.setup != null || session.ordersPlacedForSession) return
        if (!replayOpeningBarQuotesReady(instance.symbol)) {
            scheduleReplayLiquidityRetry(instanceId)
            return
        }
        val candle = session.candle ?: run {
            scheduleClosedBarRefetch(instanceId)
            return
        }
        val evaluatedAt = nowEpochMillis()
        val rules = session.rules
        val enforceCloseConfirmation = false
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
                nowEpochMillis = evaluatedAt
            )
        }
        val afterEval = repository.deployments.value.find { it.id == instanceId } ?: return
        val afterSession = afterEval.touchTurnSession ?: return
        if (afterSession.decisionOutcome in liquidityEvalNoBracketOutcomes) {
            finishLiquidityPoll(instanceId, afterEval, evaluatedAt, enforceCloseConfirmation)
            return
        }
        val setup = afterSession.setup
        if (setup == null || !TouchTurnLogic.setupActionableForEntry(setup, rules) ||
            afterSession.entryOrdersPermitted != true
        ) {
            finishLiquidityPoll(instanceId, afterEval, evaluatedAt, enforceCloseConfirmation)
            return
        }
        bracketSubmitSkipReason(afterEval)?.let { reason ->
            SessionTrace.bracketSubmitSkipped(
                deploymentId = instanceId,
                sessionId = afterEval.inProgressSession()?.id,
                symbol = afterEval.symbol,
                reason = reason
            )
            finishLiquidityPoll(instanceId, afterEval, evaluatedAt, enforceCloseConfirmation)
            return
        }
        if (FiveMinuteConfirmationLogic.shouldUseModule(rules)) {
            repository.update(instanceId) { current ->
                current.withFiveMinuteConfirmationStarted(evaluatedAt)
            }
            val afterStart = repository.deployments.value.find { it.id == instanceId }
            val startSession = afterStart?.touchTurnSession
            val confirmation = startSession?.fiveMinuteConfirmation
            val setup = startSession?.setup
            if (afterStart != null && confirmation != null && setup != null) {
                SessionTrace.fiveMinuteConfirmationStarted(
                    deploymentId = instanceId,
                    sessionId = afterStart.inProgressSession()?.id,
                    symbol = afterStart.symbol,
                    sweepPrice = confirmation.sweepPrice,
                    side = setup.side.name,
                    expiresAtEpochMs = confirmation.expiresAtEpochMs
                )
            }
            startFiveMinuteConfirmationWatch(
                instanceId = instanceId,
                executionGw = executionGw,
                evaluatedAt = evaluatedAt,
                enforceCloseConfirmation = enforceCloseConfirmation
            )
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
        TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
        TouchTurnSessionOutcome.NO_TRADE_BOUNCE_REJECTION_FAILED,
        TouchTurnSessionOutcome.NO_TRADE_BOUNCE_DATA_UNAVAILABLE,
        TouchTurnSessionOutcome.NO_TRADE_INVERT_ENTRY_MARKETABLE,
        TouchTurnSessionOutcome.NO_TRADE_INVERT_STOP_WOULD_TRIGGER,
        TouchTurnSessionOutcome.NO_TRADE_LIVE_QUOTE_UNAVAILABLE,
        TouchTurnSessionOutcome.NO_TRADE_INSUFFICIENT_MAX_DOLLARS_FOR_MIN_LOT,
        TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED,
        TouchTurnSessionOutcome.NO_TRADE_FIVE_MIN_CONFIRMATION_EXPIRED,
        TouchTurnSessionOutcome.NO_TRADE_FIVE_MIN_CONFIRMATION_INVALIDATED
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
        liquidityEvalJobs.remove(instanceId)?.cancel()
        closedBarRefetchJobs.remove(instanceId)?.cancel()
    }

    private fun startFiveMinuteConfirmationWatch(
        instanceId: String,
        executionGw: BrokerGateway?,
        evaluatedAt: Long,
        enforceCloseConfirmation: Boolean
    ) {
        fiveMinuteConfirmationJobs[instanceId]?.cancel()
        fiveMinuteConfirmationJobs[instanceId] = scope.launch {
            val runner = FiveMinuteConfirmationRunner(
                marketData = marketData,
                repository = repository,
                nowEpochMillis = { nowEpochMillis() },
                delayMillis = { delayMillis(it) },
                pollIntervalMs = { liquidityPollIntervalMs() },
                replayOpeningBarQuotesReady = { symbol -> this@TouchTurnEngine.replayOpeningBarQuotesReady(symbol) },
                bracketSubmitSkipReason = ::bracketSubmitSkipReason,
                ensureEmulatorQuotesBeforeBracketSubmit = ::ensureEmulatorQuotesBeforeBracketSubmit,
                registerPendingBracket = { id, plan, sessionId, at ->
                    pendingBracketPlacements[id] = PendingBracketPlacement(
                        plan = plan,
                        sessionId = sessionId,
                        evaluatedAt = at,
                        enforceCloseConfirmation = enforceCloseConfirmation
                    )
                },
                quoteForSymbol = ::quoteForSymbol,
                onFinished = { id ->
                    fiveMinuteConfirmationJobs.remove(id)
                    val after = repository.deployments.value.find { it.id == id } ?: return@FiveMinuteConfirmationRunner
                    finishLiquidityPoll(id, after, evaluatedAt, enforceCloseConfirmation)
                }
            )
            runner.runUntilResolved(instanceId, executionGw)
        }
    }

    private fun bracketSubmitSkipReason(instance: StrategyDeployment): String? {
        val session = instance.touchTurnSession ?: return "no_session"
        if (session.ordersPlacedForSession) return "orders_placed_for_session"
        if (pendingBracketPlacements.containsKey(instance.id)) return "bracket_submit_pending"
        if (SymbolMarkets.hasOpenPosition(instance, brokerPositions.value)) return "open_position"
        if (SymbolMarkets.hasOpenOrders(instance, brokerOpenOrders.value)) return "open_orders"
        return null
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
        val rules = session.rules
        if (!TouchTurnLogic.setupActionableForEntry(setup, rules) || session.entryOrdersPermitted != true) {
            return false
        }
        bracketSubmitSkipReason(instance)?.let { reason ->
            SessionTrace.bracketSubmitSkipped(
                deploymentId = instanceId,
                sessionId = instance.inProgressSession()?.id,
                symbol = instance.symbol,
                reason = reason
            )
            return false
        }
        val deploymentInstrument = DeploymentMarket.effectiveInstrument(instance)
        val orderSizeRules = deploymentInstrument?.orderSizeRules() ?: InstrumentOrderSizeRules.DEFAULT
        when (val sizing = TouchTurnOrderPlanner.sizeQuantity(instance.maxDollars, setup.entry, orderSizeRules)) {
            is TouchTurnOrderSizingResult.BelowMinimum -> {
                val outcome = TouchTurnSessionOutcome.NO_TRADE_INSUFFICIENT_MAX_DOLLARS_FOR_MIN_LOT
                val detail = TouchTurnOrderPlanner.insufficientFundsDetailMessage(
                    maxDollars = instance.maxDollars,
                    currencyCode = session.currencyCode,
                    entryPrice = setup.entry,
                    sizing = sizing,
                )
                repository.update(instanceId) {
                    it.withTouchTurnDecisionOutcome(outcome, detailMessage = detail)
                }
                val updatedSession = repository.deployments.value.find { it.id == instanceId }?.touchTurnSession
                TouchTurnDecisionLog.ordersSkipped(
                    instanceId = instance.id,
                    symbol = instance.symbol,
                    reason = "insufficient_max_dollars_for_min_lot",
                    session = updatedSession,
                    nowEpochMillis = evaluatedAt
                )
                SessionTrace.bracketSubmitSkipped(
                    deploymentId = instanceId,
                    sessionId = instance.inProgressSession()?.id,
                    symbol = instance.symbol,
                    reason = "insufficient_max_dollars_for_min_lot",
                    extraDetails = mapOf(
                        "maxDollars" to instance.maxDollars.toString(),
                        "entryPrice" to setup.entry.toString(),
                        "rawQuantity" to sizing.rawQuantity.toString(),
                        "minOrderSize" to sizing.minimumLot.toString(),
                        "minNotional" to sizing.minimumNotional.toString(),
                    )
                )
                return false
            }
            TouchTurnOrderSizingResult.InvalidInputs -> return false
            is TouchTurnOrderSizingResult.Ok -> Unit
        }
        val plan = TouchTurnOrderPlanner.buildOrderPlan(
            symbol = instance.symbol,
            setup = setup,
            maxDollars = instance.maxDollars,
            currencyCode = session.currencyCode,
            instrument = deploymentInstrument,
            rules = rules
        ) ?: return false
        if (!replayOpeningBarQuotesReady(instance.symbol)) {
            scheduleReplayLiquidityRetry(instanceId)
            return false
        }
        ensureEmulatorQuotesBeforeBracketSubmit(
            instance = instance,
            setup = setup,
            rules = rules,
            plan = plan
        )
        val quote = quoteForSymbol(instance.symbol)
        TouchTurnLogic.invertPlacementBlockOutcome(
            plan = plan,
            bid = quote?.bid,
            ask = quote?.ask,
            rules = rules
        )?.let { outcome ->
            repository.update(instanceId) { current ->
                current.withTouchTurnDecisionOutcome(outcome)
            }
            TouchTurnDecisionLog.ordersSkipped(
                instanceId = instance.id,
                symbol = instance.symbol,
                reason = outcome.name.lowercase(),
                session = session.copy(decisionOutcome = outcome),
                nowEpochMillis = evaluatedAt
            )
            SessionTrace.bracketSubmitSkipped(
                deploymentId = instanceId,
                sessionId = instance.inProgressSession()?.id,
                symbol = instance.symbol,
                reason = outcome.name
            )
            return false
        }
        if (executionGw == null) {
            repository.update(instanceId) { current ->
                if (TouchTurnLogic.setupActionableForEntry(setup, rules)) {
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
            brokerGateway = executionGw,
            rules = rules
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
                current.withTouchTurnDecisionOutcome(
                    orderSubmissionBlockOutcome(setup, session, rules)
                        ?: TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED
                )
            }
        }
        return bracketSubmitRequested
    }

    /** Rule-aware no-trade reason when bracket submission is blocked before the broker. */
    private fun orderSubmissionBlockOutcome(
        setup: daytrader.domain.TouchTurnBracketSetup,
        session: daytrader.domain.TouchTurnSessionContext,
        rules: daytrader.domain.TouchTurnRuleConfig
    ): TouchTurnSessionOutcome? = TouchTurnLogic.barSetupBlockOutcome(setup, rules)

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
        when (session.decisionOutcome) {
            TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
            TouchTurnSessionOutcome.NO_TRADE_DOJI,
            TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
            TouchTurnSessionOutcome.NO_TRADE_INVERT_ENTRY_MARKETABLE,
            TouchTurnSessionOutcome.NO_TRADE_INVERT_STOP_WOULD_TRIGGER,
            TouchTurnSessionOutcome.NO_TRADE_LIVE_QUOTE_UNAVAILABLE,
            TouchTurnSessionOutcome.NO_TRADE_INSUFFICIENT_MAX_DOLLARS_FOR_MIN_LOT,
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
        val rules = session.rules
        when {
            setup == null ->
                TouchTurnDecisionLog.ordersSkipped(instance.id, instance.symbol, "setup_null", session, evaluatedAt)
            !TouchTurnLogic.setupActionableForEntry(setup, rules) ->
                TouchTurnDecisionLog.ordersSkipped(
                    instance.id,
                    instance.symbol,
                    when {
                        rules.enables.requiresLiquidityRange() && !setup.isLiquidityCandle -> "not_liquidity_candle"
                        else -> "setup_not_actionable"
                    },
                    session,
                    evaluatedAt
                )
            session.entryOrdersPermitted != true ->
                TouchTurnDecisionLog.ordersSkipped(
                    instance.id,
                    instance.symbol,
                    if (enforceCloseConfirmation) "close_confirmation_pending" else "entry_not_permitted",
                    session,
                    evaluatedAt
                )
            session.decisionOutcome == null && TouchTurnLogic.setupActionableForEntry(setup, rules) ->
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
        if (LiveMarketDataLifecycle.anyDeploymentNeedsQuotes(symbol, repository.deployments.value)) {
            return
        }
        marketData.releaseStreaming(symbol, instrument)
    }

    private fun maybePruneSymbolBrokerState(stopped: StrategyDeployment) {
        if (!brokerKind.usesEmulatorExecution) return
        if (!repository.deployments.value.any { it.status == DeploymentStatus.RUNNING }) return
        (executionGateway as? QueuedBrokerGateway)?.requestSymbolSessionPrune(stopped.symbol)
    }

    private fun startSessionMarketDataCapture(deployment: StrategyDeployment) {
        if (!brokerKind.capturesSessionMarketData) return
        val session = deployment.inProgressSession() ?: return
        SessionMarketDataCapture.start(
            deploymentId = deployment.id,
            sessionId = session.id,
            symbol = deployment.symbol,
            instrument = DeploymentMarket.effectiveInstrument(deployment)
        )
        SessionTrace.log(
            type = "market_data_capture_started",
            deploymentId = deployment.id,
            sessionId = session.id,
            symbol = deployment.symbol
        )
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
            val newFills = sessionFills.filter { fill -> traced.add(fill.execId) }
            if (newFills.isEmpty()) continue
            val positionAfterBatch = positions.find {
                SymbolMarkets.symbolsMatch(it.symbol, instance.symbol)
            }?.quantity ?: 0
            var qtyBeforeBatch = positionAfterBatch
            for (fill in newFills.asReversed()) {
                qtyBeforeBatch -= signedFillQuantity(fill)
            }
            var runningQty = qtyBeforeBatch
            for (fill in newFills) {
                runningQty += signedFillQuantity(fill)
                SessionTrace.fillRecorded(
                    deploymentId = instance.id,
                    sessionId = session.id,
                    symbol = instance.symbol,
                    fill = fill,
                    positionQtyAfter = runningQty
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
            fills = fills,
            nowEpochMillis = nowEpochMillis()
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
            val snapshot = AutoStopCheckSnapshot(
                wouldStop = instance.id in candidateIds,
                hasOpenPosition = hasOpenPosition,
                hasOpenOrders = hasOpenOrders,
                tradeCycleComplete = tradeCycleComplete
            )
            if (lastLoggedAutoStopCheck[instance.id] == snapshot) continue
            lastLoggedAutoStopCheck[instance.id] = snapshot
            SessionTrace.autoStopCheck(
                deploymentId = instance.id,
                symbol = instance.symbol,
                sessionId = session.id,
                wouldStop = snapshot.wouldStop,
                hasOpenPosition = snapshot.hasOpenPosition,
                hasOpenOrders = snapshot.hasOpenOrders,
                tradeCycleComplete = snapshot.tradeCycleComplete
            )
        }
    }

    /**
     * Gateway for live quotes and trend-alignment historical fetches.
     * Hybrid / IB / replay use [sessionGateway] (live IB market data); pure emulator uses execution.
     */
    private fun marketDataBrokerGateway(): BrokerGateway? =
        if (brokerKind.usesLiveIbMarketData) {
            sessionGateway ?: executionGateway
        } else {
            executionGateway ?: sessionGateway
        }

    private fun quoteForSymbol(symbol: String): LiveQuote? {
        val gateway = marketDataBrokerGateway() ?: return null
        val normalized = SymbolMarkets.normalizeSymbol(symbol)
        return gateway.quotes.value[normalized]
    }

    private fun ensureEmulatorQuotesAfterDataReady(
        deployment: StrategyDeployment,
        session: daytrader.domain.TouchTurnSessionContext
    ) {
        if (brokerKind != BrokerKind.EMULATOR) return
        val referencePrice = session.candle?.close ?: return
        (executionGateway as? QueuedBrokerGateway)?.requestEmulatorStreaming(
            symbol = deployment.symbol,
            instrument = DeploymentMarket.effectiveInstrument(deployment),
            referencePrice = referencePrice
        )
    }

    private fun ensureEmulatorQuotesBeforeBracketSubmit(
        instance: StrategyDeployment,
        setup: daytrader.domain.TouchTurnBracketSetup,
        rules: daytrader.domain.TouchTurnRuleConfig,
        plan: TouchTurnOrderPlan?
    ) {
        if (brokerKind != BrokerKind.EMULATOR || plan == null) return
        val session = instance.touchTurnSession ?: return
        val instrument = DeploymentMarket.effectiveInstrument(instance)
        val referenceMid = session.candle?.close ?: setup.entry
        (executionGateway as? QueuedBrokerGateway)?.requestEmulatorStreaming(
            symbol = instance.symbol,
            instrument = instrument,
            referencePrice = referenceMid
        )
        val quote = quoteForSymbol(instance.symbol)
        val needsPlacementSeed = quote?.bid == null || quote.ask == null ||
            (rules.invertTradeSide &&
                TouchTurnLogic.invertPlacementBlockOutcome(
                    plan = plan,
                    bid = quote?.bid,
                    ask = quote?.ask,
                    rules = rules
                ) != null)
        if (!needsPlacementSeed) return
        val (bid, ask) = if (rules.invertTradeSide) {
            TouchTurnLogic.syntheticBidAskForInvertPlacement(plan, setup, referenceMid)
        } else {
            val spread = max(setup.range * 0.001, referenceMid * 1e-4).coerceAtLeast(0.01)
            val midBid = referenceMid - spread / 2.0
            val midAsk = referenceMid + spread / 2.0
            midBid to midAsk
        }
        val last = (bid + ask) / 2.0
        (executionGateway as? QueuedBrokerGateway)?.requestEmulatorSyntheticQuote(
            symbol = instance.symbol,
            bid = bid,
            ask = ask,
            last = last
        )
    }

    private fun emit(event: TouchTurnEvent) {
        scope.launch { eventFlow.emit(event) }
    }

    private fun signedFillQuantity(fill: BrokerFill): Int = when (fill.side.uppercase()) {
        "BUY" -> fill.quantity
        "SELL" -> -fill.quantity
        else -> 0
    }
}
