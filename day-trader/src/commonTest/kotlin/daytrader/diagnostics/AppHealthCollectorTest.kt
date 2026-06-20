package daytrader.diagnostics

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment
import daytrader.engine.support.FakeBrokerGateway
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals

class AppHealthCollectorTest {
    @Test
    fun collect_includesRunningSessionsAndConnectionState() {
        val gateway = FakeBrokerGateway()
        gateway.connect()
        val deployments = listOf(
            defaultStrategyDeployment(
                strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                symbol = "AAPL",
                maxDollars = 500,
                status = DeploymentStatus.RUNNING,
            ),
            defaultStrategyDeployment(
                strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                symbol = "MSFT",
                maxDollars = 500,
                status = DeploymentStatus.STOPPED,
            ),
        )
        val snapshot = AppHealthCollector.collect(
            brokerKind = BrokerKind.EMULATOR,
            dataDirectory = "/tmp/day-trader/emulator",
            executionGateway = gateway,
            marketDataGateway = null,
            deployments = deployments,
            trackedDataFiles = listOf("/tmp/day-trader/emulator/deployments.json"),
            nowEpochMillis = { 1_700_000_000_000L },
        )
        assertEquals(BrokerKind.EMULATOR.name, snapshot.brokerKind)
        assertEquals("Connected", snapshot.executionConnection)
        assertEquals(1, snapshot.runningSessionCount)
        assertEquals(listOf("AAPL"), snapshot.runningSessionSymbols)
        assertEquals(1, snapshot.trackedDataFiles.size)
    }

    @Test
    fun collect_includesMarketDataConnectionWhenPresent() {
        val execution = FakeBrokerGateway()
        val marketData = FakeBrokerGateway()
        execution.connect()
        marketData.disconnect()
        val snapshot = AppHealthCollector.collect(
            brokerKind = BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA,
            dataDirectory = "/tmp/day-trader/hybrid",
            executionGateway = execution,
            marketDataGateway = marketData,
            deployments = emptyList(),
            trackedDataFiles = emptyList(),
            nowEpochMillis = { 0L },
        )
        assertEquals("Connected", snapshot.executionConnection)
        assertEquals("Disconnected", snapshot.marketDataConnection)
    }

    @Test
    fun collect_surfacesGatewayErrorState() {
        val gateway = FakeBrokerGateway()
        gateway.disconnect()
        val snapshot = AppHealthCollector.collect(
            brokerKind = BrokerKind.INTERACTIVE_BROKERS,
            dataDirectory = "/tmp/day-trader/ib",
            executionGateway = gateway,
            marketDataGateway = null,
            deployments = emptyList(),
            trackedDataFiles = emptyList(),
            nowEpochMillis = { 0L },
        )
        assertEquals("Disconnected", snapshot.executionConnection)
    }
}
