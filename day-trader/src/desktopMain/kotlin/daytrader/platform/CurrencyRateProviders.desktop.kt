package daytrader.platform

import daytrader.data.CurrencyRateProvider
import daytrader.data.DesktopFrankfurterCurrencyRateClient

actual fun defaultCurrencyRateProvider(): CurrencyRateProvider = DesktopFrankfurterCurrencyRateClient()
