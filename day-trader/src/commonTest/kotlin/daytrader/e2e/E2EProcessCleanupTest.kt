package daytrader.e2e

import daytrader.broker.emulator.EmulatorLogScope
import daytrader.data.SessionMarketDataCapture
import daytrader.diagnostics.SessionPriceLog
import daytrader.e2e.support.E2EProcessCleanup
import daytrader.presentation.navigation.AppScreen
import daytrader.presentation.ui.UiFaultBus
import kotlin.test.Test
import kotlin.test.assertTrue

class E2EProcessCleanupTest {
    @Test
    fun resetAll_clearsSessionMarketDataCapture() {
        SessionMarketDataCapture.start("dep-a", "session-a", "AAPL", null)
        E2EProcessCleanup.resetAll()
        assertTrue(E2EProcessCleanup.isClean())
    }

    @Test
    fun resetAll_clearsEmulatorLogScopeSessionPriceLogAndUiFaultBus() {
        SessionMarketDataCapture.start("dep-b", "session-b", "AAPL", null)
        EmulatorLogScope.bind("dep-b", "session-b")
        SessionPriceLog.install { emptyList() }
        UiFaultBus.report(AppScreen.STRATEGIES, "cleanup-test", IllegalStateException("boom"))

        E2EProcessCleanup.resetAll()

        assertTrue(E2EProcessCleanup.isClean())
        assertTrue(UiFaultBus.faults.value.isEmpty())
    }
}
