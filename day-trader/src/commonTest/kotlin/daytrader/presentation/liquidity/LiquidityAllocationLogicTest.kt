package daytrader.presentation.liquidity

import daytrader.domain.InstrumentOrderSizeRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiquidityAllocationLogicTest {
    @Test
    fun bayesianWinRateWeight_clampsNegativeCountsToZero() {
        assertEquals(0.5, bayesianWinRateWeight(winDays = -3, lossDays = -2))
    }

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

    @Test
    fun distributeLiquidityByBayesianWinRateInLots_usUnitLot_matchesDollarDistribution() {
        val rules = InstrumentOrderSizeRules.DEFAULT
        val rows = listOf(
            LiquidityLotAllocationRow(
                deploymentId = "strong",
                winDays = 8,
                lossDays = 2,
                entryPrice = 10.0,
                orderSizeRules = rules,
                currentQuantity = 5,
            ),
            LiquidityLotAllocationRow(
                deploymentId = "unknown",
                winDays = 0,
                lossDays = 0,
                entryPrice = 10.0,
                orderSizeRules = rules,
                currentQuantity = 5,
            ),
        )
        val dollarDistribution = distributeLiquidityByBayesianWinRate(
            rows = rows.map { it.deploymentId to (it.winDays to it.lossDays) },
            available = 100,
        )
        val lotDistribution = distributeLiquidityByBayesianWinRateInLots(rows, available = 100)
        assertEquals(dollarDistribution, lotDistribution)
    }

    @Test
    fun distributeLiquidityByBayesianWinRateInLots_hkBoardLot_favorsHigherWinRateWhenOnlyOneLotFits() {
        val rules = InstrumentOrderSizeRules(minOrderSize = 1_000, orderSizeIncrement = 1_000)
        val distribution = distributeLiquidityByBayesianWinRateInLots(
            rows = listOf(
                LiquidityLotAllocationRow(
                    deploymentId = "strong",
                    winDays = 8,
                    lossDays = 2,
                    entryPrice = 100.0,
                    orderSizeRules = rules,
                    currentQuantity = 1_000,
                ),
                LiquidityLotAllocationRow(
                    deploymentId = "weak",
                    winDays = 0,
                    lossDays = 0,
                    entryPrice = 100.0,
                    orderSizeRules = rules,
                    currentQuantity = 1_000,
                ),
            ),
            available = 150_000,
        )

        assertEquals(mapOf("strong" to 100_000), distribution)
    }

    @Test
    fun distributeLiquidityByBayesianWinRateInLots_hkBoardLot_splitsWholeLotsByWinRate() {
        val rules = InstrumentOrderSizeRules(minOrderSize = 1_000, orderSizeIncrement = 1_000)
        val distribution = distributeLiquidityByBayesianWinRateInLots(
            rows = listOf(
                LiquidityLotAllocationRow(
                    deploymentId = "strong",
                    winDays = 8,
                    lossDays = 2,
                    entryPrice = 100.0,
                    orderSizeRules = rules,
                    currentQuantity = 1_000,
                ),
                LiquidityLotAllocationRow(
                    deploymentId = "weak",
                    winDays = 0,
                    lossDays = 0,
                    entryPrice = 100.0,
                    orderSizeRules = rules,
                    currentQuantity = 1_000,
                ),
            ),
            available = 500_000,
        )

        assertEquals(500_000, distribution.values.sum())
        assertEquals(300_000, distribution.getValue("strong"))
        assertEquals(200_000, distribution.getValue("weak"))
    }

    @Test
    fun distributeLiquidityByBayesianWinRateInLots_excludesSymbolsWhenPoolCannotFundOneLot() {
        val rules = InstrumentOrderSizeRules(minOrderSize = 1_000, orderSizeIncrement = 1_000)
        assertEquals(
            emptyMap(),
            distributeLiquidityByBayesianWinRateInLots(
                rows = listOf(
                    LiquidityLotAllocationRow(
                        deploymentId = "hk",
                        winDays = 1,
                        lossDays = 0,
                        entryPrice = 100.0,
                        orderSizeRules = rules,
                        currentQuantity = 1_000,
                    ),
                ),
                available = 500,
            ),
        )
    }

    @Test
    fun distributeLiquidityByBayesianWinRateInLots_roundRobinLeftover_spreadsAcrossMixedLotCosts() {
        val rows = listOf(
            LiquidityLotAllocationRow(
                deploymentId = "cheap",
                winDays = 0,
                lossDays = 0,
                entryPrice = 4.762,
                orderSizeRules = InstrumentOrderSizeRules(minOrderSize = 200, orderSizeIncrement = 200),
                currentQuantity = 10_400,
            ),
            LiquidityLotAllocationRow(
                deploymentId = "mid",
                winDays = 0,
                lossDays = 0,
                entryPrice = 86.96,
                orderSizeRules = InstrumentOrderSizeRules(minOrderSize = 100, orderSizeIncrement = 100),
                currentQuantity = 500,
            ),
            LiquidityLotAllocationRow(
                deploymentId = "expensive",
                winDays = 0,
                lossDays = 0,
                entryPrice = 119.5,
                orderSizeRules = InstrumentOrderSizeRules(minOrderSize = 100, orderSizeIncrement = 100),
                currentQuantity = 400,
            ),
        )
        val distribution = distributeLiquidityByBayesianWinRateInLots(rows, available = 300_000)

        val cheap = distribution.getValue("cheap")
        val greedyCheapShare = 203_942
        assertTrue(cheap < greedyCheapShare)
        assertTrue(distribution.containsKey("mid"))
        assertTrue(distribution.containsKey("expensive"))
        assertTrue(distribution.values.sum() >= 280_000)
    }

    @Test
    fun distributeLiquidityByBayesianWinRateInLots_maxAllocationDollars_capsSingleDeployment() {
        val rules = InstrumentOrderSizeRules(minOrderSize = 200, orderSizeIncrement = 200)
        val rows = listOf(
            LiquidityLotAllocationRow(
                deploymentId = "cheap",
                winDays = 8,
                lossDays = 2,
                entryPrice = 4.762,
                orderSizeRules = rules,
                currentQuantity = 10_400,
                maxAllocationDollars = 50_000,
            ),
            LiquidityLotAllocationRow(
                deploymentId = "peer",
                winDays = 0,
                lossDays = 0,
                entryPrice = 86.96,
                orderSizeRules = InstrumentOrderSizeRules(minOrderSize = 100, orderSizeIncrement = 100),
                currentQuantity = 500,
                maxAllocationDollars = 50_000,
            ),
        )
        val distribution = distributeLiquidityByBayesianWinRateInLots(rows, available = 300_000)

        assertTrue(distribution.getValue("cheap") <= 50_000)
        assertTrue(distribution.getValue("peer") <= 50_000)
        assertTrue(distribution.values.sum() > 50_000)
    }

    @Test
    fun distributeLiquidityByBayesianWinRateInLots_mixedHkBook_capAndRoundRobin_preventsConcentration() {
        fun row(
            id: String,
            entry: Double,
            min: Int,
            inc: Int,
            qty: Int,
            wins: Int,
            losses: Int,
        ) = LiquidityLotAllocationRow(
            deploymentId = id,
            winDays = wins,
            lossDays = losses,
            entryPrice = entry,
            orderSizeRules = InstrumentOrderSizeRules(minOrderSize = min, orderSizeIncrement = inc),
            currentQuantity = qty,
            maxAllocationDollars = 50_000,
        )

        val rows = listOf(
            row("03033", 4.762, 200, 200, 10_400, 4, 2),
            row("03690", 86.96, 100, 100, 500, 1, 1),
            row("09618", 119.7, 50, 50, 400, 1, 4),
            row("09988", 119.5, 100, 100, 400, 5, 0),
            row("01810", 27.08, 200, 200, 1_800, 1, 4),
            row("00939", 8.33, 1000, 1000, 6_000, 2, 6),
        )
        val distribution = distributeLiquidityByBayesianWinRateInLots(rows, available = 300_000)

        assertTrue((distribution["03033"] ?: 0) <= 50_000)
        assertTrue(distribution.size >= 4)
        assertTrue(distribution.values.max() <= 50_000)
        assertTrue(distribution.values.sum() >= 200_000)
    }
}
