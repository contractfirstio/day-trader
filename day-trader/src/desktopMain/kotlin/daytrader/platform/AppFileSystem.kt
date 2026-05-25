package daytrader.platform

import daytrader.data.persistence.AppDataFiles
import daytrader.gateway.BrokerKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

actual object AppFileSystem {
    private const val APP_FOLDER_NAME = "Day Trader"
    private val envOverride = System.getenv("DAY_TRADER_DATA_DIR")
    private var dataScope: BrokerKind? = null

    actual fun configureDataScope(kind: BrokerKind) {
        dataScope = kind
        migrateLegacyRootDataIfNeeded(kind)
    }

    actual fun appDataDirectory(): String {
        val scope = dataScope ?: error("AppFileSystem.configureDataScope must be called before persistence")
        return baseDataDirectory().resolve(scope.dataDirectorySegment).toString()
    }

    private fun baseDataDirectory(): Path {
        if (!envOverride.isNullOrBlank()) return Path.of(envOverride)
        val home = System.getProperty("user.home") ?: error("user.home is not set")
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val base = when {
            os.contains("mac") || os.contains("darwin") ->
                Path.of(home, "Library", "Application Support", APP_FOLDER_NAME)
            os.contains("win") -> {
                val appData = System.getenv("APPDATA") ?: Path.of(home, "AppData", "Roaming").toString()
                Path.of(appData, APP_FOLDER_NAME)
            }
            else -> Path.of(home, ".local", "share", "day-trader")
        }
        return base
    }

    /**
     * Pre-broker-split installs stored JSON at the app root. Move those files into the IB scope once.
     */
    private fun migrateLegacyRootDataIfNeeded(kind: BrokerKind) {
        if (kind != BrokerKind.INTERACTIVE_BROKERS) return
        val base = baseDataDirectory()
        val targetDir = base.resolve(kind.dataDirectorySegment)
        if (Files.exists(targetDir.resolve(AppDataFiles.INSTANCES))) return

        val legacyFiles = listOf(
            AppDataFiles.INSTANCES,
            AppDataFiles.STRATEGIES_SCREEN,
            AppDataFiles.LEGACY_STRATEGY_INSTANCES,
            AppDataFiles.LEGACY_STRATEGIES_APP_STATE,
        )
        val toMove = legacyFiles.map { base.resolve(it) }.filter { Files.exists(it) }
        if (toMove.isEmpty()) return

        Files.createDirectories(targetDir)
        toMove.forEach { source ->
            Files.move(source, targetDir.resolve(source.fileName), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    actual fun ensureAppDataDirectory() {
        Files.createDirectories(Path.of(appDataDirectory()))
    }

    actual fun readText(fileName: String): String? {
        val path = Path.of(appDataDirectory(), fileName)
        if (!Files.exists(path)) return null
        return Files.readString(path)
    }

    actual fun writeTextAtomic(fileName: String, content: String) {
        ensureAppDataDirectory()
        val target = Path.of(appDataDirectory(), fileName)
        val temp = Path.of(appDataDirectory(), "$fileName.tmp")
        Files.writeString(
            temp,
            content,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    actual fun deleteIfExists(fileName: String) {
        val path = Path.of(appDataDirectory(), fileName)
        Files.deleteIfExists(path)
    }
}
