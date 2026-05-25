package daytrader.data.persistence

import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategySession
import daytrader.platform.AppFileSystem

internal object LegacyDeploymentPersistence {
    fun load(): List<StrategyDeployment>? {
        val document = JsonFileStore.readLegacyStrategyInstances() ?: return null
        if (document.instances.isEmpty()) return null
        return document.instances.map(::migrateDeployment)
    }

    private fun migrateDeployment(legacy: LegacyDeploymentRecord): StrategyDeployment {
        val sessionHistory = legacy.runs.map { run ->
            StrategySession(
                id = run.id,
                date = run.sessionDate,
                pnl = run.pnl,
                trades = run.trades,
                maxAtRisk = run.maxDollarsAtRun,
                status = run.status
            )
        }.map { day ->
            if (day.status == SessionStatus.IN_PROGRESS) {
                day.copy(
                    pnl = legacy.todayPnL,
                    trades = legacy.tradesToday.coerceAtLeast(day.trades)
                )
            } else {
                day
            }
        }
        return StrategyDeployment(
            id = legacy.id,
            strategyType = legacy.strategyType,
            status = legacy.status,
            symbol = legacy.symbol,
            maxDollars = legacy.maxDollars,
            live = legacy.activeExecution,
            sessionHistory = sessionHistory
        )
    }
}

internal object LegacyStrategiesScreenPersistence {
    fun load(): StrategiesScreenDocument? {
        val legacy = JsonFileStore.readLegacyStrategiesScreen() ?: return null
        return StrategiesScreenDocument(
            selectedDeploymentId = legacy.selectedDeploymentId ?: legacy.selectedInstanceId,
            detailTab = when (legacy.detailTab.uppercase()) {
                "ACTIVITY" -> "live"
                else -> legacy.detailTab.lowercase()
            }
        )
    }
}
