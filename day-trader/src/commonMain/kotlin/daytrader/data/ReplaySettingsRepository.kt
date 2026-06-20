package daytrader.data

import daytrader.data.persistence.DebouncedFileWriter
import daytrader.data.persistence.DeferredFileHydration
import daytrader.data.persistence.JsonFileStore
import daytrader.data.persistence.ReplaySettingsPersistence
import daytrader.data.persistence.launchDeferredFileHydration
import daytrader.platform.AppFileSystem
import daytrader.replay.ReplaySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

interface ReplaySettingsRepository {
    val settings: StateFlow<ReplaySettings>
    fun update(transform: (ReplaySettings) -> ReplaySettings)
    fun flushPersistence()
    fun flushPersistenceBlocking()
}

class FileReplaySettingsRepository(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : ReplaySettingsRepository {
    private val writer = DebouncedFileWriter<ReplaySettings>(scope) { settings ->
        JsonFileStore.writeReplaySettings(ReplaySettingsPersistence.toDocument(settings))
    }
    private val hydration = DeferredFileHydration()

    private val _settings = MutableStateFlow(ReplaySettings())
    override val settings: StateFlow<ReplaySettings> = _settings.asStateFlow()

    init {
        scope.launchDeferredFileHydration(hydration) {
            _settings.value = loadInitial()
        }
    }

    override fun update(transform: (ReplaySettings) -> ReplaySettings) {
        _settings.update(transform)
        writer.schedule(_settings.value)
    }

    override fun flushPersistence() {
        writer.flush(_settings.value)
    }

    override fun flushPersistenceBlocking() {
        writer.flushBlocking(_settings.value)
    }

    private fun loadInitial(): ReplaySettings {
        AppFileSystem.ensureAppDataDirectory()
        return JsonFileStore.readReplaySettings()
            ?.let(ReplaySettingsPersistence::fromDocument)
            ?: ReplaySettings()
    }
}
