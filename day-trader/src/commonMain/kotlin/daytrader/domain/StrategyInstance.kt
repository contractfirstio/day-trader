package daytrader.domain

data class StrategyInstance(
    val id: String,
    val strategyType: StrategyType,
    val status: InstanceStatus,
    val symbol: String,
    val maxDollars: Int,
    /** When true, the instance is started automatically at RTH open for the symbol's market. */
    val autoStartOnMarketOpen: Boolean = false,
    /** Session date (ISO) of the last automatic market-open start; prevents duplicate starts same day. */
    val lastAutoStartSessionDate: String? = null,
    val performance: List<StrategyRun> = emptyList(),
    val live: ActiveExecution = ActiveExecution(),
    /** First 15-minute RTH candle + derived bracket levels for the active session (Touch Turn only). */
    val touchTurnSession: TouchTurnSessionContext? = null
)
