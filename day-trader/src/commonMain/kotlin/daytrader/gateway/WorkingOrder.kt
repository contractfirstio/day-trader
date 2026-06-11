package daytrader.gateway

data class WorkingOrder(
    val orderId: Int,
    /** IB permanent order id — links this order to [BrokerFill] rows across sessions. */
    val permId: Long = 0L,
    /** Session order id of the bracket parent; 0 when this order is the parent. */
    val parentOrderId: Int = 0,
    val symbol: String,
    val action: String,
    val quantity: Int,
    val filled: Int,
    val remaining: Int,
    val orderType: String,
    val limitPrice: Double?,
    val stopPrice: Double?,
    val status: String,
    val currency: String,
    /** IB adjustable-stop attachment — hidden from chart level overlays. */
    val isTrailAdjustment: Boolean = false,
    /** Favorable-move trigger that arms trailing (from plan; static on stop leg). */
    val trailTriggerPrice: Double? = null,
    /** Nominal trail distance once trailing is armed. */
    val trailAmount: Double? = null
)
