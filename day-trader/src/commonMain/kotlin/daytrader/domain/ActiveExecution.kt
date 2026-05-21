package daytrader.domain

import kotlinx.serialization.Serializable

@Serializable
data class ActiveExecution(
    val state: ExecutionState = ExecutionState.FLAT,
    val side: TradeSide = TradeSide.LONG,
    val quantity: Int = 0,
    val entryPrice: Double? = null,
    val stopPrice: Double? = null,
    val targetPrice: Double? = null,
    val marketPrice: Double? = null,
    val orderStatus: String = "—",
    val updatedAt: String = "—"
) {
    companion object {
        fun flat(updatedAt: String = "—") = ActiveExecution(updatedAt = updatedAt)
    }
}
