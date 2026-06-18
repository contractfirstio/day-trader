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
import daytrader.domain.DeploymentSessionStopLogic
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.withoutClosedSessionHistory
import daytrader.platform.MutableTradingClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/**
 * Drives a captured [SessionBundle] through [TouchTurnEnginePort] using virtual time.
 * Used by [ReplaySessionRunner] (tests) and the desktop replay UI.
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

        private const val POLL_YIELD_MS = 15L
        private const val MAX_BOOTSTRAP_TICKS = 400
        private const val MAX_LIQUIDITY_POLLS = 120
        private const val MAX_STOP_TICKS = 400
    }

    suspend fun runReplay(): ReplayComparison {
        require(bundle.hasGroundTruth) { "Replay bundle missing ground truth (session_closed)" }
        val sessionDate = bundle.sessionDate ?: error("Replay bundle missing sessionDate")
        seedDeploymentIfNeeded()
        runtime.resetExecutionState(engine)
        repository.update(bundle.deploymentId) { it.withoutClosedSessionHistory() }
        val orchestrator = runtime.playbackOrchestrator
        orchestrator.interactiveAutoStartEnabled = false
        clock.reset(bundle.timeline.sessionStartedEpochMs)

        try {
            engine.dispatch(
                TouchTurnCommand.StartSession(
                    instanceId = bundle.deploymentId,
                    sessionDate = sessionDate,
                    startedBy = bundle.groundTruth!!.runRecord.runContext.startedBy
                )
            )
            awaitBootstrap()
            orchestrator.fastForwardOpeningBar(
                instanceId = bundle.deploymentId,
                formingWallDurationMs = 0L
            )
        } finally {
            orchestrator.interactiveAutoStartEnabled = true
        }

        var polls = 0
        while (polls < MAX_LIQUIDITY_POLLS) {
            polls++
            runtime.quoteFeeder.publishUpTo(bundle.symbol, clock.nowEpochMillis())
            engine.dispatch(TouchTurnCommand.PollLiquidity(bundle.deploymentId))
            yield()
            delay(POLL_YIELD_MS)
            val instance = currentDeployment() ?: break
            if (instance.status != DeploymentStatus.RUNNING) break
            val touchTurn = instance.touchTurnSession ?: break
            if (touchTurn.candle == null && touchTurn.milestones.barClosedAt != null) {
                clock.advanceBy(TouchTurnEngineConfig.CLOSED_BAR_REFETCH_RETRY_DELAY_MS)
                runtime.quoteFeeder.publishUpTo(bundle.symbol, clock.nowEpochMillis())
            }
            if (touchTurn.decisionOutcome != null &&
                DeploymentSessionStopLogic.shouldStopAfterNoTradeDecision(instance)
            ) {
                awaitStopped()
                break
            }
            if (touchTurn.decisionOutcome != null || touchTurn.setup != null) {
                if (touchTurn.decisionOutcome != null) {
                    awaitStopped()
                }
                break
            }
        }

        val running = currentDeployment()
        if (running?.status == DeploymentStatus.RUNNING) {
            engine.dispatch(
                TouchTurnCommand.StopSession(
                    instanceId = bundle.deploymentId,
                    trigger = TouchTurnSessionStopTrigger.MANUAL
                )
            )
            awaitStopped()
        }

        val deployment = currentDeployment() ?: error("Deployment missing after replay")
        val comparison = ReplayAssertions.compare(deployment, bundle)
        lastComparison = comparison
        lastFillComparison = ReplayFillAssertions.compare(deployment, bundle)
        runtime.resetExecutionState(engine)
        return comparison
    }

    private suspend fun awaitBootstrap() {
        repeat(MAX_BOOTSTRAP_TICKS) {
            yield()
            delay(POLL_YIELD_MS)
            val session = currentDeployment()?.touchTurnSession
            if (session?.status == TouchTurnCandleStatus.READY && session.openingBarTime != null) return
        }
        error("Replay bootstrap timed out")
    }

    private suspend fun awaitStopped() {
        repeat(MAX_STOP_TICKS) {
            yield()
            delay(POLL_YIELD_MS)
            if (currentDeployment()?.status != DeploymentStatus.RUNNING) return
        }
    }

    private fun currentDeployment() =
        repository.deployments.value.find { it.id == bundle.deploymentId }
}
