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
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CoroutineScope

/**
 * Hybrid replay wiring: emulator execution + captured IB market data from a [SessionBundle].
 */
class ReplayHybridRuntime(
    val bundle: SessionBundle,
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
            brokerKind = BrokerKind.REPLAY,
            nowEpochMillis = clock::now,
            delayMillis = clock::delayMillis,
            sessionGateway = marketDataGateway,
            executionGateway = executionGateway
        )
    }
}
