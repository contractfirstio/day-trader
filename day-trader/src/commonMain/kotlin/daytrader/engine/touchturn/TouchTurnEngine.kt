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
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.InstrumentIdentity
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCloseConfirmation
import daytrader.domain.TouchTurnLogic
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
import daytrader.diagnostics.TimestampedConsoleLog
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerId
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.LiveQuote
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

class TouchTurnEngine(
    private val sessionGateway: BrokerGateway?,
    private val executionGateway: BrokerGateway?,
    private val repository: StrategyDeploymentRepository,
    private val scope: CoroutineScope,
    private val uiEffects: TouchTurnUiEffects = NoOpTouchTurnUiEffects,
    private val ensureLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null,
    private val isGlobalAutoStartEnabled: () -> Boolean = { true },
    private val releaseLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() }
) : TouchTurnEnginePort {
    private val commandQueue = Channel<TouchTurnCommand>(Channel.UNLIMITED)
    private val eventChannel = Channel<TouchTurnEvent>(Channel.UNLIMITED)
    override val events: Flow<TouchTurnEvent> = eventChannel.receiveAsFlow()

    private val stuckFormingLogged = mutableSetOf<String>()
    private val liquidityJobs = mutableMapOf<String, Job>()
    private val loadJobs = mutableMapOf<String, Job>()
    private val tracedFillExecIdsByInstance = mutableMapOf<String, MutableSet<String>>()

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
    }

    private fun startTimers() {
        scope.launch {
            while (isActive) {
                delay(TouchTurnEngineConfig.STOP_RULES_POLL_MS)
                dispatch(TouchTurnCommand.PollStopRules)
            }
        }
        scope.launch {
            while (isActive) {
                delay(TouchTurnEngineConfig.AUTO_START_POLL_MS)
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
        loadJobs.remove(command.instanceId)?.cancel()
        val gateway = executionGateway ?: sessionGateway
        val result = TouchTurnManualStopHandler.stop(
            input = TouchTurnManualStopHandler.Input(
                instance = instance,
                brokerPositions = brokerPositions.value,
                brokerOpenOrders = brokerOpenOrders.value,
                brokerFills = brokerFills.value
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
            dispatch(TouchTurnCommand.StopSession(candidate.instanceId, candidate.trigger))
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
        val gateway = sessionGateway ?: return
        loadJobs[instanceId] = scope.launch {
            val instance = repository.deployments.value.find { it.id == instanceId } ?: return@launch
            if (instance.strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return@launch
            val symbol = instance.symbol
            val instrument = DeploymentMarket.effectiveInstrument(instance)
            ensureLiveMarketData?.invoke(symbol, instrument)
            val sessionId = instance.inProgressSession()?.id
            val adrResult = gateway.fetchFourteenDayAdr(symbol, instrument)
            val adr14 = adrResult.getOrElse { error ->
                val message = error.message ?: "Failed to load 14-day ADR"
                SessionTrace.touchTurnData(
                    deploymentId = instanceId,
                    sessionId = sessionId,
                    symbol = symbol,
                    event = "adr_failed",
                    message = message
                )
                repository.update(instanceId) { it.withTouchTurnCandleFailed(sessionDate, message) }
                notifyNoTradeDecisionIfNeeded(instanceId)
                return@launch
            }
            val candleResult = gateway.fetchFirstFifteenMinuteCandle(symbol, instrument)
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
            if (candleResult.isFailure) {
                val message = candleResult.exceptionOrNull()?.message ?: "Failed to load first 15-minute candle"
                SessionTrace.touchTurnData(
                    deploymentId = instanceId,
                    sessionId = sessionId,
                    symbol = symbol,
                    event = "candle_failed",
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
                    adr14 = adr14,
                    barTime = loadedSession.candle?.time
                )
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

    private fun watchLiquidity(instanceId: String, sessionDate: String) {
        liquidityJobs[instanceId]?.cancel()
        liquidityJobs[instanceId] = scope.launch {
            while (isActive) {
                delay(TouchTurnEngineConfig.LIQUIDITY_POLL_MS)
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
        if (session.candleCloseStatus() != FirstCandleCloseStatus.CLOSED) {
            TouchTurnDecisionLog.watchPollTick(
                instanceId = instanceId,
                symbol = instance.symbol,
                closeStatus = session.candleCloseStatus(),
                hasSetup = session.setup != null,
                nowEpochMillis = System.currentTimeMillis()
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
        val evaluatedAt = System.currentTimeMillis()
        val executionGw = executionGateway ?: sessionGateway
        val enforceCloseConfirmation = executionGw?.brokerId != BrokerId.EMULATOR ||
            emulatorRequireCloseConfirmation()
        TouchTurnDecisionLog.bootstrapCandleClosed(
            instanceId = instanceId,
            symbol = instance.symbol,
            session = session,
            enforceCloseConfirmation = enforceCloseConfirmation,
            nowEpochMillis = evaluatedAt
        )
        var ordersPlaced = false
        var submittedPlan: daytrader.domain.TouchTurnOrderPlan? = null
        repository.update(instanceId) { current ->
            val updated = current.withLiquidityEvaluatedIfClosed(
                enforceCloseConfirmation = enforceCloseConfirmation,
                nowEpochMillis = evaluatedAt
            )
            val updatedSession = updated.touchTurnSession ?: return@update updated
            when (updatedSession.decisionOutcome) {
                TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
                TouchTurnSessionOutcome.NO_TRADE_DOJI,
                TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
                TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED -> return@update updated
                else -> Unit
            }
            if (enforceCloseConfirmation) {
                when (updatedSession.closeConfirmation(evaluatedAt)) {
                    TouchTurnCloseConfirmation.EXPIRED ->
                        return@update updated.withTouchTurnDecisionOutcome(
                            TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED
                        )
                    TouchTurnCloseConfirmation.FAILED ->
                        return@update updated.withTouchTurnDecisionOutcome(
                            TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED
                        )
                    else -> Unit
                }
            }
            val setup = updatedSession.setup
            if (setup == null || !setup.isLiquidityCandle || !setup.isActionable) return@update updated
            if (updatedSession.entryOrdersPermitted != true) return@update updated
            val deploymentInstrument = DeploymentMarket.effectiveInstrument(updated)
            val plan = TouchTurnOrderPlanner.buildOrderPlan(
                symbol = updated.symbol,
                setup = setup,
                maxDollars = updated.maxDollars,
                currencyCode = updatedSession.currencyCode,
                instrument = deploymentInstrument
            )
            ordersPlaced = TouchTurnOrderLog.logAfterLiquidityEvaluation(
                instanceId = updated.id,
                symbol = updated.symbol,
                sessionDate = updatedSession.sessionDate,
                maxDollars = updated.maxDollars,
                currencyCode = updatedSession.currencyCode,
                instrument = deploymentInstrument,
                setup = setup,
                openingBarClose = updatedSession.candle?.close,
                brokerGateway = executionGw
            )
            when {
                ordersPlaced && plan != null -> {
                    submittedPlan = plan
                    updated.withOrdersPlacedForSession(plan)
                }
                setup.isActionable ->
                    updated.withTouchTurnDecisionOutcome(TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED)
                else -> updated
            }
        }
        val after = repository.deployments.value.find { it.id == instanceId } ?: return
        val afterSession = after.touchTurnSession ?: return
        val afterSessionId = after.inProgressSession()?.id
        logLiquidityPollOutcome(
            instance = after,
            sessionId = afterSessionId,
            ordersPlaced = ordersPlaced,
            submittedPlan = submittedPlan,
            enforceCloseConfirmation = enforceCloseConfirmation,
            evaluatedAt = evaluatedAt
        )
        notifyNoTradeDecisionIfNeeded(instanceId)
        TouchTurnDecisionLog.watchPollExit(instanceId, after.symbol, "liquidity_poll_complete")
        liquidityJobs.remove(instanceId)?.cancel()
    }

    private fun logLiquidityPollOutcome(
        instance: StrategyDeployment,
        sessionId: String?,
        ordersPlaced: Boolean,
        submittedPlan: daytrader.domain.TouchTurnOrderPlan?,
        enforceCloseConfirmation: Boolean,
        evaluatedAt: Long
    ) {
        val session = instance.touchTurnSession ?: return
        when (session.decisionOutcome) {
            TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
            TouchTurnSessionOutcome.NO_TRADE_DOJI,
            TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
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
                details = mapOf(
                    "orderCount" to submittedPlan.orders.size.toString(),
                    "entrySide" to (session.setup?.side?.name ?: "unknown")
                )
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
        val release = releaseLiveMarketData ?: return
        val symbol = deployment.symbol
        val instrument = DeploymentMarket.effectiveInstrument(deployment)
        val stillNeeded = repository.deployments.value.any {
            it.status == DeploymentStatus.RUNNING &&
                SymbolMarkets.symbolsMatch(it.symbol, symbol)
        }
        if (!stillNeeded) release(symbol, instrument)
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
        val gateway = executionGateway ?: sessionGateway ?: return null
        val normalized = SymbolMarkets.normalizeSymbol(symbol)
        return gateway.quotes.value[normalized]
    }

    private fun emit(event: TouchTurnEvent) {
        eventChannel.trySend(event)
    }
}
