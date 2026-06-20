package daytrader.platform

import daytrader.data.persistence.AppDataFiles
import daytrader.gateway.BrokerKind
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

actual object AppFileSystem {
    /** Serializes atomic JSON/document writes (deployments, watchlists, session manifests). */
    private val documentWriteLock = Any()
    /** Serializes append-only diagnostic JSONL without blocking document writes. */
    private val logAppendLock = Any()
    private const val APP_FOLDER_NAME = "Day Trader"
    private val envOverride = System.getenv("DAY_TRADER_DATA_DIR")
    private val launchId: String by lazy { buildLaunchId() }
    private var dataScope: BrokerKind? = null

    actual fun configureDataScope(kind: BrokerKind) {
        dataScope = kind
        migrateLegacyRootDataIfNeeded(kind)
        migrateLegacyWatchlistsToEmulatorIfNeeded(kind)
    }

    actual fun currentDataScope(): BrokerKind =
        dataScope ?: error("AppFileSystem.configureDataScope must be called before persistence")

    actual fun appDataDirectory(): String {
        val scope = dataScope ?: error("AppFileSystem.configureDataScope must be called before persistence")
        return stableBaseDataDirectory().resolve(scope.dataDirectorySegment).toString()
    }

    actual fun applicationDataRoot(): String = stableBaseDataDirectory().toString()

    private fun stableBaseDataDirectory(): Path =
        if (!envOverride.isNullOrBlank()) Path.of(envOverride) else defaultBaseDataDirectory()

    private fun traceRunBaseDirectory(): Path =
        stableBaseDataDirectory().resolve("runs").resolve(launchId)

    private fun defaultBaseDataDirectory(): Path {
        val home = System.getProperty("user.home") ?: error("user.home is not set")
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") ->
                Path.of(home, "Library", "Application Support", APP_FOLDER_NAME)
            os.contains("win") -> {
                val appData = System.getenv("APPDATA") ?: Path.of(home, "AppData", "Roaming").toString()
                Path.of(appData, APP_FOLDER_NAME)
            }
            else -> Path.of(home, ".local", "share", "day-trader")
        }
    }

    private fun buildLaunchId(): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        val pid = ProcessHandle.current().pid()
        return "run-$stamp-$pid"
    }

    /**
     * Pre-broker-split installs stored JSON at the app root. Move those files into the IB scope once.
     */
    private fun migrateLegacyRootDataIfNeeded(kind: BrokerKind) {
        if (kind != BrokerKind.INTERACTIVE_BROKERS) return
        val base = stableBaseDataDirectory()
        val targetDir = base.resolve(kind.dataDirectorySegment)
        if (Files.exists(targetDir.resolve(AppDataFiles.DEPLOYMENTS))) return

        val legacyFiles = listOf(
            AppDataFiles.DEPLOYMENTS,
            AppDataFiles.LEGACY_INSTANCES_JSON,
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

    /**
     * Pre-scope installs stored one shared watchlists file at the app root (from emulator use).
     * Move it into the emulator scope once; hybrid and IB keep their own empty or existing files.
     */
    private fun migrateLegacyWatchlistsToEmulatorIfNeeded(kind: BrokerKind) {
        if (kind != BrokerKind.EMULATOR) return
        val base = stableBaseDataDirectory()
        val legacyRoot = base.resolve(AppDataFiles.WATCHLISTS)
        val emulatorDir = base.resolve(BrokerKind.EMULATOR.dataDirectorySegment)
        val emulatorTarget = emulatorDir.resolve(AppDataFiles.WATCHLISTS)
        if (Files.exists(emulatorTarget)) {
            if (Files.exists(legacyRoot)) {
                Files.deleteIfExists(legacyRoot)
            }
            return
        }
        if (!Files.exists(legacyRoot)) return
        Files.createDirectories(emulatorDir)
        Files.move(legacyRoot, emulatorTarget, StandardCopyOption.REPLACE_EXISTING)
    }

    actual fun ensureAppDataDirectory() {
        Files.createDirectories(Path.of(appDataDirectory()))
    }

    actual fun readText(fileName: String): String? {
        val path = resolveDataPath(fileName)
        if (!Files.exists(path)) return null
        return Files.readString(path)
    }

    actual fun writeTextAtomic(fileName: String, content: String) {
        synchronized(documentWriteLock) {
            ensureAppDataDirectory()
            val target = resolveDataPath(fileName)
            val dir = target.parent ?: Path.of(appDataDirectory())
            Files.createDirectories(dir)
            val temp = Files.createTempFile(dir, "write-", ".tmp")
            try {
                Files.writeString(
                    temp,
                    content,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
                try {
                    Files.move(
                        temp,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }

    actual fun appendLine(fileName: String, line: String) {
        synchronized(logAppendLock) {
            ensureAppDataDirectory()
            val path = resolveDataPath(fileName)
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(
                path,
                line,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE
            )
        }
    }

    actual fun deleteIfExists(fileName: String) {
        val path = resolveDataPath(fileName)
        Files.deleteIfExists(path)
    }

    actual fun dataFilePath(fileName: String): String = resolveDataPath(fileName).toString()

    actual fun readApplicationRootText(fileName: String): String? {
        val path = stableBaseDataDirectory().resolve(fileName)
        if (!Files.exists(path)) return null
        return Files.readString(path)
    }

    actual fun writeApplicationRootTextAtomic(fileName: String, content: String) {
        synchronized(documentWriteLock) {
            val target = stableBaseDataDirectory().resolve(fileName)
            Files.createDirectories(target.parent ?: stableBaseDataDirectory())
            val dir = target.parent ?: stableBaseDataDirectory()
            val temp = Files.createTempFile(dir, "write-", ".tmp")
            try {
                Files.writeString(
                    temp,
                    content,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
                try {
                    Files.move(
                        temp,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }

    private fun resolveDataPath(fileName: String): Path {
        val scope = dataScope ?: error("AppFileSystem.configureDataScope must be called before persistence")
        val scopeBase = if (isRunScopedFile(fileName)) {
            traceRunBaseDirectory().resolve(scope.dataDirectorySegment)
        } else {
            stableBaseDataDirectory().resolve(scope.dataDirectorySegment)
        }
        return scopeBase.resolve(fileName)
    }

    private fun isRunScopedFile(fileName: String): Boolean =
        isIbPriceLogFile(fileName)

    private fun isIbPriceLogFile(fileName: String): Boolean {
        val normalized = fileName.replace('\\', '/')
        return normalized == AppDataFiles.IB_PRICES_DIR ||
            normalized.startsWith("${AppDataFiles.IB_PRICES_DIR}/")
    }
}
