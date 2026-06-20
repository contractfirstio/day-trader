package daytrader.e2e.support

import daytrader.broker.emulator.BrokerEmulatorConfig
import daytrader.broker.emulator.EmulatorBrokerAdapter
import daytrader.broker.emulator.TouchTurnEntryScenario
import daytrader.data.StrategyDeploymentRepository
import daytrader.engine.TouchTurnEngine
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayCommand
import daytrader.gateway.QueuedBrokerGateway
import daytrader.marketdata.BrokerGatewayMarketDataProvider
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CoroutineScope

class EmulatorModeTestHarness(
    private val scope: CoroutineScope,
    config: BrokerEmulatorConfig = BrokerEmulatorConfig(
        connectDelayMs = 1,
        marketTickIntervalMs = 50,
        firstCandleSecondsUntilClose = null,
        simulateOrderProgress = false
    )
) {
    private val inbound = LinkedBlockingQueue<daytrader.gateway.GatewayEvent>()
    private val outbound = LinkedBlockingQueue<GatewayCommand>()

    val adapter = EmulatorBrokerAdapter(
        emit = { event -> inbound.offer(event) },
        receiveCommand = { outbound.take() },
        config = config,
        scope = scope
    )

    val gateway = QueuedBrokerGateway(
        sendCommand = { command -> outbound.offer(command) },
        receiveEventBlocking = { inbound.take() },
        brokerId = BrokerId.EMULATOR,
        scope = scope
    )

    fun start() {
        adapter.start()
        gateway.connect()
    }

    fun shutdown() {
        adapter.shutdown()
        gateway.disconnect()
    }

    fun createEngine(
        repository: StrategyDeploymentRepository,
        nowEpochMillis: () -> Long = { E2ETestFixtures.BAR_CLOSE_EPOCH_MS }
    ): TouchTurnEngine {
        val marketData = BrokerGatewayMarketDataProvider(
            gateway = gateway,
            ensureLiveMarketData = { symbol, instrument ->
                adapter.ensureStreamingMarketData(symbol, instrument)
            },
            releaseLiveMarketData = { symbol, instrument ->
                adapter.releaseStreamingMarketData(symbol, instrument)
            }
        )
        return TouchTurnEngine(
            marketData = marketData,
            execution = BrokerGatewayExecutionManager(gateway),
            repository = repository,
            scope = scope,
            brokerKind = BrokerKind.EMULATOR,
            nowEpochMillis = nowEpochMillis,
            sessionGateway = gateway,
            executionGateway = gateway
        )
    }

    companion object {
        fun immediateEntry(scope: CoroutineScope) = EmulatorModeTestHarness(
            scope = scope,
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                marketTickIntervalMs = 50,
                firstCandleSecondsUntilClose = null,
                simulateOrderProgress = false,
                touchTurnEntryFillImmediately = true,
                touchTurnEntryScenarioOverride = TouchTurnEntryScenario.IMMEDIATE
            )
        )

        fun neverFillEntry(scope: CoroutineScope) = EmulatorModeTestHarness(
            scope = scope,
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                marketTickIntervalMs = 50,
                firstCandleSecondsUntilClose = null,
                simulateOrderProgress = false,
                touchTurnEntryScenarioOverride = TouchTurnEntryScenario.NEVER_FILL
            )
        )

        /** Deterministic entry fill + bracket walk for full trade-cycle E2E tests. */
        fun fullTradeLifecycle(scope: CoroutineScope) = EmulatorModeTestHarness(
            scope = scope,
            config = tradeLifecycleConfig(bracketExitTakeProfitProbability = 1.0)
        )

        /** Same as [fullTradeLifecycle] but steers bracket walk toward stop loss. */
        fun stopLossLifecycle(scope: CoroutineScope) = EmulatorModeTestHarness(
            scope = scope,
            config = tradeLifecycleConfig(bracketExitTakeProfitProbability = 0.0)
        )

        /** Wider bracket walk tuned for trailing-stop conversion before take-profit exit. */
        fun trailingStopLifecycle(scope: CoroutineScope) = EmulatorModeTestHarness(
            scope = scope,
            config = tradeLifecycleConfig(
                bracketExitTakeProfitProbability = 1.0,
                bracketWalkStepPctOfRange = 0.05,
                bracketExitSpreadWidenFactor = 1.0,
            )
        )

        private fun tradeLifecycleConfig(
            bracketExitTakeProfitProbability: Double,
            bracketWalkStepPctOfRange: Double = 0.2,
            bracketExitSpreadWidenFactor: Double = 1.35,
        ) = BrokerEmulatorConfig(
            connectDelayMs = 1,
            marketTickIntervalMs = 25,
            firstCandleSecondsUntilClose = null,
            simulateOrderProgress = false,
            touchTurnEntryFillImmediately = true,
            touchTurnEntryScenarioOverride = TouchTurnEntryScenario.IMMEDIATE,
            bracketWalkStepPctOfRange = bracketWalkStepPctOfRange,
            bracketWalkDirectionFlipChance = 0.0,
            bracketExitTakeProfitProbability = bracketExitTakeProfitProbability,
            bracketWalkSteerTowardTargetProbability = 1.0,
            bracketExitSpreadWidenFactor = bracketExitSpreadWidenFactor,
        )
    }
}
