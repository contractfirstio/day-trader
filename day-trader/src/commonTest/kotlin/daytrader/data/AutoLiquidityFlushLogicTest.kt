package daytrader.data

import daytrader.domain.AUTO_LIQUIDITY_FLUSH_OFFSET_MS
import daytrader.domain.RthMarketSessions
import daytrader.domain.TouchTurnLogic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoLiquidityFlushLogicTest {
    @Test
    fun sessionDateIfFlushDue_nullBeforeOpenPlusSixteenMinutes() {
        val zone = RthMarketSessions.US.zoneId
        val sessionDate = "2026-06-04"
        val open = TouchTurnLogic.marketOpenEpochMillis(sessionDate, zone, null)!!
        assertNull(AutoLiquidityFlushLogic.sessionDateIfFlushDue(zone, open + AUTO_LIQUIDITY_FLUSH_OFFSET_MS - 1))
    }

    @Test
    fun sessionDateIfFlushDue_returnsSessionDateAfterOffset() {
        val zone = RthMarketSessions.US.zoneId
        val sessionDate = "2026-06-04"
        val open = TouchTurnLogic.marketOpenEpochMillis(sessionDate, zone, null)!!
        assertEquals(
            sessionDate,
            AutoLiquidityFlushLogic.sessionDateIfFlushDue(zone, open + AUTO_LIQUIDITY_FLUSH_OFFSET_MS),
        )
    }

    @Test
    fun flushKey_formatsZoneAndDate() {
        assertEquals(
            "America/New_York:2026-06-04",
            AutoLiquidityFlushLogic.flushKey(RthMarketSessions.US.zoneId, "2026-06-04"),
        )
    }
}
