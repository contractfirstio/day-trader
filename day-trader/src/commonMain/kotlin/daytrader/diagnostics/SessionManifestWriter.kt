package daytrader.diagnostics

import daytrader.data.persistence.AppDataFiles
import daytrader.data.persistence.JsonFileStore
import daytrader.domain.DeploymentMarket
import daytrader.domain.InstrumentIdentity
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategySession
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnRunRecord
import daytrader.platform.AppFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Writes `manifest.json` per session for replay timeline anchoring.
 *
 * Files: `sessions/{deploymentId}/{sessionId}/manifest.json`
 *
 * Disabled when `DAY_TRADER_SESSION_MANIFEST=false`.
 */
object SessionManifestWriter {
    private val json = Json {
        encodeDefaults = false
        prettyPrint = true
    }

    private val enabled: Boolean
        get() = System.getenv("DAY_TRADER_SESSION_MANIFEST")
            ?.equals("false", ignoreCase = true) != true

    fun sessionStarted(
        deployment: StrategyDeployment,
        session: StrategySession,
        startedEpochMs: Long
    ) {
        if (!enabled) return
        val manifest = SessionManifest(
            version = MANIFEST_VERSION,
            brokerKind = currentBrokerKindName(),
            deploymentId = deployment.id,
            sessionId = session.id,
            symbol = deployment.symbol,
            instrument = DeploymentMarket.effectiveInstrument(deployment),
            sessionDate = session.date,
            timeline = SessionManifestTimeline(
                sessionStartedEpochMs = startedEpochMs,
                sessionStartedAt = session.startedAt
            )
        )
        writeManifest(deployment.id, session.id, manifest)
    }

    fun sessionClosed(
        deployment: StrategyDeployment,
        session: StrategySession,
        runRecord: TouchTurnRunRecord?,
        stoppedEpochMs: Long
    ) {
        if (!enabled) return
        val path = AppDataFiles.sessionManifestFileName(deployment.id, session.id)
        val existing = runCatching {
            AppFileSystem.readText(path)?.let {
                json.decodeFromString(SessionManifest.serializer(), it)
            }
        }.getOrNull()
        val started = existing?.timeline ?: SessionManifestTimeline(
            sessionStartedEpochMs = stoppedEpochMs,
            sessionStartedAt = session.startedAt
        )
        val manifest = SessionManifest(
            version = MANIFEST_VERSION,
            brokerKind = existing?.brokerKind ?: currentBrokerKindName(),
            deploymentId = deployment.id,
            sessionId = session.id,
            symbol = deployment.symbol,
            instrument = existing?.instrument ?: DeploymentMarket.effectiveInstrument(deployment),
            sessionDate = session.date,
            timeline = started.copy(
                sessionStoppedEpochMs = stoppedEpochMs,
                sessionStoppedAt = session.stoppedAt,
                milestones = runRecord?.milestones ?: started.milestones
            )
        )
        writeManifest(deployment.id, session.id, manifest)
    }

    private fun writeManifest(deploymentId: String, sessionId: String, manifest: SessionManifest) {
        val path = AppDataFiles.sessionManifestFileName(deploymentId, sessionId)
        runCatching {
            JsonFileStore.writeSessionFile(path, json.encodeToString(SessionManifest.serializer(), manifest))
        }
    }

    private fun currentBrokerKindName(): String? =
        runCatching { AppFileSystem.currentDataScope().name }.getOrNull()

    private const val MANIFEST_VERSION = 1
}

@Serializable
data class SessionManifest(
    val version: Int,
    val brokerKind: String? = null,
    val deploymentId: String,
    val sessionId: String,
    val symbol: String,
    val instrument: InstrumentIdentity? = null,
    val sessionDate: String,
    val timeline: SessionManifestTimeline
)

@Serializable
data class SessionManifestTimeline(
    val sessionStartedEpochMs: Long,
    val sessionStartedAt: String,
    val sessionStoppedEpochMs: Long? = null,
    val sessionStoppedAt: String? = null,
    val milestones: TouchTurnMilestoneTimestamps? = null
)
