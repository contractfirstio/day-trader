package daytrader.presentation.strategies

import daytrader.domain.SessionStatus
import daytrader.domain.StrategySession
import kotlin.test.Test
import kotlin.test.assertSame

class SessionRollupCacheTest {
    @Test
    fun rollups_returnsCachedResultForSameScopeAndSessions() {
        val cache = SessionRollupCache()
        val sessions = listOf(
            StrategySession(
                id = "r1",
                date = "2026-05-20",
                pnl = 10.0,
                trades = 1,
                maxAtRisk = 500,
                status = SessionStatus.CLOSED,
                positionOpened = true,
            )
        )
        val first = cache.rollupsForDeployment("d1", sessions, "2026-05-22")
        val second = cache.rollupsForDeployment("d1", sessions, "2026-05-22")
        assertSame(first, second)
    }

    @Test
    fun clear_dropsCachedRollups() {
        val cache = SessionRollupCache()
        val sessions = listOf(
            StrategySession(
                id = "r1",
                date = "2026-05-20",
                pnl = 10.0,
                trades = 1,
                maxAtRisk = 500,
                status = SessionStatus.CLOSED,
                positionOpened = true,
            )
        )
        val first = cache.rollupsForDeployment("d1", sessions, "2026-05-22")
        cache.clear()
        val second = cache.rollupsForDeployment("d1", sessions, "2026-05-22")
        kotlin.test.assertEquals(first.totalPnl, second.totalPnl)
        kotlin.test.assertNotSame(first, second)
    }
}
