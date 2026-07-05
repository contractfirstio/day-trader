package daytrader.e2e.support

import daytrader.broker.emulator.BrokerEmulatorConfig
import daytrader.broker.emulator.EmulatorBrokerAdapter
import daytrader.data.StrategyDeploymentRepository
import daytrader.engine.TouchTurnEngine
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayCommand
import daytrader.gateway.GatewayEvent
import daytrader.gateway.LiveQuote
import daytrader.gateway.QueuedBrokerGateway
import daytrader.marketdata.BrokerGatewayMarketDataProvider
import daytrader.marketdata.MarketQuoteBus
import daytrader.marketdata.MarketQuoteBusUiRelay
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CoroutineScope

/**
 * Hybrid wiring for E2E tests: emulator execution + programmable IB market data (no real TWS).
 */
class HybridModeTestHarness(
    private val scope: CoroutineScope
) {
    val quoteBus = MarketQuoteBus()
    val ibGateway = ProgrammableIbMarketDataGateway(quoteBus)

    private val execInbound = LinkedBlockingQueue<GatewayEvent>()
    private val execOutbound = LinkedBlockingQueue<GatewayCommand>()

    private val emulatorAdapter = EmulatorBrokerAdapter(
        emit = { event -> execInbound.offer(event) },
        receiveCommand = { execOutbound.take() },
        config = BrokerEmulatorConfig.forLiveIbMarketData().copy(connectDelayMs = 1),
        onSymbolNeedsLiveQuotes = { symbol -> ibGateway.ensureStreaming(symbol) },
        quoteBus = quoteBus,
        scope = scope
    )

    val executionGateway = QueuedBrokerGateway(
        sendCommand = { command -> execOutbound.offer(command) },
        receiveEventBlocking = { execInbound.take() },
        brokerId = BrokerId.EMULATOR,
        scope = scope
    )

    private val quoteUiRelay = MarketQuoteBusUiRelay(
        bus = quoteBus,
        scope = scope,
        onSnapshot = { quotes -> ibGateway.publishQuoteSnapshot(quotes) },
        throttleMs = 1L
    )

    fun start() {
        ibGateway.resetRefetchIndex()
        emulatorAdapter.start()
        quoteUiRelay.start()
        executionGateway.connect()
        ibGateway.connect()
    }

    fun shutdown() {
        quoteUiRelay.stop()
        emulatorAdapter.shutdown()
        ibGateway.disconnect()
    }

    fun publishIbQuote(symbol: String, quote: LiveQuote, priorClose: Double? = null) {
        ibGateway.publishQuote(symbol, quote, priorClose)
    }

    fun ingestLiveQuote(symbol: String, quote: LiveQuote, priorClose: Double? = null) {
        emulatorAdapter.ingestExternalQuote(symbol, quote, priorClose)
    }

    fun applyMarketScenario(scenario: TouchTurnMarketScenario) {
        scenario.applyTo(ibGateway)
    }

    fun createEngine(
        repository: StrategyDeploymentRepository,
        nowEpochMillis: () -> Long = { E2ETestFixtures.BAR_CLOSE_EPOCH_MS }
    ): TouchTurnEngine {
        val marketData = BrokerGatewayMarketDataProvider(
            gateway = ibGateway,
            ensureLiveMarketData = { symbol, _ -> ibGateway.ensureStreaming(symbol) },
            releaseLiveMarketData = { _, _ -> }
        )
        return TouchTurnEngine(
            marketData = marketData,
            execution = BrokerGatewayExecutionManager(executionGateway),
            repository = repository,
            scope = scope,
            brokerKind = BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA,
            nowEpochMillis = nowEpochMillis,
            sessionGateway = ibGateway,
            executionGateway = executionGateway
        )
    }

    private fun ProgrammableIbMarketDataGateway.publishQuoteSnapshot(quotes: Map<String, LiveQuote>) {
        quotes.forEach { (symbol, quote) -> publishQuote(symbol, quote) }
    }
}
