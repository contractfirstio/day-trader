package daytrader.presentation.strategies

import daytrader.domain.ExecutionState
import daytrader.domain.InstanceStatus

data class LiveExecutionUiState(
    val showPanel: Boolean,
    val isRunning: Boolean,
    val state: ExecutionState,
    val headline: String,
    val entryPrice: String?,
    val stopPrice: String?,
    val targetPrice: String?,
    val formattedRisk: String?,
    val formattedUpside: String?,
    val formattedUnrealized: String?,
    val isUnrealizedPositive: Boolean,
    val riskPercentOfMax: String?
)

data class LiveTradeListSummary(
    val text: String?
)
