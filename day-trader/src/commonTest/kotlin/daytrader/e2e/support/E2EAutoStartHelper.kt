package daytrader.e2e.support

import daytrader.data.MarketOpenAutoStartLogic
import daytrader.domain.RthMarketSessions
import daytrader.domain.TouchTurnLogic
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment

object E2EAutoStartHelper {
    fun epochMillisAfterMarketOpenDelay(
        sessionDate: String,
        zoneId: String = RthMarketSessions.US.zoneId,
    ): Long {
        val open = TouchTurnLogic.marketOpenEpochMillis(sessionDate, zoneId, null)
            ?: error("no market open epoch for $sessionDate in $zoneId")
        return open + MarketOpenAutoStartLogic.AUTO_START_DELAY_AFTER_OPEN_MS + 1
    }

    fun autoStartEligibleDeployment(
        sessionDate: String,
        deploymentId: String = E2ETestFixtures.DEPLOYMENT_ID,
        symbol: String = E2ETestFixtures.SYMBOL,
    ) = defaultStrategyDeployment(
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        symbol = symbol,
        maxDollars = 500,
        status = DeploymentStatus.STOPPED,
    ).copy(
        id = deploymentId,
        autoStartOnMarketOpen = true,
    )
}
