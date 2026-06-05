package daytrader.engine.support

import daytrader.engine.TouchTurnEngine
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.gateway.BrokerGateway
import daytrader.marketdata.BrokerGatewayMarketDataProvider
import daytrader.data.StrategyDeploymentRepository
import kotlinx.coroutines.CoroutineScope

fun testTouchTurnEngine(
    gateway: BrokerGateway,
    repository: StrategyDeploymentRepository,
    scope: CoroutineScope,
    isGlobalAutoStartEnabled: () -> Boolean = { true },
    nowEpochMillis: () -> Long = { System.currentTimeMillis() }
): TouchTurnEngine {
    val marketData = BrokerGatewayMarketDataProvider(gateway)
    val execution = BrokerGatewayExecutionManager(gateway)
    return TouchTurnEngine(
        marketData = marketData,
        execution = execution,
        repository = repository,
        scope = scope,
        isGlobalAutoStartEnabled = isGlobalAutoStartEnabled,
        nowEpochMillis = nowEpochMillis,
        sessionGateway = gateway,
        executionGateway = gateway
    )
}
