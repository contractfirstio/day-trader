package daytrader.ui

import daytrader.domain.InstrumentIdentity
import daytrader.domain.MarketSource
import daytrader.domain.StrategyType
import daytrader.presentation.strategies.StrategyDeploymentAddPrefill
import daytrader.presentation.watchlist.WatchlistStrategyCreateRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchlistStrategyCreateBridgeTest {
    @Test
    fun requestCreate_navigatesAndOpensPrefilledDialog() {
        val bridge = WatchlistStrategyCreateBridge()
        var navigated = false
        var capturedPrefill: StrategyDeploymentAddPrefill? = null
        bridge.navigateToStrategies = { navigated = true }
        bridge.showStrategyAddDialog = { capturedPrefill = it }

        bridge.requestCreate(
            WatchlistStrategyCreateRequest(
                entryId = "entry-1",
                symbol = "AAPL",
                marketZoneId = "America/New_York",
                currencyCode = "USD",
                companyName = "Apple Inc.",
                instrument = InstrumentIdentity(
                    symbol = "AAPL",
                    exchange = "SMART",
                    primaryExch = "NASDAQ",
                    currency = "USD"
                ),
                strategyType = StrategyType.TOUCH_AND_TURN_SCALPER
            )
        )

        assertEquals("entry-1", bridge.pendingLinkEntryId)
        assertEquals(true, navigated)
        assertEquals("AAPL", capturedPrefill?.symbol)
        assertEquals("America/New_York", capturedPrefill?.marketZoneId)
        assertEquals(MarketSource.IB, capturedPrefill?.marketSource)
        assertEquals(StrategyType.TOUCH_AND_TURN_SCALPER, capturedPrefill?.strategyType)
    }

    @Test
    fun onDeploymentCreated_linksAndClearsPendingEntry() {
        val bridge = WatchlistStrategyCreateBridge()
        val linked = mutableListOf<Pair<String, String>>()
        bridge.pendingLinkEntryId = "entry-1"
        bridge.linkDeploymentToWatchlistEntry = { entryId, deploymentId ->
            linked += entryId to deploymentId
        }

        bridge.onDeploymentCreated("dep-1")

        assertEquals(listOf("entry-1" to "dep-1"), linked)
        assertNull(bridge.pendingLinkEntryId)
    }
}
