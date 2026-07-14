package daytrader.presentation.trades

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TradeUiMapperTest {

    @Test
    fun parseFillTime_isoDateTime_doesNotStackOverflow() {
        val parsed = TradeUiMapper.parseFillTime("2026-07-14T10:15:30")
        assertNotNull(parsed)
        assertEquals(2026, parsed.year)
        assertEquals(7, parsed.monthValue)
        assertEquals(14, parsed.dayOfMonth)
        assertEquals(10, parsed.hour)
        assertEquals(15, parsed.minute)
    }

    @Test
    fun parseFillDate_isoDateTime_usesDatePortionWithoutRecursing() {
        val parsed = TradeUiMapper.parseFillDate("2026-07-14T10:15:30")
        assertNotNull(parsed)
        assertEquals("2026-07-14", parsed.toString())
    }

    @Test
    fun parseFillTime_ibFormat_parses() {
        val parsed = TradeUiMapper.parseFillTime("20260604  09:30:00")
        assertNotNull(parsed)
        assertEquals(9, parsed.hour)
        assertEquals(30, parsed.minute)
    }

    @Test
    fun parseFillDate_dateOnly_parses() {
        assertEquals("2026-07-14", TradeUiMapper.parseFillDate("2026-07-14")!!.toString())
        assertEquals("2026-06-04", TradeUiMapper.parseFillDate("20260604")!!.toString())
    }

    @Test
    fun parseFillTime_blank_returnsNull() {
        assertNull(TradeUiMapper.parseFillTime(""))
        assertNull(TradeUiMapper.parseFillDate("   "))
    }
}
