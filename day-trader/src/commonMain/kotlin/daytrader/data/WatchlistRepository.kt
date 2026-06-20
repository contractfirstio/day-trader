package daytrader.data

import daytrader.domain.Watchlist
import daytrader.domain.WatchlistEntry
import kotlinx.coroutines.flow.StateFlow

interface WatchlistRepository {
    val watchlists: StateFlow<List<Watchlist>>
    fun addEntry(watchlistId: String, entry: WatchlistEntry)
    fun removeEntry(watchlistId: String, entryId: String)
    fun updateEntry(watchlistId: String, entryId: String, transform: (WatchlistEntry) -> WatchlistEntry)
    fun updateWatchlist(watchlistId: String, transform: (Watchlist) -> Watchlist)
    fun createWatchlist(name: String): Watchlist
    fun removeWatchlist(id: String)
    fun flushPersistence()
    fun flushPersistenceBlocking()
}
