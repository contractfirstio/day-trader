package daytrader.replay

import daytrader.broker.emulator.BrokerEmulatorConfig
import daytrader.broker.emulator.EmulatorBrokerAdapter
import daytrader.data.StrategyDeploymentRepository
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.defaultStrategyDeployment
import daytrader.engine.TouchTurnCommand
import daytrader.engine.TouchTurnEngine
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.gateway.BrokerKind
import daytrader.gateway.QueuedBrokerGateway
import daytrader.marketdata.BrokerGatewayMarketDataProvider
import daytrader.marketdata.MarketQuoteBus
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import daytrader.gateway.BrokerId
import daytrader.gateway.GatewayCommand
import daytrader.gateway.GatewayEvent

/**
 * Drives [TouchTurnEngine] through a captured [SessionBundle] using virtual time (Tier A replay).
 */
class ReplaySessionRunner(
    private val bundle: SessionBundle,
    private val repository: StrategyDeploymentRepository,
    private val scope: CoroutineScope
) {
    suspend fun run(): ReplayComparison {
        require(bundle.hasGroundTruth) { "Replay bundle missing ground truth (session_closed)" }
        val sessionDate = bundle.sessionDate ?: error("Replay bundle missing sessionDate")
        val marketInputs = bundle.groundTruth!!.runRecord.marketInputs
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = bundle.symbol,
            maxDollars = bundle.groundTruth.runRecord.runContext.maxDollars,
            marketZoneId = marketInputs.marketZoneId,
            currencyCode = marketInputs.currencyCode,
            instrument = bundle.manifest?.instrument,
            status = DeploymentStatus.STOPPED
        ).copy(id = bundle.deploymentId)
        repository.add(deployment)

        val clock = ReplayClock(bundle.timeline.sessionStartedEpochMs)
        val runtime = ReplayHybridRuntime(bundle, clock, scope)
        runtime.start()
        val engine = runtime.createEngine(repository)
        engine.start()

        try {
            engine.dispatch(
                TouchTurnCommand.StartSession(
                    instanceId = bundle.deploymentId,
                    sessionDate = sessionDate,
                    startedBy = bundle.groundTruth.runRecord.runContext.startedBy
                )
            )
            awaitBootstrap()

            val session = currentDeployment()?.touchTurnSession
                ?: error("Touch Turn session missing after bootstrap")
            val openingBarTime = session.openingBarTime
                ?: error("Opening bar time missing after bootstrap")
            val zoneId = session.marketZoneId
            val barEnd = daytrader.domain.TouchTurnLogic.barEndEpochMillis(openingBarTime, zoneId)
                ?: error("Invalid opening bar time: $openingBarTime")
            val settleMs = daytrader.domain.TouchTurnDefaults.CLOSED_BAR_REFETCH_SETTLE_MS
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
                    clock.advanceBy(daytrader.engine.TouchTurnEngineConfig.CLOSED_BAR_REFETCH_RETRY_DELAY_MS)
                    runtime.quoteFeeder.publishUpTo(clock.now())
                }
                if (touchTurn.decisionOutcome != null &&
                    daytrader.domain.DeploymentSessionStopLogic.shouldStopAfterNoTradeDecision(instance)
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
                        trigger = daytrader.domain.TouchTurnSessionStopTrigger.MANUAL
                    )
                )
                awaitStopped()
            }

            return ReplayAssertions.compare(
                currentDeployment() ?: error("Deployment missing after replay"),
                bundle
            )
        } finally {
            runtime.shutdown()
        }
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

/**
 * Hybrid replay wiring: live-exchange emulator execution + captured IB market data.
 */
class ReplayHybridRuntime(
    private val bundle: SessionBundle,
    val clock: ReplayClock,
    private val scope: CoroutineScope
) {
    val quoteBus = MarketQuoteBus()
    val marketDataGateway = ReplayMarketDataGateway(bundle)
    private val inbound = LinkedBlockingQueue<GatewayEvent>()
    private val outbound = LinkedBlockingQueue<GatewayCommand>()

    private val emulator = EmulatorBrokerAdapter(
        emit = { event -> inbound.offer(event) },
        receiveCommand = { outbound.take() },
        config = BrokerEmulatorConfig.forLiveIbMarketData().copy(connectDelayMs = 1),
        quoteBus = quoteBus,
        scope = scope
    )

    val executionGateway = QueuedBrokerGateway(
        sendCommand = { command -> outbound.offer(command) },
        receiveEventBlocking = { inbound.take() },
        brokerId = BrokerId.EMULATOR,
        scope = scope
    )

    val quoteFeeder = QuoteFeeder(
        bundle = bundle,
        quoteBus = quoteBus,
        marketDataGateway = marketDataGateway
    )

    fun start() {
        marketDataGateway.resetRefetchIndex()
        quoteFeeder.reset()
        emulator.start()
        executionGateway.connect()
        marketDataGateway.connect()
    }

    fun shutdown() {
        emulator.shutdown()
        marketDataGateway.disconnect()
    }

    fun createEngine(repository: StrategyDeploymentRepository): TouchTurnEngine {
        val marketData = BrokerGatewayMarketDataProvider(
            gateway = marketDataGateway,
            ensureLiveMarketData = { _, _ -> quoteFeeder.publishUpTo(clock.now()) }
        )
        return TouchTurnEngine(
            marketData = marketData,
            execution = BrokerGatewayExecutionManager(executionGateway),
            repository = repository,
            scope = scope,
            brokerKind = BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA,
            nowEpochMillis = clock::now,
            delayMillis = clock::delayMillis,
            sessionGateway = marketDataGateway,
            executionGateway = executionGateway
        )
    }
}
