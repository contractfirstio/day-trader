package daytrader.domain

import daytrader.broker.SymbolMarkets
import kotlin.test.Test
import kotlin.test.assertEquals

class DeploymentMarketZoneTest {
    @Test
    fun effectiveZoneId_prefersDeploymentMarketOverUsdHeuristic() {
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "NWG",
            maxDollars = 500,
            marketZoneId = RthMarketSessions.EUR.zoneId,
            currencyCode = "USD",
            instrument = InstrumentIdentity.heuristic("NWG", "USD")
        )
        assertEquals(RthMarketSessions.EUR.zoneId, DeploymentMarket.effectiveZoneId(deployment))
        assertEquals(
            RthMarketSessions.EUR.zoneId,
            SymbolMarkets.marketZoneIdForSession(
                "NWG",
                DeploymentMarket.effectiveInstrument(deployment),
                deploymentMarketZoneId = DeploymentMarket.effectiveZoneId(deployment)
            )
        )
    }

    @Test
    fun effectiveCurrencyCode_alignsUsdOnLondonDeploymentToGbp() {
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "NWG",
            maxDollars = 500,
            marketZoneId = RthMarketSessions.EUR.zoneId,
            currencyCode = "USD"
        )
        assertEquals("GBP", DeploymentMarket.effectiveCurrencyCode(deployment))
    }

    @Test
    fun effectiveInstrument_addsLsePrimaryForLondonHeuristic() {
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "NWG",
            maxDollars = 500,
            marketZoneId = RthMarketSessions.EUR.zoneId,
            currencyCode = "GBP"
        )
        val instrument = DeploymentMarket.effectiveInstrument(deployment)
        assertEquals("GBP", instrument.currency)
        assertEquals("LSE", instrument.primaryExch)
    }
}
