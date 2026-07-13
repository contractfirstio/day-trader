package daytrader.presentation.strategies

import daytrader.broker.SymbolMarkets
import daytrader.domain.BracketAmendTarget
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.Watchlist
import daytrader.domain.WatchlistPlanOrderContext
import daytrader.domain.WatchlistPlanOrderLinks
import daytrader.domain.isTouchTurn
import daytrader.gateway.WorkingOrder
import daytrader.presentation.Formatters
import daytrader.presentation.liquidity.LiquidityAllocatorMapper

data class TouchTurnBracketAmendUiState(
    val target: BracketAmendTarget,
    /** Broker entry quantity — matches the open-orders list. */
    val currentQuantity: Int,
    val currencyCode: String,
    val entryPriceLabel: String,
    val isApplying: Boolean,
    val error: String?,
    val successMessage: String? = null,
) {
    val amendKey: String get() = target.amendKey
}

object TouchTurnBracketAmendUiMapper {
    fun resolve(
        deployment: StrategyDeployment,
        openOrders: List<WorkingOrder>,
        isApplying: Boolean,
        error: String?,
        successMessage: String? = null,
    ): TouchTurnBracketAmendUiState? {
        if (!deployment.isTouchTurn) return null
        if (deployment.status != DeploymentStatus.RUNNING && deployment.status != DeploymentStatus.STOPPED) {
            return null
        }
        val session = deployment.touchTurnSession ?: return null
        if (!session.ordersPlacedForSession) return null
        if (session.milestones.positionOpenedAt != null) return null
        val bracket = session.plannedBracket ?: return null
        val symbolOrders = SymbolMarkets.openOrdersForDeployment(deployment, openOrders)
        val entryOrder = symbolOrders.firstOrNull { it.parentOrderId == 0 && it.remaining > 0 } ?: return null
        if (entryOrder.filled > 0) return null
        return TouchTurnBracketAmendUiState(
            target = BracketAmendTarget.Deployment(deployment.id),
            currentQuantity = LiquidityAllocatorMapper.brokerEntryQuantity(entryOrder),
            currencyCode = deployment.currencyCode,
            entryPriceLabel = Formatters.price(bracket.entry),
            isApplying = isApplying,
            error = error,
            successMessage = successMessage,
        )
    }

    fun resolveOpenBracket(
        symbolKey: String,
        groupOrders: List<WorkingOrder>,
        isApplying: Boolean,
        error: String?,
        successMessage: String? = null,
    ): TouchTurnBracketAmendUiState? {
        if (groupOrders.size < 2) return null
        val entryOrder = groupOrders.firstOrNull { it.parentOrderId == 0 && it.remaining > 0 } ?: return null
        if (entryOrder.filled > 0) return null
        val orderIds = LiquidityAllocatorMapper.resolveBracketOrderIdsFromOrders(groupOrders) ?: return null
        val entryPrice = entryOrder.limitPrice ?: entryOrder.stopPrice ?: return null
        return TouchTurnBracketAmendUiState(
            target = BracketAmendTarget.OpenBracket(
                symbolKey = symbolKey,
                parentOrderId = orderIds.parentOrderId,
            ),
            currentQuantity = LiquidityAllocatorMapper.brokerEntryQuantity(entryOrder),
            currencyCode = entryOrder.currency,
            entryPriceLabel = Formatters.price(entryPrice),
            isApplying = isApplying,
            error = error,
            successMessage = successMessage,
        )
    }

