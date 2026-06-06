package daytrader.engine.support

import daytrader.data.WatchlistRepository
import daytrader.domain.Watchlist
import daytrader.domain.WatchlistEntry
import daytrader.domain.defaultWatchlist
import daytrader.domain.newWatchlistId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class InMemoryWatchlistRepository(
    initial: List<Watchlist> = listOf(defaultWatchlist())
) : WatchlistRepository {
    private val _watchlists = MutableStateFlow(initial)
    override val watchlists: StateFlow<List<Watchlist>> = _watchlists.asStateFlow()

    override fun addEntry(watchlistId: String, entry: WatchlistEntry) {
        _watchlists.update { lists ->
            lists.map { watchlist ->
                if (watchlist.id == watchlistId) {
                    watchlist.copy(entries = watchlist.entries + entry)
                } else {
                    watchlist
                }
            }
        }
    }

    override fun removeEntry(watchlistId: String, entryId: String) {
        _watchlists.update { lists ->
            lists.map { watchlist ->
                if (watchlist.id == watchlistId) {
                    watchlist.copy(entries = watchlist.entries.filterNot { it.id == entryId })
                } else {
                    watchlist
                }
            }
        }
    }

    override fun updateEntry(
        watchlistId: String,
        entryId: String,
        transform: (WatchlistEntry) -> WatchlistEntry
    ) {
        _watchlists.update { lists ->
            lists.map { watchlist ->
                if (watchlist.id == watchlistId) {
                    watchlist.copy(
                        entries = watchlist.entries.map { entry ->
                            if (entry.id == entryId) transform(entry) else entry
                        }
                    )
                } else {
                    watchlist
                }
            }
        }
    }

    override fun updateWatchlist(watchlistId: String, transform: (Watchlist) -> Watchlist) {
        _watchlists.update { lists ->
            lists.map { watchlist ->
                if (watchlist.id == watchlistId) transform(watchlist) else watchlist
            }
        }
    }

    override fun createWatchlist(name: String): Watchlist {
        val watchlist = defaultWatchlist().copy(
            id = newWatchlistId(),
            name = name.trim().ifBlank { "Watchlist" }
        )
        _watchlists.update { it + watchlist }
        return watchlist
    }

    override fun removeWatchlist(id: String) {
        _watchlists.update { it.filterNot { watchlist -> watchlist.id == id } }
    }

    override fun flushPersistence() = Unit
}
