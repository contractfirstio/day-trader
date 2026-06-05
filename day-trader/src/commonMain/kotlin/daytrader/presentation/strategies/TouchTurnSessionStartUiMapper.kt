package daytrader.presentation.strategies

import daytrader.domain.DeploymentMarket
import daytrader.domain.inProgressSession
import daytrader.domain.DeploymentStatus
import daytrader.domain.RthMarketSessions
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategySession
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnPrepareSnapshot
import daytrader.domain.TouchTurnPrepareStatus
import daytrader.domain.TouchTurnRunContext
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.presentation.Formatters
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class TouchTurnSessionStartUi(
    val headline: String,
    val detail: String?,
    val startedAtLabel: String?,
    val stoppedAtLabel: String?,
    val startedByLabel: String?,
    val brokerLabel: String?,
    val marketLabel: String?,
    val sessionDateLabel: String?,
    val maxRiskLabel: String,
    val bootstrapPathLabel: String?,
    val prepareChecks: List<TouchTurnPrepareCheckRowUi>,
    val prepareOverallLabel: String?,
    val preparePreparedAtLabel: String?
)

object TouchTurnSessionStartUiMapper {
    fun forLive(
        instance: StrategyDeployment,
        session: TouchTurnSessionContext?,
        lastClosedRun: StrategySession?,
        graphCaption: String? = null
    ): TouchTurnSessionStartUi {
        val run = instance.inProgressSession() ?: lastClosedRun
        val zoneId = session?.marketZoneId ?: DeploymentMarket.effectiveZoneId(instance)
        val currency = session?.currencyCode ?: DeploymentMarket.effectiveCurrencyCode(instance)
        val sessionDate = session?.sessionDate ?: run?.date ?: DeploymentMarket.sessionDateIso(instance)
        val snapshot = session?.prepareSnapshot
        val startedAt = run?.startedAt?.takeIf { it.isNotBlank() }
            ?: Formatters.milestoneTimeFromIso(session?.milestones?.startingSessionAt)
        val stoppedAt = run?.stoppedAt?.takeIf { it.isNotBlank() }
        val startedBy = run?.touchTurnStartedBy
        val (headline, detail) = liveHeadline(
            instance = instance,
            session = session,
            graphCaption = graphCaption
        )
        return TouchTurnSessionStartUi(
            headline = headline,
            detail = detail,
            startedAtLabel = startedAt,
            stoppedAtLabel = stoppedAt,
            startedByLabel = startedBy?.let(::startedByLabel),
            brokerLabel = null,
            marketLabel = marketListingLabel(zoneId, currency, instance),
            sessionDateLabel = sessionDate,
            maxRiskLabel = Formatters.currencyPlain(instance.maxDollars.toDouble()),
            bootstrapPathLabel = bootstrapPathLabel(snapshot),
            prepareChecks = prepareChecksFromSnapshot(snapshot),
            prepareOverallLabel = snapshot?.overall()?.name?.let(::prepareOverallLabel),
            preparePreparedAtLabel = snapshot?.preparedAtEpochMillis?.let {
                formatPreparedAt(it, zoneId)
            }
        )
    }

    fun forHistory(
        instance: StrategyDeployment,
        run: StrategySession,
        runContext: TouchTurnRunContext
    ): TouchTurnSessionStartUi {
        val snapshot = runContext.prepareSnapshot
        val marketInputs = run.touchTurnRunRecord?.marketInputs
        val zoneId = marketInputs?.marketZoneId ?: DeploymentMarket.effectiveZoneId(instance)
        val currency = marketInputs?.currencyCode ?: DeploymentMarket.effectiveCurrencyCode(instance)
        return TouchTurnSessionStartUi(
            headline = "Session started",
            detail = "Pre-flight and bootstrap path for this run.",
            startedAtLabel = Formatters.runStartTimeDisplay(run.startedAt).takeIf { run.startedAt.isNotBlank() },
            stoppedAtLabel = Formatters.runStartTimeDisplay(run.stoppedAt).takeIf { run.stoppedAt.isNotBlank() },
            startedByLabel = startedByLabel(runContext.startedBy),
            brokerLabel = brokerLabel(runContext),
            marketLabel = marketListingLabel(zoneId, currency, instance),
            sessionDateLabel = run.date,
            maxRiskLabel = Formatters.maxAtRisk(runContext.maxDollars),
            bootstrapPathLabel = bootstrapPathLabel(snapshot),
            prepareChecks = prepareChecksFromSnapshot(snapshot),
            prepareOverallLabel = snapshot?.overall()?.name?.let(::prepareOverallLabel),
            preparePreparedAtLabel = snapshot?.preparedAtEpochMillis?.let {
                formatPreparedAt(it, zoneId)
            }
        )
    }

