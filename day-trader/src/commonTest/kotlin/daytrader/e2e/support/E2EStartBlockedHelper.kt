package daytrader.e2e.support

import daytrader.gateway.AccountPosition

object E2EStartBlockedHelper {
    fun openPosition(
        symbol: String = E2ETestFixtures.SYMBOL,
        quantity: Int = 50,
    ): AccountPosition = AccountPosition(
        account = "DU123",
        symbol = symbol,
        companyName = "Test Co.",
        quantity = quantity,
        avgPrice = 100.0,
        marketPrice = 101.0,
        priorClose = 99.0,
        totalUnrealizedPnL = 50.0,
        currency = "USD"
    )
}
