package daytrader.presentation.trades

import daytrader.gateway.BrokerFill
import kotlin.test.Test
import kotlin.test.assertEquals

class TradeLedgerFilterTest {
    @Test
    fun filter_includesTradesWithinInclusiveDateRange() {
        val fills = listOf(
            fill(execId = "before", time = "2026-07-06"),
            fill(execId = "inside", time = "2026-07-07"),
            fill(execId = "after", time = "2026-07-08"),
        )
        val filtered = TradeLedgerFilter.filter(
            fills,
            TradeDateRange(
                from = java.time.LocalDate.of(2026, 7, 7),
                to = java.time.LocalDate.of(2026, 7, 7),
            )
        )
        assertEquals(listOf("inside"), filtered.map { it.execId })
    }

    @Test
    fun summarize_aggregatesRealizedPnLAndCommission() {
        val fills = listOf(
            fill(execId = "a", time = "2026-07-07", realizedPnL = 10.0, commission = 1.0),
            fill(execId = "b", time = "2026-07-07", realizedPnL = -3.5, commission = 0.5),
        )
        val summary = TradeLedgerFilter.summarize(fills)
        assertEquals(2, summary.tradeCount)
        assertEquals(6.5, summary.realizedPnL)
        assertEquals(1.5, summary.commission)
        assertEquals(setOf("USD"), summary.currencies)
    }

    @Test
    fun filterByColumnDates_limitsRowsToSelectedDates() {
        val fills = listOf(
            fill(execId = "day1", time = "2026-07-07"),
            fill(execId = "day2", time = "2026-07-08"),
            fill(execId = "day3", time = "2026-07-08"),
        )
        val columnFilters = TradeColumnFilters(
            dates = TradeSetColumnFilter(
                selected = setOf("2026-07-08"),
                available = setOf("2026-07-07", "2026-07-08"),
            )
        )
        val filtered = TradeLedgerFilter.filter(fills, TradeDateRange(), columnFilters)
        assertEquals(listOf("day2", "day3"), filtered.map { it.execId })
    }

    @Test
    fun filterBySymbol_limitsRowsToMatchingSymbol() {
        val fills = listOf(
            fill(execId = "a", symbol = "1299", time = "2026-07-07"),
            fill(execId = "b", symbol = "0700", time = "2026-07-07"),
            fill(execId = "c", symbol = "1299", time = "2026-07-07"),
        )
        val filtered = TradeLedgerFilter.filter(
            fills,
            TradeDateRange(),
            symbol = "1299",
        )
        assertEquals(listOf("a", "c"), filtered.map { it.execId })
    }

    @Test
    fun distinctSymbols_returnsSortedUniqueSymbols() {
        val fills = listOf(
            fill(execId = "a", symbol = "9988", time = "2026-07-07"),
            fill(execId = "b", symbol = "0700", time = "2026-07-07"),
            fill(execId = "c", symbol = "9988", time = "2026-07-07"),
        )
        assertEquals(listOf("0700", "9988"), TradeLedgerFilter.distinctSymbols(fills))
    }

    private fun fill(
        execId: String,
        time: String,
        symbol: String = "AAPL",
        realizedPnL: Double? = null,
        commission: Double? = null,
    ) = BrokerFill(
        execId = execId,
        orderId = 1,
        permId = 1L,
        parentOrderId = 0,
        symbol = symbol,
        side = "BOT",
        quantity = 10,
        price = 100.0,
        time = time,
        currency = "USD",
        commission = commission,
        realizedPnL = realizedPnL,
    )
}
