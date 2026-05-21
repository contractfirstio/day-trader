package daytrader.data.persistence

import daytrader.domain.RunStatus
import daytrader.domain.StrategyInstance
import daytrader.domain.StrategyRun
import daytrader.platform.AppFileSystem

internal object LegacyInstancePersistence {
    fun load(): List<StrategyInstance>? {
        val document = JsonFileStore.readLegacyInstances() ?: return null
        if (document.instances.isEmpty()) return null
        return document.instances.map(::migrateInstance)
    }

    private fun migrateInstance(legacy: LegacyInstanceRecord): StrategyInstance {
        val performance = legacy.runs.map { run ->
            StrategyRun(
                id = run.id,
                date = run.sessionDate,
                pnl = run.pnl,
                trades = run.trades,
                maxAtRisk = run.maxDollarsAtRun,
                status = run.status
            )
        }.map { day ->
            if (day.status == RunStatus.IN_PROGRESS) {
                day.copy(
                    pnl = legacy.todayPnL,
                    trades = legacy.tradesToday.coerceAtLeast(day.trades)
                )
            } else {
                day
            }
        }
        return StrategyInstance(
            id = legacy.id,
            strategyType = legacy.strategyType,
            status = legacy.status,
            symbol = legacy.symbol,
            maxDollars = legacy.maxDollars,
            live = legacy.activeExecution,
            performance = performance
        )
    }
}

internal object LegacyStrategiesScreenPersistence {
    fun load(): StrategiesScreenDocument? {
        val legacy = JsonFileStore.readLegacyStrategiesScreen() ?: return null
        return StrategiesScreenDocument(
            selectedInstanceId = legacy.selectedInstanceId,
            search = legacy.searchQuery,
            statusFilter = legacy.instanceFilter.lowercase(),
            strategyFilter = legacy.strategyTypeFilter,
            detailTab = when (legacy.detailTab.uppercase()) {
                "ACTIVITY" -> "live"
                else -> legacy.detailTab.lowercase()
            }
        )
    }
}
