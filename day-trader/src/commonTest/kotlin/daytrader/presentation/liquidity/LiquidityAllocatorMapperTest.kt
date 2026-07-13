package daytrader.presentation.liquidity

import daytrader.domain.DeploymentStatus
import daytrader.domain.withOrdersPlacedForSession
import daytrader.e2e.support.E2EBracketHelper
import daytrader.e2e.support.E2ELiquidityAllocatorHelper
import daytrader.e2e.support.E2ETestFixtures
import daytrader.gateway.WorkingOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiquidityAllocatorMapperTest {
    @Test
    fun effectiveEntryQuantity_usesPlannedQuantityWhenGreaterThanOpenOrder() {
        val entry = E2ELiquidityAllocatorHelper.bracketOpenOrders().first { it.parentOrderId == 0 }
        assertEquals(5, entry.quantity)
        assertEquals(10, LiquidityAllocatorMapper.effectiveEntryQuantity(entry, plannedQuantity = 10))
    }

    @Test
    fun buildRowForDeployment_sizesFromPlannedQuantityWhenOpenOrderIsStale() {
        val sessionDate = "2026-06-04"
        val deployment = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment().let { dep ->
            val session = dep.touchTurnSession ?: return@let dep
            dep.copy(
                touchTurnSession = session.copy(
                    sessionDate = sessionDate,
                    plannedQuantity = 10,
                ),
            )
        }
        val row = LiquidityAllocatorMapper.buildRowForDeployment(
            deployment = deployment,
            openOrders = E2ELiquidityAllocatorHelper.bracketOpenOrders(),
            quotes = mapOf("AAPL" to E2ELiquidityAllocatorHelper.touchableQuote()),
            selectedCurrency = "USD",
            allocationAdditionalQty = 2,
        )
        requireNotNull(row)
        assertEquals(10, row.currentQuantity)
        assertTrue(row.previewQuantity > 10)
    }

    @Test
    fun openOrdersWithBracketQuantity_updatesAllBracketLegs() {
        val plan = E2EBracketHelper.liquidityPlan()
        val deployment = E2ETestFixtures.runningDeployment()
            .withOrdersPlacedForSession(plan = plan)
        val openOrders = E2ELiquidityAllocatorHelper.bracketOpenOrders()
        val updated = LiquidityAllocatorMapper.openOrdersWithBracketQuantity(
            openOrders = openOrders,
            deployment = deployment,
            newQuantity = 12,
        )
        assertEquals(3, updated.size)
        assertTrue(updated.all { it.quantity == 12 && it.remaining == 12 })
    }
}
