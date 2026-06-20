package daytrader.presentation.liquidity

/**
 * Bayesian-shrunk win rate: (winDays + 1) / (tradedDays + 2).
 * Untested configurations start at 50%; thin samples regress toward 50%.
 */
fun bayesianWinRateWeight(winDays: Int, lossDays: Int): Double {
    require(winDays >= 0 && lossDays >= 0) { "win/loss counts must be non-negative" }
    return (winDays + 1.0) / (winDays + lossDays + 2.0)
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
