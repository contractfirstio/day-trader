package daytrader.engine.touchturn

import daytrader.domain.DeploymentMarket
import daytrader.domain.TouchTurnLogic
import daytrader.domain.withSessionFiveMinuteBarsAppended
import daytrader.diagnostics.SessionHistoricalLog
import daytrader.domain.inProgressSession
import daytrader.data.StrategyDeploymentRepository
import daytrader.marketdata.MarketDataProvider

/**
 * Polls closed 5m bars for the full Touch Turn session and persists them on
 * [daytrader.domain.TouchTurnSessionContext.sessionFiveMinuteBars], independent of
 * five-minute confirmation being enabled.
 */
class SessionFiveMinuteBarCollector(
    private val marketData: MarketDataProvider,
    private val repository: StrategyDeploymentRepository,
    private val nowEpochMillis: () -> Long,
    private val delayMillis: suspend (Long) -> Unit,
    private val pollIntervalMs: () -> Long
) {
    suspend fun runUntilSessionEnds(instanceId: String) {
        while (true) {
            val instance = repository.deployments.value.find { it.id == instanceId } ?: return
            if (instance.status != daytrader.domain.DeploymentStatus.RUNNING) return
            val session = instance.touchTurnSession ?: return
            if (instance.inProgressSession() == null) return

            val openingTime = session.openingBarTime ?: session.candle?.time
            val afterBarOpenEpochMs = openingTime
                ?.let { TouchTurnLogic.barStartEpochMillis(it, session.marketZoneId) }
            if (afterBarOpenEpochMs == null) {
                delayMillis(pollIntervalMs())
                continue
            }

            val barsResult = marketData.fetchFiveMinuteBars(
                symbol = instance.symbol,
                instrument = DeploymentMarket.effectiveInstrument(instance),
                afterBarOpenEpochMs = afterBarOpenEpochMs,
                marketZoneId = session.marketZoneId,
                includePrecedingBar = false
            )
            if (barsResult.isSuccess) {
                val bars = barsResult.getOrThrow()
                val known = session.sessionFiveMinuteBars.mapNotNull { it.time }.toSet()
                val fresh = bars.filter { bar ->
                    val time = bar.time ?: return@filter false
                    time !in known
                }
                if (fresh.isNotEmpty()) {
                    fresh.forEach { bar ->
                        SessionHistoricalLog.recordFiveMinuteBar(
                            deploymentId = instanceId,
                            sessionId = instance.inProgressSession()?.id,
                            symbol = instance.symbol,
                            bar = bar,
                            sweepPrice = session.fiveMinuteConfirmation?.sweepPrice
                        )
                    }
                    repository.update(instanceId) { current ->
                        current.withSessionFiveMinuteBarsAppended(fresh)
                    }
                }
            }
            delayMillis(pollIntervalMs())
        }
    }
}