    fun resolveWatchlistPlan(
        context: WatchlistPlanOrderContext,
        groupOrders: List<WorkingOrder>,
        isApplying: Boolean,
        error: String?,
        successMessage: String? = null,
    ): TouchTurnBracketAmendUiState? {
        val plan = context.plan
        if (!plan.hasPlacedOrder || plan.placedOrderIds.isEmpty()) return null
        if (TouchTurnOrderRole.ENTRY in plan.executedBracketLegs) return null
        val entryPrice = plan.entryPrice ?: return null
        val entryOrder = groupOrders.firstOrNull {
            it.parentOrderId == 0 && it.remaining > 0 && it.orderId in plan.placedOrderIds
        } ?: groupOrders.firstOrNull { it.parentOrderId == 0 && it.remaining > 0 }
            ?: return null
        if (entryOrder.filled > 0) return null
        return TouchTurnBracketAmendUiState(
            target = BracketAmendTarget.WatchlistPlan(
                watchlistId = context.watchlistId,
                entryId = context.entry.id,
                planId = plan.id,
            ),
            currentQuantity = LiquidityAllocatorMapper.brokerEntryQuantity(entryOrder),
            currencyCode = context.entry.currencyCode,
            entryPriceLabel = Formatters.price(entryPrice),
            isApplying = isApplying,
            error = error,
            successMessage = successMessage,
        )
    }

    fun resolveForOrderGroup(
        symbolKey: String,
        groupOrders: List<WorkingOrder>,
        deployments: List<StrategyDeployment>,
        watchlists: List<Watchlist>,
        allOpenOrders: List<WorkingOrder>,
        isApplying: (String) -> Boolean,
        errorFor: (String) -> String?,
        successFor: (String) -> String?,
    ): TouchTurnBracketAmendUiState? {
        deploymentForOrderGroup(symbolKey, groupOrders, deployments)?.let { deployment ->
            val amendKey = BracketAmendTarget.Deployment(deployment.id).amendKey
            return resolve(
                deployment = deployment,
                openOrders = allOpenOrders,
                isApplying = isApplying(amendKey),
                error = errorFor(amendKey),
                successMessage = successFor(amendKey),
            )
        }
        WatchlistPlanOrderLinks.planContextForOrderGroup(groupOrders, watchlists)?.let { context ->
            return resolveWatchlistPlan(
                context = context,
                groupOrders = groupOrders,
                isApplying = isApplying(context.targetAmendKey()),
                error = errorFor(context.targetAmendKey()),
                successMessage = successFor(context.targetAmendKey()),
            )
        }
        val openKey = BracketAmendTarget.OpenBracket(
            symbolKey = symbolKey,
            parentOrderId = groupOrders.firstOrNull { it.parentOrderId == 0 }?.orderId ?: return null,
        ).amendKey
        return resolveOpenBracket(
            symbolKey = symbolKey,
            groupOrders = groupOrders,
            isApplying = isApplying(openKey),
            error = errorFor(openKey),
            successMessage = successFor(openKey),
        )
    }

    fun deploymentForOrderGroup(
        symbolKey: String,
        groupOrders: List<WorkingOrder>,
        deployments: List<StrategyDeployment>,
    ): StrategyDeployment? {
        val entryOrderId = groupOrders.firstOrNull { it.parentOrderId == 0 }?.orderId ?: return null
        deployments.filter { deployment ->
            deployment.isTouchTurn &&
                SymbolMarkets.symbolsMatch(deployment.symbol, symbolKey) &&
                deployment.touchTurnSession?.bracketOrderIds?.parentOrderId == entryOrderId
        }.minByOrNull { deployment ->
            when (deployment.status) {
                DeploymentStatus.RUNNING -> 0
                DeploymentStatus.STOPPED -> 1
                else -> 2
            }
        }?.let { return it }
        val symbolMatches = deployments.filter { deployment ->
            deployment.isTouchTurn &&
                deployment.status == DeploymentStatus.RUNNING &&
                SymbolMarkets.symbolsMatch(deployment.symbol, symbolKey)
        }
        if (symbolMatches.isEmpty()) return null
        symbolMatches.firstOrNull { deployment ->
            deployment.touchTurnSession?.bracketOrderIds?.parentOrderId == entryOrderId
        }?.let { return it }
        if (symbolMatches.size == 1) {
            val deployment = symbolMatches.single()
            if (SymbolMarkets.openOrdersForDeployment(deployment, groupOrders)
                    .any { order -> order.orderId == entryOrderId }
            ) {
                return deployment
            }
        }
        return null
    }

    private fun WatchlistPlanOrderContext.targetAmendKey(): String =
        BracketAmendTarget.WatchlistPlan(
            watchlistId = watchlistId,
            entryId = entry.id,
            planId = plan.id,
        ).amendKey
}
