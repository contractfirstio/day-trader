package daytrader.ui.tools

import daytrader.gateway.BrokerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PriceFeedOptionCatalogTest {
    @Test
    fun hybrid_includesMarketDataExecutionAndQuoteBus() {
        val feeds = PriceFeedOptionCatalog.available(
            brokerKind = BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA,
            hasSeparateMarketDataGateway = true,
            hasQuoteBus = true
        )
        assertEquals(
            listOf(
                PriceFeedOption.MARKET_DATA_GATEWAY,
                PriceFeedOption.EXECUTION_GATEWAY,
                PriceFeedOption.QUOTE_BUS
            ),
            feeds
        )
    }

    @Test
    fun fullIb_onlyExecutionGateway() {
        val feeds = PriceFeedOptionCatalog.available(
            brokerKind = BrokerKind.INTERACTIVE_BROKERS,
            hasSeparateMarketDataGateway = false,
            hasQuoteBus = false
        )
        assertEquals(listOf(PriceFeedOption.EXECUTION_GATEWAY), feeds)
    }

    @Test
    fun defaultSelection_prefersMarketDataWhenPresent() {
        val available = listOf(
            PriceFeedOption.MARKET_DATA_GATEWAY,
            PriceFeedOption.EXECUTION_GATEWAY
        )
        assertEquals(
            setOf(PriceFeedOption.MARKET_DATA_GATEWAY),
            PriceFeedOptionCatalog.defaultSelection(available)
        )
    }

    @Test
    fun streamingSubscription_requiredForMarketDataAndQuoteBus() {
        assertTrue(PriceFeedOption.MARKET_DATA_GATEWAY.requiresStreamingSubscription())
        assertTrue(PriceFeedOption.QUOTE_BUS.requiresStreamingSubscription())
        assertEquals(false, PriceFeedOption.EXECUTION_GATEWAY.requiresStreamingSubscription())
    }
}
