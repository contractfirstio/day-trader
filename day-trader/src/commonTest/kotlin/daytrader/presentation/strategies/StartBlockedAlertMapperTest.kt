package daytrader.presentation.strategies

import daytrader.gateway.AccountPosition
import daytrader.broker.SymbolMarkets
import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StartBlockedAlertMapperTest {
    private val instance = defaultStrategyDeployment(
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        symbol = "700",
        maxDollars = 500
    )

    private val position = AccountPosition(
        account = "DU123",
        symbol = "700",
        companyName = "Tencent",
        quantity = 100,
        avgPrice = 400.0,
        marketPrice = 410.0,
        priorClose = 395.0,
        totalUnrealizedPnL = 1000.0,
        currency = "HKD"
    )

    @Test
    fun findOpenPosition_matchesInstanceSymbol() {
        assertNotNull(SymbolMarkets.findOpenPosition("700", listOf(position)))
        assertNotNull(SymbolMarkets.findOpenPosition("0700", listOf(position)))
    }

    @Test
    fun findOpenPosition_ignoresFlatQuantity() {
        val flat = position.copy(quantity = 0)
        assertNull(SymbolMarkets.findOpenPosition("700", listOf(flat)))
    }

    @Test
    fun alertDescribesPositionAndReason() {
        val alert = StartBlockedAlertMapper.from(instance, position)
        assertEquals("700", alert.instanceSymbol)
        assertContains(alert.summary, "700")
        assertContains(alert.positionDetails, "Tencent")
        assertContains(alert.positionDetails, "Long")
        assertContains(alert.reason, "flat")
        assertContains(alert.reason, "deployment")
    }
}
