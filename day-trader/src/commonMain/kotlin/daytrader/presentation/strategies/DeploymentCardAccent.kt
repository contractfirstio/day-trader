package daytrader.presentation.strategies

/** Visual accent for instance list/detail cards (border, surface, status chip). */
enum class DeploymentCardAccent {
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

data class DeploymentCardPresentation(
    val accent: DeploymentCardAccent,
    val chipLabel: String
)

/** Active / in-between states pulse; settled win and loss stay solid. */
val DeploymentCardAccent.isPulsing: Boolean
    get() = when (this) {
        DeploymentCardAccent.RUNNING_FLAT,
        DeploymentCardAccent.RUNNING_IN_THE_MONEY,
        DeploymentCardAccent.RUNNING_OUT_OF_THE_MONEY,
        DeploymentCardAccent.STOPPED_NEUTRAL,
        DeploymentCardAccent.OPEN_ORDERS -> true
        else -> false
    }
