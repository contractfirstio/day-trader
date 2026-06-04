package daytrader.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IbPriceTickMergerTest {

    @Test
    fun mergeJsonl_buildsQuoteTimelineFromPerFieldTicks() {
        val jsonl = """
            {"at":"2026-06-04T09:30:00.000","epochMs":1000,"symbol":"AAPL","field":1,"fieldName":"BID","price":100.0,"bid":100.0}
            {"at":"2026-06-04T09:30:00.100","epochMs":1100,"symbol":"AAPL","field":2,"fieldName":"ASK","price":100.2,"bid":100.0,"ask":100.2}
            {"at":"2026-06-04T09:30:00.200","epochMs":1200,"symbol":"AAPL","field":4,"fieldName":"LAST","price":100.1,"bid":100.0,"ask":100.2,"last":100.1}
            {"at":"2026-06-04T09:30:00.300","epochMs":1300,"symbol":"AAPL","field":1,"fieldName":"BID","price":100.05,"bid":100.05,"ask":100.2,"last":100.1}
        """.trimIndent()

        val events = IbPriceTickMerger.mergeJsonl(jsonl, symbolFilter = "AAPL")
        assertTrue(events.size >= 2)
        assertEquals(1100L, events.first().epochMs)
        assertEquals(100.0, events.first().quote.bid)
        assertEquals(100.2, events.first().quote.ask)
        assertEquals(100.05, events.last().quote.bid)
    }

    @Test
    fun mergeJsonl_filtersBySymbol() {
        val jsonl = """
            {"at":"t","epochMs":1000,"symbol":"AAPL","field":1,"fieldName":"BID","price":100.0,"bid":100.0}
            {"at":"t","epochMs":1001,"symbol":"AAPL","field":2,"fieldName":"ASK","price":100.2,"bid":100.0,"ask":100.2}
            {"at":"t","epochMs":1002,"symbol":"MSFT","field":1,"fieldName":"BID","price":200.0,"bid":200.0}
            {"at":"t","epochMs":1003,"symbol":"MSFT","field":2,"fieldName":"ASK","price":200.2,"bid":200.0,"ask":200.2}
        """.trimIndent()

        val events = IbPriceTickMerger.mergeJsonl(jsonl, symbolFilter = "AAPL")
        assertEquals(1, events.size)
        assertEquals("AAPL", events.single().symbol)
    }

    @Test
    fun mergeJsonl_dedupesUnchangedSnapshots() {
        val jsonl = """
            {"at":"t","epochMs":1000,"symbol":"AAPL","field":1,"fieldName":"BID","price":100.0,"bid":100.0}
            {"at":"t","epochMs":1001,"symbol":"AAPL","field":2,"fieldName":"ASK","price":100.2,"bid":100.0,"ask":100.2}
            {"at":"t","epochMs":1002,"symbol":"AAPL","field":1,"fieldName":"BID","price":100.0,"bid":100.0,"ask":100.2}
        """.trimIndent()

        val events = IbPriceTickMerger.mergeJsonl(jsonl)
        assertEquals(1, events.size)
    }
}
