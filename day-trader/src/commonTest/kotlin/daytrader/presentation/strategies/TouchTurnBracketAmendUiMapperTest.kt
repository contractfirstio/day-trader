package daytrader.presentation.strategies

import daytrader.domain.BracketAmendTarget
import daytrader.domain.DeploymentStatus
import daytrader.domain.TouchTurnBracketOrderIds
import daytrader.domain.withOrdersPlacedForSession
import daytrader.e2e.support.E2EBracketHelper
import daytrader.e2e.support.E2ELiquidityAllocatorHelper
import daytrader.e2e.support.E2ETestFixtures
import daytrader.domain.defaultWatchlist
import daytrader.domain.newWatchlistEntry
import daytrader.domain.TradeSide
import daytrader.domain.WatchlistTradePlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TouchTurnBracketAmendUiMapperTest {
    @Test
    fun resolveForOrderGroup_matchesRunningTouchTurnDeployment() {
        val orders = E2ELiquidityAllocatorHelper.bracketOpenOrders()
        val deployment = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment()
        val stopped = deployment.copy(status = DeploymentStatus.STOPPED)

        val amend = TouchTurnBracketAmendUiMapper.resolveForOrderGroup(
            symbolKey = "AAPL",
            groupOrders = orders,
            deployments = listOf(stopped, deployment),
            watchlists = emptyList(),
            allOpenOrders = orders,
            isApplying = { false },
            errorFor = { null },
            successFor = { null },
        )

        assertNotNull(amend)
        assertEquals(deployment.id, (amend.target as BracketAmendTarget.Deployment).deploymentId)
        assertEquals(5, amend.currentQuantity)
    }

    @Test
    fun resolveForOrderGroup_supportsStoppedDeploymentWhenBracketOrderIdsMatch() {
        val orders = E2ELiquidityAllocatorHelper.bracketOpenOrders()
        val stopped = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment()
            .copy(status = DeploymentStatus.STOPPED)

        val amend = TouchTurnBracketAmendUiMapper.resolveForOrderGroup(
            symbolKey = "AAPL",
            groupOrders = orders,
            deployments = listOf(stopped),
            watchlists = emptyList(),
            allOpenOrders = orders,
            isApplying = { false },
            errorFor = { null },
            successFor = { null },
        )

        assertNotNull(amend)
        assertEquals(stopped.id, (amend.target as BracketAmendTarget.Deployment).deploymentId)
    }

    @Test
    fun resolveForOrderGroup_prefersBracketParentOrderIdMatch() {
        val orders = E2ELiquidityAllocatorHelper.bracketOpenOrders(orderIdBase = 2_000)
        val entryId = 2_000
        val matched = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment(deploymentId = "matched").let { dep ->
            val session = dep.touchTurnSession ?: return@let dep
            dep.copy(
                id = "matched",
                touchTurnSession = session.copy(
                    bracketOrderIds = TouchTurnBracketOrderIds(
                        parentOrderId = entryId,
                        takeProfitOrderId = entryId + 1,
                        stopLossOrderId = entryId + 2,
                    ),
                ),
            ).withOrdersPlacedForSession(plan = E2EBracketHelper.liquidityPlan())
        }
        val other = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment(deploymentId = "other")

        val deployment = TouchTurnBracketAmendUiMapper.deploymentForOrderGroup(
            symbolKey = E2ETestFixtures.SYMBOL,
            groupOrders = orders,
            deployments = listOf(other, matched),
        )

        assertEquals("matched", deployment?.id)
    }

    @Test
    fun resolveForOrderGroup_returnsNullWhenMultipleDeploymentsAndNoOrderIdMatch() {
        val orders = E2ELiquidityAllocatorHelper.bracketOpenOrders(orderIdBase = 3_000)
        val first = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment(deploymentId = "first")
        val second = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment(deploymentId = "second")

        val deployment = TouchTurnBracketAmendUiMapper.deploymentForOrderGroup(
            symbolKey = E2ETestFixtures.SYMBOL,
            groupOrders = orders,
            deployments = listOf(first, second),
        )

        assertNull(deployment)
    }

    @Test
    fun resolveForOrderGroup_supportsWatchlistPlacedBracketWithoutRunningSession() {
        val orders = E2ELiquidityAllocatorHelper.bracketOpenOrders()
        val entry = newWatchlistEntry(
            symbol = E2ETestFixtures.SYMBOL,
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            companyName = "Apple",
            instrument = null,
        ).copy(
            tradePlans = listOf(
                WatchlistTradePlan(
                    id = "plan-a",
                    label = "Plan A",
                    side = TradeSide.LONG,
                    entryPrice = 100.0,
                    stopPrice = 99.0,
                    targetPrice = 101.0,
                    investmentAmount = 500.0,
                    orderPlacedAtEpochMs = 1L,
                    placedOrderIds = listOf(1_000, 1_001, 1_002),
                )
            )
        )
        val watchlists = listOf(defaultWatchlist().copy(entries = listOf(entry)))

        val amend = TouchTurnBracketAmendUiMapper.resolveForOrderGroup(
            symbolKey = E2ETestFixtures.SYMBOL,
            groupOrders = orders,
            deployments = emptyList(),
            watchlists = watchlists,
            allOpenOrders = orders,
            isApplying = { false },
            errorFor = { null },
            successFor = { null },
        )

        assertNotNull(amend)
        assertIs<BracketAmendTarget.WatchlistPlan>(amend.target)
        assertEquals(5, amend.currentQuantity)
    }
}
