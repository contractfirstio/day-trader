package daytrader.e2e

import daytrader.data.SessionMarketDataCapture
import daytrader.e2e.support.E2EProcessCleanup
import kotlin.test.Test
import kotlin.test.assertTrue

class E2EProcessCleanupTest {
    @Test
    fun resetAll_clearsSessionMarketDataCapture() {
        SessionMarketDataCapture.start("dep-a", "session-a", "AAPL", null)
        E2EProcessCleanup.resetAll()
        assertTrue(E2EProcessCleanup.isClean())
    }
}
