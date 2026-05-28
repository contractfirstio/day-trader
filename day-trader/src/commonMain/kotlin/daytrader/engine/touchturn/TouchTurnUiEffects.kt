package daytrader.engine

import daytrader.presentation.strategies.StartBlockedByPositionAlert
import daytrader.presentation.strategies.StrategyDetailTab

interface TouchTurnUiEffects {
    fun selectDeployment(instanceId: String, tab: StrategyDetailTab)
    fun showStartBlockedAlert(alert: StartBlockedByPositionAlert)
}

object NoOpTouchTurnUiEffects : TouchTurnUiEffects {
    override fun selectDeployment(instanceId: String, tab: StrategyDetailTab) = Unit
    override fun showStartBlockedAlert(alert: StartBlockedByPositionAlert) = Unit
}
