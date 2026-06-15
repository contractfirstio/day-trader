package daytrader.presentation.strategies

import daytrader.domain.StrategyType
import daytrader.domain.SymbolImportParseError
import daytrader.domain.SymbolImportRow

enum class DeploymentImportRowStatus {
    PENDING,
    RESOLVING,
    SUCCESS,
    FAILED,
    SKIPPED
}

data class DeploymentImportRowUi(
    val symbol: String,
    val exchangeCode: String,
    val marketLabel: String,
    val lineNumber: Int,
    val status: DeploymentImportRowStatus = DeploymentImportRowStatus.PENDING,
    val detail: String? = null,
    val companyName: String? = null
)

enum class DeploymentImportPhase {
    CONFIG,
    IMPORTING,
    COMPLETE
}

enum class SymbolImportTarget {
    DEPLOYMENT,
    WATCHLIST
}

data class DeploymentSymbolImportUiState(
    val phase: DeploymentImportPhase = DeploymentImportPhase.CONFIG,
    val target: SymbolImportTarget = SymbolImportTarget.DEPLOYMENT,
    val filePath: String? = null,
    val parseErrors: List<SymbolImportParseError> = emptyList(),
    val rows: List<DeploymentImportRowUi> = emptyList(),
    val strategyType: StrategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
    val maxDollarsText: String = "",
    val brokerConnected: Boolean = false,
    val watchlistImportEnabled: Boolean = true,
    val completed: Int = 0,
    val total: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0
) {
    val pendingImportCount: Int =
        rows.count { it.status == DeploymentImportRowStatus.PENDING }

    val canStartImport: Boolean =
        phase == DeploymentImportPhase.CONFIG &&
            pendingImportCount > 0 &&
            parseErrors.isEmpty() &&
            when (target) {
                SymbolImportTarget.DEPLOYMENT ->
                    maxDollarsText.toIntOrNull()?.let { it > 0 } == true
                SymbolImportTarget.WATCHLIST -> watchlistImportEnabled
            }

    val canDismiss: Boolean = phase != DeploymentImportPhase.IMPORTING

    val existingSkipLabel: String = when (target) {
        SymbolImportTarget.DEPLOYMENT -> "already deployed"
        SymbolImportTarget.WATCHLIST -> "already in watchlist"
    }
}

fun SymbolImportRow.toImportRowUi(): DeploymentImportRowUi = DeploymentImportRowUi(
    symbol = symbol,
    exchangeCode = exchangeCode,
    marketLabel = daytrader.domain.SymbolImportExchange.marketLabel(marketZoneId),
    lineNumber = lineNumber
)
