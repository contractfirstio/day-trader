package daytrader.platform

import daytrader.data.DesktopFredYieldCurveClient
import daytrader.data.MacroYieldDataProvider

actual fun defaultMacroYieldDataProvider(): MacroYieldDataProvider = DesktopFredYieldCurveClient()
