package daytrader.data

import daytrader.data.persistence.DebouncedFileWriter
import daytrader.data.persistence.DeferredFileHydration
import daytrader.data.persistence.JsonFileStore
import daytrader.data.persistence.LegacyDataCleanup
import daytrader.data.persistence.LegacyStrategiesScreenPersistence
import daytrader.data.persistence.StrategiesAppStatePersistence
import daytrader.data.persistence.launchDeferredFileHydration
import daytrader.domain.StrategyType
import daytrader.platform.AppFileSystem
import daytrader.presentation.strategies.DeploymentFilter
import daytrader.presentation.strategies.StrategyDetailTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class StrategiesAppState(
    val selectedDeploymentId: String? = null,
    val searchQuery: String = "",
    val deploymentFilter: DeploymentFilter = DeploymentFilter.ALL,
    val strategyTypeFilter: StrategyType? = null,
    val detailTab: StrategyDetailTab = StrategyDetailTab.CONFIGURATION,
    /** Master switch: when false, no deployment auto-starts at market open. */
    val globalAutoStartEnabled: Boolean = true,
    /** When true, no-trade liquidity is auto-redistributed at open + 16 min per market zone. */
    val autoLiquidityFlushEnabled: Boolean = false,
    /** Keys "${zoneId}:${sessionDate}" for completed auto flushes (restart idempotency). */
    val flushedLiquidityZoneDates: Set<String> = emptySet(),
    /**
     * deploymentId → closed session run id hidden on the Trading tab.
     * A new closed run with a different id shows the recap again.
     */
    val tradingPanelDismissedRecapSessionId: Map<String, String> = emptyMap(),
)

interface StrategiesAppStateRepository {
    val state: StateFlow<StrategiesAppState>
    fun update(transform: (StrategiesAppState) -> StrategiesAppState)
    fun flushPersistence()
    fun flushPersistenceBlocking()
}

class FileStrategiesAppStateRepository(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : StrategiesAppStateRepository {
    private val writer = DebouncedFileWriter<StrategiesAppState>(scope) { state ->
        JsonFileStore.writeStrategiesScreen(StrategiesAppStatePersistence.toDocument(state))
        LegacyDataCleanup.removeOrphanedLegacyFiles()
    }
    private val hydration = DeferredFileHydration()

    private val _state = MutableStateFlow(StrategiesAppState())
    override val state: StateFlow<StrategiesAppState> = _state.asStateFlow()

    init {
        scope.launchDeferredFileHydration(hydration) {
            _state.value = loadInitial()
        }
    }

    override fun update(transform: (StrategiesAppState) -> StrategiesAppState) {
        _state.update(transform)
        writer.schedule(_state.value)
    }

    override fun flushPersistence() {
        writer.flush(_state.value)
    }

    override fun flushPersistenceBlocking() {
        writer.flushBlocking(_state.value)
    }

    private fun loadInitial(): StrategiesAppState {
        AppFileSystem.ensureAppDataDirectory()
        val fromNew = JsonFileStore.readStrategiesScreen()?.let(StrategiesAppStatePersistence::fromDocument)
        if (fromNew != null) return fromNew

        val fromLegacyDoc = LegacyStrategiesScreenPersistence.load()
        if (fromLegacyDoc != null) {
            val state = StrategiesAppStatePersistence.fromDocument(fromLegacyDoc)
            writer.persistNow(state)
            return state
        }

        return StrategiesAppState()
    }
}
