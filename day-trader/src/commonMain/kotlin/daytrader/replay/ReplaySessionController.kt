package daytrader.replay

import daytrader.data.StrategyDeploymentRepository
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.defaultStrategyDeployment
import daytrader.gateway.BrokerKind
import daytrader.engine.TouchTurnCommand
import daytrader.engine.TouchTurnEngineConfig
import daytrader.engine.TouchTurnEnginePort
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.withoutClosedSessionHistory
import daytrader.platform.MutableTradingClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
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
    private val bundle: SessionBundle
        get() = runtime.bundle

    val clock: MutableTradingClock get() = runtime.clock

    var lastComparison: ReplayComparison? = null
        private set

    var lastFillComparison: ReplayFillComparison? = null
        private set

    fun seedDeploymentIfNeeded() {
        seedDeploymentIfNeeded(repository, bundle)
    }

    companion object {
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
        captureDirectory: String? = null
    ): ReplayBacktestResult {
        require(bundle.hasGroundTruth) { "Replay bundle missing ground truth (session_closed)" }
        val sessionDate = bundle.sessionDate ?: error("Replay bundle missing sessionDate")
        val deploymentId = bundle.deploymentId
        seedDeploymentIfNeeded(repository, bundle)
        runtime.registerBundle(bundle)
        runtime.resetExecutionState(engine)
        runtime.reseedBacktestRandom(ReplayBacktestPolicy.emulatorSeed(bundle))
        repository.update(deploymentId) { it.withoutClosedSessionHistory() }
        val deploymentBefore = repository.deployments.value.find { it.id == deploymentId }
        val useGroundTruthFills = deploymentBefore?.let {
            ReplayBacktestPolicy.useGroundTruthFills(it, bundle)
        } == true
        val orchestrator = runtime.playbackOrchestrator
        orchestrator.interactiveAutoStartEnabled = false
        clock.reset(bundle.timeline.sessionStartedEpochMs)

        try {
            engine.dispatch(
                TouchTurnCommand.StartSession(
                    instanceId = deploymentId,
                    sessionDate = sessionDate,
                    startedBy = bundle.groundTruth!!.runRecord.runContext.startedBy
                )
            )
            awaitBootstrap(deploymentId)
            orchestrator.fastForwardOpeningBar(
                instanceId = deploymentId,
                formingWallDurationMs = ReplayPlaybackConfig.FORMING_WALL_DURATION_MS
            )
            driveSessionToCompletion(deploymentId, bundle)
        } finally {
            orchestrator.interactiveAutoStartEnabled = true
        }

        if (useGroundTruthFills) {
            ReplayGroundTruthApplier.apply(repository, deploymentId, bundle)
        }

        repository.flushPersistenceBlocking()
        val deployment = repository.deployments.value.find { it.id == deploymentId }
        val result = ReplayBacktestResultBuilder.fromDeployment(
            deployment = deployment,
            bundle = bundle,
            captureDirectory = captureDirectory,
            usedGroundTruthFills = useGroundTruthFills
        )
        runtime.resetExecutionState(engine)
        return result
    }

    /** Regression replay for a single primary [bundle]; compares against captured ground truth. */
    suspend fun runReplay(): ReplayComparison {
        val result = runBacktestReplay(bundle)
        val deployment = repository.deployments.value.find { it.id == bundle.deploymentId }
            ?: error("Deployment missing after replay")
        val comparison = ReplayAssertions.compare(deployment, bundle)
        lastComparison = comparison
        lastFillComparison = ReplayFillAssertions.compare(deployment, bundle)
        if (!result.hasTangibleResult && result.errorMessage != null) {
            error(result.errorMessage)
        }
        return comparison
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
        val maxDriveRounds = min(
            MAX_DRIVE_ROUNDS_CAP,
            quotes.size + STUCK_STOP_RULE_POLLS + 200
        )

        while (rounds < maxDriveRounds) {
            rounds++
            val deployment = currentDeployment(instanceId) ?: break
            if (deployment.status != DeploymentStatus.RUNNING) break

            val feeder = runtime.quoteFeeder.feederForSymbol(symbol)
            val published = feeder?.publishedQuoteCount ?: 0
            if (published < quotes.size) {
                val targetIndex = min(published + BACKTEST_QUOTE_CHUNK_SIZE, quotes.size) - 1
                val targetEpoch = quotes[targetIndex].epochMs
                clock.advanceTo(targetEpoch)
                runtime.quoteFeeder.publishUpTo(symbol, targetEpoch)
            } else if (clock.nowEpochMillis() < maxEpoch) {
                clock.advanceTo(maxEpoch)
                runtime.quoteFeeder.publishUpTo(symbol, maxEpoch)
            }

            engine.dispatch(TouchTurnCommand.PollLiquidity(instanceId))
            engine.dispatch(TouchTurnCommand.PollStopRules)
            drainEngine()

            val touchTurn = deployment.touchTurnSession
            if (touchTurn?.candle == null && touchTurn?.milestones?.barClosedAt != null) {
                clock.advanceBy(TouchTurnEngineConfig.CLOSED_BAR_REFETCH_RETRY_DELAY_MS)
                runtime.quoteFeeder.publishUpTo(symbol, clock.nowEpochMillis())
                engine.dispatch(TouchTurnCommand.PollLiquidity(instanceId))
                drainEngine()
            }

            val after = currentDeployment(instanceId)
            if (after?.status != DeploymentStatus.RUNNING) break

            val nowPublished = runtime.quoteFeeder.feederForSymbol(symbol)?.publishedQuoteCount ?: published
            if (nowPublished >= quotes.size && nowPublished == lastPublished) {
                stuckPolls++
                if (stuckPolls >= STUCK_STOP_RULE_POLLS) break
            } else {
                stuckPolls = 0
            }
            lastPublished = nowPublished
        }

        if (currentDeployment(instanceId)?.status == DeploymentStatus.RUNNING) {
            engine.dispatch(
                TouchTurnCommand.StopSession(
                    instanceId = instanceId,
                    trigger = TouchTurnSessionStopTrigger.MANUAL
                )
            )
            awaitStopped(instanceId)
        }
    }

    private suspend fun drainEngine() {
        repeat(ENGINE_DRAIN_ROUNDS) {
            yield()
            delay(ENGINE_DRAIN_MS)
        }
    }

    private suspend fun awaitBootstrap(instanceId: String) {
        repeat(MAX_BOOTSTRAP_TICKS) {
            yield()
            delay(ENGINE_DRAIN_MS)
            val session = currentDeployment(instanceId)?.touchTurnSession
            if (session?.status == TouchTurnCandleStatus.READY && session.openingBarTime != null) return
        }
        error("Replay bootstrap timed out")
    }

    private suspend fun awaitStopped(instanceId: String) {
        repeat(MAX_STOP_TICKS) {
            yield()
            delay(ENGINE_DRAIN_MS)
            if (currentDeployment(instanceId)?.status != DeploymentStatus.RUNNING) return
        }
    }

    private fun currentDeployment(instanceId: String) =
        repository.deployments.value.find { it.id == instanceId }
}
