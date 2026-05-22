package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class RthTradingCalendarTest {
    @Test
    fun isRthTradingDay_weekendsFalse() {
        assertFalse(TouchTurnLogic.isRthTradingDay(LocalDate.of(2026, 5, 23))) // Saturday
        assertFalse(TouchTurnLogic.isRthTradingDay(LocalDate.of(2026, 5, 24))) // Sunday
        assertTrue(TouchTurnLogic.isRthTradingDay(LocalDate.of(2026, 5, 22))) // Friday
    }

    @Test
    fun isRthMarketOpen_falseOnWeekend() {
        val zone = ZoneId.of("America/New_York")
        val saturdayMidday = LocalDate.of(2026, 5, 23)
            .atTime(12, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        assertFalse(TouchTurnLogic.isRthMarketOpen(RthMarketSessions.US, saturdayMidday))
    }

    @Test
    fun nextMarketOpen_onSaturdaySkipsToMonday() {
        val zone = "America/New_York"
        val zoneId = ZoneId.of(zone)
        val saturdayMorning = LocalDate.of(2026, 5, 23)
            .atTime(8, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val mondayOpen = TouchTurnLogic.marketOpenEpochMillis("2026-05-25", zone, null)!!
        assertEquals(mondayOpen, TouchTurnLogic.nextMarketOpenEpochMillis(zone, saturdayMorning))
    }

    @Test
    fun nextMarketOpen_fridayAfterCloseSkipsWeekendToMonday() {
        val zone = "America/New_York"
        val fridayOpen = TouchTurnLogic.marketOpenEpochMillis("2026-05-22", zone, null)!!
        val afterClose = fridayOpen + 7 * 60 * 60 * 1000
        val mondayOpen = TouchTurnLogic.marketOpenEpochMillis("2026-05-25", zone, null)!!
        assertEquals(mondayOpen, TouchTurnLogic.nextMarketOpenEpochMillis(zone, afterClose))
    }

    @Test
    fun marketOpenEpochMillisForZone_nullOnWeekend() {
        val zone = "Asia/Hong_Kong"
        val zoneId = ZoneId.of(zone)
        val sunday = LocalDate.of(2026, 5, 24)
            .atTime(10, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        assertEquals(null, TouchTurnLogic.marketOpenEpochMillisForZone(zone, sunday))
    }
}
