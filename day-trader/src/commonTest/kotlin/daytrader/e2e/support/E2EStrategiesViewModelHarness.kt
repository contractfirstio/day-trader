package daytrader.e2e.support

import daytrader.data.StrategiesAppStateRepository
import daytrader.domain.DeploymentStatus
import daytrader.domain.InstrumentIdentity
import daytrader.engine.TouchTurnEngine
import daytrader.engine.TouchTurnEnginePort
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategiesAppStateRepository
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerKind
import daytrader.marketdata.BrokerGatewayMarketDataProvider
import daytrader.platform.TradingClock
import daytrader.presentation.markets.MarketFilterState
import daytrader.presentation.strategies.StrategiesViewModel
import daytrader.presentation.strategies.StrategyDetailTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * Wires [StrategiesViewModel] + [TouchTurnEngine] + [FakeBrokerGateway] the same way
 * [daytrader.ui.rememberAppDependencies] does for the strategies screen.
 */
class E2EStrategiesViewModelHarness(
    val repository: InMemoryStrategyDeploymentRepository,
    val gateway: BrokerGateway,
    val appStateRepository: StrategiesAppStateRepository,
    val marketFilter: MarketFilterState,
    val engine: TouchTurnEnginePort,
    val viewModel: StrategiesViewModel,
    val brokerKind: BrokerKind,
    private val onStart: () -> Unit = { gateway.connect() },
    private val onShutdown: () -> Unit = { gateway.disconnect() },
) {
    fun start() {
        onStart()
        engine.start()
    }

    fun shutdown() {
        engine.shutdown()
        onShutdown()
    }

    /** Mirrors [daytrader.ui.App] `DisposableEffect` teardown before broker runtime shutdown. */
    fun simulateApplicationDispose() {
        viewModel.shutdownRunningSessions()
        engine.shutdown()
    }

    fun selectDeployment(deploymentId: String) {
        appStateRepository.update {
            it.copy(
                selectedDeploymentId = deploymentId,
                detailTab = StrategyDetailTab.CONFIGURATION
            )
        }
    }

    suspend fun awaitListRowStatus(
        deploymentId: String,
        status: DeploymentStatus,
        timeoutMs: Long = 15_000
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val row = viewModel.listState.value.filteredRows.find { it.id == deploymentId }
            if (row?.status == status) return
            delay(25)
        }
        val row = viewModel.listState.value.filteredRows.find { it.id == deploymentId }
        error(
            "Timed out after ${timeoutMs}ms waiting for list row status=$status; " +
                "actual=${row?.status} chip=${row?.statusChipLabel}"
        )
    }

    suspend fun awaitDetailTab(tab: StrategyDetailTab, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (viewModel.detailState.value.detailTab == tab) return
            delay(25)
        }
        error(
            "Timed out after ${timeoutMs}ms waiting for detailTab=$tab; " +
                "actual=${viewModel.detailState.value.detailTab}"
        )
    }

    suspend fun awaitStartBlockedAlert(timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (viewModel.chromeState.value.startBlockedAlert != null) return
            delay(25)
        }
        error("Timed out after ${timeoutMs}ms waiting for startBlockedAlert")
    }

    suspend fun awaitListRowPositionPnL(
        deploymentId: String,
        expected: Double,
        timeoutMs: Long = 15_000
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val row = viewModel.listState.value.filteredRows.find { it.id == deploymentId }
            if (row?.positionPnL == expected) return
            delay(25)
        }
        val row = viewModel.listState.value.filteredRows.find { it.id == deploymentId }
        error(
            "Timed out after ${timeoutMs}ms waiting for positionPnL=$expected; actual=${row?.positionPnL}"
        )
    }

    suspend fun awaitListRowTotalPnL(
        deploymentId: String,
        expected: String,
        timeoutMs: Long = 15_000
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val row = viewModel.listState.value.filteredRows.find { it.id == deploymentId }
            if (row?.formattedTotalPnL == expected) return
            delay(25)
        }
        val row = viewModel.listState.value.filteredRows.find { it.id == deploymentId }
        error(
            "Timed out after ${timeoutMs}ms waiting for formattedTotalPnL=$expected; " +
                "actual=${row?.formattedTotalPnL}"
        )
    }

    companion object {
        fun create(
            scope: CoroutineScope,
            repository: InMemoryStrategyDeploymentRepository,
            gateway: FakeBrokerGateway,
            brokerKind: BrokerKind = BrokerKind.INTERACTIVE_BROKERS,
            nowEpochMillis: () -> Long = { E2ETestFixtures.BAR_CLOSE_EPOCH_MS },
        ): E2EStrategiesViewModelHarness =
            createWithGateway(
                scope = scope,
                repository = repository,
                gateway = gateway,
                brokerKind = brokerKind,
                nowEpochMillis = nowEpochMillis,
            )

        fun createWithGateway(
            scope: CoroutineScope,
            repository: InMemoryStrategyDeploymentRepository,
            gateway: BrokerGateway,
            brokerKind: BrokerKind,
            nowEpochMillis: () -> Long = { E2ETestFixtures.BAR_CLOSE_EPOCH_MS },
            ensureLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null,
            releaseLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null,
            onStart: () -> Unit = { gateway.connect() },
            onShutdown: () -> Unit = { gateway.disconnect() },
        ): E2EStrategiesViewModelHarness {
            val appStateRepository = InMemoryStrategiesAppStateRepository()
            val marketFilter = MarketFilterState()
            val tradingClock = object : TradingClock {
                override fun nowEpochMillis(): Long = nowEpochMillis()
                override suspend fun delayMillis(ms: Long) {
                    if (ms > 0) delay(ms)
                }
            }
            val engine = TouchTurnEngine(
                marketData = BrokerGatewayMarketDataProvider(
                    gateway = gateway,
                    ensureLiveMarketData = ensureLiveMarketData,
                    releaseLiveMarketData = releaseLiveMarketData,
                ),
                execution = BrokerGatewayExecutionManager(gateway),
                repository = repository,
                scope = scope,
                brokerKind = brokerKind,
                nowEpochMillis = tradingClock::nowEpochMillis,
                delayMillis = tradingClock::delayMillis,
                sessionGateway = gateway,
                executionGateway = gateway
            )
            val viewModel = StrategiesViewModel(
                repository = repository,
                appStateRepository = appStateRepository,
                marketFilter = marketFilter,
                brokerGateway = gateway,
                touchTurnSessionGateway = gateway,
                brokerKind = brokerKind,
                touchTurnEngine = engine,
                tradingClock = tradingClock,
            )
            return E2EStrategiesViewModelHarness(
                repository = repository,
                gateway = gateway,
                appStateRepository = appStateRepository,
                marketFilter = marketFilter,
                engine = engine,
                viewModel = viewModel,
                brokerKind = brokerKind,
                onStart = onStart,
                onShutdown = onShutdown,
            )
        }

        fun createWithEmulator(
            scope: CoroutineScope,
            repository: InMemoryStrategyDeploymentRepository,
            emulatorHarness: EmulatorModeTestHarness,
        ): E2EStrategiesViewModelHarness {
            val gateway = emulatorHarness.gateway
            return createWithGateway(
                scope = scope,
                repository = repository,
                gateway = gateway,
                brokerKind = BrokerKind.EMULATOR,
                ensureLiveMarketData = { symbol, instrument ->
                    emulatorHarness.adapter.ensureStreamingMarketData(symbol, instrument)
                },
                releaseLiveMarketData = { symbol, instrument ->
                    emulatorHarness.adapter.releaseStreamingMarketData(symbol, instrument)
                },
                onStart = { emulatorHarness.start() },
                onShutdown = { emulatorHarness.shutdown() },
            )
        }
    }
}
