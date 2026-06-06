package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchlistTradePlanCalculatorTest {
    @Test
    fun longNotionalSizing_computesPnlAndRMultiple() {
        val plan = WatchlistTradePlan(
            id = "p1",
            label = "Plan A",
            side = TradeSide.LONG,
            entryPrice = 100.0,
            stopPrice = 95.0,
            targetPrice = 110.0,
            investmentAmount = 10_000.0,
            sizingMode = PlanSizingMode.NOTIONAL
        )

        val outcome = WatchlistTradePlanCalculator.compute(plan)

        assertTrue(outcome.errors.isEmpty())
        assertEquals(100, outcome.quantity)
        assertEquals(10_000.0, outcome.notionalAtEntry)
        assertEquals(-500.0, outcome.lossAtStop)
        assertEquals(1_000.0, outcome.profitAtTarget)
        assertEquals(2.0, outcome.rMultiple)
        assertEquals(10.0, outcome.returnAtTargetPct)
        assertEquals(-5.0, outcome.returnAtStopPct)
    }

    @Test
    fun shortRiskBudgetSizing_computesPnl() {
        val plan = WatchlistTradePlan(
            id = "p1",
            label = "Plan B",
            side = TradeSide.SHORT,
            entryPrice = 50.0,
            stopPrice = 55.0,
            targetPrice = 40.0,
            investmentAmount = 500.0,
            sizingMode = PlanSizingMode.RISK_BUDGET
        )

        val outcome = WatchlistTradePlanCalculator.compute(plan)

        assertTrue(outcome.errors.isEmpty())
        assertEquals(100, outcome.quantity)
        assertEquals(-500.0, outcome.lossAtStop)
        assertEquals(1_000.0, outcome.profitAtTarget)
    }

    @Test
    fun invalidLongGeometry_returnsErrors() {
        val plan = WatchlistTradePlan(
            id = "p1",
            label = "Plan A",
            side = TradeSide.LONG,
            entryPrice = 100.0,
            stopPrice = 101.0,
            targetPrice = 110.0,
            investmentAmount = 1_000.0
        )

        val outcome = WatchlistTradePlanCalculator.compute(plan)

        assertTrue(outcome.errors.any { it.contains("below entry") })
    }

    @Test
    fun missingFields_returnsErrors() {
        val plan = WatchlistTradePlan(
            id = "p1",
            label = "Plan A",
            entryPrice = 100.0
        )

        val outcome = WatchlistTradePlanCalculator.compute(plan)

        assertTrue(outcome.errors.isNotEmpty())
        assertEquals(null, outcome.quantity)
    }
}
