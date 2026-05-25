package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RthMarketSessionsTest {
    @Test
    fun eurSession_usesLondonZoneAndLseHours() {
        val eur = RthMarketSessions.EUR
        assertEquals("Europe/London", eur.zoneId)
        assertEquals(8, eur.openHour)
        assertEquals(0, eur.openMinute)
        assertEquals(16, eur.closeHour)
        assertEquals(30, eur.closeMinute)
    }

    @Test
    fun forZoneId_mapsLegacyBerlinToEurSession() {
        assertEquals(RthMarketSessions.EUR, RthMarketSessions.forZoneId("Europe/Berlin"))
    }

    @Test
    fun isRthMarketOpen_london_falseBeforeEightAm() {
        val open = TouchTurnLogic.marketOpenEpochMillis("2026-05-22", "Europe/London", null)!!
        assertFalse(TouchTurnLogic.isRthMarketOpen(RthMarketSessions.EUR, open - 60_000))
    }

    @Test
    fun isRthMarketOpen_london_trueDuringLseRth() {
        val open = TouchTurnLogic.marketOpenEpochMillis("2026-05-22", "Europe/London", null)!!
        val midday = open + 4 * 60 * 60 * 1000
        assertTrue(TouchTurnLogic.isRthMarketOpen(RthMarketSessions.EUR, midday))
    }

    @Test
    fun isRthMarketOpen_london_falseAfterClose() {
        val close = TouchTurnLogic.marketCloseEpochMillis("2026-05-22", "Europe/London")!!
        assertFalse(TouchTurnLogic.isRthMarketOpen(RthMarketSessions.EUR, close))
    }
}
