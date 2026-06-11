package daytrader.replay

import daytrader.broker.emulator.BrokerEmulatorConfig
import daytrader.broker.emulator.EmulatorBrokerAdapter
import daytrader.data.StrategyDeploymentRepository
import daytrader.engine.TouchTurnEngine
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

    fun start() {
        marketDataGateway.resetRefetchIndex()
        quoteFeeder.reset()
        emulator.start()
        executionGateway.connect()
        marketDataGateway.connect()
    }

    /** Resets captured market-data cursors when a new replay session starts at virtual open. */
    fun prepareForSession() {
        playbackOrchestrator.stop()
        marketDataGateway.resetRefetchIndex()
        quoteFeeder.reset()
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
            sessionGateway = marketDataGateway,
            executionGateway = executionGateway
        )
    }
}
