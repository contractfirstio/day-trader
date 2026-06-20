package daytrader.data

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment

object PortfolioExposureCalculator {
    data class Snapshot(
        val runningDeploymentCount: Int,
        val totalMaxAtRiskUsd: Int,
        val maxAtRiskBySymbol: Map<String, Int>,
    )

    fun calculate(deployments: List<StrategyDeployment>): Snapshot {
        val running = deployments.filter { it.status == DeploymentStatus.RUNNING }
        return Snapshot(
            runningDeploymentCount = running.size,
            totalMaxAtRiskUsd = running.sumOf { it.maxDollars },
            maxAtRiskBySymbol = running
                .groupBy { it.symbol.uppercase() }
                .mapValues { (_, instances) -> instances.sumOf { it.maxDollars } },
        )
    }
}

object PortfolioExposureLimits {
    const val ENV_MAX_PORTFOLIO_AT_RISK = "DAY_TRADER_MAX_PORTFOLIO_AT_RISK"

    fun configuredMaxAtRisk(getenv: (String) -> String? = System::getenv): Int? =
        getenv(ENV_MAX_PORTFOLIO_AT_RISK)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }

    fun isOverCap(snapshot: PortfolioExposureCalculator.Snapshot, getenv: (String) -> String? = System::getenv): Boolean {
        val cap = configuredMaxAtRisk(getenv) ?: return false
        return snapshot.totalMaxAtRiskUsd > cap
    }
}
