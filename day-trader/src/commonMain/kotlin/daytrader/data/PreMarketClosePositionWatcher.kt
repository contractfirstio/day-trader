package daytrader.data

import daytrader.gateway.BrokerGateway
import daytrader.broker.SymbolMarkets
import daytrader.domain.DeploymentStatus
import daytrader.domain.PreMarketCloseLogic
import daytrader.domain.RthMarketSessions
import daytrader.domain.TouchTurnLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Logs when an open IB position would be closed [StrategyCatalog.CLOSE_POSITIONS_BEFORE_MARKET_CLOSE_MIN]
 * minutes before that market's RTH close. Does not call IB yet.
 */
class PreMarketClosePositionWatcher(
    private val gateway: BrokerGateway,
    private val repository: StrategyDeploymentRepository,
    private val scope: CoroutineScope
) {
    private val loggedSessionKeys = ConcurrentHashMap.newKeySet<String>()

    fun start() {
        scope.launch {
            while (isActive) {
                delay(POLL_MS)
                checkOpenPositions()
            }
        }
    }

    private fun checkOpenPositions() {
        val now = System.currentTimeMillis()
        val positions = gateway.positions.value.filter { it.quantity != 0 }
        if (positions.isEmpty()) return

        val runningBySymbol = repository.deployments.value
            .filter { it.status == DeploymentStatus.RUNNING }
            .groupBy { SymbolMarkets.normalizeSymbol(it.symbol) }

        for (position in positions) {
            val zoneId = SymbolMarkets.zoneId(position.symbol)
            val session = RthMarketSessions.forZoneId(zoneId)
            if (!TouchTurnLogic.isRthMarketOpen(
                    zoneId = session.zoneId,
                    closeHour = session.closeHour,
                    closeMinute = session.closeMinute,
                    nowEpochMillis = now
                )
            ) {
                continue
            }

            val sessionDate = TouchTurnLogic.sessionDateIsoInMarketZone(zoneId, now)
            val marketClose = TouchTurnLogic.marketCloseEpochMillis(sessionDate, zoneId) ?: continue
            if (!PreMarketCloseLogic.isWithinPreCloseExitWindow(
                    nowEpochMillis = now,
                    marketCloseEpochMillis = marketClose,
                    minutesBeforeClose = StrategyCatalog.CLOSE_POSITIONS_BEFORE_MARKET_CLOSE_MIN
                )
            ) {
                continue
            }

            val logKey = "${sessionDate}|${zoneId}|${SymbolMarkets.normalizeSymbol(position.symbol)}"
            if (!loggedSessionKeys.add(logKey)) continue

            val runningInstanceId = runningBySymbol[SymbolMarkets.normalizeSymbol(position.symbol)]
                ?.firstOrNull()
                ?.id

            PreMarketClosePositionLog.logWouldClosePosition(
                position = position,
                sessionDateIso = sessionDate,
                marketZoneId = zoneId,
                runningInstanceId = runningInstanceId
            )
        }
    }

    private companion object {
        const val POLL_MS = 30_000L
    }
}
