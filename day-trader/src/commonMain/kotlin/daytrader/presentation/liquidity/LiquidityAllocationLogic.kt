package daytrader.presentation.liquidity

import daytrader.domain.InstrumentOrderSizeRules

/**
 * Bayesian-shrunk win rate: (winDays + 1) / (tradedDays + 2).
 * Untested configurations start at 50%; thin samples regress toward 50%.
 */
fun bayesianWinRateWeight(winDays: Int, lossDays: Int): Double {
    val wins = winDays.coerceAtLeast(0)
    val losses = lossDays.coerceAtLeast(0)
    return (wins + 1.0) / (wins + losses + 2.0)
}

data class LiquidityAllocationTarget(
    val deploymentId: String,
    val weight: Double,
)

/**
 * Splits [available] dollars across [targets] proportionally by [LiquidityAllocationTarget.weight].
 * Uses largest-remainder rounding so allocations sum exactly to [available].
 */
fun distributeLiquidityByWeight(
    targets: List<LiquidityAllocationTarget>,
    available: Int,
): Map<String, Int> {
    if (targets.isEmpty() || available <= 0) return emptyMap()
    val positiveTargets = targets.filter { it.weight > 0.0 }
    if (positiveTargets.isEmpty()) return emptyMap()

    val totalWeight = positiveTargets.sumOf { it.weight }
    if (totalWeight <= 0.0) return emptyMap()

    data class Share(val deploymentId: String, val floorAmount: Int, val remainder: Double)

    val shares = positiveTargets.map { target ->
        val exact = available * target.weight / totalWeight
        val floorAmount = kotlin.math.floor(exact).toInt()
        Share(target.deploymentId, floorAmount, exact - floorAmount)
    }

    val result = shares.associate { it.deploymentId to it.floorAmount }.toMutableMap()
    var leftover = available - shares.sumOf { it.floorAmount }
    val priority = shares.sortedWith(
        compareByDescending<Share> { it.remainder }.thenBy { it.deploymentId }
    )
    for (index in 0 until leftover) {
        val deploymentId = priority[index].deploymentId
        result[deploymentId] = result.getValue(deploymentId) + 1
    }
    return result.filterValues { it > 0 }
}

fun distributeLiquidityByBayesianWinRate(
    rows: List<Pair<String, Pair<Int, Int>>>,
    available: Int,
): Map<String, Int> {
    val targets = rows.map { (deploymentId, winLoss) ->
        val (winDays, lossDays) = winLoss
        LiquidityAllocationTarget(
            deploymentId = deploymentId,
            weight = bayesianWinRateWeight(winDays, lossDays),
        )
    }
    return distributeLiquidityByWeight(targets, available)
}

data class LiquidityLotAllocationRow(
    val deploymentId: String,
    val winDays: Int,
    val lossDays: Int,
    val entryPrice: Double,
    val orderSizeRules: InstrumentOrderSizeRules,
    val currentQuantity: Int,
)

/**
 * Splits [available] dollars across [rows] by Bayesian win rate in whole upsize lots.
 * Rows whose lot notional exceeds [available] are excluded; leftover budget stays unallocated.
 */
fun distributeLiquidityByBayesianWinRateInLots(
    rows: List<LiquidityLotAllocationRow>,
    available: Int,
): Map<String, Int> =
    lotTargetsFromRows(rows, available).let { targets ->
        distributeLotCountsInWholeLots(targets, available).mapNotNull { (deploymentId, lots) ->
            if (lots <= 0) return@mapNotNull null
            val lotCost = targets.first { it.deploymentId == deploymentId }.lotCost
            deploymentId to lots * lotCost
        }.toMap()
    }

/** Manual allocator: split [available] pool into additional share counts by Bayesian win rate. */
fun distributeAdditionalQuantityByBayesianWinRateInLots(
    rows: List<LiquidityLotAllocationRow>,
    available: Int,
): Map<String, Int> =
    lotTargetsFromRows(rows, available).let { targets ->
        distributeLotCountsInWholeLots(targets, available).mapNotNull { (deploymentId, lots) ->
            if (lots <= 0) return@mapNotNull null
            val lotShares = targets.first { it.deploymentId == deploymentId }.lotShares
            deploymentId to lots * lotShares
        }.toMap()
    }

/** Manual allocator: split affordable whole lots evenly across eligible rows. */
fun distributeAdditionalQuantityEvenlyInLots(
    rows: List<LiquidityLotAllocationRow>,
    available: Int,
): Map<String, Int> {
    val targets = lotTargetsFromRows(rows, available)
    if (targets.isEmpty() || available <= 0) return emptyMap()
    val evenTargets = targets.map { target ->
        target.copy(weight = 1.0)
    }
    return distributeLotCountsInWholeLots(evenTargets, available).mapNotNull { (deploymentId, lots) ->
        if (lots <= 0) return@mapNotNull null
        val lotShares = targets.first { it.deploymentId == deploymentId }.lotShares
        deploymentId to lots * lotShares
    }.toMap()
}

private data class LotAllocationTarget(
    val deploymentId: String,
    val weight: Double,
    val lotCost: Int,
    val lotShares: Int,
)

private fun lotTargetsFromRows(
    rows: List<LiquidityLotAllocationRow>,
    available: Int,
): List<LotAllocationTarget> =
    rows.mapNotNull { row ->
        val lotCost = row.orderSizeRules.additionalLotNotional(row.entryPrice, row.currentQuantity)
        if (lotCost <= 0 || lotCost > available) return@mapNotNull null
        val weight = bayesianWinRateWeight(row.winDays, row.lossDays)
        if (weight <= 0.0) return@mapNotNull null
        LotAllocationTarget(
            deploymentId = row.deploymentId,
            weight = weight,
            lotCost = lotCost,
            lotShares = row.orderSizeRules.additionalLotShares(row.currentQuantity),
        )
    }

private fun distributeLotCountsInWholeLots(
    targets: List<LotAllocationTarget>,
    available: Int,
): Map<String, Int> {
    if (targets.isEmpty() || available <= 0) return emptyMap()
    val totalWeight = targets.sumOf { it.weight }
    if (totalWeight <= 0.0) return emptyMap()

    data class Share(
        val deploymentId: String,
        val lotCost: Int,
        val floorLots: Int,
        val remainder: Double,
    )

    val shares = targets.map { target ->
        val exactLots = available * target.weight / totalWeight / target.lotCost
        val floorLots = kotlin.math.floor(exactLots).toInt()
        Share(
            deploymentId = target.deploymentId,
            lotCost = target.lotCost,
            floorLots = floorLots,
            remainder = exactLots - floorLots,
        )
    }

    val lotCounts = shares.associate { it.deploymentId to it.floorLots }.toMutableMap()
    var leftover = available - shares.sumOf { it.floorLots * it.lotCost }
    val priority = shares.sortedWith(
        compareByDescending<Share> { it.remainder }.thenBy { it.deploymentId }
    )

    while (leftover > 0) {
        val next = priority.firstOrNull { leftover >= it.lotCost } ?: break
        lotCounts[next.deploymentId] = lotCounts.getValue(next.deploymentId) + 1
        leftover -= next.lotCost
    }

    return lotCounts.filterValues { it > 0 }
}
