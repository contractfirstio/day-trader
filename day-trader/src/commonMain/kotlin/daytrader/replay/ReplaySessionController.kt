package daytrader.replay

import daytrader.data.StrategyDeploymentRepository
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.defaultStrategyDeployment
import daytrader.engine.TouchTurnCommand
import daytrader.engine.TouchTurnEngineConfig
import daytrader.engine.TouchTurnEnginePort
import daytrader.domain.DeploymentSessionStopLogic
import daytrader.domain.TouchTurnDefaults
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnSessionStopTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/**
 * Drives a captured [SessionBundle] through [TouchTurnEnginePort] using virtual time.
 * Used by [ReplaySessionRunner] (tests) and the desktop replay UI.
 */
class ReplaySessionController(
    private val bundle: SessionBundle,
    private val runtime: ReplayHybridRuntime,
    private val repository: StrategyDeploymentRepository,
    private val engine: TouchTurnEnginePort,
    private val scope: CoroutineScope
) {
    val clock: ReplayClock get() = runtime.clock

    var lastComparison: ReplayComparison? = null
        private set

    var lastFillComparison: ReplayFillComparison? = null
        private set

    fun seedDeploymentIfNeeded() {
        if (repository.deployments.value.any { it.id == bundle.deploymentId }) return
        val groundTruth = bundle.groundTruth ?: return
        val marketInputs = groundTruth.runRecord.marketInputs
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = bundle.symbol,
            maxDollars = groundTruth.runRecord.runContext.maxDollars,
            marketZoneId = marketInputs.marketZoneId,
            currencyCode = marketInputs.currencyCode,
            instrument = bundle.manifest?.instrument,
            status = DeploymentStatus.STOPPED
        ).copy(id = bundle.deploymentId)
        repository.add(deployment)
    }

    suspend fun runReplay(): ReplayComparison {
        require(bundle.hasGroundTruth) { "Replay bundle missing ground truth (session_closed)" }
        val sessionDate = bundle.sessionDate ?: error("Replay bundle missing sessionDate")
        seedDeploymentIfNeeded()
        clock.reset(bundle.timeline.sessionStartedEpochMs)
        runtime.quoteFeeder.reset()
        runtime.marketDataGateway.resetRefetchIndex()

        engine.dispatch(
            TouchTurnCommand.StartSession(
                instanceId = bundle.deploymentId,
                sessionDate = sessionDate,
                startedBy = bundle.groundTruth!!.runRecord.runContext.startedBy
            )
        )
        awaitBootstrap()

        val session = currentDeployment()?.touchTurnSession
            ?: error("Touch Turn session missing after bootstrap")
        val openingBarTime = session.openingBarTime
            ?: error("Opening bar time missing after bootstrap")
        val zoneId = session.marketZoneId
        val barEnd = TouchTurnLogic.barEndEpochMillis(openingBarTime, zoneId)
            ?: error("Invalid opening bar time: $openingBarTime")
        val settleMs = TouchTurnDefaults.CLOSED_BAR_REFETCH_SETTLE_MS
        clock.advanceTo(barEnd + settleMs + 1)
        runtime.quoteFeeder.publishUpTo(clock.now())

        var polls = 0
        while (polls < MAX_LIQUIDITY_POLLS) {
            polls++
            runtime.quoteFeeder.publishUpTo(clock.now())
            engine.dispatch(TouchTurnCommand.PollLiquidity(bundle.deploymentId))
            yield()
            delay(POLL_YIELD_MS)
            val instance = currentDeployment() ?: break
            if (instance.status != DeploymentStatus.RUNNING) break
            val touchTurn = instance.touchTurnSession ?: break
            if (touchTurn.candle == null && touchTurn.milestones.barClosedAt != null) {
                clock.advanceBy(TouchTurnEngineConfig.CLOSED_BAR_REFETCH_RETRY_DELAY_MS)
                runtime.quoteFeeder.publishUpTo(clock.now())
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

    companion object {
        private const val POLL_YIELD_MS = 15L
        private const val MAX_BOOTSTRAP_TICKS = 400
        private const val MAX_LIQUIDITY_POLLS = 120
        private const val MAX_STOP_TICKS = 400
    }
}
