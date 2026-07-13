package daytrader.engine.liquidity

data class LiquidityFlushLoopAudit(
    val loopIndex: Int,
    val eligibleCount: Int = 0,
    val distributionCount: Int = 0,
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

    /**
     * Idempotency mark: burn the once-per-zone-day attempt unless broker resizes all failed
     * with the pool still full — those should retry on the next poll.
     */
    fun shouldMarkZoneFlushed(): Boolean {
        if (skippedDisabled || skippedInFlight) return false
        if (skippedEmptyPool) return true
        val failedResizeCount = loops.sumOf { it.failedResize.size }
        if (failedResizeCount > 0 && totalDebited == 0 && remainingPoolAvailable > 0) {
            return false
        }
        return true
    }

    /** Flattened forensic fields for SessionTrace / orphan JSONL. */
    fun toTraceDetails(zoneId: String, markedFlushed: Boolean): Map<String, String> = buildMap {
        put("zoneId", zoneId)
        put("sessionDate", sessionDate)
        put("currency", currencyCode)
        put("startingPool", startingPoolAvailable.toString())
        put("remainingPool", remainingPoolAvailable.toString())
        put("totalDebited", totalDebited.toString())
        put("loopCount", loops.size.toString())
        put("markedFlushed", markedFlushed.toString())
        put("skippedDisabled", skippedDisabled.toString())
        put("skippedEmptyPool", skippedEmptyPool.toString())
        put("skippedInFlight", skippedInFlight.toString())
        if (loops.isNotEmpty()) {
            put(
                "eligibleCounts",
                loops.joinToString(",") { "${it.loopIndex}:${it.eligibleCount}" },
            )
            put(
                "distributionCounts",
                loops.joinToString(",") { "${it.loopIndex}:${it.distributionCount}" },
            )
            put(
                "debitedByDeployment",
                loops.flatMap { loop ->
                    loop.debited.map { (id, amount) -> "L${loop.loopIndex}:$id=$amount" }
                }.joinToString(";"),
            )
            put(
                "skippedLot",
                loops.flatMap { loop ->
                    loop.skippedLot.map { id -> "L${loop.loopIndex}:$id" }
                }.joinToString(";"),
            )
            put(
                "skippedNotEligible",
                loops.flatMap { loop ->
                    loop.skippedNotEligible.map { id -> "L${loop.loopIndex}:$id" }
                }.joinToString(";"),
            )
            put(
                "failedResize",
                loops.flatMap { loop ->
                    loop.failedResize.map { (id, reason) -> "L${loop.loopIndex}:$id=$reason" }
                }.joinToString(";"),
            )
        }
    }
}

data class LiquidityFlushRequest(
    val currencyCode: String,
    val sessionDate: String,
    val deployments: List<daytrader.domain.StrategyDeployment>,
    val openOrders: List<daytrader.gateway.WorkingOrder>,
    val quotes: Map<String, daytrader.gateway.LiveQuote>,
    val enabled: Boolean = true,
)
