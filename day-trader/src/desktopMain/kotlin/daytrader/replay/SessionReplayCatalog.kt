package daytrader.replay

import daytrader.data.persistence.AppDataFiles
import daytrader.gateway.BrokerKind
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A captured session directory that can be loaded for desktop replay.
 */
data class SessionReplayEntry(
    val directoryPath: String,
    val brokerScope: String,
    val deploymentId: String,
    val sessionId: String,
    val symbol: String?,
    val sessionDate: String?,
    val sessionStartedEpochMs: Long?,
    val label: String
) {
    val sessionStartedAtLabel: String?
        get() = sessionStartedEpochMs?.let(SessionReplayCatalog::formatSessionStartedAt)
}

/**
 * Discovers captured session folders under the Day Trader app data directory.
 * Excludes `emulator/` and `replay/` — only hybrid and IB captures are listed.
 */
object SessionReplayCatalog {
    private val brokerScopes = listOf(
        BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA.dataDirectorySegment,
        BrokerKind.INTERACTIVE_BROKERS.dataDirectorySegment
    )

    private val startedAtFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm:ss")
    private val startedAtDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val startedAtTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun discover(baseDataDirectory: String): List<SessionReplayEntry> =
        brokerScopes.flatMap { scope ->
            discoverUnderScope(Path.of(baseDataDirectory).resolve(scope), scope)
        }.sortedByDescending { it.sessionStartedEpochMs ?: Long.MIN_VALUE }

    fun discoverUnderScope(scopeRoot: Path, brokerScope: String): List<SessionReplayEntry> {
        val sessionsRoot = scopeRoot.resolve(AppDataFiles.SESSIONS_DIR)
        if (!Files.isDirectory(sessionsRoot)) return emptyList()
        return sessionsRoot.toFile().listFiles()?.asSequence()
            ?.filter { it.isDirectory }
            ?.flatMap { deploymentDir ->
                deploymentDir.listFiles()?.asSequence()
                    ?.filter { it.isDirectory }
                    ?.mapNotNull { sessionDir ->
                        toEntry(sessionDir.toPath(), brokerScope)
                    }
                    .orEmpty()
            }
            ?.toList()
            .orEmpty()
    }

    fun entryFromDirectory(sessionDirectoryPath: String, brokerScope: String = "custom"): SessionReplayEntry? =
        toEntry(Path.of(sessionDirectoryPath), brokerScope)

    fun toEntry(sessionDir: Path, brokerScope: String): SessionReplayEntry? {
        val application = sessionDir.resolve(AppDataFiles.SESSION_APPLICATION_LOG)
        val manifest = sessionDir.resolve(AppDataFiles.SESSION_MANIFEST)
        if (!Files.exists(application) && !Files.exists(manifest)) return null
        val deploymentId = sessionDir.parent?.fileName?.toString() ?: return null
        val sessionId = sessionDir.fileName.toString()
        val bundle = runCatching {
            SessionBundleDirectoryReader.loadFromDirectory(sessionDir.toString()).getOrThrow()
        }.getOrNull()
        if (bundle != null && !ReplaySourceValidation.isSupportedReplayCapture(bundle.brokerKind)) return null
        val symbol = bundle?.symbol
        val sessionDate = bundle?.sessionDate
        val sessionStartedEpochMs = bundle?.timeline?.sessionStartedEpochMs
        val label = buildString {
            append(symbol ?: deploymentId)
            sessionDate?.let { append(" · ").append(it) }
            sessionStartedEpochMs?.let { append(" · ").append(formatSessionStartedAt(it)) }
            append(" · ").append(sessionId)
            append(" (").append(brokerScope).append(')')
        }
        return SessionReplayEntry(
            directoryPath = sessionDir.toString(),
            brokerScope = brokerScope,
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            sessionDate = sessionDate,
            sessionStartedEpochMs = sessionStartedEpochMs,
            label = label
        )
    }

    fun formatSessionStartedAt(epochMs: Long): String =
        startedAtFormatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    fun SessionReplayEntry.toCaptureRef(): ReplayCaptureRef =
        ReplayCaptureRef(
            directoryPath = directoryPath,
            deploymentId = deploymentId,
            symbol = symbol,
            sessionDate = sessionDate,
            sessionStartedEpochMs = sessionStartedEpochMs
        )

    fun filter(entries: List<SessionReplayEntry>, symbolQuery: String, startedAtQuery: String): List<SessionReplayEntry> =
        filterByStartedAt(filterBySymbol(entries, symbolQuery), startedAtQuery)

    fun filterBySymbol(entries: List<SessionReplayEntry>, symbolQuery: String): List<SessionReplayEntry> {
        val query = symbolQuery.trim()
        if (query.isEmpty()) return entries
        return entries.filter { entry ->
            entry.symbol?.contains(query, ignoreCase = true) == true
        }
    }

    fun filterByStartedAt(entries: List<SessionReplayEntry>, startedAtQuery: String): List<SessionReplayEntry> {
        val query = startedAtQuery.trim()
        if (query.isEmpty()) return entries
        return entries.filter { entry -> entry.matchesStartedAtQuery(query) }
    }

    fun distinctSymbols(entries: List<SessionReplayEntry>): List<String> =
        entries.mapNotNull { entry -> entry.symbol?.trim()?.takeIf { it.isNotEmpty() } }
            .distinctBy { it.uppercase() }
            .sortedBy { it.uppercase() }

    fun distinctSessionDates(entries: List<SessionReplayEntry>): List<String> =
        entries.mapNotNull { entry -> entry.filterableSessionDate() }
            .distinct()
            .sortedDescending()

    private fun SessionReplayEntry.filterableSessionDate(): String? =
        sessionDate?.trim()?.takeIf { it.isNotEmpty() }
            ?: sessionStartedEpochMs?.let { formatSessionStartedIsoDate(it) }

    private fun SessionReplayEntry.matchesStartedAtQuery(query: String): Boolean {
        filterableSessionDate()?.contains(query, ignoreCase = true)?.takeIf { it }?.let { return true }
        val epoch = sessionStartedEpochMs ?: return false
        if (formatSessionStartedAt(epoch).contains(query, ignoreCase = true)) return true
        if (formatSessionStartedIsoDate(epoch).contains(query, ignoreCase = true)) return true
        return formatSessionStartedTime(epoch).contains(query, ignoreCase = true)
    }

    private fun formatSessionStartedIsoDate(epochMs: Long): String =
        startedAtDateFormatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    private fun formatSessionStartedTime(epochMs: Long): String =
        startedAtTimeFormatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}
