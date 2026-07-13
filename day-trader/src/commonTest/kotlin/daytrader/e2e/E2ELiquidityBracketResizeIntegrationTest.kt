package daytrader.e2e

import daytrader.domain.LiquidityBucketLogic
import daytrader.domain.withOrdersPlacedForSession
import daytrader.e2e.support.E2EBracketHelper
import daytrader.e2e.support.E2ELiquidityAllocatorHelper
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.EmulatorModeTestHarness
import daytrader.e2e.support.IbModeTestHarness
import daytrader.e2e.support.InMemoryLiquidityBucketRepository
import daytrader.e2e.support.shutdownEmulatorHarness
import daytrader.engine.liquidity.LiquidityAllocationApplier
import daytrader.engine.liquidity.LiquidityAllocationApplyRequest
import daytrader.engine.liquidity.LiquidityAllocationApplyResult
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.gateway.BrokerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * E2E bracket resize through execution + liquidity applier (emulator engine and IB-mode gateway).
 */
class E2ELiquidityBracketResizeIntegrationTest {
    @E2EEmulatorTest
    @Test
    fun emulator_applyAllocation_resizesAllWorkingBracketLegs() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var harness: EmulatorModeTestHarness? = null
        try {
            harness = EmulatorModeTestHarness.neverFillEntry(scope)
            harness.start()

            val repository = InMemoryStrategyDeploymentRepository()
            val bucketRepository = InMemoryLiquidityBucketRepository()
            val sessionDate = "2026-06-04"
            E2ELiquidityAllocatorHelper.creditUsdBucket(
                bucketRepository,
                amount = 1_000,
                sessionDate = sessionDate,
            )

            val plan = E2EBracketHelper.liquidityPlan()
            harness.gateway.placeTouchTurnBracket(plan)

            withTimeout(5_000) {
                while (harness.gateway.openOrders.value.none { it.parentOrderId == 0 && it.remaining > 0 }) {
                    delay(50)
                }
            }

            val deployment = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment().let { dep ->
                val session = dep.touchTurnSession ?: return@let dep
                dep.copy(touchTurnSession = session.copy(sessionDate = sessionDate))
            }.withOrdersPlacedForSession(plan = plan)
            repository.add(deployment)

            val entryBefore = harness.gateway.openOrders.value.first { it.parentOrderId == 0 }
            val applier = LiquidityAllocationApplier(
                liquidityBucketRepository = bucketRepository,
                executionManager = BrokerGatewayExecutionManager(harness.gateway),
                deploymentRepository = repository,
            )
            val result = applier.apply(
                LiquidityAllocationApplyRequest(
                    deploymentId = E2ETestFixtures.DEPLOYMENT_ID,
                    additionalQuantity = 2,
                    deployment = repository.deployments.value.single(),
                    openOrders = harness.gateway.openOrders.value,
                    quotes = mapOf("AAPL" to E2ELiquidityAllocatorHelper.touchableQuote()),
                    selectedCurrency = "USD",
                    sessionDate = sessionDate,
                )
            )
            val success = assertIs<LiquidityAllocationApplyResult.Success>(result)
            assertTrue(success.newQuantity > entryBefore.quantity)

            withTimeout(5_000) {
                while (harness.gateway.openOrders.value.none { it.parentOrderId == 0 && it.quantity == success.newQuantity }) {
                    delay(50)
                }
            }
            val legs = harness.gateway.openOrders.value.filter {
                E2ETestFixtures.SYMBOL.equals(it.symbol, ignoreCase = true)
            }
            assertTrue(legs.size >= 3)
            assertTrue(legs.all { it.quantity == success.newQuantity })

            val available = LiquidityBucketLogic.rollBucketForDate(
                LiquidityBucketLogic.bucketForCurrency(bucketRepository.state.value, "USD"),
                sessionDate,
            ).available
            assertEquals(1_000 - success.debitedAmount, available)
        } finally {
            harness.shutdownEmulatorHarness()
            scope.cancel()
        }
    }

    @E2EIbTest
    @Test
    fun ibMode_applyAllocation_resizesBracketViaPlaceOrderPattern() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val harness = IbModeTestHarness()
        try {
            harness.start()
            val repository = InMemoryStrategyDeploymentRepository()
            val bucketRepository = InMemoryLiquidityBucketRepository()
            val sessionDate = "2026-06-04"
            E2ELiquidityAllocatorHelper.creditUsdBucket(
                bucketRepository,
                amount = 500,
                sessionDate = sessionDate,
            )

            harness.gateway.setOpenOrders(E2ELiquidityAllocatorHelper.bracketOpenOrders())
            harness.gateway.setQuotes(
                mapOf("AAPL" to E2ELiquidityAllocatorHelper.touchableQuote())
            )

            val deployment = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment().let { dep ->
                val session = dep.touchTurnSession ?: return@let dep
                dep.copy(touchTurnSession = session.copy(sessionDate = sessionDate))
            }
            repository.add(deployment)

            val entryBefore = harness.gateway.openOrders.value.first { it.parentOrderId == 0 }
            val applier = LiquidityAllocationApplier(
                liquidityBucketRepository = bucketRepository,
                executionManager = BrokerGatewayExecutionManager(harness.gateway),
                deploymentRepository = repository,
            )
            val result = applier.apply(
                LiquidityAllocationApplyRequest(
                    deploymentId = E2ETestFixtures.DEPLOYMENT_ID,
                    additionalQuantity = 2,
                    deployment = deployment,
                    openOrders = harness.gateway.openOrders.value,
                    quotes = harness.gateway.quotes.value,
                    selectedCurrency = "USD",
                    sessionDate = sessionDate,
                )
            )
            val success = assertIs<LiquidityAllocationApplyResult.Success>(result)
            assertTrue(success.newQuantity > entryBefore.quantity)
            assertEquals(1, harness.gateway.bracketResizeRequests.size)

            val resize = harness.gateway.bracketResizeRequests.single()
            assertEquals(listOf(1_000, 1_001, 1_002), resize.orderIds.allIds)
            assertTrue(resize.plan.orders.all { it.quantity == success.newQuantity })

            val entryAfter = harness.gateway.openOrders.value.first { it.parentOrderId == 0 }
            assertEquals(success.newQuantity, entryAfter.quantity)
        } finally {
            harness.shutdown()
            scope.cancel()
        }
    }
}
