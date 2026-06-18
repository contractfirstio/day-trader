package daytrader.replay

import daytrader.broker.SymbolMarkets
import daytrader.broker.emulator.BrokerEmulatorConfig
import daytrader.broker.emulator.EmulatorBrokerAdapter
import daytrader.data.StrategyDeploymentRepository
import daytrader.engine.TouchTurnEngine
import daytrader.engine.TouchTurnEnginePort
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayCommand
import daytrader.gateway.GatewayEvent
import daytrader.gateway.QueuedBrokerGateway
import daytrader.marketdata.BrokerGatewayMarketDataProvider
import daytrader.marketdata.MarketQuoteBus
import daytrader.platform.MutableTradingClock
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CoroutineScope

/**
 * Hybrid replay wiring: emulator execution + captured IB market data from a [SessionBundle].
 */
class ReplayHybridRuntime(
    bundle: SessionBundle,
    val clock: MutableTradingClock,
    private val scope: CoroutineScope
) {
    var bundle: SessionBundle = bundle
        private set
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
        bundle = this.bundle,
        quoteBus = quoteBus,
        marketDataGateway = marketDataGateway
    )

    val playbackOrchestrator = ReplayPlaybackOrchestrator(
        clock = clock,
        quoteFeeder = quoteFeeder,
        scope = scope
    )

    private var sessionEngine: TouchTurnEnginePort? = null

    fun attachSessionEngine(engine: TouchTurnEnginePort) {
        sessionEngine = engine
    }

    fun start() {
        marketDataGateway.resetRefetchIndex()
        quoteFeeder.reset()
        emulator.start()
        executionGateway.connect()
        marketDataGateway.connect()
    }

    /**
     * Resets captured market-data cursors, broker snapshots, and emulator session state
     * when a new replay session starts at virtual open.
     */
    fun prepareForSession() {
        resetExecutionState(sessionEngine)
    }

    /**
     * Clears replay/runtime memory retained across session boundaries: quote cursors, gateway
     * snapshots, emulator fills/orders, engine tracking, and stale queue events.
     */
    fun resetExecutionState(engine: TouchTurnEnginePort? = sessionEngine) {
        playbackOrchestrator.stop()
        marketDataGateway.resetRefetchIndex()
        marketDataGateway.clearLiveState()
        quoteFeeder.reset()
        drainGatewayQueues()
        emulator.resetSessionState()
        executionGateway.resetSessionLiveState()
        engine?.resetSessionMemory()
    }

    /** Switches active hybrid capture (quotes, historical bootstrap/refetch) for another deployment. */
    fun swapBundle(newBundle: SessionBundle) {
        if (newBundle.sessionId == bundle.sessionId && newBundle.deploymentId == bundle.deploymentId) return
        playbackOrchestrator.stop()
        bundle = newBundle
        marketDataGateway.replaceBundle(newBundle)
        quoteFeeder.replaceBundle(newBundle)
    }

    fun shutdown() {
        playbackOrchestrator.stop()
        emulator.shutdown()
        marketDataGateway.disconnect()
        drainGatewayQueues()
    }

    private fun drainGatewayQueues() {
        while (inbound.poll() != null) { }
        while (outbound.poll() != null) { }
    }

    fun createEngine(repository: StrategyDeploymentRepository): TouchTurnEngine {
        val marketData = BrokerGatewayMarketDataProvider(
            gateway = marketDataGateway,
            ensureLiveMarketData = { _, _ -> playbackOrchestrator.ensureQuotesFlowing() }
        )
        return TouchTurnEngine(
            marketData = marketData,
            execution = BrokerGatewayExecutionManager(executionGateway),
            repository = repository,
            scope = scope,
            brokerKind = BrokerKind.REPLAY,
            nowEpochMillis = clock::nowEpochMillis,
            delayMillis = clock::delayMillis,
            onReplaySessionStarting = { _, _ -> prepareForSession() },
            activateReplayCapture = { deployment ->
                if (deployment.id == bundle.deploymentId) bundle.sessionDate else null
            },
            sessionGateway = marketDataGateway,
            executionGateway = executionGateway
        )
    }
}
