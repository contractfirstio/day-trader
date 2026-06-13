package daytrader.replay

import daytrader.broker.SymbolMarkets
import daytrader.broker.emulator.EmulatorSymbolLookup
import daytrader.data.persistence.AppDataFiles
import daytrader.data.persistence.DeploymentsDocument
import daytrader.data.persistence.WatchlistsDocument
import daytrader.gateway.BrokerKind
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json

/**
 * A captured session directory that can be loaded for desktop replay.
 */
data class SessionReplayEntry(
    val directoryPath: String,
    val brokerScope: String,
    val deploymentId: String,
    val sessionId: String,
    val symbol: String?,
    val companyName: String? = null,
    val sessionDate: String?,
    val sessionStartedEpochMs: Long?,
    val label: String,
    val captureSummary: SessionReplayCaptureSummary? = null
) {
    val sessionStartedAtLabel: String?
        get() = sessionStartedEpochMs?.let(SessionReplayCatalog::formatSessionStartedAt)
}

/**
 * Discovers captured session folders under the Day Trader app data directory.
 * Excludes `emulator/` and `replay/` — only hybrid and IB captures are listed.
 */
object SessionReplayCatalog {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

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
        val deploymentCompanyNames = loadDeploymentCompanyNames(scopeRoot)
        val watchlistCompanyNames = loadWatchlistCompanyNames(scopeRoot)
        return sessionsRoot.toFile().listFiles()?.asSequence()
            ?.filter { it.isDirectory }
            ?.flatMap { deploymentDir ->
                deploymentDir.listFiles()?.asSequence()
                    ?.filter { it.isDirectory }
                    ?.mapNotNull { sessionDir ->
                        toEntry(
                            sessionDir = sessionDir.toPath(),
                            brokerScope = brokerScope,
                            deploymentCompanyNames = deploymentCompanyNames,
                            watchlistCompanyNames = watchlistCompanyNames
                        )
                    }
                    .orEmpty()
            }
            ?.toList()
            .orEmpty()
    }

    fun entryFromDirectory(sessionDirectoryPath: String, brokerScope: String = "custom"): SessionReplayEntry? =
        toEntry(Path.of(sessionDirectoryPath), brokerScope)

    fun toEntry(sessionDir: Path, brokerScope: String): SessionReplayEntry? =
        toEntry(sessionDir, brokerScope, emptyMap(), emptyMap())

    fun toEntry(
        sessionDir: Path,
        brokerScope: String,
        deploymentCompanyNames: Map<String, String>,
        watchlistCompanyNames: Map<String, String>
    ): SessionReplayEntry? {
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
        val companyName = resolveCompanyName(
            deploymentId = deploymentId,
            symbol = symbol,
            deploymentCompanyNames = deploymentCompanyNames,
            watchlistCompanyNames = watchlistCompanyNames
        )
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
            companyName = companyName,
            sessionDate = sessionDate,
            sessionStartedEpochMs = sessionStartedEpochMs,
            label = label,
            captureSummary = bundle?.toReplayCaptureSummary()
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
            SessionReplaySearch.matches(
                query = query,
                symbol = entry.symbol,
                companyName = entry.companyName,
                deploymentId = entry.deploymentId,
                sessionId = entry.sessionId,
                label = entry.label
            )
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

    private fun loadDeploymentCompanyNames(scopeRoot: Path): Map<String, String> {
        val path = scopeRoot.resolve(AppDataFiles.DEPLOYMENTS)
        if (!Files.exists(path)) return emptyMap()
        return runCatching {
            json.decodeFromString<DeploymentsDocument>(Files.readString(path))
                .deployments
                .mapNotNull { record ->
                    record.configuration.companyName?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { name -> record.id to name }
                }
                .toMap()
        }.getOrElse { emptyMap() }
    }

    private fun loadWatchlistCompanyNames(scopeRoot: Path): Map<String, String> {
        val path = scopeRoot.resolve(AppDataFiles.WATCHLISTS)
        if (!Files.exists(path)) return emptyMap()
        return runCatching {
            json.decodeFromString<WatchlistsDocument>(Files.readString(path))
                .watchlists
                .flatMap { watchlist -> watchlist.entries }
                .mapNotNull { entry ->
                    entry.companyName?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { name -> SymbolMarkets.normalizeSymbol(entry.symbol) to name }
                }
                .toMap()
        }.getOrElse { emptyMap() }
    }

    private fun resolveCompanyName(
        deploymentId: String,
        symbol: String?,
        deploymentCompanyNames: Map<String, String>,
        watchlistCompanyNames: Map<String, String>
    ): String? {
        deploymentCompanyNames[deploymentId]?.let { return it }
        symbol?.let { sym ->
            val normalized = SymbolMarkets.normalizeSymbol(sym)
            watchlistCompanyNames[normalized]?.let { return it }
            EmulatorSymbolLookup.companyName(sym)?.let { return it }
        }
        return null
    }
}
