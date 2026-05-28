package daytrader.engine

import daytrader.data.TouchTurnManualStopHandler
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.onSessionStarted
import daytrader.engine.support.FakeBrokerGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchTurnManualStopTest {
    @Test
    fun stop_flattensBrokerAndMarksDeploymentStopped() {
        val gateway = FakeBrokerGateway()
        val running = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = DeploymentStatus.RUNNING
        ).onSessionStarted("2026-05-22")

        val result = TouchTurnManualStopHandler.stop(
            input = TouchTurnManualStopHandler.Input(
                instance = running,
                brokerPositions = emptyList(),
                brokerOpenOrders = emptyList(),
                brokerFills = emptyList()
            ),
            gateway = gateway,
            explicitTrigger = TouchTurnSessionStopTrigger.MANUAL
        )

        assertEquals(DeploymentStatus.STOPPED, result.stoppedDeployment.status)
        assertTrue(gateway.flattenedSymbols.contains("AAPL"))
        assertEquals(TouchTurnSessionStopTrigger.MANUAL, result.stopTrigger)
    }
}
