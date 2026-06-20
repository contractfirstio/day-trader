package daytrader.diagnostics

import daytrader.data.persistence.AppDataFiles
import daytrader.platform.AppFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object DebugBundleExporter {
    private const val HEALTH_SNAPSHOT_FILE = "diagnostics/latest-health.json"
    private val json = Json {
        prettyPrint = true
        encodeDefaults = false
    }

    fun trackedPersistenceFiles(): List<String> =
        listOf(
            AppDataFiles.DEPLOYMENTS,
            AppDataFiles.DEPLOYMENTS_BACKUP,
            AppDataFiles.STRATEGIES_SCREEN,
            AppDataFiles.STRATEGIES_SCREEN_BACKUP,
            AppDataFiles.WATCHLISTS,
            AppDataFiles.WATCHLISTS_BACKUP,
            AppDataFiles.LIQUIDITY_BUCKETS,
            AppDataFiles.LIQUIDITY_BUCKETS_BACKUP,
            AppDataFiles.REPLAY_SETTINGS,
        ).map(AppFileSystem::dataFilePath)

    fun export(snapshot: AppHealthSnapshot): String {
        val encoded = json.encodeToString(DebugBundleRecord.from(snapshot))
        AppFileSystem.writeTextAtomic(HEALTH_SNAPSHOT_FILE, encoded)
        return AppFileSystem.dataFilePath(HEALTH_SNAPSHOT_FILE)
    }

    @Serializable
    private data class DebugBundleRecord(
        val capturedAtEpochMs: Long,
        val brokerKind: String,
        val dataDirectory: String,
        val executionConnection: String,
        val marketDataConnection: String?,
        val runningSessionCount: Int,
        val runningSessionSymbols: List<String>,
        val activeQuoteCount: Int,
        val openOrderCount: Int,
        val openPositionCount: Int,
        val trackedDataFiles: List<String>,
    ) {
        companion object {
            fun from(snapshot: AppHealthSnapshot): DebugBundleRecord =
                DebugBundleRecord(
                    capturedAtEpochMs = snapshot.capturedAtEpochMs,
                    brokerKind = snapshot.brokerKind,
                    dataDirectory = snapshot.dataDirectory,
                    executionConnection = snapshot.executionConnection,
                    marketDataConnection = snapshot.marketDataConnection,
                    runningSessionCount = snapshot.runningSessionCount,
                    runningSessionSymbols = snapshot.runningSessionSymbols,
                    activeQuoteCount = snapshot.activeQuoteCount,
                    openOrderCount = snapshot.openOrderCount,
                    openPositionCount = snapshot.openPositionCount,
                    trackedDataFiles = snapshot.trackedDataFiles,
                )
        }
    }
}
