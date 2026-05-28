package daytrader.presentation.strategies

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.lastClosedTouchTurnSession

object TradingPanelRecap {
    /** Whether the Trading tab should show the most recent closed Touch Turn session. */
    fun showsLastSession(
        instance: StrategyDeployment,
        dismissedRecapSessionIdByDeployment: Map<String, String>,
    ): Boolean {
        if (instance.strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return false
        if (instance.status == DeploymentStatus.RUNNING) return false
        val lastClosed = instance.lastClosedTouchTurnSession() ?: return false
        return dismissedRecapSessionIdByDeployment[instance.id] != lastClosed.id
    }
}
