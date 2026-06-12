package daytrader.domain

import kotlinx.serialization.Serializable

/** Broker order ids for a placed Touch Turn bracket (parent, take-profit, stop, optional adjustable stop). */
@Serializable
data class TouchTurnBracketOrderIds(
    val parentOrderId: Int,
    val takeProfitOrderId: Int,
    val stopLossOrderId: Int,
    val adjustableStopOrderId: Int? = null
) {
    val allIds: List<Int> = buildList {
        add(parentOrderId)
        add(takeProfitOrderId)
        add(stopLossOrderId)
        adjustableStopOrderId?.let { add(it) }
    }

    companion object {
        fun fromAckOrderIds(orderIds: List<Int>): TouchTurnBracketOrderIds? {
            if (orderIds.size < 3) return null
            return TouchTurnBracketOrderIds(
                parentOrderId = orderIds[0],
                takeProfitOrderId = orderIds[1],
                stopLossOrderId = orderIds[2],
                adjustableStopOrderId = orderIds.getOrNull(3)
            )
        }
    }
}

/** Resize an unfilled bracket by modifying leg quantities at the broker. */
data class TouchTurnBracketResizeRequest(
    val symbol: String,
    val currencyCode: String,
    val instrument: InstrumentIdentity?,
    val orderIds: TouchTurnBracketOrderIds,
    val plan: TouchTurnOrderPlan
)
