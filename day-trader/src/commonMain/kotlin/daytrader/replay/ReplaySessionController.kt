package daytrader.replay

import daytrader.data.StrategyDeploymentRepository
import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.defaultStrategyDeployment
import daytrader.gateway.BrokerKind
import daytrader.engine.TouchTurnCommand
import daytrader.engine.TouchTurnEngineConfig
import daytrader.engine.TouchTurnEnginePort
import daytrader.domain.TouchTurnDefaults
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.InstrumentIdentity
import daytrader.domain.withoutClosedSessionHistory
import daytrader.platform.MutableTradingClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext
import kotlin.math.min

/**
 * Drives a captured [SessionBundle] through [TouchTurnEnginePort] using virtual time.
 * Used by [ReplaySessionRunner] (tests), batch what-if backtest, and the desktop replay UI.
 */
class ReplaySessionController(
    val runtime: ReplayHybridRuntime,
    private val repository: StrategyDeploymentRepository,
    private val engine: TouchTurnEnginePort,
    private val scope: CoroutineScope
) {
    /** Engine scope — batch replay must run here so sync backtest commands can execute. */
    val engineCoroutineContext: CoroutineContext get() = scope.coroutineContext

    private val bundle: SessionBundle
        get() = runtime.bundle

    val clock: MutableTradingClock get() = runtime.clock

    var lastComparison: ReplayComparison? = null
        private set

    var lastFillComparison: ReplayFillComparison? = null
        private set

    private var backtestFastPath = false

    fun seedDeploymentIfNeeded() {
        ensureDeploymentForCapture(repository, bundle)
    }

    companion object {
        fun ensureDeploymentForCapture(repository: StrategyDeploymentRepository, bundle: SessionBundle) {
            seedDeploymentIfNeeded(repository, bundle)
            syncInstrumentFromCapture(repository, bundle)
        }

        fun syncInstrumentFromCapture(repository: StrategyDeploymentRepository, bundle: SessionBundle) {
            val instrument = resolveCaptureInstrument(bundle) ?: return
            if (!repository.deployments.value.any { it.id == bundle.deploymentId }) return
            repository.update(bundle.deploymentId) { deployment ->
                if (deployment.instrument == instrument) deployment
                else deployment.copy(instrument = instrument)
            }
        }

        fun resolveCaptureInstrument(bundle: SessionBundle): InstrumentIdentity? {
            bundle.manifest?.instrument?.let { return it }
            val entryFill = bundle.groundTruth?.dedupedFills?.firstOrNull()
                ?: bundle.groundTruth?.rawFills?.firstOrNull()
            if (entryFill != null && entryFill.quantity > 1) {
                return InstrumentIdentity.heuristic(bundle.symbol).copy(
                    minOrderSize = entryFill.quantity,
                    orderSizeIncrement = entryFill.quantity
                )
            }
            return null
        }

        fun seedDeploymentIfNeeded(repository: StrategyDeploymentRepository, bundle: SessionBundle) {
            if (repository.deployments.value.any { it.id == bundle.deploymentId }) return
            val groundTruth = bundle.groundTruth ?: return
            val marketInputs = groundTruth.runRecord.marketInputs
            val sourceRules = groundTruth.runRecord.rules
                ?: TouchTurnRuleConfig.defaultForBrokerKind(BrokerKind.REPLAY)
            val deployment = defaultStrategyDeployment(
                strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                symbol = bundle.symbol,
                maxDollars = groundTruth.runRecord.runContext.maxDollars,
                marketZoneId = marketInputs.marketZoneId,
                currencyCode = marketInputs.currencyCode,
                instrument = bundle.manifest?.instrument,
                status = DeploymentStatus.STOPPED,
                brokerKind = BrokerKind.REPLAY
            ).copy(
                id = bundle.deploymentId,
                touchTurnRules = sourceRules
            )
            repository.add(deployment)
        }

        fun seedDeploymentsFromDirectories(
            repository: StrategyDeploymentRepository,
            directoryPaths: Collection<String>,
            loadBundle: (String) -> Result<SessionBundle>
        ) {
            directoryPaths.distinct().forEach { path ->
                loadBundle(path).onSuccess { bundle -> seedDeploymentIfNeeded(repository, bundle) }
            }
        }

        private const val ENGINE_DRAIN_MS = 1L
        private const val ENGINE_DRAIN_ROUNDS = 4
        private const val MAX_BOOTSTRAP_TICKS = 400
        private const val MAX_STOP_TICKS = 400
        /** One captured quote per drive round for tick-accurate fill sequencing. */
        private const val BACKTEST_QUOTE_CHUNK_SIZE = 1
        private const val MAX_DRIVE_ROUNDS_CAP = 50_000
        private const val STUCK_STOP_RULE_POLLS = 24
    }

    /**
     * Replays [bundle] headlessly with the deployment's current configuration, driving through
     * the full captured quote timeline until the session stops naturally or all quotes are exhausted.
     */
    suspend fun runBacktestReplay(
        bundle: SessionBundle,
        captureDirectory: String? = null,
        options: ReplayBacktestOptions = ReplayBacktestOptions(),
    ): ReplayBacktestRun = ReplayHeadlessProcessLock.withExclusiveLock {
        runBacktestReplayUnlocked(bundle, captureDirectory, options)
    }

    private suspend fun runBacktestReplayUnlocked(
        bundle: SessionBundle,
        captureDirectory: String? = null,
        options: ReplayBacktestOptions = ReplayBacktestOptions(),
    ): ReplayBacktestRun {
        require(bundle.hasGroundTruth) { "Replay bundle missing ground truth (session_closed)" }
        val sessionDate = bundle.sessionDate ?: error("Replay bundle missing sessionDate")
        val deploymentId = bundle.deploymentId
        val orchestrator = runtime.playbackOrchestrator
        stopAllRunningSessions()
        orchestrator.stopAll()
        orchestrator.interactiveAutoStartEnabled = false
        ensureDeploymentForCapture(repository, bundle)
        runtime.resetExecutionState(engine)
        runtime.registerBundle(bundle)
        runtime.reseedBacktestRandom(ReplayBacktestPolicy.emulatorSeed(bundle))
        repository.update(deploymentId) { it.withoutClosedSessionHistory() }
        val deploymentBefore = repository.deployments.value.find { it.id == deploymentId }
        val useGroundTruthFills = options.applyGroundTruthFills && deploymentBefore?.let {
            ReplayBacktestPolicy.useGroundTruthFills(it, bundle)
        } == true
        alignBacktestClock(deploymentBefore, sessionDate, bundle)
        enableBacktestFastPath()
        runtime.beginHeadlessBacktest(deploymentId, bundle)

        var runError: Throwable? = null
        try {
            dispatchEngine(
                TouchTurnCommand.StartSession(
                    instanceId = deploymentId,
                    sessionDate = sessionDate,
                    startedBy = bundle.groundTruth!!.runRecord.runContext.startedBy
                )
            )
            awaitBootstrap(deploymentId)
            fastForwardOpeningBarForBacktest(deploymentId, bundle.symbol)
            if (shouldAwaitBacktestBracketOutcome(deploymentId)) {
                awaitBacktestBracketOutcome(deploymentId)
            }
            alignFillAnchorAfterBracket(bundle)
            runtime.awaitEmulatorBracketPipeline()
            runtime.drainAllPendingInboundEvents()
            driveSessionToCompletion(deploymentId, bundle)
            awaitBacktestSessionStopped(deploymentId)
            forceStopIfRunning(
                deploymentId,
                trigger = TouchTurnSessionStopTrigger.REPLAY_QUOTES_EXHAUSTED
            )
            awaitClosedSessionForGroundTruth(deploymentId)

            if (useGroundTruthFills) {
                ReplayGroundTruthApplier.apply(repository, deploymentId, bundle)
                drainEngine()
            }
        } catch (error: Throwable) {
            runError = error
        } finally {
            runtime.endHeadlessBacktest()
            disableBacktestFastPath()
            if (!options.deferRepositoryUpdates) {
                orchestrator.interactiveAutoStartEnabled = true
            }
            forceStopIfRunning(deploymentId)
            if (!options.deferRepositoryUpdates) {
                runtime.resetExecutionState(engine)
            }
        }

        val deployment = repository.deployments.value.find { it.id == deploymentId }
        val result = ReplayBacktestResultBuilder.fromDeployment(
            deployment = deployment,
            bundle = bundle,
            captureDirectory = captureDirectory,
            usedGroundTruthFills = useGroundTruthFills,
            errorMessage = runError?.message ?: runError?.let { it::class.simpleName },
        )
        if (!options.deferRepositoryUpdates) {
            repository.flushPersistenceBlocking()
        }
        runError?.let { throw it }
        return ReplayBacktestRun(result = result, deploymentAfterReplay = deployment)
    }

    private suspend fun dispatchEngine(command: TouchTurnCommand) {
        if (backtestFastPath) {
            engine.dispatchAndAwait(
                command,
                idleSpins = ReplayBacktestFastPath.ENGINE_IDLE_MAX_SPINS,
            )
        } else {
            engine.dispatch(command)
        }
    }

    suspend fun cleanupAfterBacktestRun() {
        stopAllRunningSessions()
        runtime.resetExecutionState(engine)
    }

    fun setInteractiveAutoStartEnabled(enabled: Boolean) {
        runtime.playbackOrchestrator.interactiveAutoStartEnabled = enabled
    }

    fun setEngineGlobalAutoStartEnabled(enabled: Boolean) {
        engine.updateGlobalAutoStartEnabled(enabled)
    }

    /** One headless catalog run at a time — no parallel replay sessions or market-open auto-starts. */
    suspend fun beginBatchReplayIsolation() {
        setEngineGlobalAutoStartEnabled(false)
        setInteractiveAutoStartEnabled(false)
        runtime.playbackOrchestrator.stopAll()
        stopAllRunningSessions()
    }

    suspend fun endBatchReplayIsolation(restoreEngineGlobalAutoStart: Boolean) {
        stopAllRunningSessions()
        cleanupAfterBacktestRun()
        setEngineGlobalAutoStartEnabled(restoreEngineGlobalAutoStart)
        setInteractiveAutoStartEnabled(true)
    }

    /** Regression replay for a single primary [bundle]; compares against captured ground truth. */
    suspend fun runReplay(): ReplayComparison {
        val run = runBacktestReplay(bundle)
        val deployment = repository.deployments.value.find { it.id == bundle.deploymentId }
            ?: error("Deployment missing after replay")
        val comparison = ReplayAssertions.compare(deployment, bundle)
        lastComparison = comparison
        lastFillComparison = ReplayFillAssertions.compare(deployment, bundle)
        if (!run.result.hasTangibleResult && run.result.errorMessage != null) {
            error(run.result.errorMessage)
        }
        return comparison
    }

    private fun alignBacktestClock(
        deployment: daytrader.domain.StrategyDeployment?,
        sessionDate: String,
        bundle: SessionBundle,
    ) {
        if (deployment != null &&
            ReplaySessionTiming.alignClockToSessionOpen(clock, deployment, sessionDate) != null
        ) {
            return
        }
        clock.reset(bundle.timeline.sessionStartedEpochMs)
    }

    private suspend fun forceStopIfRunning(
        instanceId: String,
        trigger: TouchTurnSessionStopTrigger = TouchTurnSessionStopTrigger.MANUAL
    ) {
        if (currentDeployment(instanceId)?.status != DeploymentStatus.RUNNING) return
        dispatchEngine(
            TouchTurnCommand.StopSession(
                instanceId = instanceId,
                trigger = trigger,
            )
        )
        awaitStopped(instanceId)
    }

    suspend fun stopAllRunningSessions() {
        val maxRounds = if (backtestFastPath) {
            ReplayBacktestFastPath.STOP_MAX_YIELDS
        } else {
            MAX_STOP_TICKS
        }
        repeat(maxRounds) {
            val running = repository.deployments.value.filter { it.status == DeploymentStatus.RUNNING }
            if (running.isEmpty()) return
            running.forEach { deployment ->
                dispatchEngine(
                    TouchTurnCommand.StopSession(
                        instanceId = deployment.id,
                        trigger = TouchTurnSessionStopTrigger.MANUAL,
                    )
                )
            }
            drainEngine()
            yield()
        }
    }

    private suspend fun fastForwardOpeningBarForBacktest(instanceId: String, symbol: String) {
        val deployment = currentDeployment(instanceId) ?: return
        val session = deployment.touchTurnSession ?: return
        val openingBarTime = session.resolvedOpeningBarTime() ?: return
        val zoneId = session.marketZoneId
        val barEnd = TouchTurnLogic.barEndEpochMillis(openingBarTime, zoneId) ?: return
        val targetMs = TouchTurnDefaults.CLOSED_BAR_REFETCH_SETTLE_MS + barEnd + 1
        runtime.fastForwardOpeningBarForBacktest(symbol, targetMs)
        runtime.quoteFeeder.markOpeningBarQuotesReady(symbol)
        dispatchEngine(TouchTurnCommand.PollLiquidity(instanceId))
        awaitClosedBarAfterFastForward(instanceId)
        runtime.quoteFeeder.seekToFirstQuoteAfter(symbol, targetMs)
    }

    private suspend fun awaitClosedBarAfterFastForward(instanceId: String) {
        repeat(ReplayPlaybackConfig.CLOSED_BAR_WAIT_POLLS) {
            drainEngine()
            val session = currentDeployment(instanceId)?.touchTurnSession
            if (session?.milestones?.barClosedAt != null && session.candle != null) return
            dispatchEngine(TouchTurnCommand.PollLiquidity(instanceId))
        }
    }

    private fun alignFillAnchorAfterBracket(bundle: SessionBundle) {
        ReplayQuoteFillAnchor.ordersPlacedAnchorEpochMs(bundle)?.let { anchorMs ->
            ReplayQuoteFillAnchor.alignAfterBracketPlaced(
                runtime.quoteFeeder,
                clock,
                bundle.symbol,
                anchorMs,
            )
        }
    }

    private suspend fun driveSessionToCompletion(instanceId: String, bundle: SessionBundle) {
        val symbol = bundle.symbol
        val quotes = bundle.quoteEvents
        val maxEpoch = bundle.timeline.sessionStoppedEpochMs
            ?: quotes.lastOrNull()?.epochMs
            ?: clock.nowEpochMillis()
        var rounds = 0
        var lastPublished = -1
        var stuckPolls = 0
        var fillAnchorAppliedInDrive = false
        val maxDriveRounds = min(
            MAX_DRIVE_ROUNDS_CAP,
            quotes.size + STUCK_STOP_RULE_POLLS + 200
        )

        while (rounds < maxDriveRounds) {
            rounds++
            val deployment = currentDeployment(instanceId) ?: return
            if (deployment.status != DeploymentStatus.RUNNING) return

            val touchTurnBeforePublish = deployment.touchTurnSession
            if (touchTurnBeforePublish?.ordersPlacedForSession == true && !fillAnchorAppliedInDrive) {
                alignFillAnchorAfterBracket(bundle)
                fillAnchorAppliedInDrive = true
                runtime.drainAllPendingInboundEvents()
            }

            val feeder = runtime.quoteFeeder.feederForSymbol(symbol)
            val published = feeder?.publishedQuoteCount ?: 0
            if (published < quotes.size) {
                val targetIndex = min(published + BACKTEST_QUOTE_CHUNK_SIZE, quotes.size) - 1
                val targetEpoch = quotes[targetIndex].epochMs
                clock.advanceTo(targetEpoch)
                publishBacktestQuotesUpTo(symbol, targetEpoch)
            } else if (clock.nowEpochMillis() < maxEpoch) {
                clock.advanceTo(maxEpoch)
                publishBacktestQuotesUpTo(symbol, maxEpoch)
            }

            dispatchEngine(TouchTurnCommand.PollLiquidity(instanceId))
            dispatchEngine(TouchTurnCommand.PollStopRules)
            drainEngine()

            val after = currentDeployment(instanceId)
            if (after?.status != DeploymentStatus.RUNNING) return

            val touchTurn = after.touchTurnSession
            if (touchTurn?.candle == null && touchTurn?.milestones?.barClosedAt != null) {
                clock.advanceBy(TouchTurnEngineConfig.CLOSED_BAR_REFETCH_RETRY_DELAY_MS)
                publishBacktestQuotesUpTo(symbol, clock.nowEpochMillis())
                dispatchEngine(TouchTurnCommand.PollLiquidity(instanceId))
                drainEngine()
            }

            if (touchTurn?.ordersPlacedForSession == true) {
                stuckPolls = 0
            }

            val nowPublished = runtime.quoteFeeder.feederForSymbol(symbol)?.publishedQuoteCount ?: published
            val quotesExhausted = nowPublished >= quotes.size
            val clockAtEnd = clock.nowEpochMillis() >= maxEpoch
            val awaitingFillOutcome = touchTurn?.ordersPlacedForSession == true &&
                !quotesExhausted
            if (!awaitingFillOutcome && quotesExhausted && clockAtEnd && nowPublished == lastPublished) {
                stuckPolls++
                if (stuckPolls >= STUCK_STOP_RULE_POLLS) return
            } else if (!awaitingFillOutcome) {
                stuckPolls = 0
            }
            lastPublished = nowPublished
        }
    }

    private fun shouldAwaitBacktestBracketOutcome(instanceId: String): Boolean {
        val session = currentDeployment(instanceId)?.touchTurnSession ?: return false
        if (session.ordersPlacedForSession) return false
        if (session.decisionOutcome in backtestTerminalNoBracketOutcomes) return false
        val setup = session.setup ?: return false
        return session.entryOrdersPermitted == true &&
            TouchTurnLogic.setupActionableForEntry(setup, session.rules)
    }

    private suspend fun awaitBacktestBracketOutcome(instanceId: String) {
        repeat(ReplayBacktestFastPath.BRACKET_ACK_MAX_YIELDS) {
            drainEngine()
            runtime.awaitEmulatorBracketPipeline(maxSpins = 8)
            val session = currentDeployment(instanceId)?.touchTurnSession ?: return
            if (session.ordersPlacedForSession) return
            if (session.decisionOutcome in backtestTerminalNoBracketOutcomes) return
            if (session.milestones.liquidityEvaluatedAt != null &&
                (session.setup == null || session.entryOrdersPermitted != true)
            ) {
                return
            }
            yield()
        }
    }

    private val backtestTerminalNoBracketOutcomes = setOf(
        TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED,
        TouchTurnSessionOutcome.NO_TRADE_LIVE_QUOTE_UNAVAILABLE,
        TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
        TouchTurnSessionOutcome.NO_TRADE_OPENING_BAR_COLOR_SKIPPED,
        TouchTurnSessionOutcome.NO_TRADE_OPENING_BAR_CLOSE_POSITION_SKIPPED,
        TouchTurnSessionOutcome.NO_TRADE_DOJI,
        TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
        TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
        TouchTurnSessionOutcome.NO_TRADE_INVERT_ENTRY_MARKETABLE,
        TouchTurnSessionOutcome.NO_TRADE_INVERT_STOP_WOULD_TRIGGER,
        TouchTurnSessionOutcome.NO_TRADE_INSUFFICIENT_MAX_DOLLARS_FOR_MIN_LOT,
    )

    private suspend fun awaitBacktestSessionStopped(instanceId: String) {
        val maxTicks = if (backtestFastPath) {
            ReplayBacktestFastPath.STOP_MAX_YIELDS
        } else {
            MAX_STOP_TICKS
        }
        repeat(maxTicks) {
            if (currentDeployment(instanceId)?.status != DeploymentStatus.RUNNING) return
            dispatchEngine(TouchTurnCommand.PollStopRules)
            drainEngine()
            yield()
        }
    }

    private suspend fun publishBacktestQuotesUpTo(symbol: String, epochMs: Long) {
        if (backtestFastPath) {
            runtime.publishBacktestQuotesUpTo(symbol, epochMs)
        } else {
            runtime.quoteFeeder.publishUpTo(symbol, epochMs)
        }
    }

    private fun enableBacktestFastPath() {
        backtestFastPath = true
        runtime.enableBacktestFastPath()
        engine.setBacktestSyncCommands(true)
    }

    private fun disableBacktestFastPath() {
        backtestFastPath = false
        engine.setBacktestSyncCommands(false)
        runtime.disableBacktestFastPath()
    }

    private suspend fun drainEngine() {
        if (backtestFastPath) {
            repeat(ReplayBacktestFastPath.ENGINE_DRAIN_YIELD_ROUNDS) { yield() }
            engine.drainUntilIdle(ReplayBacktestFastPath.ENGINE_IDLE_MAX_SPINS)
            runtime.drainEmulatorPipeline()
            runtime.drainAllPendingInboundEvents()
        } else {
            repeat(ENGINE_DRAIN_ROUNDS) {
                yield()
                delay(ENGINE_DRAIN_MS)
            }
        }
    }

    private suspend fun awaitBootstrap(instanceId: String) {
        val maxTicks = if (backtestFastPath) {
            ReplayBacktestFastPath.BOOTSTRAP_MAX_YIELDS
        } else {
            MAX_BOOTSTRAP_TICKS
        }
        repeat(maxTicks) {
            drainEngine()
            val session = currentDeployment(instanceId)?.touchTurnSession
            if (session?.status == TouchTurnCandleStatus.READY && session.openingBarTime != null) return
            yield()
            if (!backtestFastPath) delay(ENGINE_DRAIN_MS)
        }
        error("Replay bootstrap timed out")
    }

    private suspend fun awaitClosedSessionForGroundTruth(instanceId: String) {
        val maxTicks = if (backtestFastPath) {
            ReplayBacktestFastPath.STOP_MAX_YIELDS
        } else {
            MAX_STOP_TICKS
        }
        repeat(maxTicks) {
            drainEngine()
            val deployment = currentDeployment(instanceId) ?: return
            val closed = deployment.sessionHistory.lastOrNull { it.status == SessionStatus.CLOSED }
            val outcome = ReplayBacktestResultBuilder.resolveOutcome(deployment, closed)
            if (deployment.status != DeploymentStatus.RUNNING && closed != null && outcome != null) return
            if (deployment.status == DeploymentStatus.RUNNING) {
                dispatchEngine(TouchTurnCommand.PollStopRules)
            }
            yield()
            if (!backtestFastPath) delay(ENGINE_DRAIN_MS)
        }
    }

    private suspend fun awaitStopped(instanceId: String) {
        val maxTicks = if (backtestFastPath) {
            ReplayBacktestFastPath.STOP_MAX_YIELDS
        } else {
            MAX_STOP_TICKS
        }
        repeat(maxTicks) {
            yield()
            if (!backtestFastPath) delay(ENGINE_DRAIN_MS)
            engine.drainUntilIdle(ReplayBacktestFastPath.ENGINE_IDLE_MAX_SPINS)
            if (currentDeployment(instanceId)?.status != DeploymentStatus.RUNNING) return
        }
    }

    private fun currentDeployment(instanceId: String) =
        repository.deployments.value.find { it.id == instanceId }
}
