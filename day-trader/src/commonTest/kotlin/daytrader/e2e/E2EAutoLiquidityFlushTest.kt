package daytrader.e2e

import daytrader.data.AutoLiquidityFlushLogic
import daytrader.domain.AUTO_LIQUIDITY_FLUSH_OFFSET_MS
import daytrader.domain.LiquidityBucketLogic
import daytrader.domain.RthMarketSessions
import daytrader.domain.TouchTurnLogic
import daytrader.e2e.support.E2ELiquidityAllocatorHelper
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.InMemoryLiquidityBucketRepository
import daytrader.engine.TouchTurnCommand
import daytrader.engine.TouchTurnEngine
import daytrader.engine.liquidity.LiquidityFlushCoordinator
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategiesAppStateRepository
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.gateway.LiveQuote
import daytrader.marketdata.BrokerGatewayMarketDataProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

@E2EEmulatorTest
class E2EAutoLiquidityFlushTest {
    @Test
    fun engine_evaluateAutoLiquidityFlush_redistributesPoolAndDebitsBucket() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionDate = "2026-06-04"
        val zoneId = RthMarketSessions.US.zoneId
        val now = flushEpochMillis(sessionDate, zoneId)
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
            val bucketRepository = InMemoryLiquidityBucketRepository()
            val appStateRepository = InMemoryStrategiesAppStateRepository()
            E2ELiquidityAllocatorHelper.creditUsdBucket(
                bucketRepository,
                amount = 500,
                sessionDate = sessionDate,
            )

            gateway.setOpenOrders(E2ELiquidityAllocatorHelper.bracketOpenOrders())
            gateway.setQuotes(
                mapOf(
                    "AAPL" to LiveQuote(
                        symbol = "AAPL",
                        bid = 99.0,
                        ask = 99.5,
                        last = 99.25,
                        quoteEpochMillis = now,
                    )
                )
            )

            val deployment = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment().let { dep ->
                dep.copy(touchTurnSession = dep.touchTurnSession?.copy(sessionDate = sessionDate))
            }
            repository.add(deployment)
            appStateRepository.update { it.copy(autoLiquidityFlushEnabled = true) }

            val coordinator = LiquidityFlushCoordinator(
                liquidityBucketRepository = bucketRepository,
                executionManager = BrokerGatewayExecutionManager(gateway),
                deploymentRepository = repository,
            )
            val engine = TouchTurnEngine(
                marketData = BrokerGatewayMarketDataProvider(gateway),
                execution = BrokerGatewayExecutionManager(gateway),
                repository = repository,
                scope = scope,
                brokerKind = BrokerKind.EMULATOR,
                isAutoLiquidityFlushEnabled = { appStateRepository.state.value.autoLiquidityFlushEnabled },
                nowEpochMillis = { now },
                sessionGateway = gateway,
                executionGateway = gateway,
                liquidityBucketRepository = bucketRepository,
                liquidityFlushCoordinator = coordinator,
                strategiesAppStateRepository = appStateRepository,
            )
            engine.updateAutoLiquidityFlushEnabled(true)
            engine.start()

            engine.dispatch(TouchTurnCommand.EvaluateAutoLiquidityFlush)
            delay(200)

            val plannedQty = repository.deployments.value.single().touchTurnSession?.plannedQuantity
            assertTrue(plannedQty != null && plannedQty > 5)

            val available = LiquidityBucketLogic.rollBucketForDate(
                LiquidityBucketLogic.bucketForCurrency(bucketRepository.state.value, "USD"),
                sessionDate,
            ).available
            assertTrue(available < 500)

            assertTrue(
                appStateRepository.state.value.flushedLiquidityZoneDates.contains(
                    AutoLiquidityFlushLogic.flushKey(zoneId, sessionDate)
                )
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun engine_autoLiquidityFlushDisabled_doesNotRedistribute() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionDate = "2026-06-04"
        val zoneId = RthMarketSessions.US.zoneId
        val now = flushEpochMillis(sessionDate, zoneId)
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR)
            val bucketRepository = InMemoryLiquidityBucketRepository()
            val appStateRepository = InMemoryStrategiesAppStateRepository()
            E2ELiquidityAllocatorHelper.creditUsdBucket(
                bucketRepository,
                amount = 500,
                sessionDate = sessionDate,
            )

            gateway.setOpenOrders(E2ELiquidityAllocatorHelper.bracketOpenOrders())
            gateway.setQuotes(
                mapOf(
                    "AAPL" to LiveQuote(
                        symbol = "AAPL",
                        bid = 99.0,
                        ask = 99.5,
                        last = 99.25,
                        quoteEpochMillis = now,
                    )
                )
            )

            val deployment = E2ELiquidityAllocatorHelper.allocatorEligibleDeployment().let { dep ->
                dep.copy(touchTurnSession = dep.touchTurnSession?.copy(sessionDate = sessionDate))
            }
            repository.add(deployment)
            val originalQty = deployment.touchTurnSession?.plannedQuantity

            val coordinator = LiquidityFlushCoordinator(
                liquidityBucketRepository = bucketRepository,
                executionManager = BrokerGatewayExecutionManager(gateway),
                deploymentRepository = repository,
            )
            val engine = TouchTurnEngine(
                marketData = BrokerGatewayMarketDataProvider(gateway),
                execution = BrokerGatewayExecutionManager(gateway),
                repository = repository,
                scope = scope,
                brokerKind = BrokerKind.EMULATOR,
                isAutoLiquidityFlushEnabled = { appStateRepository.state.value.autoLiquidityFlushEnabled },
                nowEpochMillis = { now },
                sessionGateway = gateway,
                executionGateway = gateway,
                liquidityBucketRepository = bucketRepository,
                liquidityFlushCoordinator = coordinator,
                strategiesAppStateRepository = appStateRepository,
            )
            engine.start()

            engine.dispatch(TouchTurnCommand.EvaluateAutoLiquidityFlush)
            delay(200)

            assertEquals(originalQty, repository.deployments.value.single().touchTurnSession?.plannedQuantity)
            assertEquals(500, LiquidityBucketLogic.rollBucketForDate(
                LiquidityBucketLogic.bucketForCurrency(bucketRepository.state.value, "USD"),
                sessionDate,
            ).available)
            assertEquals(emptySet(), appStateRepository.state.value.flushedLiquidityZoneDates)
        } finally {
            scope.cancel()
        }
    }

    private fun flushEpochMillis(sessionDate: String, zoneId: String): Long {
        val open = TouchTurnLogic.marketOpenEpochMillis(sessionDate, zoneId, null)!!
        return open + AUTO_LIQUIDITY_FLUSH_OFFSET_MS + 1
    }
}
