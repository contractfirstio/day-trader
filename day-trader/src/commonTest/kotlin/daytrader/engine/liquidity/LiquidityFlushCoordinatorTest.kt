package daytrader.engine.liquidity

import daytrader.domain.AUTO_LIQUIDITY_FLUSH_MAX_LOOPS
import daytrader.domain.LiquidityBucketLogic
import daytrader.domain.InstrumentIdentity
import daytrader.domain.TouchTurnBracketOrderIds
import daytrader.domain.withOrdersPlacedForSession
import daytrader.e2e.support.E2EBracketHelper
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
    fun flush_resizesWhenPriceTouchable() = runBlocking {
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

        assertTrue(audit.totalDebited > 0)
        assertTrue(
            LiquidityBucketLogic.rollBucketForDate(
                LiquidityBucketLogic.bucketForCurrency(bucketRepository.state.value, "USD"),
                sessionDate,
            ).available < 500,
        )
    }

    @Test
    fun flush_resizesWithoutLiveQuote_whenOpenOrdersExist() = runBlocking {
        val sessionDate = "2026-06-04"
        val repository = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
        val bucketRepository = InMemoryLiquidityBucketRepository()
        E2ELiquidityAllocatorHelper.creditUsdBucket(bucketRepository, amount = 500, sessionDate = sessionDate)

        gateway.setOpenOrders(E2ELiquidityAllocatorHelper.bracketOpenOrders())
        gateway.setQuotes(emptyMap())

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
                quotes = emptyMap(),
                enabled = true,
            )
        )

        assertTrue(audit.totalDebited > 0)
    }

    @Test
    fun flush_skipsWhenPoolCannotFundBoardLot() = runBlocking {
        val sessionDate = "2026-06-04"
        val repository = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
        val bucketRepository = InMemoryLiquidityBucketRepository()
        E2ELiquidityAllocatorHelper.creditCurrencyBucket(
            repository = bucketRepository,
            currencyCode = "HKD",
            amount = 500,
            sessionDate = sessionDate,
        )

        val hkInstrument = InstrumentIdentity(
            symbol = "939",
            exchange = "SEHK",
            primaryExch = "SEHK",
            currency = "HKD",
            minOrderSize = 1_000,
            orderSizeIncrement = 1_000,
        )
        gateway.setOpenOrders(
            E2ELiquidityAllocatorHelper.bracketOpenOrders(symbol = "939", orderIdBase = 1_000).map {
                it.copy(currency = "HKD")
            },
        )
        gateway.setQuotes(emptyMap())

        val plan = E2EBracketHelper.liquidityPlan(symbol = "939")
        val deployment = E2ETestFixtures.runningDeployment(symbol = "939", sessionDate = sessionDate)
            .copy(
                id = "dep-hk",
                currencyCode = "HKD",
                instrument = hkInstrument,
            )
            .withOrdersPlacedForSession(
                plan = plan,
                bracketOrderIds = TouchTurnBracketOrderIds(
                    parentOrderId = 1_000,
                    takeProfitOrderId = 1_001,
                    stopLossOrderId = 1_002,
                ),
            )
        repository.add(deployment)

        val coordinator = LiquidityFlushCoordinator(
            liquidityBucketRepository = bucketRepository,
            executionManager = BrokerGatewayExecutionManager(gateway),
            deploymentRepository = repository,
        )
        val audit = coordinator.flush(
            LiquidityFlushRequest(
                currencyCode = "HKD",
                sessionDate = sessionDate,
                deployments = listOf(deployment),
                openOrders = gateway.openOrders.value,
                quotes = emptyMap(),
                enabled = true,
            )
        )

        assertEquals(0, audit.totalDebited)
        assertEquals(500, audit.remainingPoolAvailable)
        assertTrue(audit.loops.isNotEmpty())
        assertTrue(audit.loops.all { it.distributionCount == 0 })
        assertTrue(audit.shouldMarkZoneFlushed())
    }

    @Test
    fun flush_allocatesWholeHkBoardLotWhenPoolCanFundIt() = runBlocking {
        val sessionDate = "2026-06-04"
        val repository = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
        val bucketRepository = InMemoryLiquidityBucketRepository()
        E2ELiquidityAllocatorHelper.creditCurrencyBucket(
            repository = bucketRepository,
            currencyCode = "HKD",
            amount = 150_000,
            sessionDate = sessionDate,
        )

        val hkInstrument = InstrumentIdentity(
            symbol = "939",
            exchange = "SEHK",
            primaryExch = "SEHK",
            currency = "HKD",
            minOrderSize = 1_000,
            orderSizeIncrement = 1_000,
        )
        gateway.setOpenOrders(
            E2ELiquidityAllocatorHelper.bracketOpenOrders(symbol = "939", orderIdBase = 1_000).map {
                it.copy(currency = "HKD", quantity = 1_000, remaining = 1_000)
            },
        )
        gateway.setQuotes(emptyMap())

        val plan = E2EBracketHelper.liquidityPlan(symbol = "939")
        val deployment = E2ETestFixtures.runningDeployment(
            symbol = "939",
            sessionDate = sessionDate,
            maxDollars = 100_000,
        )
            .copy(
                id = "dep-hk",
                currencyCode = "HKD",
                instrument = hkInstrument,
            )
            .withOrdersPlacedForSession(
                plan = plan,
                bracketOrderIds = TouchTurnBracketOrderIds(
                    parentOrderId = 1_000,
                    takeProfitOrderId = 1_001,
                    stopLossOrderId = 1_002,
                ),
            )
        repository.add(deployment)

        val coordinator = LiquidityFlushCoordinator(
            liquidityBucketRepository = bucketRepository,
            executionManager = BrokerGatewayExecutionManager(gateway),
            deploymentRepository = repository,
        )
        val audit = coordinator.flush(
            LiquidityFlushRequest(
                currencyCode = "HKD",
                sessionDate = sessionDate,
                deployments = listOf(deployment),
                openOrders = gateway.openOrders.value,
                quotes = emptyMap(),
                enabled = true,
            )
        )

        assertEquals(100_000, audit.totalDebited)
        assertEquals(50_000, audit.remainingPoolAvailable)
        assertEquals(2_000, repository.deployments.value.single().touchTurnSession?.plannedQuantity)
    }

    @Test
    fun flush_capsDebitPerDeploymentAtMaxDollars() = runBlocking {
        val sessionDate = "2026-06-04"
        val repository = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
        val bucketRepository = InMemoryLiquidityBucketRepository()
        E2ELiquidityAllocatorHelper.creditUsdBucket(bucketRepository, amount = 1_000, sessionDate = sessionDate)

        gateway.setOpenOrders(
            E2ELiquidityAllocatorHelper.bracketOpenOrders(symbol = "AAPL", orderIdBase = 2_000) +
                E2ELiquidityAllocatorHelper.bracketOpenOrders(symbol = "MSFT", orderIdBase = 1_000),
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
            dep.copy(
                maxDollars = 50,
                touchTurnSession = dep.touchTurnSession?.copy(sessionDate = sessionDate),
            )
        }
        val unknown = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment(
            symbol = "AAPL",
            deploymentId = "dep-unknown",
        ).let { dep ->
            dep.copy(
                maxDollars = 50,
                touchTurnSession = dep.touchTurnSession?.copy(sessionDate = sessionDate),
            )
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
            ),
        )

        audit.loops.flatMap { it.debited.entries }.forEach { (_, amount) ->
            assertTrue(amount <= 50, "debited $amount exceeds maxDollars cap")
        }
        assertTrue(audit.totalDebited <= 100)
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
                        additionalQuantity = 1,
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

    @Test
    fun flush_secondLoop_doesNotDownsizeAfterFirstSuccessfulApply() = runBlocking {
        val sessionDate = "2026-06-04"
        val repository = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
        val bucketRepository = InMemoryLiquidityBucketRepository()
        E2ELiquidityAllocatorHelper.creditUsdBucket(bucketRepository, amount = 500, sessionDate = sessionDate)

        gateway.setOpenOrders(
            E2ELiquidityAllocatorHelper.bracketOpenOrders(symbol = "AAPL", orderIdBase = 2_000) +
                E2ELiquidityAllocatorHelper.bracketOpenOrders(symbol = "MSFT", orderIdBase = 1_000)
        )
        val safeQuote = { symbol: String ->
            LiveQuote(symbol = symbol, bid = 99.0, ask = 99.5, last = 99.25, quoteEpochMillis = 0L)
        }
        gateway.setQuotes(
            mapOf(
                "AAPL" to E2ELiquidityAllocatorHelper.touchableQuote(symbol = "AAPL"),
                "MSFT" to safeQuote("MSFT"),
            )
        )

        val strong = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment(
            symbol = "MSFT",
            deploymentId = "dep-strong",
            winDays = 8,
            lossDays = 2,
        ).let { dep ->
            dep.copy(touchTurnSession = dep.touchTurnSession?.copy(sessionDate = sessionDate))
        }
        val touchable = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment(
            symbol = "AAPL",
            deploymentId = "dep-touchable",
        ).let { dep ->
            dep.copy(touchTurnSession = dep.touchTurnSession?.copy(sessionDate = sessionDate))
        }
        repository.add(strong)
        repository.add(touchable)

        val coordinator = LiquidityFlushCoordinator(
            liquidityBucketRepository = bucketRepository,
            executionManager = BrokerGatewayExecutionManager(gateway),
            deploymentRepository = repository,
        )
        coordinator.flush(
            LiquidityFlushRequest(
                currencyCode = "USD",
                sessionDate = sessionDate,
                deployments = repository.deployments.value,
                openOrders = gateway.openOrders.value,
                quotes = gateway.quotes.value,
                enabled = true,
            )
        )

        val strongResizes = gateway.bracketResizeRequests.filter { it.symbol.equals("MSFT", ignoreCase = true) }
        assertTrue(strongResizes.size >= 2, "MSFT should resize in at least loop 1 and loop 2")
        strongResizes.zipWithNext { earlier, later ->
            assertTrue(
                later.plan.quantity > earlier.plan.quantity,
                "each resize must upsize from updated quantity, not downsize from stale snapshot",
            )
        }
    }
}
