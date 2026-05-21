package daytrader.domain

data class StrategyInstance(
    val id: String,
    val strategyType: StrategyType,
    val status: InstanceStatus,
    val symbol: String,
    val maxDollars: Int,
    val performance: List<StrategyRun> = emptyList(),
    val live: ActiveExecution = ActiveExecution()
)
