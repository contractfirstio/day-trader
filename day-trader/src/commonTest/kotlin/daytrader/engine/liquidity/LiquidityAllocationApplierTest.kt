package daytrader.engine.liquidity

import daytrader.domain.LiquidityBucketLogic
import daytrader.e2e.support.E2ELiquidityAllocatorHelper
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.InMemoryLiquidityBucketRepository
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.gateway.BrokerId
import daytrader.gateway.LiveQuote
import daytrader.platform.currentSessionDateIso
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class LiquidityAllocationApplierTest {
    @Test
    fun applier_debitsEffectiveNotionalNotWeight() = runBlocking {
        val sessionDate = currentSessionDateIso()
        val repository = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
        val bucketRepository = InMemoryLiquidityBucketRepository()
        E2ELiquidityAllocatorHelper.creditUsdBucket(bucketRepository, amount = 1_000, sessionDate = sessionDate)

        gateway.setOpenOrders(E2ELiquidityAllocatorHelper.bracketOpenOrders())
        val safeQuote = LiveQuote(
            symbol = "AAPL",
            bid = 99.0,
            ask = 99.5,
            last = 99.25,
            quoteEpochMillis = 0L,
        )
        gateway.setQuotes(mapOf("AAPL" to safeQuote))

        val deployment = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment().let { dep ->
            val session = dep.touchTurnSession ?: return@let dep
            dep.copy(touchTurnSession = session.copy(sessionDate = sessionDate))
        }
        repository.add(deployment)

        val applier = LiquidityAllocationApplier(
            liquidityBucketRepository = bucketRepository,
            executionManager = BrokerGatewayExecutionManager(gateway),
            deploymentRepository = repository,
        )
        val result = applier.apply(
            LiquidityAllocationApplyRequest(
                deploymentId = E2ETestFixtures.DEPLOYMENT_ID,
                additionalQuantity = 2,
                deployment = deployment,
                openOrders = gateway.openOrders.value,
                quotes = gateway.quotes.value,
                selectedCurrency = "USD",
                sessionDate = sessionDate,
            )
        )

        val success = assertIs<LiquidityAllocationApplyResult.Success>(result)
        assertEquals(2 * 100, success.debitedAmount)
        val available = LiquidityBucketLogic.rollBucketForDate(
            LiquidityBucketLogic.bucketForCurrency(bucketRepository.state.value, "USD"),
            sessionDate,
        ).available
        assertEquals(1_000 - success.debitedAmount, available)
    }

    @Test
    fun applier_refundsOnResizeFailure() = runBlocking {
        val sessionDate = currentSessionDateIso()
        val repository = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR).apply {
            bracketResizeResult = Result.failure(IllegalStateException("resize_failed"))
        }
        val bucketRepository = InMemoryLiquidityBucketRepository()
        E2ELiquidityAllocatorHelper.creditUsdBucket(bucketRepository, amount = 500, sessionDate = sessionDate)

        gateway.setOpenOrders(E2ELiquidityAllocatorHelper.bracketOpenOrders())
        gateway.setQuotes(
            mapOf(
                "AAPL" to LiveQuote(
                    symbol = "AAPL",
                    bid = 99.0,
                    ask = 99.5,
                    last = 99.25,
                    quoteEpochMillis = 0L,
                )
            )
        )

        val deployment = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment().let { dep ->
            val session = dep.touchTurnSession ?: return@let dep
            dep.copy(touchTurnSession = session.copy(sessionDate = sessionDate))
        }
        repository.add(deployment)

        val applier = LiquidityAllocationApplier(
            liquidityBucketRepository = bucketRepository,
            executionManager = BrokerGatewayExecutionManager(gateway),
            deploymentRepository = repository,
        )
        val result = applier.apply(
            LiquidityAllocationApplyRequest(
                deploymentId = E2ETestFixtures.DEPLOYMENT_ID,
                additionalQuantity = 2,
                deployment = deployment,
                openOrders = gateway.openOrders.value,
                quotes = gateway.quotes.value,
                selectedCurrency = "USD",
                sessionDate = sessionDate,
            )
        )
        assertIs<LiquidityAllocationApplyResult.Failed>(result)
        assertTrue(result.message.contains("resize_failed"))
        assertEquals(500, LiquidityBucketLogic.rollBucketForDate(
            LiquidityBucketLogic.bucketForCurrency(bucketRepository.state.value, "USD"),
            sessionDate,
        ).available)
    }

    @Test
    fun applier_usesSharedBracketResizerWhenBrokerQtyLagsPlannedQuantity() = runBlocking {
        val sessionDate = currentSessionDateIso()
        val repository = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
        val bucketRepository = InMemoryLiquidityBucketRepository()
        E2ELiquidityAllocatorHelper.creditUsdBucket(bucketRepository, amount = 1_000, sessionDate = sessionDate)

        gateway.setOpenOrders(E2ELiquidityAllocatorHelper.bracketOpenOrders())
        gateway.setQuotes(
            mapOf(
                "AAPL" to LiveQuote(
                    symbol = "AAPL",
                    bid = 99.0,
                    ask = 99.5,
                    last = 99.25,
                    quoteEpochMillis = 0L,
                )
            )
        )

        val deployment = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment().let { dep ->
            val session = dep.touchTurnSession ?: return@let dep
            dep.copy(
                touchTurnSession = session.copy(
                    sessionDate = sessionDate,
                    plannedQuantity = 10,
                ),
            )
        }
        repository.add(deployment)

        val applier = LiquidityAllocationApplier(
            liquidityBucketRepository = bucketRepository,
            executionManager = BrokerGatewayExecutionManager(gateway),
            deploymentRepository = repository,
        )
        val result = applier.apply(
            LiquidityAllocationApplyRequest(
                deploymentId = E2ETestFixtures.DEPLOYMENT_ID,
                additionalQuantity = 2,
                deployment = deployment,
                openOrders = gateway.openOrders.value,
                quotes = gateway.quotes.value,
                selectedCurrency = "USD",
                sessionDate = sessionDate,
            )
        )

        val success = assertIs<LiquidityAllocationApplyResult.Success>(result)
        assertTrue(
            success.newQuantity > 5,
            "Should upsize from broker qty 5 even when plannedQuantity is 10",
        )
        assertEquals(1, gateway.bracketResizeRequests.size)
        assertEquals(success.newQuantity, gateway.bracketResizeRequests.single().plan.quantity)
        assertEquals(
            success.newQuantity,
            repository.deployments.value.single().touchTurnSession?.plannedQuantity,
        )
    }

    @Test
    fun applier_skipsWithoutDebitWhenPreviewDoesNotExceedBrokerQty() = runBlocking {
        val sessionDate = currentSessionDateIso()
        val repository = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
        val bucketRepository = InMemoryLiquidityBucketRepository()
        E2ELiquidityAllocatorHelper.creditUsdBucket(bucketRepository, amount = 500, sessionDate = sessionDate)

        val openOrders = E2ELiquidityAllocatorHelper.bracketOpenOrders()
        gateway.setOpenOrders(openOrders)
        gateway.setQuotes(
            mapOf(
                "AAPL" to LiveQuote(
                    symbol = "AAPL",
                    bid = 99.0,
                    ask = 99.5,
                    last = 99.25,
                    quoteEpochMillis = 0L,
                )
            )
        )

        val brokerQty = openOrders.first { it.parentOrderId == 0 }.remaining
        val deployment = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment().let { dep ->
            val session = dep.touchTurnSession ?: return@let dep
            dep.copy(
                touchTurnSession = session.copy(
                    sessionDate = sessionDate,
                    plannedQuantity = brokerQty,
                ),
            )
        }
        repository.add(deployment)

        val applier = LiquidityAllocationApplier(
            liquidityBucketRepository = bucketRepository,
            executionManager = BrokerGatewayExecutionManager(gateway),
            deploymentRepository = repository,
        )
        val result = applier.apply(
            LiquidityAllocationApplyRequest(
                deploymentId = E2ETestFixtures.DEPLOYMENT_ID,
                additionalQuantity = 0,
                deployment = deployment,
                openOrders = gateway.openOrders.value,
                quotes = gateway.quotes.value,
                selectedCurrency = "USD",
                sessionDate = sessionDate,
            )
        )
        assertIs<LiquidityAllocationApplyResult.Skipped>(result)
        assertEquals(LiquidityApplySkipReason.NO_ADDITIONAL_QUANTITY, result.reason)
        assertEquals(500, LiquidityBucketLogic.rollBucketForDate(
            LiquidityBucketLogic.bucketForCurrency(bucketRepository.state.value, "USD"),
            sessionDate,
        ).available)
        assertTrue(gateway.bracketResizeRequests.isEmpty())
    }
}
