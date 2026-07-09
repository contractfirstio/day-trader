package daytrader.data.persistence

import daytrader.gateway.BrokerFill
import kotlin.test.Test
import kotlin.test.assertEquals

class TradesPersistenceTest {
    @Test
    fun roundTrip_preservesBrokerFillFields() {
        val original = sampleFill(execId = "abc", symbol = "AAPL", commission = 0.35)
        val restored = TradesPersistence.toDomain(TradesPersistence.toRecord(original))
        assertEquals(original, restored)
    }

    @Test
    fun mergeFills_addsNewExecIds() {
        val stored = listOf(sampleFill(execId = "one", symbol = "AAPL"))
        val incoming = listOf(
            sampleFill(execId = "one", symbol = "AAPL"),
            sampleFill(execId = "two", symbol = "MSFT")
        )
        val result = TradesPersistence.mergeFills(stored, incoming)
        assertEquals(1, result.added)
        assertEquals(0, result.updated)
        assertEquals(2, result.fills.size)
    }

    @Test
    fun mergeFills_enrichesCommissionAndRealizedPnL() {
        val stored = listOf(sampleFill(execId = "one", symbol = "AAPL"))
        val incoming = listOf(
            sampleFill(
                execId = "one",
                symbol = "AAPL",
                commission = 0.35,
                realizedPnL = 12.5
            )
        )
        val result = TradesPersistence.mergeFills(stored, incoming)
        assertEquals(0, result.added)
        assertEquals(1, result.updated)
        assertEquals(0.35, result.fills.single().commission)
        assertEquals(12.5, result.fills.single().realizedPnL)
    }

    @Test
    fun mergeFills_ignoresEmptyIncoming() {
        val stored = listOf(sampleFill(execId = "one", symbol = "AAPL"))
        val result = TradesPersistence.mergeFills(stored, emptyList())
        assertEquals(0, result.added)
        assertEquals(0, result.updated)
        assertEquals(stored, result.fills)
    }

    @Test
    fun mergeFills_backfillsEmptyTradeTime() {
        val stored = listOf(sampleFill(execId = "one", symbol = "AAPL", time = ""))
        val incoming = listOf(sampleFill(execId = "one", symbol = "AAPL", time = "2026-07-07"))
        val result = TradesPersistence.mergeFills(stored, incoming)
        assertEquals(0, result.added)
        assertEquals(1, result.updated)
        assertEquals("2026-07-07", result.fills.single().time)
    }

    @Test
    fun mergeFills_overwritesExistingTradeDate() {
        val stored = listOf(sampleFill(execId = "flex-1", symbol = "AAPL", time = "2026-07-07"))
        val incoming = listOf(sampleFill(execId = "flex-1", symbol = "AAPL", time = "2026-07-06"))
        val result = TradesPersistence.mergeFills(stored, incoming)
        assertEquals(0, result.added)
        assertEquals(1, result.updated)
        assertEquals("2026-07-06", result.fills.single().time)
    }

    @Test
    fun mergeFlexFills_replacesStoredFlexRowsAndRefreshesDates() {
        val stored = listOf(
            sampleFill(execId = "flex-1", symbol = "AAPL", time = "2026-07-07"),
            sampleFill(execId = "live-1", symbol = "MSFT", time = "2026-07-08"),
            sampleFill(execId = "flex-2", symbol = "NVDA", time = "2026-07-07"),
        )
        val incoming = listOf(
            sampleFill(execId = "flex-1", symbol = "AAPL", time = "2026-07-06"),
            sampleFill(execId = "flex-3", symbol = "TSLA", time = "2026-07-08"),
        )
        val result = TradesPersistence.mergeFlexFills(stored, incoming)
        assertEquals(2, result.added)
        assertEquals(0, result.updated)
        assertEquals(
            listOf("2026-07-06", "2026-07-08", "2026-07-08"),
            result.fills.map { it.time }.sorted(),
        )
        assertEquals(listOf("flex-1", "flex-3", "live-1"), result.fills.map { it.execId }.sorted())
    }

    private fun sampleFill(
        execId: String,
        symbol: String,
        time: String = "20260601  10:00:00",
        commission: Double? = null,
        realizedPnL: Double? = null,
    ) = BrokerFill(
        execId = execId,
        orderId = 1,
        permId = 100L,
        parentOrderId = 0,
        symbol = symbol,
        side = "BOT",
        quantity = 10,
        price = 150.0,
        time = time,
        commission = commission,
        realizedPnL = realizedPnL,
    )
}
