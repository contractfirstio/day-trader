package daytrader.replay

import daytrader.data.persistence.AppDataFiles
import daytrader.gateway.BrokerKind
import java.nio.file.Files
import java.nio.file.Path

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
    val label: String
)

/**
 * Discovers captured session folders under the Day Trader app data directory.
 */
object SessionReplayCatalog {
    private val brokerScopes = listOf(
        BrokerKind.EMULATOR.dataDirectorySegment,
        BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA.dataDirectorySegment,
        BrokerKind.INTERACTIVE_BROKERS.dataDirectorySegment,
        BrokerKind.REPLAY.dataDirectorySegment
    )

    fun discover(baseDataDirectory: String): List<SessionReplayEntry> =
        brokerScopes.flatMap { scope ->
            discoverUnderScope(Path.of(baseDataDirectory).resolve(scope), scope)
        }.sortedByDescending { it.sessionDate.orEmpty() }

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
        val symbol = bundle?.symbol
        val sessionDate = bundle?.sessionDate
        val label = buildString {
            append(symbol ?: deploymentId)
            sessionDate?.let { append(" · ").append(it) }
            append(" (").append(brokerScope).append(')')
        }
        return SessionReplayEntry(
            directoryPath = sessionDir.toString(),
            brokerScope = brokerScope,
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            sessionDate = sessionDate,
            label = label
        )
    }
}
