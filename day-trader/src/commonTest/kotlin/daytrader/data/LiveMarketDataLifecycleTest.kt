package daytrader.data

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.onSessionStarted
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveMarketDataLifecycleTest {
    @BeforeTest
    fun clear() {
        SessionMarketDataCapture.stopAll()
    }

    @Test
    fun anyDeploymentNeedsQuotes_trueWhenPostSessionCaptureActive() {
        val stopped = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "GOOGL",
            maxDollars = 500,
            status = DeploymentStatus.STOPPED
        )
        SessionMarketDataCapture.start("inst-a", "session-1", "GOOGL", null)
        assertTrue(LiveMarketDataLifecycle.anyDeploymentNeedsQuotes("GOOGL", listOf(stopped)))
    }

    @Test
    fun anyDeploymentNeedsQuotes_trueWhenRunningDeploymentMatchesSymbol() {
        val running = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "GOOGL",
            maxDollars = 500,
            status = DeploymentStatus.STOPPED
        ).onSessionStarted("2026-06-10")
        assertTrue(LiveMarketDataLifecycle.anyDeploymentNeedsQuotes("GOOGL", listOf(running)))
    }

    @Test
    fun anyDeploymentNeedsQuotes_falseWhenNothingNeedsSymbol() {
        val stopped = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "GOOGL",
            maxDollars = 500,
            status = DeploymentStatus.STOPPED
        )
        assertFalse(LiveMarketDataLifecycle.anyDeploymentNeedsQuotes("GOOGL", listOf(stopped)))
    }
}
