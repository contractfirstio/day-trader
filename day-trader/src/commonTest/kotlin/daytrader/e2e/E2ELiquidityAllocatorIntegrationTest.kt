package daytrader.e2e

import daytrader.e2e.support.E2ELiquidityAllocatorHelper
import daytrader.e2e.support.E2ESyncedOpenOrderRepository
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.InMemoryLiquidityBucketRepository
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.domain.LiquidityBucketLogic
import daytrader.gateway.BrokerId
import daytrader.platform.currentSessionDateIso
import daytrader.presentation.liquidity.LiquidityAllocatorViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * End-to-end: Liquidity Allocator ViewModel wired like [daytrader.ui.AppDependencies].
 */
class E2ELiquidityAllocatorIntegrationTest {
    @Test
    fun viewModel_runningTouchTurnWithOpenEntry_showsAllocatorRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionDate = currentSessionDateIso()
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
            val bucketRepository = InMemoryLiquidityBucketRepository()
            val openOrderRepository = E2ESyncedOpenOrderRepository(gateway, scope)
            E2ELiquidityAllocatorHelper.creditUsdBucket(
                bucketRepository,
                sessionDate = sessionDate,
            )

            gateway.setOpenOrders(E2ELiquidityAllocatorHelper.bracketOpenOrders())
            gateway.setQuotes(mapOf("AAPL" to E2ELiquidityAllocatorHelper.touchableQuote()))

            val deployment = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment().let { dep ->
                val session = dep.touchTurnSession ?: return@let dep
                dep.copy(touchTurnSession = session.copy(sessionDate = sessionDate))
            }
            repository.add(deployment)

            val viewModel = LiquidityAllocatorViewModel(
                deploymentRepository = repository,
                openOrderRepository = openOrderRepository,
                liquidityBucketRepository = bucketRepository,
                brokerGateway = gateway,
                executionManager = BrokerGatewayExecutionManager(gateway),
                scope = scope,
            )
            delay(50)

            val state = viewModel.uiState.value
            assertTrue(state.availableLiquidity > 0)
            assertEquals(1, state.rows.size)
            assertEquals(E2ETestFixtures.DEPLOYMENT_ID, state.rows.single().deploymentId)
            assertEquals(E2ETestFixtures.SYMBOL.uppercase(), state.rows.single().symbol)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun viewModel_applyAllocation_debitsBucketAndUpdatesPlannedQuantity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionDate = currentSessionDateIso()
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
            val bucketRepository = InMemoryLiquidityBucketRepository()
            val openOrderRepository = E2ESyncedOpenOrderRepository(gateway, scope)
            E2ELiquidityAllocatorHelper.creditUsdBucket(
                bucketRepository,
                amount = 1_000,
                sessionDate = sessionDate,
            )

            gateway.setOpenOrders(E2ELiquidityAllocatorHelper.bracketOpenOrders())
            gateway.setQuotes(mapOf("AAPL" to E2ELiquidityAllocatorHelper.touchableQuote()))

            val deployment = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment().let { dep ->
                val session = dep.touchTurnSession ?: return@let dep
                dep.copy(touchTurnSession = session.copy(sessionDate = sessionDate))
            }
            repository.add(deployment)

            val viewModel = LiquidityAllocatorViewModel(
                deploymentRepository = repository,
                openOrderRepository = openOrderRepository,
                liquidityBucketRepository = bucketRepository,
                brokerGateway = gateway,
                executionManager = BrokerGatewayExecutionManager(gateway),
                scope = scope,
            )
            delay(50)

            val allocationDollars = 200
            viewModel.onAllocationChanged(E2ETestFixtures.DEPLOYMENT_ID, allocationDollars)
            viewModel.applyRow(E2ETestFixtures.DEPLOYMENT_ID)
            delay(100)

            val updated = repository.deployments.value.single()
            val plannedQty = updated.touchTurnSession?.plannedQuantity
            assertNotNull(plannedQty)
            assertTrue(plannedQty!! > 0)

            val available = LiquidityBucketLogic.rollBucketForDate(
                LiquidityBucketLogic.bucketForCurrency(bucketRepository.state.value, "USD"),
                sessionDate,
            ).available
            assertEquals(800, available)

            val row = viewModel.uiState.value.rows.singleOrNull()
            assertTrue(row == null || row.allocationDollars == 0)
        } finally {
            scope.cancel()
        }
    }
}
