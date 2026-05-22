package daytrader.domain

data class StrategyRun(
    val id: String,
    val date: String,
    /** ISO local date-time when this run cycle started (e.g. 2026-05-22T09:31:05). */
    val startedAt: String = "",
    /** ISO local date-time when this run cycle stopped; empty while [status] is in progress. */
    val stoppedAt: String = "",
    val pnl: Double,
    val trades: Int,
    val maxAtRisk: Int,
    val status: RunStatus,
    /** Touch Turn: liquidity candle confirmed after bar close. */
    val hadLiquidityCandle: Boolean? = null,
    /** Touch Turn: bracket orders placed/logged for the opening candle. */
    val ordersPlacedForCandle: Boolean? = null,
    /** Whether a broker (or demo) position was open when the run stopped. */
    val positionOpened: Boolean? = null
)
