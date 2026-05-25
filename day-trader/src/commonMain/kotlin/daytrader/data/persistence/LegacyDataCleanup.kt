package daytrader.data.persistence

import daytrader.platform.AppFileSystem

/** Removes pre-migration JSON files so only [AppDataFiles.DEPLOYMENTS] and [AppDataFiles.STRATEGIES_SCREEN] remain. */
object LegacyDataCleanup {
    fun removeOrphanedLegacyFiles() {
        AppFileSystem.deleteIfExists(AppDataFiles.LEGACY_INSTANCES_JSON)
        AppFileSystem.deleteIfExists(AppDataFiles.LEGACY_STRATEGY_INSTANCES)
        AppFileSystem.deleteIfExists(AppDataFiles.LEGACY_STRATEGIES_APP_STATE)
    }
}
