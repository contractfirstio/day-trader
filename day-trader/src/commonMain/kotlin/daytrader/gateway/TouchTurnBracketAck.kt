package daytrader.gateway

import daytrader.domain.TouchTurnOrderPlan

/** Broker acknowledgment after a Touch Turn bracket is submitted (IB or emulator). */
data class TouchTurnBracketAck(
    val symbol: String,
    val orderIds: List<Int>,
    val result: Result<Unit>,
    val plan: TouchTurnOrderPlan? = null
)
