package daytrader.domain

import daytrader.gateway.BrokerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchlistStrategyLinksTest {
    @Test
    fun resolveAndMerge_supportMultipleStrategiesPerEntry() {
        val deploymentA = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 1_000
        )
        val deploymentB = defaultStrategyDeployment(
            strategyType = StrategyType.QUICK_FLIP_SCALPER,
            symbol = "MSFT",
            maxDollars = 1_000
        ).copy(id = "dep-b")
        val deployments = listOf(deploymentA, deploymentB)
        val entry = newWatchlistEntry(
            symbol = "AAPL",
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            companyName = "Apple",
            instrument = null
        ).copy(strategyDeploymentIds = listOf(deploymentA.id, deploymentB.id))

        val resolved = WatchlistStrategyLinks.resolve(entry.strategyDeploymentIds, deployments)
        assertEquals(2, resolved.size)
        assertTrue(WatchlistStrategyLinks.entryHasStrategy(entry, deploymentA.id))
        assertTrue(WatchlistStrategyLinks.entryHasStrategy(entry, deploymentB.id))
    }

    @Test
    fun entryHasStrategyType_matchesAnyLinkedDeploymentOfType() {
        val touchTurn = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 1_000
        )
        val quickFlip = defaultStrategyDeployment(
            strategyType = StrategyType.QUICK_FLIP_SCALPER,
            symbol = "AAPL",
            maxDollars = 1_000
        ).copy(id = "dep-quick")
        val deployments = listOf(touchTurn, quickFlip)
        val entry = newWatchlistEntry(
            symbol = "AAPL",
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            companyName = "Apple",
            instrument = null
        ).copy(strategyDeploymentIds = listOf(touchTurn.id))

        assertTrue(
            WatchlistStrategyLinks.entryHasStrategyType(
                entry,
                StrategyType.TOUCH_AND_TURN_SCALPER,
                deployments
            )
        )
        assertEquals(
            1,
            WatchlistStrategyLinks.countForStrategyType(
                listOf(entry),
                StrategyType.TOUCH_AND_TURN_SCALPER,
                deployments
            )
        )
        assertEquals(
            listOf(StrategyType.TOUCH_AND_TURN_SCALPER),
            WatchlistStrategyLinks.linkedStrategyTypes(listOf(entry), deployments)
        )
    }

    @Test
    fun remapAssignedIds_dropsDeletedDeployments() {
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 1_000
        )
        val remapped = WatchlistStrategyLinks.remapAssignedIds(
            assignedIds = listOf(deployment.id, "missing"),
            deployments = listOf(deployment)
        )
        assertEquals(listOf(deployment.id), remapped)
    }
}