    private fun liveHeadline(
        instance: StrategyDeployment,
        session: TouchTurnSessionContext?,
        graphCaption: String?
    ): Pair<String, String?> = when {
        instance.status == DeploymentStatus.RUNNING && session?.status == TouchTurnCandleStatus.LOADING ->
            "Session started" to "Loading opening bar and 14-day ADR from broker…"
        instance.status == DeploymentStatus.RUNNING && session == null ->
            "Arming session" to "Touch Turn session context is initializing…"
        instance.status == DeploymentStatus.RUNNING ->
            "Session running" to (graphCaption?.takeIf { it.isNotBlank() }
                ?: "Follow the pipeline for bar close, liquidity, and orders.")
        session != null || instance.inProgressSession() != null ->
            "Session ended" to "Review pre-flight checks and bootstrap path for this run."
        else ->
            "Deployment stopped" to "Start a session to arm the next run."
    }

    private fun prepareChecksFromSnapshot(snapshot: TouchTurnPrepareSnapshot?): List<TouchTurnPrepareCheckRowUi> =
        snapshot?.checks.orEmpty().map { row ->
            TouchTurnPrepareCheckRowUi(
                label = row.label,
                status = runCatching { TouchTurnPrepareStatus.valueOf(row.status) }
                    .getOrDefault(TouchTurnPrepareStatus.FAIL),
                detail = row.detail
            )
        }

    private fun bootstrapPathLabel(snapshot: TouchTurnPrepareSnapshot?): String? =
        when (snapshot?.bootstrapReusedFromPrepare) {
            true -> "Reused Prepare cache — skipped IB bootstrap fetch on Start"
            false -> "Fetched bootstrap from broker on Start"
            null -> null
        }

    private fun prepareOverallLabel(status: String): String = when (status.uppercase()) {
        "PASS" -> "Pre-flight passed"
        "WARN" -> "Pre-flight passed (warnings)"
        else -> "Pre-flight failed"
    }

    private fun startedByLabel(startedBy: TouchTurnSessionStartedBy): String = when (startedBy) {
        TouchTurnSessionStartedBy.MANUAL -> "Manual start"
        TouchTurnSessionStartedBy.AUTO_MARKET_OPEN -> "Auto start at market open"
    }

    private fun brokerLabel(context: TouchTurnRunContext): String = when (context.brokerKind) {
        BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA -> "Paper · IB market data"
        BrokerKind.REPLAY -> "Replay"
        BrokerKind.EMULATOR -> "Emulator"
        BrokerKind.INTERACTIVE_BROKERS -> "Interactive Brokers"
        null -> when (context.brokerId) {
            BrokerId.INTERACTIVE_BROKERS -> "Interactive Brokers"
            BrokerId.EMULATOR -> "Emulator"
        }
    }

    private fun marketListingLabel(
        zoneId: String,
        currency: String,
        instance: StrategyDeployment
    ): String {
        val session = RthMarketSessions.forZoneId(zoneId)
        val instrument = DeploymentMarket.effectiveInstrument(instance)
        return "${session.label} · $currency · ${instrument.primaryExch ?: instrument.exchange}"
    }

    private fun formatPreparedAt(epochMillis: Long, zoneId: String): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.of(zoneId))
            .format(DateTimeFormatter.ofPattern("HH:mm"))
}
