package daytrader.data

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionMarketDataCaptureTest {
    @BeforeTest
    fun clear() {
        SessionMarketDataCapture.stopAll()
    }

    @Test
    fun startAndStop_tracksTargetByDeployment() {
        SessionMarketDataCapture.start(
            deploymentId = "inst-a",
            sessionId = "session-1",
            symbol = "GOOGL",
            instrument = null
        )
        val active = SessionMarketDataCapture.activeForDeployment("inst-a")
        assertEquals("session-1", active?.sessionId)
        assertEquals(1, SessionMarketDataCapture.targetsForSymbol("GOOGL").size)

        val stopped = SessionMarketDataCapture.stop("inst-a")
        assertEquals("session-1", stopped?.sessionId)
        assertNull(SessionMarketDataCapture.activeForDeployment("inst-a"))
    }

    @Test
    fun start_replacesPreviousCaptureForSameDeployment() {
        SessionMarketDataCapture.start("inst-a", "session-1", "AAPL", null)
        SessionMarketDataCapture.start("inst-a", "session-2", "AAPL", null)
        assertEquals("session-2", SessionMarketDataCapture.activeForDeployment("inst-a")?.sessionId)
        assertEquals(1, SessionMarketDataCapture.activeTargets().size)
    }

    @Test
    fun stopAll_clearsEveryTarget() {
        SessionMarketDataCapture.start("inst-a", "session-1", "AAPL", null)
        SessionMarketDataCapture.start("inst-b", "session-2", "MSFT", null)
        val removed = SessionMarketDataCapture.stopAll()
        assertEquals(2, removed.size)
        assertTrue(SessionMarketDataCapture.activeTargets().isEmpty())
    }
}
