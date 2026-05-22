package daytrader.data

import daytrader.broker.SymbolMarkets
import daytrader.domain.InstanceStatus
import daytrader.domain.StrategyInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Starts stopped instances with [StrategyInstance.autoStartOnMarketOpen] when RTH opens
 * in the symbol's market timezone (once per session day per instance).
 */
class MarketOpenAutoStarter(
    private val repository: StrategyInstanceRepository,
    private val touchTurnBootstrap: TouchTurnSessionBootstrap?,
    private val scope: CoroutineScope,
    private val isGlobalAutoStartEnabled: () -> Boolean = { true },
    private val canStartInstance: (StrategyInstance) -> Boolean = { true },
    private val onInstanceAutoStarted: (instanceId: String) -> Unit = {}
) {
    fun start() {
        scope.launch {
            while (isActive) {
                delay(POLL_MS)
                checkMarketOpens()
            }
        }
    }

    private fun checkMarketOpens() {
        val now = System.currentTimeMillis()
        val zones = repository.instances.value
            .map { SymbolMarkets.zoneId(it.symbol) }
            .toSet()
        for (zone in zones) {
            val sessionDate = MarketOpenAutoStartLogic.sessionDateIfMarketOpen(zone, now) ?: continue
            onMarketOpened(zone, sessionDate)
        }
    }

    private fun onMarketOpened(marketZoneId: String, sessionDate: String) {
        if (!isGlobalAutoStartEnabled()) return
        val candidates = repository.instances.value.filter { instance ->
            SymbolMarkets.zoneId(instance.symbol) == marketZoneId &&
                instance.autoStartOnMarketOpen &&
                instance.status == InstanceStatus.STOPPED &&
                instance.lastAutoStartSessionDate != sessionDate &&
                canStartInstance(instance)
        }
        for (instance in candidates) {
            repository.update(instance.id) { current ->
                InstanceRunController.start(
                    instance = current,
                    sessionDate = sessionDate,
                    touchTurnBootstrap = touchTurnBootstrap,
                    markAutoStarted = true
                )
            }
            repository.flushPersistence()
            onInstanceAutoStarted(instance.id)
        }
    }

    private companion object {
        const val POLL_MS = 1_000L
    }
}
