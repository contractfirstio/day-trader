package daytrader.domain

import kotlinx.serialization.Serializable

/** Minimum quantity and lot step from IB [ContractDetails] when available. */
@Serializable
data class InstrumentOrderSizeRules(
    val minOrderSize: Int? = null,
    val orderSizeIncrement: Int? = null
) {
    fun isEmpty(): Boolean = minOrderSize == null && orderSizeIncrement == null
}
