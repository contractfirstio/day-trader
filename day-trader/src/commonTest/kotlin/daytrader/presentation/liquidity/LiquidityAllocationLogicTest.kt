package daytrader.presentation.liquidity

import kotlin.test.Test
import kotlin.test.assertEquals

class LiquidityAllocationLogicTest {
    @Test
    fun bayesianWinRateWeight_usesNeutralPriorForNoHistory() {
        assertEquals(0.5, bayesianWinRateWeight(winDays = 0, lossDays = 0))
    }

    @Test
    fun bayesianWinRateWeight_shrinksPerfectRecordTowardNeutral() {
        assertEquals(2.0 / 3.0, bayesianWinRateWeight(winDays = 1, lossDays = 0))
    }

    @Test
    fun distributeLiquidityByBayesianWinRate_favorsHigherWinRate() {
        val distribution = distributeLiquidityByBayesianWinRate(
            rows = listOf(
                "strong" to (8 to 2),
                "unknown" to (0 to 0),
            ),
            available = 100,
        )

        assertEquals(60, distribution["strong"])
        assertEquals(40, distribution["unknown"])
        assertEquals(100, distribution.values.sum())
    }

    @Test
    fun distributeLiquidityByBayesianWinRate_splitsEvenlyWhenWeightsMatch() {
        val distribution = distributeLiquidityByBayesianWinRate(
            rows = listOf(
                "a" to (4 to 6),
                "b" to (8 to 12),
            ),
            available = 100,
        )

        assertEquals(50, distribution["a"])
        assertEquals(50, distribution["b"])
    }

    @Test
    fun distributeLiquidityByWeight_usesLargestRemainderRounding() {
        val distribution = distributeLiquidityByWeight(
            targets = listOf(
                LiquidityAllocationTarget("a", 1.0),
                LiquidityAllocationTarget("b", 1.0),
                LiquidityAllocationTarget("c", 1.0),
            ),
            available = 100,
        )

        assertEquals(100, distribution.values.sum())
        assertEquals(34, distribution.values.max())
        assertEquals(33, distribution.values.min())
    }

    @Test
    fun distributeLiquidityByBayesianWinRate_returnsEmptyWhenNoBudget() {
        assertEquals(
            emptyMap(),
            distributeLiquidityByBayesianWinRate(
                rows = listOf("a" to (1 to 0)),
                available = 0,
            ),
        )
    }
}
