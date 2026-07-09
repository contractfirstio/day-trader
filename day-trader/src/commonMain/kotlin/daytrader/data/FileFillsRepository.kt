package daytrader.data

import daytrader.data.persistence.DebouncedFileWriter
import daytrader.data.persistence.DeferredFileHydration
import daytrader.data.persistence.JsonFileStore
import daytrader.data.persistence.MergeFillsResult
import daytrader.data.persistence.TradesDocument
import daytrader.data.persistence.TradesPersistence
import daytrader.data.persistence.launchDeferredFileHydration
import daytrader.gateway.BrokerFill
import daytrader.gateway.BrokerGateway
import daytrader.platform.AppFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Durable account fill ledger. Merges live gateway snapshots and IB sync results;
 * never replaces the store when the broker publishes an empty snapshot on disconnect.
 */
class FileFillsRepository(
    gateway: BrokerGateway,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : FillsRepository {

    private val writer = DebouncedFileWriter<List<BrokerFill>>(scope) { fills ->
        persistFills(fills)
    }
    private val hydration = DeferredFileHydration()

    private val _fills = MutableStateFlow<List<BrokerFill>>(emptyList())
    override val fills: StateFlow<List<BrokerFill>> = _fills.asStateFlow()

    init {
        scope.launchDeferredFileHydration(hydration) {
            _fills.value = loadInitial()
        }
        gateway.fills
            .onEach { incoming -> mergeFromGateway(incoming) }
            .launchIn(scope)
    }

    override suspend fun awaitHydrated() {
        hydration.awaitComplete()
    }

    override fun flushPersistenceBlocking() {
        writer.flushBlocking(_fills.value)
    }

    override fun mergeFills(incoming: List<BrokerFill>): MergeFillsResult {
        if (incoming.isEmpty()) return MergeFillsResult(_fills.value, added = 0, updated = 0)
        return applyMerge(TradesPersistence.mergeFills(_fills.value, incoming))
    }

    override fun mergeFlexFills(incoming: List<BrokerFill>): MergeFillsResult {
        if (incoming.isEmpty()) return MergeFillsResult(_fills.value, added = 0, updated = 0)
        return applyMerge(TradesPersistence.mergeFlexFills(_fills.value, incoming))
    }

    private fun applyMerge(merged: MergeFillsResult): MergeFillsResult {
        if (merged.added == 0 && merged.updated == 0) return merged
        _fills.value = merged.fills
        writer.schedule(merged.fills)
        return merged
    }

    private fun mergeFromGateway(incoming: List<BrokerFill>) {
        mergeFills(incoming)
    }

    private fun loadInitial(): List<BrokerFill> {
        AppFileSystem.ensureAppDataDirectory()
        return JsonFileStore.readTrades()
            ?.fills
            ?.map(TradesPersistence::toDomain)
            .orEmpty()
    }

    private fun persistFills(fills: List<BrokerFill>) {
        JsonFileStore.writeTrades(
            TradesDocument(fills = fills.map(TradesPersistence::toRecord))
        )
    }
}
