package daytrader.data

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.beginTouchTurnSession
import daytrader.domain.defaultStrategyDeployment
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.gateway.WorkingOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortfolioExposureCalculatorTest {
    @Test
    fun calculate_sumsRunningDeploymentMaxAtRisk() {
        val deployments = listOf(
            running("AAPL", 500),
            running("MSFT", 250),
            defaultStrategyDeployment(
                strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                symbol = "GOOG",
                maxDollars = 1_000,
                status = DeploymentStatus.STOPPED,
            ),
        )
        val snapshot = PortfolioExposureCalculator.calculate(deployments)
        assertEquals(2, snapshot.runningDeploymentCount)
        assertEquals(750, snapshot.totalMaxAtRiskUsd)
        assertEquals(500, snapshot.maxAtRiskBySymbol["AAPL"])
        assertEquals(250, snapshot.maxAtRiskBySymbol["MSFT"])
    }

    @Test
    fun limits_overCap_whenTotalExceedsConfiguredMax() {
        val snapshot = PortfolioExposureCalculator.Snapshot(
            runningDeploymentCount = 2,
            totalMaxAtRiskUsd = 1_500,
            maxAtRiskBySymbol = mapOf("AAPL" to 1_500),
        )
        assertTrue(
            PortfolioExposureLimits.isOverCap(snapshot) { key ->
                if (key == PortfolioExposureLimits.ENV_MAX_PORTFOLIO_AT_RISK) "1000" else null
            }
        )
        assertFalse(
            PortfolioExposureLimits.isOverCap(snapshot) { key ->
                if (key == PortfolioExposureLimits.ENV_MAX_PORTFOLIO_AT_RISK) "2000" else null
            }
        )
    }

    private fun running(symbol: String, maxDollars: Int) =
        defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = symbol,
            maxDollars = maxDollars,
            status = DeploymentStatus.RUNNING,
        ).beginTouchTurnSession("2026-06-10")
}

class GlobalSessionKillSwitchTest {
    @Test
    fun activate_stopsRunningDeploymentsAndFlattensTheirSymbols() {
        val repository = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway()
        val deployment = running("AAPL")
        repository.add(deployment)

        val stopped = GlobalSessionKillSwitch.activate(
            repository = repository,
            gateway = gateway,
        )

        assertEquals(1, stopped.size)
        assertEquals(DeploymentStatus.STOPPED, repository.deployments.value.single().status)
        assertTrue(gateway.flattenedSymbols.contains("AAPL"))
        assertTrue(repository.flushInvocationCount >= 1)
    }

    @Test
    fun activate_flattensOrphanBrokerSymbolsOutsideRunningSessions() {
        val repository = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway()
        gateway.setOpenOrders(
            listOf(
                WorkingOrder(
                    orderId = 1,
                    symbol = "TSLA",
                    action = "BUY",
                    quantity = 5,
                    filled = 0,
                    remaining = 5,
                    orderType = "LMT",
                    limitPrice = 200.0,
                    stopPrice = null,
                    status = "Submitted",
                    currency = "USD",
                )
            )
        )

        GlobalSessionKillSwitch.activate(
            repository = repository,
            gateway = gateway,
            brokerOpenOrders = gateway.openOrders.value,
        )

        assertTrue(gateway.flattenedSymbols.contains("TSLA"))
    }

    private fun running(symbol: String) =
        defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = symbol,
            maxDollars = 500,
            status = DeploymentStatus.RUNNING,
        ).beginTouchTurnSession("2026-06-10")
}
