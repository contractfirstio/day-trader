package daytrader.data

import daytrader.data.persistence.DebouncedFileWriter
import daytrader.data.persistence.DeferredFileHydration
import daytrader.data.persistence.JsonFileStore
import daytrader.data.persistence.WatchlistPersistence
import daytrader.data.persistence.WatchlistsDocument
import daytrader.data.persistence.launchDeferredFileHydration
import daytrader.domain.Watchlist
import daytrader.domain.WatchlistEntry
import daytrader.domain.defaultWatchlistForBrokerKind
import daytrader.domain.newWatchlistId
import daytrader.domain.watchlistNameForBrokerKind
import daytrader.gateway.BrokerKind
import daytrader.platform.AppFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FileWatchlistRepository(
    private val brokerKind: BrokerKind,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : WatchlistRepository {
    private val writer = DebouncedFileWriter<List<Watchlist>>(scope) { watchlists ->
        persistWatchlists(watchlists)
    }
    private val hydration = DeferredFileHydration()

    private val _watchlists = MutableStateFlow<List<Watchlist>>(emptyList())
    override val watchlists: StateFlow<List<Watchlist>> = _watchlists.asStateFlow()

    init {
        scope.launchDeferredFileHydration(hydration) {
            _watchlists.value = loadInitial()
        }
    }

    suspend fun awaitHydrated() {
        hydration.awaitComplete()
    }

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
        writer.schedule(_watchlists.value)
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
        writer.schedule(_watchlists.value)
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
        writer.schedule(_watchlists.value)
    }

    override fun updateWatchlist(watchlistId: String, transform: (Watchlist) -> Watchlist) {
        _watchlists.update { lists ->
            lists.map { watchlist ->
                if (watchlist.id == watchlistId) transform(watchlist) else watchlist
            }
        }
        writer.schedule(_watchlists.value)
    }

    override fun createWatchlist(name: String): Watchlist {
        val watchlist = defaultWatchlistForBrokerKind(brokerKind).copy(
            id = newWatchlistId(),
            name = name.trim().ifBlank { watchlistNameForBrokerKind(brokerKind) }
        )
        _watchlists.update { it + watchlist }
        writer.schedule(_watchlists.value)
        return watchlist
    }

    override fun removeWatchlist(id: String) {
        _watchlists.update { it.filterNot { watchlist -> watchlist.id == id } }
        writer.schedule(_watchlists.value)
    }

    override fun flushPersistence() {
        writer.flush(_watchlists.value)
    }

    override fun flushPersistenceBlocking() {
        writer.flushBlocking(_watchlists.value)
    }

    private fun loadInitial(): List<Watchlist> {
        AppFileSystem.ensureAppDataDirectory()
        val fromDisk = JsonFileStore.readWatchlists()
            ?.watchlists
            ?.map(WatchlistPersistence::toDomain)
        if (fromDisk != null) {
            return fromDisk.ifEmpty { listOf(defaultWatchlistForBrokerKind(brokerKind)) }
        }
        return listOf(defaultWatchlistForBrokerKind(brokerKind))
    }

    private fun persistWatchlists(watchlists: List<Watchlist>) {
        val document = WatchlistsDocument(watchlists.map(WatchlistPersistence::toRecord))
        JsonFileStore.writeWatchlists(document)
    }
}
