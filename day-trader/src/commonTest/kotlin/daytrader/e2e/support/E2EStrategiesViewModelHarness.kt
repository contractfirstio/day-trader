package daytrader.e2e.support

import daytrader.data.StrategiesAppStateRepository
import daytrader.domain.DeploymentStatus
import daytrader.engine.TouchTurnEngine
import daytrader.engine.TouchTurnEnginePort
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategiesAppStateRepository
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.execution.BrokerGatewayExecutionManager
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
    val gateway: FakeBrokerGateway,
    val appStateRepository: StrategiesAppStateRepository,
    val marketFilter: MarketFilterState,
    val engine: TouchTurnEnginePort,
    val viewModel: StrategiesViewModel,
    val brokerKind: BrokerKind,
) {
    fun start() {
        gateway.connect()
        engine.start()
    }

    fun shutdown() {
        engine.shutdown()
        gateway.disconnect()
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

    companion object {
        fun create(
            scope: CoroutineScope,
            repository: InMemoryStrategyDeploymentRepository,
            gateway: FakeBrokerGateway,
            brokerKind: BrokerKind = BrokerKind.INTERACTIVE_BROKERS,
            nowEpochMillis: () -> Long = { E2ETestFixtures.BAR_CLOSE_EPOCH_MS },
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
                marketData = BrokerGatewayMarketDataProvider(gateway),
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
            )
        }
    }
}
