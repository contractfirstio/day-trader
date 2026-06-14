package daytrader.data

import daytrader.domain.StrategyDeployment
import daytrader.domain.withEntryInwardOffsetForBrokerKind
import daytrader.domain.withLiquidityGatesForBrokerKind
import daytrader.gateway.BrokerKind

internal object DeploymentLoadNormalizer {
    fun normalize(
        deployment: StrategyDeployment,
        hadPersistedTouchTurnRules: Boolean,
        brokerKind: BrokerKind
    ): StrategyDeployment {
        if (brokerKind == BrokerKind.REPLAY) return deployment
        if (hadPersistedTouchTurnRules) return deployment
        return deployment.withEntryInwardOffsetForBrokerKind(brokerKind)
            .withLiquidityGatesForBrokerKind(brokerKind)
    }

    /** Legacy file migration: seed broker defaults when rules were never persisted. */
    fun normalizeLegacy(deployments: List<StrategyDeployment>, brokerKind: BrokerKind): List<StrategyDeployment> {
        if (brokerKind == BrokerKind.REPLAY) return deployments
        var updated = deployments.map { it.withEntryInwardOffsetForBrokerKind(brokerKind) }
        updated = updated.map { it.withLiquidityGatesForBrokerKind(brokerKind) }
        return updated
    }
}
