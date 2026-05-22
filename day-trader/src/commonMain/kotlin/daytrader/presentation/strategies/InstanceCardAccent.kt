package daytrader.presentation.strategies

/** Visual accent for instance list/detail cards (border, surface, status chip). */
enum class InstanceCardAccent {
    ERROR,
    STOPPED_IDLE,
    STOPPED_WIN,
    STOPPED_LOSS,
    STOPPED_NEUTRAL,
    RUNNING_FLAT,
    RUNNING_IN_THE_MONEY,
    RUNNING_OUT_OF_THE_MONEY,
    /** IB has working open orders for this instance's symbol. */
    OPEN_ORDERS
}

data class InstanceCardPresentation(
    val accent: InstanceCardAccent,
    val chipLabel: String
)

/** Active / in-between states pulse; settled win and loss stay solid. */
val InstanceCardAccent.isPulsing: Boolean
    get() = when (this) {
        InstanceCardAccent.RUNNING_FLAT,
        InstanceCardAccent.RUNNING_IN_THE_MONEY,
        InstanceCardAccent.RUNNING_OUT_OF_THE_MONEY,
        InstanceCardAccent.STOPPED_NEUTRAL,
        InstanceCardAccent.OPEN_ORDERS -> true
        else -> false
    }
