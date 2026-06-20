package daytrader.replay

import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.sessionRealizedPnL
import daytrader.gateway.BrokerKind

object ReplayBacktestPolicy {
    fun emulatorSeed(bundle: SessionBundle): Long =
        bundle.sessionId.hashCode().toLong() xor bundle.deploymentId.hashCode().toLong()

    fun rulesMatchGroundTruth(deployment: StrategyDeployment, bundle: SessionBundle): Boolean {
        val groundTruth = bundle.groundTruth ?: return false
        val capturedRules = groundTruth.runRecord.rules ?: return false
        val deploymentRules = deployment.touchTurnRules
            ?: TouchTurnRuleConfig.defaultForBrokerKind(BrokerKind.REPLAY)
        return deploymentRules == capturedRules &&
            deployment.maxDollars == groundTruth.runRecord.runContext.maxDollars
    }

    fun useGroundTruthFills(deployment: StrategyDeployment, bundle: SessionBundle): Boolean =
        rulesMatchGroundTruth(deployment, bundle) &&
            bundle.groundTruth!!.dedupedFills.isNotEmpty()

    fun originalPnl(bundle: SessionBundle): Double =
        bundle.groundTruth?.dedupedFills?.sessionRealizedPnL() ?: 0.0

    fun originalOutcome(bundle: SessionBundle): TouchTurnSessionOutcome? =
        bundle.groundTruth?.runRecord?.decision?.outcome
}
