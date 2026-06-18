package daytrader.replay

import daytrader.broker.SymbolMarkets
import daytrader.broker.emulator.EmulatorSymbolLookup
import daytrader.data.persistence.AppDataFiles
import daytrader.data.persistence.DeploymentsDocument
import daytrader.data.persistence.WatchlistsDocument
import daytrader.diagnostics.SessionManifest
import daytrader.domain.DeploymentMarket
import daytrader.domain.InstrumentMarketResolver
import daytrader.domain.RthMarketSessions
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
    val marketZoneId: String? = null,
    val label: String,
    val captureSummary: SessionReplayCaptureSummary? = null
) {
    val sessionStartedAtLabel: String?
        get() = sessionStartedEpochMs?.let { epoch ->
            SessionReplayCatalog.formatSessionStartedAt(epoch, marketZoneId)
        }
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
    private val ISO_SESSION_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

    fun discover(baseDataDirectory: String): List<SessionReplayEntry> =
        brokerScopes.flatMap { scope ->
            discoverUnderScope(Path.of(baseDataDirectory).resolve(scope), scope)
        }.sortedByDescending { it.sessionStartedEpochMs ?: Long.MIN_VALUE }

    fun discoverUnderScope(scopeRoot: Path, brokerScope: String): List<SessionReplayEntry> {
        val sessionsRoot = scopeRoot.resolve(AppDataFiles.SESSIONS_DIR)
        if (!Files.isDirectory(sessionsRoot)) return emptyList()
        val deploymentCompanyNames = loadDeploymentCompanyNames(scopeRoot)
        val deploymentMarketZoneIds = loadDeploymentMarketZoneIds(scopeRoot)
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
                            deploymentMarketZoneIds = deploymentMarketZoneIds,
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
        toEntry(sessionDir, brokerScope, emptyMap(), emptyMap(), emptyMap())

    fun toEntry(
        sessionDir: Path,
        brokerScope: String,
        deploymentCompanyNames: Map<String, String>,
        watchlistCompanyNames: Map<String, String>
    ): SessionReplayEntry? = toEntry(
        sessionDir = sessionDir,
        brokerScope = brokerScope,
        deploymentCompanyNames = deploymentCompanyNames,
        deploymentMarketZoneIds = emptyMap(),
        watchlistCompanyNames = watchlistCompanyNames
    )

    fun toEntry(
        sessionDir: Path,
        brokerScope: String,
        deploymentCompanyNames: Map<String, String>,
        deploymentMarketZoneIds: Map<String, String>,
        watchlistCompanyNames: Map<String, String>
    ): SessionReplayEntry? {
        val application = sessionDir.resolve(AppDataFiles.SESSION_APPLICATION_LOG)
        val manifest = sessionDir.resolve(AppDataFiles.SESSION_MANIFEST)
        if (!Files.exists(application) && !Files.exists(manifest)) return null
        val deploymentId = sessionDir.parent?.fileName?.toString() ?: return null
        val sessionId = sessionDir.fileName.toString()
        val manifestPath = sessionDir.resolve(AppDataFiles.SESSION_MANIFEST)
        val manifestSummary = readManifestSummary(manifestPath)
        val bundle = runCatching {
            SessionBundleDirectoryReader.loadFromDirectory(sessionDir.toString()).getOrThrow()
        }.getOrNull()
        if (bundle != null && !ReplaySourceValidation.isSupportedReplayCapture(bundle.brokerKind)) return null
        val symbol = bundle?.symbol ?: manifestSummary?.symbol
        val companyName = resolveCompanyName(
            deploymentId = deploymentId,
            symbol = symbol,
            deploymentCompanyNames = deploymentCompanyNames,
            watchlistCompanyNames = watchlistCompanyNames
        )
        val sessionDate = bundle?.sessionDate ?: manifestSummary?.sessionDate
        val sessionStartedEpochMs = bundle?.timeline?.sessionStartedEpochMs ?: manifestSummary?.sessionStartedEpochMs
        val marketZoneId = resolveMarketZoneId(
            bundle = bundle,
            symbol = symbol,
            deploymentId = deploymentId,
            deploymentMarketZoneIds = deploymentMarketZoneIds,
            manifestInstrument = manifestSummary?.instrument
        )
        val label = buildString {
            append(symbol ?: deploymentId)
            sessionDate?.let { append(" · ").append(it) }
            sessionStartedEpochMs?.let { append(" · ").append(formatSessionStartedAt(it, marketZoneId)) }
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
            marketZoneId = marketZoneId,
            label = label,
            captureSummary = bundle?.toReplayCaptureSummary()
        )
    }

    fun resolveMarketZoneId(
        bundle: SessionBundle?,
        symbol: String?,
        deploymentId: String? = null,
        deploymentMarketZoneIds: Map<String, String> = emptyMap(),
        manifestInstrument: daytrader.domain.InstrumentIdentity? = null
    ): String? {
        deploymentId?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
            deploymentMarketZoneIds[id]?.trim()?.takeIf { it.isNotEmpty() }?.let { zone ->
                return RthMarketSessions.forZoneId(zone).zoneId
            }
        }
        bundle?.groundTruth?.runRecord?.marketInputs?.marketZoneId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return RthMarketSessions.forZoneId(it).zoneId }
        bundle?.manifest?.instrument?.let { instrument ->
            return InstrumentMarketResolver.fromIbContract(
                InstrumentMarketResolver.ContractSnapshot(
                    symbol = instrument.symbol,
                    exchange = instrument.exchange,
                    primaryExch = instrument.primaryExch,
                    currency = instrument.currency
                )
            ).marketZoneId
        }
        manifestInstrument?.let { instrument ->
            return InstrumentMarketResolver.fromIbContract(
                InstrumentMarketResolver.ContractSnapshot(
                    symbol = instrument.symbol,
                    exchange = instrument.exchange,
                    primaryExch = instrument.primaryExch,
                    currency = instrument.currency
                )
            ).marketZoneId
        }
        symbol?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return SymbolMarkets.zoneId(it)
        }
        return null
    }

    fun formatSessionStartedAt(epochMs: Long, marketZoneId: String? = null): String {
        val zone = marketZoneId?.let { ZoneId.of(RthMarketSessions.forZoneId(it).zoneId) }
            ?: ZoneId.systemDefault()
        return startedAtFormatter.format(Instant.ofEpochMilli(epochMs).atZone(zone))
    }

    fun SessionReplayEntry.toCaptureRef(): ReplayCaptureRef =
        ReplayCaptureRef(
            directoryPath = directoryPath,
            deploymentId = deploymentId,
            symbol = symbol,
            sessionDate = sessionDate,
            sessionStartedEpochMs = sessionStartedEpochMs
        )

    fun filter(
        entries: List<SessionReplayEntry>,
        symbolQuery: String,
        startedAtQuery: String,
        marketZoneId: String? = null
    ): List<SessionReplayEntry> =
        filterByMarket(filterByStartedAt(filterBySymbol(entries, symbolQuery), startedAtQuery), marketZoneId)

    fun filterByMarket(entries: List<SessionReplayEntry>, marketZoneId: String?): List<SessionReplayEntry> {
        val zoneId = marketZoneId?.trim()?.takeIf { it.isNotEmpty() } ?: return entries
        return entries.filter { entry ->
            entry.effectiveMarketZoneId()?.let { candidate ->
                DeploymentMarket.zonesMatch(zoneId, candidate)
            } == true
        }
    }

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
        entries.mapNotNull { entry -> entry.tradingSessionDate() }
            .distinct()
            .sortedDescending()

    internal fun SessionReplayEntry.tradingSessionDate(): String? =
        sessionDate?.trim()?.takeIf { it.isNotEmpty() }

    /** Market zone for filtering — deployment config, capture metadata, then symbol heuristics. */
    fun SessionReplayEntry.effectiveMarketZoneId(): String? {
        marketZoneId?.trim()?.takeIf { it.isNotEmpty() }?.let { zone ->
            return RthMarketSessions.forZoneId(zone).zoneId
        }
        symbol?.trim()?.takeIf { it.isNotEmpty() }?.let { sym ->
            return SymbolMarkets.zoneId(sym)
        }
        return null
    }

    fun filterForBulkReplay(
        entries: List<SessionReplayEntry>,
        symbolQuery: String,
        sessionDate: String,
        marketZoneId: String
    ): List<SessionReplayEntry> = filter(
        entries = entries,
        symbolQuery = symbolQuery,
        startedAtQuery = sessionDate,
        marketZoneId = marketZoneId
    )

    private fun SessionReplayEntry.filterableSessionDate(): String? =
        tradingSessionDate()
            ?: sessionStartedEpochMs?.let { formatSessionStartedIsoDate(it, marketZoneId) }

    private fun SessionReplayEntry.matchesStartedAtQuery(query: String): Boolean {
        if (query.matches(ISO_SESSION_DATE)) {
            return tradingSessionDate()?.equals(query, ignoreCase = true) == true
        }
        tradingSessionDate()?.contains(query, ignoreCase = true)?.takeIf { it }?.let { return true }
        filterableSessionDate()?.contains(query, ignoreCase = true)?.takeIf { it }?.let { return true }
        val epoch = sessionStartedEpochMs ?: return false
        if (formatSessionStartedAt(epoch, marketZoneId).contains(query, ignoreCase = true)) return true
        if (formatSessionStartedIsoDate(epoch, marketZoneId).contains(query, ignoreCase = true)) return true
        return formatSessionStartedTime(epoch, marketZoneId).contains(query, ignoreCase = true)
    }

    private fun formatSessionStartedIsoDate(epochMs: Long, marketZoneId: String?): String {
        val zone = marketZoneId?.let { ZoneId.of(RthMarketSessions.forZoneId(it).zoneId) }
            ?: ZoneId.systemDefault()
        return startedAtDateFormatter.format(Instant.ofEpochMilli(epochMs).atZone(zone))
    }

    private fun formatSessionStartedTime(epochMs: Long, marketZoneId: String?): String {
        val zone = marketZoneId?.let { ZoneId.of(RthMarketSessions.forZoneId(it).zoneId) }
            ?: ZoneId.systemDefault()
        return startedAtTimeFormatter.format(Instant.ofEpochMilli(epochMs).atZone(zone))
    }

    private data class ManifestSummary(
        val symbol: String,
        val sessionDate: String,
        val sessionStartedEpochMs: Long,
        val instrument: daytrader.domain.InstrumentIdentity?
    )

    private fun readManifestSummary(manifestPath: Path): ManifestSummary? {
        if (!Files.exists(manifestPath)) return null
        return runCatching {
            json.decodeFromString<SessionManifest>(Files.readString(manifestPath))
        }.getOrNull()?.let { manifest ->
            ManifestSummary(
                symbol = manifest.symbol,
                sessionDate = manifest.sessionDate,
                sessionStartedEpochMs = manifest.timeline.sessionStartedEpochMs,
                instrument = manifest.instrument
            )
        }
    }

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

    private fun loadDeploymentMarketZoneIds(scopeRoot: Path): Map<String, String> =
        runCatching {
            val path = scopeRoot.resolve(AppDataFiles.DEPLOYMENTS)
            if (!Files.exists(path)) return emptyMap()
            json.decodeFromString<DeploymentsDocument>(Files.readString(path))
                .deployments
                .mapNotNull { record ->
                    record.configuration.marketZoneId?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { zone -> record.id to zone }
                }
                .toMap()
        }.getOrElse { emptyMap() }

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
