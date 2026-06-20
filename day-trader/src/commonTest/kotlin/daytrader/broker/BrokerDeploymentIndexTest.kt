package daytrader.broker

import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.gateway.AccountPosition
import daytrader.gateway.WorkingOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrokerDeploymentIndexTest {
    @Test
    fun build_resolvesPositionAndOrdersPerDeployment() {
        val deployment = StrategyDeployment(
            id = "d1",
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            status = daytrader.domain.DeploymentStatus.STOPPED,
            symbol = "700",
            maxDollars = 1000,
        )
        val other = deployment.copy(id = "d2", symbol = "AAPL")
        val positions = listOf(
            AccountPosition(
                account = "DU1",
                symbol = "700",
                companyName = "Tencent",
                quantity = 100,
                avgPrice = 400.0,
                marketPrice = 405.0,
                priorClose = 398.0,
                totalUnrealizedPnL = 500.0,
                currency = "HKD",
            ),
            AccountPosition(
                account = "DU1",
                symbol = "AAPL",
                companyName = "Apple",
                quantity = 0,
                avgPrice = 0.0,
                marketPrice = 0.0,
                priorClose = 0.0,
                totalUnrealizedPnL = 0.0,
                currency = "USD",
            ),
        )
        val orders = listOf(
            WorkingOrder(
                orderId = 1,
                symbol = "700",
                action = "BUY",
                quantity = 100,
                filled = 0,
                remaining = 100,
                orderType = "LMT",
                limitPrice = 400.0,
                stopPrice = null,
                status = "Submitted",
                currency = "HKD",
            ),
            WorkingOrder(
                orderId = 2,
                symbol = "AAPL",
                action = "BUY",
                quantity = 10,
                filled = 0,
                remaining = 10,
                orderType = "LMT",
                limitPrice = 200.0,
                stopPrice = null,
                status = "Submitted",
                currency = "USD",
            ),
        )
        val index = BrokerDeploymentIndex.build(
            deployments = listOf(deployment, other),
            positions = positions,
            openOrders = orders,
        )
        assertNotNull(index.openPosition(deployment))
        assertEquals(500.0, index.openPosition(deployment)!!.totalUnrealizedPnL)
        assertNull(index.openPosition(other))
        assertFalse(index.hasOpenPosition(other))
        assertTrue(index.hasOpenOrders(deployment))
        assertEquals(1, index.openOrders(deployment).size)
        assertEquals(1, index.openOrders(deployment).single().orderId)
        assertEquals(1, index.openOrdersForSymbol("0700").single().orderId)
    }
}
