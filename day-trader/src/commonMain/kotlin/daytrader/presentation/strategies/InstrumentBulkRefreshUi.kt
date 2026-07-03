package daytrader.presentation.strategies

data class InstrumentBulkRefreshRowUi(
    val deploymentId: String,
    val symbol: String,
    val status: DeploymentImportRowStatus = DeploymentImportRowStatus.PENDING,
    val detail: String? = null,
)

enum class InstrumentBulkRefreshPhase {
    CONFIRM,
    REFRESHING,
    COMPLETE
}

data class InstrumentBulkRefreshUiState(
    val phase: InstrumentBulkRefreshPhase = InstrumentBulkRefreshPhase.CONFIRM,
    val scopeLabel: String = "",
    val rows: List<InstrumentBulkRefreshRowUi> = emptyList(),
    val brokerConnected: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0
) {
    val canDismiss: Boolean
        get() = phase != InstrumentBulkRefreshPhase.REFRESHING

    val canStart: Boolean
        get() = phase == InstrumentBulkRefreshPhase.CONFIRM &&
            brokerConnected &&
            rows.any { it.status == DeploymentImportRowStatus.PENDING }
}
