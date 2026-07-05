package daytrader.e2e.support

import daytrader.data.StrategyDeploymentRepository
import daytrader.engine.TouchTurnEngine
import daytrader.engine.support.FakeBrokerGateway
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.marketdata.BrokerGatewayMarketDataProvider
import kotlinx.coroutines.CoroutineScope

/**
 * Full IB mode E2E harness using [FakeBrokerGateway] with [BrokerId.INTERACTIVE_BROKERS].
 * Mocks the entire IB interface at the gateway boundary.
 */
class IbModeTestHarness(
    gateway: FakeBrokerGateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
) {
    val gateway: FakeBrokerGateway = gateway

    fun start() {
        gateway.connect()
    }

    fun applyMarketScenario(scenario: TouchTurnMarketScenario) {
        scenario.applyTo(gateway)
    }

    fun shutdown() {
        gateway.disconnect()
    }

    fun createEngine(
        repository: StrategyDeploymentRepository,
        scope: CoroutineScope,
        nowEpochMillis: () -> Long = { E2ETestFixtures.BAR_CLOSE_EPOCH_MS }
    ): TouchTurnEngine {
        val marketData = BrokerGatewayMarketDataProvider(gateway)
        return TouchTurnEngine(
            marketData = marketData,
            execution = BrokerGatewayExecutionManager(gateway),
            repository = repository,
            scope = scope,
            brokerKind = BrokerKind.INTERACTIVE_BROKERS,
            nowEpochMillis = nowEpochMillis,
            sessionGateway = gateway,
            executionGateway = gateway
        )
    }
}
