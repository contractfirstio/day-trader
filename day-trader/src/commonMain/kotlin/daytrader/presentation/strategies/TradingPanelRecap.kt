package daytrader.presentation.strategies

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.lastClosedTouchTurnSession
import daytrader.domain.touchTurnRecapRun

object TradingPanelRecap {
    /**
     * Whether the Trading tab should show a closed Touch Turn session recap.
     * [historicRunId] — explicit pick from Session history; always shown when set.
     * Otherwise shows the latest closed run unless dismissed via Reset panel.
     */
    fun showsSessionRecap(
        instance: StrategyDeployment,
        dismissedRecapSessionIdByDeployment: Map<String, String>,
        historicRunId: String? = null,
    ): Boolean {
        if (instance.strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return false
        if (instance.status == DeploymentStatus.RUNNING) return false
        val run = instance.touchTurnRecapRun(historicRunId) ?: return false
        if (historicRunId != null) return true
        return dismissedRecapSessionIdByDeployment[instance.id] != run.id
    }

    /** @see [showsSessionRecap] for the latest closed run only. */
    fun showsLastSession(
        instance: StrategyDeployment,
        dismissedRecapSessionIdByDeployment: Map<String, String>,
    ): Boolean = showsSessionRecap(instance, dismissedRecapSessionIdByDeployment, historicRunId = null)

    /** Whether bid/ask/last should appear on the Trading tab for this deployment. */
    fun showsLiveMarketQuotes(
        instance: StrategyDeployment,
        dismissedRecapSessionIdByDeployment: Map<String, String>,
        historicRunId: String? = null,
    ): Boolean {
        if (instance.status == DeploymentStatus.RUNNING) return true
        if (instance.strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return false
        return showsSessionRecap(instance, dismissedRecapSessionIdByDeployment, historicRunId)
    }
}
