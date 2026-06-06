package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchlistLabelsTest {
    @Test
    fun normalizeName_trimsAndTitleCasesWords() {
        assertEquals("Earnings Play", WatchlistLabels.normalizeName("  earnings   play "))
    }

    @Test
    fun ensureLabel_dedupesCaseInsensitively() {
        val labels = mutableListOf(WatchlistLabel("l1", "Earnings", 0L))
        val ensured = WatchlistLabels.ensureLabel(labels, "earnings")
        assertEquals("l1", ensured.id)
        assertEquals(1, labels.size)
    }

    @Test
    fun mergeLabelId_addsUniqueId() {
        assertEquals(listOf("a", "b"), WatchlistLabels.mergeLabelId(listOf("a"), "b"))
        assertEquals(listOf("a"), WatchlistLabels.mergeLabelId(listOf("a"), "a"))
    }

    @Test
    fun filterSuggestions_prefersPrefixMatches() {
        val candidates = listOf(
            WatchlistLabel("1", "Breakout", 0L),
            WatchlistLabel("2", "Earnings", 0L)
        )
        assertEquals(listOf("Earnings"), WatchlistLabels.filterSuggestions(candidates, "ear").map { it.name })
    }

    @Test
    fun combinedRegistry_dedupesByName() {
        val registry = WatchlistLabels.combinedRegistry(
            watchlistLabels = listOf(WatchlistLabel("l1", "Tech", 0L)),
            pendingLabels = listOf(WatchlistLabel("l2", "tech", 0L))
        )
        assertEquals(1, registry.size)
        assertEquals("l1", registry.first().id)
    }
}
