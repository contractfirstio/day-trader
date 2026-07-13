package daytrader.engine.liquidity

data class LiquidityFlushLoopAudit(
    val loopIndex: Int,
    val debited: Map<String, Int> = emptyMap(),
    val skippedLot: Set<String> = emptySet(),
    val skippedNotEligible: Set<String> = emptySet(),
    val failedResize: Map<String, String> = emptyMap(),
)

data class LiquidityFlushAudit(
    val currencyCode: String,
    val sessionDate: String,
    val startingPoolAvailable: Int,
    val remainingPoolAvailable: Int,
    val loops: List<LiquidityFlushLoopAudit> = emptyList(),
    val skippedDisabled: Boolean = false,
    val skippedEmptyPool: Boolean = false,
    val skippedInFlight: Boolean = false,
) {
    val totalDebited: Int get() = loops.sumOf { it.debited.values.sum() }
}

data class LiquidityFlushRequest(
    val currencyCode: String,
    val sessionDate: String,
    val deployments: List<daytrader.domain.StrategyDeployment>,
    val openOrders: List<daytrader.gateway.WorkingOrder>,
    val quotes: Map<String, daytrader.gateway.LiveQuote>,
    val enabled: Boolean = true,
)
