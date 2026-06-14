package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SymbolImportCsvParserTest {
    @Test
    fun parsesSymbolAndExchangeRows() {
        val result = SymbolImportCsvParser.parse(
            """
            META,US
            NWG,UK
            1211,HK
            """.trimIndent()
        )
        assertTrue(result.errors.isEmpty())
        assertEquals(3, result.rows.size)
        assertEquals("META", result.rows[0].symbol)
        assertEquals(RthMarketSessions.US.zoneId, result.rows[0].marketZoneId)
        assertEquals("NWG", result.rows[1].symbol)
        assertEquals(RthMarketSessions.EUR.zoneId, result.rows[1].marketZoneId)
        assertEquals("1211", result.rows[2].symbol)
        assertEquals(RthMarketSessions.HK.zoneId, result.rows[2].marketZoneId)
    }

    @Test
    fun skipsHeaderRowWhenPresent() {
        val result = SymbolImportCsvParser.parse("symbol,exchange\nAAPL,US")
        assertEquals(1, result.rows.size)
        assertEquals("AAPL", result.rows[0].symbol)
    }

    @Test
    fun reportsUnknownExchange() {
        val result = SymbolImportCsvParser.parse("FOO,ZZ")
        assertEquals(1, result.errors.size)
        assertTrue(result.rows.isEmpty())
    }
}
