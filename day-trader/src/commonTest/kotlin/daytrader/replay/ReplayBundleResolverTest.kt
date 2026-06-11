package daytrader.replay

import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReplayBundleResolverTest {
    private val deployment = defaultStrategyDeployment(
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        symbol = "WDC",
        maxDollars = 500
    ).copy(id = "inst-wdc")

    @Test
    fun selectCapture_prefersMatchingDeploymentId() {
        val older = capture("older", deploymentId = "other", symbol = "WDC", started = 100L)
        val exact = capture("exact", deploymentId = deployment.id, symbol = "WDC", started = 50L)
        assertEquals(exact, ReplayBundleResolver.selectCapture(deployment, listOf(older, exact)))
    }

    @Test
    fun selectCapture_fallsBackToNewestSymbolMatch() {
        val older = capture("older", deploymentId = "other-a", symbol = "WDC", started = 100L)
        val newer = capture("newer", deploymentId = "other-b", symbol = "WDC", started = 200L)
        assertEquals(newer, ReplayBundleResolver.selectCapture(deployment, listOf(older, newer)))
    }

    @Test
    fun selectCapture_returnsNullWhenNoSymbolMatch() {
        val msft = capture("msft", deploymentId = "inst-msft", symbol = "MSFT", started = 300L)
        assertNull(ReplayBundleResolver.selectCapture(deployment, listOf(msft)))
    }

    private fun capture(
        path: String,
        deploymentId: String,
        symbol: String,
        started: Long
    ) = ReplayCaptureRef(
        directoryPath = path,
        deploymentId = deploymentId,
        symbol = symbol,
        sessionDate = "2026-06-10",
        sessionStartedEpochMs = started
    )
}
