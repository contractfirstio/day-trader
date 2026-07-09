package daytrader.presentation.trades

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TradeSetColumnFilterTest {
    @Test
    fun allSelected_matchesEverything() {
        val filter = TradeSetColumnFilter.forValues(listOf("AAPL", "MSFT"))
        assertFalse(filter.isActive)
        assertTrue(filter.matches("AAPL"))
        assertTrue(filter.matches("MSFT"))
    }

    @Test
    fun subsetSelection_filtersRows() {
        val all = TradeSetColumnFilter.forValues(listOf("AAPL", "MSFT"))
        val onlyAapl = all.toggle("MSFT")
        assertTrue(onlyAapl.isActive)
        assertTrue(onlyAapl.matches("AAPL"))
        assertFalse(onlyAapl.matches("MSFT"))
    }

    @Test
    fun emptySelection_matchesNothing() {
        val none = TradeSetColumnFilter.forValues(listOf("AAPL", "MSFT")).setSelectAll(false)
        assertTrue(none.isActive)
        assertFalse(none.matches("AAPL"))
    }
}
