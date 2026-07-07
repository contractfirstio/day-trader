package daytrader.e2e

import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.inProgressSession
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.IbModeTestHarness
import daytrader.e2e.support.shutdownEngine
import daytrader.engine.TouchTurnCommand
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.gateway.AccountPosition
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
            engine = harness.createEngine(repository, scope)
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
            assertTrue(harness.gateway.cancelCalls.any { it.preserveStopLoss })
            assertTrue(harness.gateway.cancelCalls.any { !it.preserveStopLoss })
            assertTrue(harness.gateway.closedPositions.isNotEmpty())

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
                openDeadlineConfirmTimeoutMs = 200
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
        E2ETestFixtures.runningDeployment().copy(
            touchTurnRules = TouchTurnRuleConfig.DEFAULT.copy(
                enables = TouchTurnRuleEnables.DEFAULT.copy(openDeadline = true)
            )
        ).also { require(it.inProgressSession() != null) }

    private fun shortPosition() = AccountPosition(
        account = "DU123",
        symbol = E2ETestFixtures.SYMBOL,
        companyName = "Apple Inc.",
        quantity = -100,
        avgPrice = 150.0,
        marketPrice = 149.0,
        priorClose = 148.0,
        totalUnrealizedPnL = 100.0,
        currency = "USD"
    )

    private fun stopLossOrder() = WorkingOrder(
        orderId = 1001,
        symbol = E2ETestFixtures.SYMBOL,
        action = "BUY",
        quantity = 100,
        filled = 0,
        remaining = 100,
        orderType = "STP",
        limitPrice = null,
        stopPrice = 155.0,
        status = "Submitted",
        currency = "USD"
    )

    private fun takeProfitOrder() = WorkingOrder(
        orderId = 1002,
        symbol = E2ETestFixtures.SYMBOL,
        action = "BUY",
        quantity = 100,
        filled = 0,
        remaining = 100,
        orderType = "LMT",
        limitPrice = 140.0,
        stopPrice = null,
        status = "Submitted",
        currency = "USD"
    )
}
