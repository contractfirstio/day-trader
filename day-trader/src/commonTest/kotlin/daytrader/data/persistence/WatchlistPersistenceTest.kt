package daytrader.data.persistence

import daytrader.domain.DEFAULT_WATCHLIST_ID
import daytrader.domain.InstrumentIdentity
import daytrader.domain.PlanSizingMode
import daytrader.domain.TradeSide
import daytrader.domain.WatchlistLabel
import daytrader.domain.WatchlistTradePlan
import daytrader.domain.defaultWatchlist
import daytrader.domain.newWatchlistEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchlistPersistenceTest {
    @Test
    fun watchlistRoundTrip_persistsEntriesAndMetadata() {
        val entry = newWatchlistEntry(
            symbol = "AAPL",
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            companyName = "Apple Inc.",
            instrument = InstrumentIdentity(
                symbol = "AAPL",
                exchange = "SMART",
                currency = "USD",
                conId = 265598L
            ),
            notes = "Swing candidate"
        )
        val original = defaultWatchlist().copy(
            name = "Tech",
            entries = listOf(entry)
        )

        val record = WatchlistPersistence.toRecord(original)
        val restored = WatchlistPersistence.toDomain(record)

        assertEquals("Tech", restored.name)
        assertEquals(1, restored.entries.size)
        assertEquals("AAPL", restored.entries.first().symbol)
        assertEquals("Apple Inc.", restored.entries.first().companyName)
        assertEquals("America/New_York", restored.entries.first().marketZoneId)
        assertEquals("USD", restored.entries.first().currencyCode)
        assertEquals("Swing candidate", restored.entries.first().notes)
        assertEquals(265598L, restored.entries.first().instrument?.conId)
    }

    @Test
    fun tradePlansRoundTrip_persistsBracketFields() {
        val plan = WatchlistTradePlan(
            id = "plan-a",
            label = "Plan A",
            side = TradeSide.LONG,
            entryPrice = 185.0,
            stopPrice = 178.0,
            targetPrice = 200.0,
            investmentAmount = 10_000.0,
            sizingMode = PlanSizingMode.NOTIONAL
        )
        val entry = newWatchlistEntry(
            symbol = "AAPL",
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            companyName = "Apple Inc.",
            instrument = null
        ).copy(tradePlans = listOf(plan))

        val restored = WatchlistPersistence.toDomain(
            WatchlistPersistence.toRecord(defaultWatchlist().copy(entries = listOf(entry)))
        ).entries.first()

        assertEquals(1, restored.tradePlans.size)
        assertEquals("Plan A", restored.tradePlans.first().label)
        assertEquals(185.0, restored.tradePlans.first().entryPrice)
        assertEquals(178.0, restored.tradePlans.first().stopPrice)
        assertEquals(200.0, restored.tradePlans.first().targetPrice)
        assertEquals(10_000.0, restored.tradePlans.first().investmentAmount)
        assertEquals(PlanSizingMode.NOTIONAL, restored.tradePlans.first().sizingMode)
    }

    @Test
    fun tradePlansRoundTrip_persistsOrderPlacement() {
        val plan = WatchlistTradePlan(
            id = "plan-a",
            label = "Plan A",
            orderPlacedAtEpochMs = 1_700_000_000_000L,
            placedOrderIds = listOf(100, 101, 102)
        )
        val entry = newWatchlistEntry(
            symbol = "AAPL",
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            companyName = "Apple Inc.",
            instrument = null
        ).copy(tradePlans = listOf(plan))

        val restored = WatchlistPersistence.toDomain(
            WatchlistPersistence.toRecord(defaultWatchlist().copy(entries = listOf(entry)))
        ).entries.first().tradePlans.first()

        assertEquals(1_700_000_000_000L, restored.orderPlacedAtEpochMs)
        assertEquals(listOf(100, 101, 102), restored.placedOrderIds)
        assertTrue(restored.hasPlacedOrder)
    }

    @Test
    fun labelsRoundTrip_persistsRegistryAndEntryLinks() {
        val earnings = WatchlistLabel(id = "lbl-earnings", name = "Earnings", createdAtEpochMs = 1L)
        val tech = WatchlistLabel(id = "lbl-tech", name = "Tech", createdAtEpochMs = 1L)
        val entry = newWatchlistEntry(
            symbol = "AAPL",
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            companyName = "Apple Inc.",
            instrument = null
        ).copy(labelIds = listOf(earnings.id, tech.id))
        val original = defaultWatchlist().copy(
            labels = listOf(earnings, tech),
            entries = listOf(entry)
        )

        val restored = WatchlistPersistence.toDomain(WatchlistPersistence.toRecord(original))

        assertEquals(2, restored.labels.size)
        assertEquals(listOf("lbl-earnings", "lbl-tech"), restored.entries.first().labelIds)
        assertEquals("Earnings", restored.labels.first { it.id == "lbl-earnings" }.name)
    }

    @Test
    fun legacyTags_migrateToLabelRegistryOnLoad() {
        val entryRecord = WatchlistEntryRecord(
            id = "entry-1",
            symbol = "AAPL",
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            addedAtEpochMs = 1L,
            tags = listOf("Earnings", "tech")
        )
        val record = WatchlistRecord(
            id = DEFAULT_WATCHLIST_ID,
            name = "Watchlist",
            entries = listOf(entryRecord),
            createdAtEpochMs = 1L
        )

        val restored = WatchlistPersistence.toDomain(record)

        assertEquals(2, restored.labels.size)
        assertEquals(2, restored.entries.first().labelIds.size)
        assertEquals(listOf("Earnings", "Tech"), restored.labels.map { it.name }.sorted())
    }
}
