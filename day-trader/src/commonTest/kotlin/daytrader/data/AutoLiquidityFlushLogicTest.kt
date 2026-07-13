package daytrader.data

import daytrader.domain.AUTO_LIQUIDITY_FLUSH_OFFSET_MS
import daytrader.domain.RthMarketSessions
import daytrader.domain.TouchTurnLogic
import daytrader.engine.liquidity.LiquidityFlushAudit
import daytrader.engine.liquidity.LiquidityFlushLoopAudit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun shouldMarkZoneFlushed_falseWhenAllResizesFailedWithPoolLeft() {
        val audit = LiquidityFlushAudit(
            currencyCode = "HKD",
            sessionDate = "2026-07-13",
            startingPoolAvailable = 400_000,
            remainingPoolAvailable = 400_000,
            loops = listOf(
                LiquidityFlushLoopAudit(
                    loopIndex = 1,
                    eligibleCount = 13,
                    distributionCount = 5,
                    failedResize = mapOf("dep-1" to "bracket_resize_missing_perm_id"),
                )
            ),
        )
        assertFalse(audit.shouldMarkZoneFlushed())
    }

    @Test
    fun shouldMarkZoneFlushed_trueWhenEmptyPoolOrSuccessfulDebit() {
        assertTrue(
            LiquidityFlushAudit(
                currencyCode = "USD",
                sessionDate = "2026-07-13",
                startingPoolAvailable = 0,
                remainingPoolAvailable = 0,
                skippedEmptyPool = true,
            ).shouldMarkZoneFlushed()
        )
        assertTrue(
            LiquidityFlushAudit(
                currencyCode = "USD",
                sessionDate = "2026-07-13",
                startingPoolAvailable = 5_000,
                remainingPoolAvailable = 138,
                loops = listOf(
                    LiquidityFlushLoopAudit(
                        loopIndex = 1,
                        eligibleCount = 9,
                        distributionCount = 9,
                        debited = mapOf("dep-a" to 4_862),
                    )
                ),
            ).shouldMarkZoneFlushed()
        )
    }

    @Test
    fun toTraceDetails_includesFailedResizeForensics() {
        val details = LiquidityFlushAudit(
            currencyCode = "HKD",
            sessionDate = "2026-07-13",
            startingPoolAvailable = 400_000,
            remainingPoolAvailable = 400_000,
            loops = listOf(
                LiquidityFlushLoopAudit(
                    loopIndex = 1,
                    eligibleCount = 2,
                    distributionCount = 2,
                    failedResize = mapOf("dep-hk" to "timeout"),
                    skippedLot = setOf("dep-lot"),
                )
            ),
        ).toTraceDetails(zoneId = "Asia/Hong_Kong", markedFlushed = false)

        assertEquals("Asia/Hong_Kong", details["zoneId"])
        assertEquals("0", details["totalDebited"])
        assertEquals("false", details["markedFlushed"])
        assertTrue(details["failedResize"]!!.contains("dep-hk=timeout"))
        assertTrue(details["skippedLot"]!!.contains("dep-lot"))
        assertEquals("1:2", details["eligibleCounts"])
    }
}
