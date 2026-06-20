package daytrader.data

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.beginTouchTurnSession
import daytrader.domain.defaultStrategyDeployment
import daytrader.gateway.AccountPosition
import daytrader.gateway.WorkingOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunningBrokerReconciliationTest {
    @Test
    fun evaluate_noRunningDeployments_returnsEmpty() {
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = DeploymentStatus.STOPPED,
        )
        assertTrue(
            RunningBrokerReconciliation.evaluate(
                deployments = listOf(deployment),
                positions = emptyList(),
                openOrders = emptyList(),
            ).isEmpty()
        )
    }

    @Test
    fun evaluate_orphanBrokerOrders_flagsRunningSession() {
        val deployment = runningTouchTurn("AAPL")
        val findings = RunningBrokerReconciliation.evaluate(
            deployments = listOf(deployment),
            positions = emptyList(),
            openOrders = listOf(submittedOrder("AAPL")),
        )
        assertEquals(1, findings.size)
        assertEquals(RunningBrokerReconciliation.Kind.ORPHAN_BROKER_ORDERS, findings.single().kind)
        assertEquals(deployment.id, findings.single().deploymentId)
    }

    @Test
    fun evaluate_sessionPlacedOrders_doesNotFlagOrphanOrders() {
        val deployment = runningTouchTurn("AAPL").copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-06-10",
                status = TouchTurnCandleStatus.READY,
                ordersPlacedForSession = true,
            )
        )
        assertTrue(
            RunningBrokerReconciliation.evaluate(
                deployments = listOf(deployment),
                positions = emptyList(),
                openOrders = listOf(submittedOrder("AAPL")),
            ).isEmpty()
        )
    }

    @Test
    fun evaluate_unexpectedOpenPosition_flagsRunningSession() {
        val deployment = runningTouchTurn("AAPL")
        val findings = RunningBrokerReconciliation.evaluate(
            deployments = listOf(deployment),
            positions = listOf(
                AccountPosition(
                    account = "DU1",
                    symbol = "AAPL",
                    companyName = "Apple",
                    quantity = 10,
                    avgPrice = 100.0,
                    marketPrice = 101.0,
                    priorClose = 99.0,
                    totalUnrealizedPnL = 10.0,
                    currency = "USD",
                )
            ),
            openOrders = emptyList(),
        )
        assertEquals(1, findings.size)
        assertEquals(RunningBrokerReconciliation.Kind.UNEXPECTED_OPEN_POSITION, findings.single().kind)
    }

    @Test
    fun evaluate_positionWithSessionMilestone_doesNotFlagUnexpectedPosition() {
        val deployment = runningTouchTurn("AAPL").copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-06-10",
                status = TouchTurnCandleStatus.READY,
                ordersPlacedForSession = true,
                milestones = TouchTurnMilestoneTimestamps(positionOpenedAt = "2026-06-10T10:00:00"),
            )
        )
        assertTrue(
            RunningBrokerReconciliation.evaluate(
                deployments = listOf(deployment),
                positions = listOf(
                    AccountPosition(
                        account = "DU1",
                        symbol = "AAPL",
                        companyName = "Apple",
                        quantity = 10,
                        avgPrice = 100.0,
                        marketPrice = 101.0,
                        priorClose = 99.0,
                        totalUnrealizedPnL = 10.0,
                        currency = "USD",
                    )
                ),
                openOrders = emptyList(),
            ).isEmpty()
        )
    }

    private fun runningTouchTurn(symbol: String) =
        defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = symbol,
            maxDollars = 500,
            status = DeploymentStatus.RUNNING,
        ).beginTouchTurnSession("2026-06-10")

    private fun submittedOrder(symbol: String) = WorkingOrder(
        orderId = 1,
        symbol = symbol,
        action = "BUY",
        quantity = 10,
        filled = 0,
        remaining = 10,
        orderType = "LMT",
        limitPrice = 100.0,
        stopPrice = null,
        status = "Submitted",
        currency = "USD",
    )
}
