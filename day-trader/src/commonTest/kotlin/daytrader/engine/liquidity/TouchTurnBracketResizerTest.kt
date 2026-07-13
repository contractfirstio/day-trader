package daytrader.engine.liquidity

import daytrader.domain.LiquidityBucketLogic
import daytrader.e2e.support.E2ELiquidityAllocatorHelper
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.InMemoryLiquidityBucketRepository
import daytrader.engine.support.FakeBrokerGateway
import daytrader.domain.BracketAmendTarget
import daytrader.domain.TradeSide
import daytrader.domain.WatchlistTradePlan
import daytrader.domain.defaultWatchlist
import daytrader.domain.newWatchlistEntry
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.engine.support.InMemoryWatchlistRepository
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.gateway.BrokerId
import daytrader.platform.currentSessionDateIso
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class TouchTurnBracketResizerTest {
    @Test
    fun amend_resizesWithoutDebitingLiquidityPool() = runBlocking {
        val sessionDate = currentSessionDateIso()
        val repository = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
        val bucketRepository = InMemoryLiquidityBucketRepository()
        E2ELiquidityAllocatorHelper.creditUsdBucket(bucketRepository, amount = 500, sessionDate = sessionDate)

        gateway.setOpenOrders(E2ELiquidityAllocatorHelper.bracketOpenOrders())
        val deployment = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment().let { dep ->
            val session = dep.touchTurnSession ?: return@let dep
            dep.copy(touchTurnSession = session.copy(sessionDate = sessionDate))
        }
        repository.add(deployment)

        val resizer = TouchTurnBracketResizer(
            executionManager = BrokerGatewayExecutionManager(gateway),
            deploymentRepository = repository,
        )
        val result = resizer.amend(
            deploymentId = E2ETestFixtures.DEPLOYMENT_ID,
            deployment = deployment,
            openOrders = gateway.openOrders.value,
            targetQuantity = 10,
        )

        val success = assertIs<TouchTurnBracketAmendResult.Success>(result)
        assertEquals(10, success.newQuantity)
        assertEquals(10, repository.deployments.value.single().touchTurnSession?.plannedQuantity)
        assertEquals(1, gateway.bracketResizeRequests.size)

        val available = LiquidityBucketLogic.rollBucketForDate(
            LiquidityBucketLogic.bucketForCurrency(bucketRepository.state.value, "USD"),
            sessionDate,
        ).available
        assertEquals(500, available, "amend must not debit liquidity pool")
    }

    @Test
    fun amend_stoppedDeploymentStillResizes() = runBlocking {
        val repository = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
        gateway.setOpenOrders(E2ELiquidityAllocatorHelper.bracketOpenOrders())
        val deployment = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment()
            .copy(status = daytrader.domain.DeploymentStatus.STOPPED)
        repository.add(deployment)

        val resizer = TouchTurnBracketResizer(
            executionManager = BrokerGatewayExecutionManager(gateway),
            deploymentRepository = repository,
        )
        val result = resizer.amend(
            target = BracketAmendTarget.Deployment(E2ETestFixtures.DEPLOYMENT_ID),
            openOrders = gateway.openOrders.value,
            targetQuantity = 10,
        )

        assertIs<TouchTurnBracketAmendResult.Success>(result)
        assertEquals(1, gateway.bracketResizeRequests.size)
    }

    @Test
    fun amend_rejectsTargetNotGreaterThanCurrent() = runBlocking {
        val repository = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
        gateway.setOpenOrders(E2ELiquidityAllocatorHelper.bracketOpenOrders())
        val deployment = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment()
        repository.add(deployment)

        val resizer = TouchTurnBracketResizer(
            executionManager = BrokerGatewayExecutionManager(gateway),
            deploymentRepository = repository,
        )
        val result = resizer.amend(
            deploymentId = E2ETestFixtures.DEPLOYMENT_ID,
            deployment = deployment,
            openOrders = gateway.openOrders.value,
            targetQuantity = 5,
        )

        assertIs<TouchTurnBracketAmendResult.Skipped>(result)
        assertTrue(gateway.bracketResizeRequests.isEmpty())
    }

    @Test
    fun amend_watchlistPlan_resizesWithoutRunningSession() = runBlocking {
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
        val orders = E2ELiquidityAllocatorHelper.bracketOpenOrders()
        gateway.setOpenOrders(orders)
        val watchlistRepository = InMemoryWatchlistRepository()
        val entry = newWatchlistEntry(
            symbol = E2ETestFixtures.SYMBOL,
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            companyName = "Apple",
            instrument = null,
        ).copy(
            id = "entry-1",
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
        val watchlist = defaultWatchlist().copy(entries = listOf(entry))
        watchlistRepository.updateWatchlist(watchlist.id) { watchlist }

        val resizer = TouchTurnBracketResizer(
            executionManager = BrokerGatewayExecutionManager(gateway),
            deploymentRepository = InMemoryStrategyDeploymentRepository(),
            watchlistRepository = watchlistRepository,
        )
        val result = resizer.amend(
            target = BracketAmendTarget.WatchlistPlan(
                watchlistId = watchlist.id,
                entryId = entry.id,
                planId = "plan-a",
            ),
            openOrders = orders,
            targetQuantity = 10,
        )

        val success = assertIs<TouchTurnBracketAmendResult.Success>(result)
        assertEquals(10, success.newQuantity)
        assertEquals(1, gateway.bracketResizeRequests.size)
        val updatedPlan = watchlistRepository.watchlists.value.single().entries.single().tradePlans.single()
        assertEquals(1_000.0, updatedPlan.investmentAmount)
    }
}
