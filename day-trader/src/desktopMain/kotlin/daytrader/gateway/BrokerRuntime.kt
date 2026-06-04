package daytrader.gateway

import daytrader.broker.DesktopIbGatewayConnection
import daytrader.broker.IbConnectionMode
import daytrader.broker.emulator.BrokerEmulatorConfig
import daytrader.broker.emulator.EmulatorBrokerAdapter
import daytrader.marketdata.MarketQuoteBus
import daytrader.marketdata.MarketQuoteBusUiRelay
import daytrader.platform.CrashLogging
import daytrader.replay.ReplayClock
import daytrader.replay.ReplayHybridRuntime
import daytrader.replay.SessionBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

data class BrokerRuntime(
    val kind: BrokerKind,
    val gateway: QueuedBrokerGateway,
    /** IB or replay market-data gateway for Touch Turn ADR / first candle / quotes. */
    val marketDataGateway: BrokerGateway? = null,
    /** Subscribes to IB streaming quotes for a symbol (used by hybrid paper mode for emulator marks). */
    val ensureLiveMarketData: ((String, daytrader.domain.InstrumentIdentity?) -> Unit)? = null,
    /** Cancels symbol-only streaming when no session needs quotes (hybrid mode only). */
    val releaseLiveMarketData: ((String, daytrader.domain.InstrumentIdentity?) -> Unit)? = null,
    /** IB streaming quote mode (live / delayed / delayed-frozen); null when not on an IB connection. */
    val getStreamingMarketDataType: (() -> IbStreamingMarketDataType)? = null,
    val setStreamingMarketDataType: ((IbStreamingMarketDataType) -> Unit)? = null,
    val quoteBus: MarketQuoteBus? = null,
    val replayBundle: SessionBundle? = null,
    val replayHybridRuntime: ReplayHybridRuntime? = null,
    private val adapters: List<BrokerAdapter> = emptyList(),
    private val queueSets: List<BlockingGatewayQueues> = emptyList(),
    private val quoteUiRelay: MarketQuoteBusUiRelay? = null
) {
    fun start() {
        quoteUiRelay?.start()
        adapters.forEach { it.start() }
        gateway.connect()
        marketDataGateway?.connect()
    }

    fun shutdown() {
        quoteUiRelay?.stop()
        queueSets.forEach { it.outbound.offer(GatewayCommand.Shutdown) }
        adapters.forEach { it.shutdown() }
        replayHybridRuntime?.shutdown()
    }

    companion object {
        fun create(
            kind: BrokerKind = BrokerKind.fromEnvironment(),
            scope: CoroutineScope = CoroutineScope(
                SupervisorJob() +
                    Dispatchers.Default +
                    CoroutineName("BrokerRuntime[$kind]") +
                    CrashLogging.coroutineExceptionHandler("BrokerRuntime[$kind]")
            )
        ): BrokerRuntime = when (kind) {
            BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA -> createHybrid(scope)
            BrokerKind.REPLAY -> error("use createReplay(bundle, scope)")
            else -> createSingle(kind, scope)
        }

        fun createReplay(
            bundle: SessionBundle,
            scope: CoroutineScope = CoroutineScope(
                SupervisorJob() +
                    Dispatchers.Default +
                    CoroutineName("BrokerRuntime[REPLAY]") +
                    CrashLogging.coroutineExceptionHandler("BrokerRuntime[REPLAY]")
            )
        ): BrokerRuntime {
            val clock = ReplayClock(bundle.timeline.sessionStartedEpochMs)
            val hybrid = ReplayHybridRuntime(bundle, clock, scope)
            hybrid.start()
            return BrokerRuntime(
                kind = BrokerKind.REPLAY,
                gateway = hybrid.executionGateway,
                marketDataGateway = hybrid.marketDataGateway,
                ensureLiveMarketData = { _, _ -> hybrid.quoteFeeder.publishUpTo(clock.now()) },
                releaseLiveMarketData = { _, _ -> },
                quoteBus = hybrid.quoteBus,
                replayHybridRuntime = hybrid,
                replayBundle = bundle
            )
        }

        private fun createSingle(
            kind: BrokerKind,
            scope: CoroutineScope
        ): BrokerRuntime {
            val quoteBus = MarketQuoteBus()
            val queues = BlockingGatewayQueues()
            val brokerId = BrokerId.from(kind)
            val adapter: BrokerAdapter = when (kind) {
                BrokerKind.INTERACTIVE_BROKERS -> DesktopIbGatewayConnection(queues = queues, scope = scope)
                BrokerKind.EMULATOR -> EmulatorBrokerAdapter(
                    emit = { queues.inbound.offer(it) },
                    receiveCommand = { queues.outbound.take() },
                    config = BrokerEmulatorConfig.fromEnvironment(),
                    quoteBus = quoteBus,
                    scope = scope
                )
                BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA -> error("use createHybrid")
                BrokerKind.REPLAY -> error("use createReplay")
            }
            val gateway = QueuedBrokerGateway(
                sendCommand = { queues.outbound.offer(it) },
                receiveEventBlocking = { queues.inbound.take() },
                brokerId = brokerId,
                scope = scope
            )
            val ibConnection = adapter as? DesktopIbGatewayConnection
            return BrokerRuntime(
                kind = kind,
                gateway = gateway,
                quoteBus = quoteBus,
                getStreamingMarketDataType = ibConnection?.let { ib -> { ib.currentStreamingMarketDataType() } },
                setStreamingMarketDataType = ibConnection?.let { ib -> { type -> ib.setStreamingMarketDataType(type) } },
                adapters = listOf(adapter),
                queueSets = listOf(queues)
            )
        }

        private fun createHybrid(scope: CoroutineScope): BrokerRuntime {
            val quoteBus = MarketQuoteBus()
            val execQueues = BlockingGatewayQueues()
            val mdQueues = BlockingGatewayQueues()
            lateinit var ibAdapter: DesktopIbGatewayConnection
            val emulatorAdapter = EmulatorBrokerAdapter(
                emit = { execQueues.inbound.offer(it) },
                receiveCommand = { execQueues.outbound.take() },
                config = BrokerEmulatorConfig.forLiveIbMarketData(),
                onSymbolNeedsLiveQuotes = { symbol -> ibAdapter.ensureStreamingMarketData(symbol) },
                quoteBus = quoteBus,
                scope = scope
            )
            val quoteUiRelay = MarketQuoteBusUiRelay(
                bus = quoteBus,
                scope = scope,
                onSnapshot = { quotes -> mdQueues.inbound.offer(GatewayEvent.QuotesSnapshot(quotes)) }
            )
            ibAdapter = DesktopIbGatewayConnection(
                queues = mdQueues,
                connectionMode = IbConnectionMode.MARKET_DATA_ONLY,
                quoteBus = quoteBus,
                scope = scope
            )
            val executionGateway = QueuedBrokerGateway(
                sendCommand = { execQueues.outbound.offer(it) },
                receiveEventBlocking = { execQueues.inbound.take() },
                brokerId = BrokerId.EMULATOR,
                scope = scope
            )
            val marketDataGateway = QueuedBrokerGateway(
                sendCommand = { mdQueues.outbound.offer(it) },
                receiveEventBlocking = { mdQueues.inbound.take() },
                brokerId = BrokerId.INTERACTIVE_BROKERS,
                scope = scope
            )
            return BrokerRuntime(
                kind = BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA,
                gateway = executionGateway,
                marketDataGateway = marketDataGateway,
                ensureLiveMarketData = { symbol, instrument ->
                    ibAdapter.ensureStreamingMarketData(symbol, instrument)
                },
                releaseLiveMarketData = { symbol, instrument ->
                    ibAdapter.releaseStreamingMarketData(symbol, instrument)
                },
                getStreamingMarketDataType = { ibAdapter.currentStreamingMarketDataType() },
                setStreamingMarketDataType = { type -> ibAdapter.setStreamingMarketDataType(type) },
                quoteBus = quoteBus,
                adapters = listOf(emulatorAdapter, ibAdapter),
                queueSets = listOf(execQueues, mdQueues),
                quoteUiRelay = quoteUiRelay
            )
        }
    }
}
