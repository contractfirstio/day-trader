package daytrader.gateway

import daytrader.broker.DesktopIbGatewayConnection
import daytrader.broker.IbConnectionMode
import daytrader.broker.emulator.BrokerEmulatorConfig
import daytrader.broker.emulator.EmulatorBrokerAdapter
import daytrader.platform.CrashLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

data class BrokerRuntime(
    val kind: BrokerKind,
    val gateway: QueuedBrokerGateway,
    /** IB gateway for Touch Turn ADR / first candle when [kind] is [BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA]. */
    val marketDataGateway: QueuedBrokerGateway? = null,
    /** Subscribes to IB streaming quotes for a symbol (hybrid mode only). */
    val ensureLiveMarketData: ((String) -> Unit)? = null,
    /** Cancels symbol-only streaming when no session needs quotes (hybrid mode only). */
    val releaseLiveMarketData: ((String) -> Unit)? = null,
    private val adapters: List<BrokerAdapter> = emptyList(),
    private val queueSets: List<BlockingGatewayQueues> = emptyList()
) {
    fun start() {
        adapters.forEach { it.start() }
        gateway.connect()
        marketDataGateway?.connect()
    }

    fun shutdown() {
        queueSets.forEach { it.outbound.offer(GatewayCommand.Shutdown) }
        adapters.forEach { it.shutdown() }
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
            else -> createSingle(kind, scope)
        }

        private fun createSingle(
            kind: BrokerKind,
            scope: CoroutineScope
        ): BrokerRuntime {
            val queues = BlockingGatewayQueues()
            val brokerId = BrokerId.from(kind)
            val adapter: BrokerAdapter = when (kind) {
                BrokerKind.INTERACTIVE_BROKERS -> DesktopIbGatewayConnection(queues = queues, scope = scope)
                BrokerKind.EMULATOR -> EmulatorBrokerAdapter(
                    emit = { queues.inbound.offer(it) },
                    receiveCommand = { queues.outbound.take() },
                    config = BrokerEmulatorConfig.fromEnvironment(),
                    scope = scope
                )
                BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA -> error("use createHybrid")
            }
            val gateway = QueuedBrokerGateway(
                sendCommand = { queues.outbound.offer(it) },
                receiveEventBlocking = { queues.inbound.take() },
                brokerId = brokerId,
                scope = scope
            )
            return BrokerRuntime(
                kind = kind,
                gateway = gateway,
                adapters = listOf(adapter),
                queueSets = listOf(queues)
            )
        }

        private fun createHybrid(scope: CoroutineScope): BrokerRuntime {
            val execQueues = BlockingGatewayQueues()
            val mdQueues = BlockingGatewayQueues()
            lateinit var ibAdapter: DesktopIbGatewayConnection
            val emulatorAdapter = EmulatorBrokerAdapter(
                emit = { execQueues.inbound.offer(it) },
                receiveCommand = { execQueues.outbound.take() },
                config = BrokerEmulatorConfig.forLiveIbMarketData(),
                onSymbolNeedsLiveQuotes = { symbol -> ibAdapter.ensureStreamingMarketData(symbol) },
                scope = scope
            )
            ibAdapter = DesktopIbGatewayConnection(
                queues = mdQueues,
                connectionMode = IbConnectionMode.MARKET_DATA_ONLY,
                onLiveMark = { symbol, price, priorClose ->
                    emulatorAdapter.ingestLiveMark(symbol, price, priorClose)
                },
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
                ensureLiveMarketData = { symbol -> ibAdapter.ensureStreamingMarketData(symbol) },
                releaseLiveMarketData = { symbol -> ibAdapter.releaseStreamingMarketData(symbol) },
                adapters = listOf(emulatorAdapter, ibAdapter),
                queueSets = listOf(execQueues, mdQueues)
            )
        }
    }
}
