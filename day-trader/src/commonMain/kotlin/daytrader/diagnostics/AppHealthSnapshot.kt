package daytrader.diagnostics

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayConnectionState

data class AppHealthSnapshot(
    val capturedAtEpochMs: Long,
    val brokerKind: String,
    val dataDirectory: String,
    val executionConnection: String,
    val marketDataConnection: String?,
    val runningSessionCount: Int,
    val runningSessionSymbols: List<String>,
    val activeQuoteCount: Int,
    val openOrderCount: Int,
    val openPositionCount: Int,
    val trackedDataFiles: List<String>,
)

object AppHealthCollector {
    fun collect(
        brokerKind: BrokerKind,
        dataDirectory: String,
        executionGateway: BrokerGateway,
        marketDataGateway: BrokerGateway?,
        deployments: List<StrategyDeployment>,
        trackedDataFiles: List<String>,
        nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    ): AppHealthSnapshot {
        val running = deployments.filter { it.status == DeploymentStatus.RUNNING }
        return AppHealthSnapshot(
            capturedAtEpochMs = nowEpochMillis(),
            brokerKind = brokerKind.name,
            dataDirectory = dataDirectory,
            executionConnection = executionGateway.connectionState.value.toLabel(),
            marketDataConnection = marketDataGateway?.connectionState?.value?.toLabel(),
            runningSessionCount = running.size,
            runningSessionSymbols = running.map { it.symbol },
            activeQuoteCount = executionGateway.quotes.value.size,
            openOrderCount = executionGateway.openOrders.value.size,
            openPositionCount = executionGateway.positions.value.size,
            trackedDataFiles = trackedDataFiles,
        )
    }

    private fun GatewayConnectionState.toLabel(): String = when (this) {
        GatewayConnectionState.Disconnected -> "Disconnected"
        GatewayConnectionState.Connecting -> "Connecting"
        GatewayConnectionState.Connected -> "Connected"
        is GatewayConnectionState.Error -> "Error: ${message}"
    }
}
