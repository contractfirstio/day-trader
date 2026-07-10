package daytrader.engine.liquidity

import daytrader.domain.AUTO_LIQUIDITY_FLUSH_MAX_LOOPS
import daytrader.domain.LiquidityBucketLogic
import daytrader.e2e.support.E2ELiquidityAllocatorHelper
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.InMemoryLiquidityBucketRepository
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.gateway.BrokerId
import daytrader.gateway.LiveQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class LiquidityFlushCoordinatorTest {
    @Test
    fun flush_distributesByBayesianWinRate() = runBlocking {
        val sessionDate = "2026-06-04"
        val repository = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
        val bucketRepository = InMemoryLiquidityBucketRepository()
        E2ELiquidityAllocatorHelper.creditUsdBucket(bucketRepository, amount = 100, sessionDate = sessionDate)

        gateway.setOpenOrders(
            E2ELiquidityAllocatorHelper.bracketOpenOrders(symbol = "AAPL", orderIdBase = 2_000) +
                E2ELiquidityAllocatorHelper.bracketOpenOrders(symbol = "MSFT", orderIdBase = 1_000)
        )
        val safeQuote = { symbol: String ->
            LiveQuote(symbol = symbol, bid = 99.0, ask = 99.5, last = 99.25, quoteEpochMillis = 0L)
        }
        gateway.setQuotes(mapOf("AAPL" to safeQuote("AAPL"), "MSFT" to safeQuote("MSFT")))

        val strong = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment(
            symbol = "MSFT",
            deploymentId = "dep-strong",
            winDays = 8,
            lossDays = 2,
        ).let { dep ->
            dep.copy(touchTurnSession = dep.touchTurnSession?.copy(sessionDate = sessionDate))
        }
        val unknown = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment(
            symbol = "AAPL",
            deploymentId = "dep-unknown",
        ).let { dep ->
            dep.copy(touchTurnSession = dep.touchTurnSession?.copy(sessionDate = sessionDate))
        }
        repository.add(strong)
        repository.add(unknown)

        val coordinator = LiquidityFlushCoordinator(
            liquidityBucketRepository = bucketRepository,
            executionManager = BrokerGatewayExecutionManager(gateway),
            deploymentRepository = repository,
        )
        val audit = coordinator.flush(
            LiquidityFlushRequest(
                currencyCode = "USD",
                sessionDate = sessionDate,
                deployments = repository.deployments.value,
                openOrders = gateway.openOrders.value,
                quotes = gateway.quotes.value,
                enabled = true,
            )
        )

        assertTrue(audit.totalDebited > 0)
        val strongLoop = audit.loops.first().debited["dep-strong"] ?: 0
        val unknownLoop = audit.loops.first().debited["dep-unknown"] ?: 0
        assertTrue(strongLoop > unknownLoop)
    }

    @Test
    fun flush_skipsWhenPriceTooCloseToEntry() = runBlocking {
        val sessionDate = "2026-06-04"
        val repository = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
        val bucketRepository = InMemoryLiquidityBucketRepository()
        E2ELiquidityAllocatorHelper.creditUsdBucket(bucketRepository, amount = 500, sessionDate = sessionDate)

        gateway.setOpenOrders(E2ELiquidityAllocatorHelper.bracketOpenOrders())
        gateway.setQuotes(mapOf("AAPL" to E2ELiquidityAllocatorHelper.touchableQuote()))

        val deployment = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment().let { dep ->
            dep.copy(touchTurnSession = dep.touchTurnSession?.copy(sessionDate = sessionDate))
        }
        repository.add(deployment)

        val coordinator = LiquidityFlushCoordinator(
            liquidityBucketRepository = bucketRepository,
            executionManager = BrokerGatewayExecutionManager(gateway),
            deploymentRepository = repository,
        )
        val audit = coordinator.flush(
            LiquidityFlushRequest(
                currencyCode = "USD",
                sessionDate = sessionDate,
                deployments = listOf(deployment),
                openOrders = gateway.openOrders.value,
                quotes = gateway.quotes.value,
                enabled = true,
            )
        )

        assertEquals(0, audit.totalDebited)
        assertTrue(audit.loops.any { E2ETestFixtures.DEPLOYMENT_ID in it.skippedProximity })
        assertEquals(
            500,
            LiquidityBucketLogic.rollBucketForDate(
                LiquidityBucketLogic.bucketForCurrency(bucketRepository.state.value, "USD"),
                sessionDate,
            ).available,
        )
    }

    @Test
    fun flush_loopsMaxThreeTimes() = runBlocking {
        val sessionDate = "2026-06-04"
        val repository = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
        val bucketRepository = InMemoryLiquidityBucketRepository()
        E2ELiquidityAllocatorHelper.creditUsdBucket(bucketRepository, amount = 500, sessionDate = sessionDate)

        gateway.setOpenOrders(E2ELiquidityAllocatorHelper.bracketOpenOrders())
        gateway.setQuotes(mapOf("AAPL" to E2ELiquidityAllocatorHelper.touchableQuote()))

        val deployment = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment().let { dep ->
            dep.copy(touchTurnSession = dep.touchTurnSession?.copy(sessionDate = sessionDate))
        }
        repository.add(deployment)

        val coordinator = LiquidityFlushCoordinator(
            liquidityBucketRepository = bucketRepository,
            executionManager = BrokerGatewayExecutionManager(gateway),
            deploymentRepository = repository,
        )
        val audit = coordinator.flush(
            LiquidityFlushRequest(
                currencyCode = "USD",
                sessionDate = sessionDate,
                deployments = listOf(deployment),
                openOrders = gateway.openOrders.value,
                quotes = gateway.quotes.value,
                enabled = true,
            )
        )

        assertEquals(AUTO_LIQUIDITY_FLUSH_MAX_LOOPS, audit.loops.size)
        assertEquals(500, audit.remainingPoolAvailable)
    }

    @Test
    fun flush_doesNotRunWhenSwitchOff() = runBlocking {
        val sessionDate = "2026-06-04"
        val repository = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
        val bucketRepository = InMemoryLiquidityBucketRepository()
        E2ELiquidityAllocatorHelper.creditUsdBucket(bucketRepository, amount = 500, sessionDate = sessionDate)

        val coordinator = LiquidityFlushCoordinator(
            liquidityBucketRepository = bucketRepository,
            executionManager = BrokerGatewayExecutionManager(gateway),
            deploymentRepository = repository,
        )
        val audit = coordinator.flush(
            LiquidityFlushRequest(
                currencyCode = "USD",
                sessionDate = sessionDate,
                deployments = emptyList(),
                openOrders = emptyList(),
                quotes = emptyMap(),
                enabled = false,
            )
        )
        assertTrue(audit.skippedDisabled)
        assertEquals(0, audit.loops.size)
    }

    @Test
    fun concurrentManualAndFlush_serialized() = runBlocking {
        val sessionDate = "2026-06-04"
        val repository = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
        val bucketRepository = InMemoryLiquidityBucketRepository()
        E2ELiquidityAllocatorHelper.creditUsdBucket(bucketRepository, amount = 300, sessionDate = sessionDate)

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
            dep.copy(touchTurnSession = dep.touchTurnSession?.copy(sessionDate = sessionDate))
        }
        repository.add(deployment)

        val coordinator = LiquidityFlushCoordinator(
            liquidityBucketRepository = bucketRepository,
            executionManager = BrokerGatewayExecutionManager(gateway),
            deploymentRepository = repository,
        )
        val starting = 300
        awaitAll(
            async {
                coordinator.applyAllocation(
                    LiquidityAllocationApplyRequest(
                        deploymentId = E2ETestFixtures.DEPLOYMENT_ID,
                        allocationDollars = 100,
                        deployment = repository.deployments.value.single(),
                        openOrders = gateway.openOrders.value,
                        quotes = gateway.quotes.value,
                        selectedCurrency = "USD",
                        sessionDate = sessionDate,
                    )
                )
            },
            async {
                coordinator.flush(
                    LiquidityFlushRequest(
                        currencyCode = "USD",
                        sessionDate = sessionDate,
                        deployments = listOf(deployment),
                        openOrders = gateway.openOrders.value,
                        quotes = gateway.quotes.value,
                        enabled = true,
                    )
                )
            },
        )
        val remaining = LiquidityBucketLogic.rollBucketForDate(
            LiquidityBucketLogic.bucketForCurrency(bucketRepository.state.value, "USD"),
            sessionDate,
        ).available
        assertTrue(remaining >= 0)
        assertTrue(remaining <= starting)
    }
}
