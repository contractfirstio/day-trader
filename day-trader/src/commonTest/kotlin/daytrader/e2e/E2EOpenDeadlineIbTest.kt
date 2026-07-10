package daytrader.e2e

import daytrader.broker.SymbolMarkets
import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStopLogic
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.hasClosingFill
import daytrader.domain.inProgressSession
import daytrader.domain.sessionRealizedPnL
import daytrader.domain.beginTouchTurnSession
import daytrader.domain.onSessionStarted
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.IbModeTestHarness
import daytrader.e2e.support.shutdownEngine
import daytrader.engine.TouchTurnCommand
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import daytrader.gateway.LiveQuote
import daytrader.gateway.WorkingOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** IB mode: OPEN_DEADLINE must confirm flat before session close; retain SL if close fails. */
class E2EOpenDeadlineIbTest {
    private companion object {
        const val FIRST_SYMBOL = "AAPL"
        const val SECOND_SYMBOL = "MU"
    }

    @E2EIbTest
    @Test
    fun ibMode_openDeadlineBatch_secondSymbolFlattenedWhenRefreshWipesSharedCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var engine: daytrader.engine.TouchTurnEngine? = null
        val harness = IbModeTestHarness()
        try {
            harness.start()
            val repository = InMemoryStrategyDeploymentRepository()
            val first = openDeadlineInPositionDeployment(E2ETestFixtures.DEPLOYMENT_ID, FIRST_SYMBOL)
            val second = openDeadlineInPositionDeployment(E2ETestFixtures.DEPLOYMENT_ID_2, SECOND_SYMBOL)
            repository.add(first)
            repository.add(second)

            val openEpoch = TouchTurnSessionStopLogic.sessionOpenEpochMillis(first, E2ETestFixtures.SESSION_DATE)!!
            val deadlineEpoch = openEpoch + 90 * 60_000L + 1_000L

            engine = harness.createEngine(
                repository = repository,
                scope = scope,
                nowEpochMillis = { deadlineEpoch },
                openDeadlineConfirmTimeoutMs = 500,
                openDeadlineMarketFallbackConfirmTimeoutMs = 500,
                openDeadlineFillDrainTimeoutMs = 100
            )
            engine.start()

            harness.gateway.refreshPositionsClearsAllPositions = true
            harness.gateway.closeClearsPosition = true
            harness.gateway.setPositions(
                listOf(
                    shortPosition(symbol = FIRST_SYMBOL, quantity = -100),
                    shortPosition(symbol = SECOND_SYMBOL, quantity = -5)
                )
            )
            harness.gateway.setOpenOrders(
                listOf(
                    stopLossOrder(symbol = FIRST_SYMBOL, orderId = 1001),
                    takeProfitOrder(symbol = FIRST_SYMBOL, orderId = 1002),
                    stopLossOrder(symbol = SECOND_SYMBOL, orderId = 2001, quantity = 5),
                    takeProfitOrder(symbol = SECOND_SYMBOL, orderId = 2002, quantity = 5)
                )
            )
            delay(50)

            engine.setBacktestSyncCommands(true)
            engine.dispatchAndAwait(TouchTurnCommand.PollStopRules)
            engine.setBacktestSyncCommands(false)
            delay(4_000)

            assertEquals(DeploymentStatus.STOPPED, repository.deployments.value[0].status)
            assertEquals(DeploymentStatus.STOPPED, repository.deployments.value[1].status)

            assertTrue(
                harness.gateway.closedPositions.any { SymbolMarkets.symbolsMatch(FIRST_SYMBOL, it.symbol) } ||
                    harness.gateway.tightenStopCalls.any { SymbolMarkets.symbolsMatch(FIRST_SYMBOL, it.symbol) },
                "first symbol must exit at OPEN_DEADLINE via tight stop or market fallback"
            )
            assertTrue(
                harness.gateway.closedPositions.any { SymbolMarkets.symbolsMatch(SECOND_SYMBOL, it.symbol) } ||
                    harness.gateway.tightenStopCalls.any { SymbolMarkets.symbolsMatch(SECOND_SYMBOL, it.symbol) },
                "second symbol must exit when an earlier stop refresh wiped the shared position cache"
            )
            assertTrue(
                harness.gateway.positions.value.none { SymbolMarkets.symbolsMatch(SECOND_SYMBOL, it.symbol) },
                "MU position must be flat at broker after batch OPEN_DEADLINE"
            )

            val secondClosed = repository.deployments.value[1].sessionHistory.single { it.status == SessionStatus.CLOSED }
            assertEquals(TouchTurnSessionStopTrigger.OPEN_DEADLINE, secondClosed.touchTurnRunRecord?.stopEvent?.stopTrigger)
            assertEquals(true, secondClosed.positionOpened)
        } finally {
            engine.shutdownEngine()
            harness.shutdown()
            scope.cancel()
        }
    }
    @E2EIbTest
    @Test
    fun ibMode_openDeadlineStop_confirmsFlatBeforeSessionClosed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var engine: daytrader.engine.TouchTurnEngine? = null
        val harness = IbModeTestHarness()
        try {
            harness.start()
            val repository = InMemoryStrategyDeploymentRepository()
            repository.add(openDeadlineRunningDeployment())
            engine = harness.createEngine(
                repository = repository,
                scope = scope,
                openDeadlineConfirmTimeoutMs = 500,
                openDeadlineMarketFallbackConfirmTimeoutMs = 500
            )
            engine.start()

            harness.gateway.setPositions(listOf(shortPosition()))
            harness.gateway.setOpenOrders(
                listOf(
                    stopLossOrder(),
                    takeProfitOrder()
                )
            )
            harness.gateway.closeClearsPosition = true
            delay(50)

            engine.setBacktestSyncCommands(true)
            engine.dispatchAndAwait(
                TouchTurnCommand.StopSession(
                    instanceId = E2ETestFixtures.DEPLOYMENT_ID,
                    trigger = TouchTurnSessionStopTrigger.OPEN_DEADLINE
                )
            )
            engine.setBacktestSyncCommands(false)
            delay(50)

            val deployment = repository.deployments.value.single()
            assertEquals(DeploymentStatus.STOPPED, deployment.status)
            assertTrue(harness.gateway.positions.value.none { it.symbol == E2ETestFixtures.SYMBOL })
            assertTrue(harness.gateway.tightenStopCalls.isNotEmpty())
            assertTrue(harness.gateway.flattenedSymbols.isEmpty())
            assertTrue(harness.gateway.cancelCalls.any { it.preserveStopLoss })
            assertTrue(harness.gateway.cancelCalls.any { !it.preserveStopLoss })

            val closed = deployment.sessionHistory.single { it.status == SessionStatus.CLOSED }
            assertEquals(TouchTurnSessionStopTrigger.OPEN_DEADLINE, closed.touchTurnRunRecord?.stopEvent?.stopTrigger)
            assertTrue(harness.gateway.positions.value.isEmpty())
        } finally {
            engine.shutdownEngine()
            harness.shutdown()
            scope.cancel()
        }
    }

    @E2EIbTest
    @Test
    fun ibMode_openDeadlineStop_drainsExitFillBeforeSessionClosed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var engine: daytrader.engine.TouchTurnEngine? = null
        val harness = IbModeTestHarness()
        try {
            harness.start()
            val repository = InMemoryStrategyDeploymentRepository()
            repository.add(
                openDeadlineInPositionDeployment(
                    id = E2ETestFixtures.DEPLOYMENT_ID,
                    symbol = E2ETestFixtures.SYMBOL,
                    sessionStartedAt = "2026-06-04T09:31:00"
                )
            )
            engine = harness.createEngine(
                repository = repository,
                scope = scope,
                openDeadlineConfirmTimeoutMs = 500,
                openDeadlineFillDrainTimeoutMs = 1_000
            )
            engine.start()

            harness.gateway.synthesizeExitFillOnClose = true
            harness.gateway.setPositions(listOf(shortPosition()))
            harness.gateway.setFills(
                listOf(
                    BrokerFill(
                        execId = "entry-1",
                        orderId = 521,
                        permId = 521L,
                        parentOrderId = 0,
                        symbol = E2ETestFixtures.SYMBOL,
                        side = "SLD",
                        quantity = 100,
                        price = 150.0,
                        time = "2026-06-04T09:46:00",
                        currency = "USD"
                    )
                )
            )
            harness.gateway.setOpenOrders(listOf(stopLossOrder(), takeProfitOrder()))
            harness.gateway.closeClearsPosition = true
            delay(50)

            engine.setBacktestSyncCommands(true)
            engine.dispatchAndAwait(
                TouchTurnCommand.StopSession(
                    instanceId = E2ETestFixtures.DEPLOYMENT_ID,
                    trigger = TouchTurnSessionStopTrigger.OPEN_DEADLINE,
                    brokerPositionAtDecision = shortPosition()
                )
            )
            engine.setBacktestSyncCommands(false)
            delay(50)

            val closed = repository.deployments.value.single()
                .sessionHistory
                .single { it.status == SessionStatus.CLOSED }
            assertEquals(2, closed.sessionTrades.size)
            assertEquals(1, closed.trades)
            assertTrue(closed.sessionTrades.hasClosingFill())
            assertTrue((closed.sessionTrades.sessionRealizedPnL()) > 0.0)
        } finally {
            engine.shutdownEngine()
            harness.shutdown()
            scope.cancel()
        }
    }

    @E2EIbTest
    @Test
    fun ibMode_openDeadlineStop_infersPositionFromFillsWhenCachesEmpty() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var engine: daytrader.engine.TouchTurnEngine? = null
        val harness = IbModeTestHarness()
        try {
            harness.start()
            val repository = InMemoryStrategyDeploymentRepository()
            repository.add(
                openDeadlineInPositionDeployment(
                    id = E2ETestFixtures.DEPLOYMENT_ID,
                    symbol = E2ETestFixtures.SYMBOL,
                    sessionStartedAt = "2026-06-04T09:31:00"
                )
            )
            engine = harness.createEngine(repository, scope, openDeadlineConfirmTimeoutMs = 500)
            engine.start()

            harness.gateway.setPositions(emptyList())
            harness.gateway.setFills(
                listOf(
                    BrokerFill(
                        execId = "entry-1",
                        orderId = 521,
                        permId = 521L,
                        parentOrderId = 0,
                        symbol = E2ETestFixtures.SYMBOL,
                        side = "SLD",
                        quantity = 100,
                        price = 150.0,
                        time = "2026-06-04T09:46:00",
                        currency = "USD"
                    )
                )
            )
            harness.gateway.setOpenOrders(listOf(stopLossOrder(), takeProfitOrder()))
            harness.gateway.closeClearsPosition = true
            delay(50)

            engine.setBacktestSyncCommands(true)
            engine.dispatchAndAwait(
                TouchTurnCommand.StopSession(
                    instanceId = E2ETestFixtures.DEPLOYMENT_ID,
                    trigger = TouchTurnSessionStopTrigger.OPEN_DEADLINE
                )
            )
            engine.setBacktestSyncCommands(false)
            delay(50)

            assertTrue(
                harness.gateway.positions.value.none { it.symbol == E2ETestFixtures.SYMBOL },
                "OPEN_DEADLINE must exit from inferred fill qty when broker caches are empty"
            )
            assertTrue(harness.gateway.tightenStopCalls.isNotEmpty())
            assertTrue(harness.gateway.cancelCalls.any { it.preserveStopLoss })
            assertTrue(harness.gateway.cancelCalls.any { !it.preserveStopLoss })

            val closed = repository.deployments.value.single()
                .sessionHistory
                .single { it.status == SessionStatus.CLOSED }
            assertEquals(true, closed.positionOpened)
        } finally {
            engine.shutdownEngine()
            harness.shutdown()
            scope.cancel()
        }
    }

    @E2EIbTest
    @Test
    fun ibMode_openDeadlineStop_whenBrokerDropsStopLoss_replacesStopWithoutFlatten() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var engine: daytrader.engine.TouchTurnEngine? = null
        val harness = IbModeTestHarness()
        try {
            harness.start()
            val repository = InMemoryStrategyDeploymentRepository()
            repository.add(openDeadlineRunningDeployment())
            engine = harness.createEngine(
                repository = repository,
                scope = scope,
                openDeadlineConfirmTimeoutMs = 100,
                openDeadlineFillDrainTimeoutMs = 100
            )
            engine.start()

            harness.gateway.setPositions(listOf(shortPosition(quantity = -16)))
            harness.gateway.setOpenOrders(listOf(takeProfitOrder(quantity = 16)))
            harness.gateway.setQuotes(
                mapOf(E2ETestFixtures.SYMBOL to LiveQuote(E2ETestFixtures.SYMBOL, bid = 149.0, ask = 149.05))
            )
            delay(50)

            engine.setBacktestSyncCommands(true)
            engine.dispatchAndAwait(
                TouchTurnCommand.StopSession(
                    instanceId = E2ETestFixtures.DEPLOYMENT_ID,
                    trigger = TouchTurnSessionStopTrigger.OPEN_DEADLINE,
                    brokerPositionAtDecision = shortPosition(quantity = -16)
                )
            )
            engine.setBacktestSyncCommands(false)

            assertTrue(harness.gateway.flattenedSymbols.isEmpty())
            assertTrue(harness.gateway.tightenStopCalls.isNotEmpty())
            assertTrue(harness.gateway.positions.value.none { it.symbol == E2ETestFixtures.SYMBOL })
            assertEquals(DeploymentStatus.STOPPED, repository.deployments.value.single().status)

            val closed = repository.deployments.value.single()
                .sessionHistory
                .single { it.status == SessionStatus.CLOSED }
            assertEquals(TouchTurnSessionStopTrigger.OPEN_DEADLINE, closed.touchTurnRunRecord?.stopEvent?.stopTrigger)
        } finally {
            engine.shutdownEngine()
            harness.shutdown()
            scope.cancel()
        }
    }

    @E2EIbTest
    @Test
    fun ibMode_openDeadlineStop_whenCloseUnconfirmed_retainsStopLossAtBroker() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var engine: daytrader.engine.TouchTurnEngine? = null
        val harness = IbModeTestHarness()
        try {
            harness.start()
            val repository = InMemoryStrategyDeploymentRepository()
            repository.add(openDeadlineRunningDeployment())
            engine = harness.createEngine(
                repository = repository,
                scope = scope,
                openDeadlineConfirmTimeoutMs = 200,
                openDeadlineMarketFallbackConfirmTimeoutMs = 200
            )
            engine.start()

            harness.gateway.setPositions(listOf(shortPosition()))
            harness.gateway.setOpenOrders(listOf(stopLossOrder(), takeProfitOrder()))
            harness.gateway.closeClearsPosition = false
            delay(50)

            engine.setBacktestSyncCommands(true)
            engine.dispatchAndAwait(
                TouchTurnCommand.StopSession(
                    instanceId = E2ETestFixtures.DEPLOYMENT_ID,
                    trigger = TouchTurnSessionStopTrigger.OPEN_DEADLINE
                )
            )
            engine.setBacktestSyncCommands(false)

            assertTrue(harness.gateway.positions.value.any { it.symbol == E2ETestFixtures.SYMBOL })
            assertEquals(listOf(1001), harness.gateway.openOrders.value.map { it.orderId })
            assertTrue(harness.gateway.cancelCalls.all { it.preserveStopLoss })

            val closed = repository.deployments.value.single()
                .sessionHistory
                .single { it.status == SessionStatus.CLOSED }
            assertEquals(true, closed.positionOpened)
        } finally {
            engine.shutdownEngine()
            harness.shutdown()
            scope.cancel()
        }
    }

    private fun openDeadlineRunningDeployment(): StrategyDeployment =
        openDeadlineInPositionDeployment(E2ETestFixtures.DEPLOYMENT_ID, E2ETestFixtures.SYMBOL)

    private fun openDeadlineInPositionDeployment(
        id: String,
        symbol: String,
        sessionStartedAt: String = "2026-06-04T09:31:00"
    ): StrategyDeployment {
        val sessionDate = E2ETestFixtures.SESSION_DATE
        val bar = E2ETestFixtures.liquidityOpeningBar()
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(openDeadline = true)
        )
        return defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = symbol,
            maxDollars = 500,
            status = DeploymentStatus.RUNNING
        )
            .copy(id = id, touchTurnRules = rules)
            .onSessionStarted(sessionDate, startedAt = sessionStartedAt)
            .beginTouchTurnSession(sessionDate)
            .copy(
                touchTurnSession = TouchTurnSessionContext(
                    sessionDate = sessionDate,
                    status = TouchTurnCandleStatus.READY,
                    openingBarTime = bar.time,
                    candle = bar,
                    ordersPlacedForSession = true,
                    decisionOutcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
                    rules = rules,
                    milestones = TouchTurnMilestoneTimestamps(
                        startingSessionAt = "2026-06-04T09:31:00",
                        ordersPlacedAt = "2026-06-04T09:45:05",
                        positionOpenedAt = "2026-06-04T09:46:00"
                    )
                )
            )
            .also { require(it.inProgressSession() != null) }
    }

    private fun shortPosition(
        symbol: String = E2ETestFixtures.SYMBOL,
        quantity: Int = -100
    ) = AccountPosition(
        account = "DU123",
        symbol = symbol,
        companyName = "$symbol Inc.",
        quantity = quantity,
        avgPrice = 150.0,
        marketPrice = 149.0,
        priorClose = 148.0,
        totalUnrealizedPnL = 100.0,
        currency = "USD"
    )

    private fun stopLossOrder(
        symbol: String = E2ETestFixtures.SYMBOL,
        orderId: Int = 1001,
        quantity: Int = 100
    ) = WorkingOrder(
        orderId = orderId,
        symbol = symbol,
        action = "BUY",
        quantity = quantity,
        filled = 0,
        remaining = quantity,
        orderType = "STP",
        limitPrice = null,
        stopPrice = 155.0,
        status = "Submitted",
        currency = "USD"
    )

    private fun takeProfitOrder(
        symbol: String = E2ETestFixtures.SYMBOL,
        orderId: Int = 1002,
        quantity: Int = 100
    ) = WorkingOrder(
        orderId = orderId,
        symbol = symbol,
        action = "BUY",
        quantity = quantity,
        filled = 0,
        remaining = quantity,
        orderType = "LMT",
        limitPrice = 140.0,
        stopPrice = null,
        status = "Submitted",
        currency = "USD"
    )
}
