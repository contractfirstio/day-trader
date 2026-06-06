package daytrader.presentation.strategies

import daytrader.domain.DeploymentMarket
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.isTouchTurn
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnPrepareOverallStatus
import daytrader.domain.TouchTurnPrepareStatus
import daytrader.domain.TouchTurnSessionPrepare
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class TouchTurnPrepareCheckRowUi(
    val label: String,
    val status: TouchTurnPrepareStatus,
    val detail: String?
)

data class TouchTurnPrepareUiState(
    val inProgress: Boolean,
    val overallStatus: TouchTurnPrepareOverallStatus?,
    val preparedAtLabel: String?,
    val checks: List<TouchTurnPrepareCheckRowUi>,
    val readyForStart: Boolean,
    val stale: Boolean
)

object TouchTurnPrepareUiMapper {
    fun forDeployment(
        instance: StrategyDeployment,
        prepareInProgress: Boolean,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): TouchTurnPrepareUiState? {
        if (!instance.isTouchTurn) return null
        if (instance.status == DeploymentStatus.RUNNING) return null
        val sessionDate = DeploymentMarket.sessionDateIso(instance)
        val prepare = instance.touchTurnPrepare
        val checks = prepare?.checks.orEmpty().map { row ->
            TouchTurnPrepareCheckRowUi(
                label = row.label,
                status = runCatching { TouchTurnPrepareStatus.valueOf(row.status) }
                    .getOrDefault(TouchTurnPrepareStatus.FAIL),
                detail = row.detail
            )
        }
        val ready = TouchTurnSessionPrepare.isValidForStart(
            prepare = prepare,
            deployment = instance,
            sessionDateIso = sessionDate,
            nowEpochMillis = nowEpochMillis
        )
        val stale = prepare != null && !ready &&
            prepare.sessionDateIso == sessionDate &&
            prepare.instrumentKey == DeploymentMarket.effectiveInstrument(instance).dedupeKey()
        return TouchTurnPrepareUiState(
            inProgress = prepareInProgress,
            overallStatus = prepare?.overall(),
            preparedAtLabel = prepare?.let { formatPreparedAt(it.preparedAtEpochMillis, instance) },
            checks = checks,
            readyForStart = ready,
            stale = stale
        )
    }

    private fun formatPreparedAt(epochMillis: Long, deployment: StrategyDeployment): String {
        val zone = ZoneId.of(DeploymentMarket.effectiveZoneId(deployment))
        return Instant.ofEpochMilli(epochMillis)
            .atZone(zone)
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }
}
