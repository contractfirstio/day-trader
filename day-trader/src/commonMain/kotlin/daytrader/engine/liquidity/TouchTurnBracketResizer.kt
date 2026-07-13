package daytrader.engine.liquidity

import daytrader.broker.SymbolMarkets
import daytrader.data.StrategyDeploymentRepository
import daytrader.data.WatchlistRepository
import daytrader.domain.BracketAmendTarget
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnBracketOrderIds
import daytrader.domain.TouchTurnBracketResizeRequest
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TradeSide
import daytrader.domain.WatchlistBracketOrderPlanner
import daytrader.domain.InstrumentOrderSizeRules
import daytrader.domain.orderSizeRules
import daytrader.domain.WatchlistPlanOrderContext
import daytrader.domain.isTouchTurn
import daytrader.domain.withAmendedQuantity
import daytrader.execution.ExecutionManager
import daytrader.gateway.WorkingOrder
import daytrader.presentation.liquidity.LiquidityAllocatorMapper

sealed interface TouchTurnBracketAmendResult {
    data class Success(val newQuantity: Int) : TouchTurnBracketAmendResult
    data class Skipped(val reason: String) : TouchTurnBracketAmendResult
    data class Failed(val message: String) : TouchTurnBracketAmendResult
}

/**
 * Resizes a working bracket at the broker without debiting the liquidity pool.
 * Supports Touch Turn running sessions and watchlist-placed brackets.
 */
class TouchTurnBracketResizer(
    private val executionManager: ExecutionManager?,
    private val deploymentRepository: StrategyDeploymentRepository,
    private val watchlistRepository: WatchlistRepository? = null,
) {
    suspend fun amend(
        target: BracketAmendTarget,
        openOrders: List<WorkingOrder>,
        targetQuantity: Int,
    ): TouchTurnBracketAmendResult = when (target) {
        is BracketAmendTarget.Deployment -> amendDeployment(
            deploymentId = target.deploymentId,
            openOrders = openOrders,
            targetQuantity = targetQuantity,
        )
        is BracketAmendTarget.WatchlistPlan -> amendWatchlistPlan(
            watchlistId = target.watchlistId,
            entryId = target.entryId,
            planId = target.planId,
            openOrders = openOrders,
            targetQuantity = targetQuantity,
        )
        is BracketAmendTarget.OpenBracket -> amendOpenBracket(
            symbolKey = target.symbolKey,
            parentOrderId = target.parentOrderId,
            openOrders = openOrders,
            targetQuantity = targetQuantity,
        )
    }

    suspend fun amend(
        deploymentId: String,
        deployment: StrategyDeployment,
        openOrders: List<WorkingOrder>,
        targetQuantity: Int,
    ): TouchTurnBracketAmendResult = amend(
        target = BracketAmendTarget.Deployment(deploymentId),
        openOrders = openOrders,
        targetQuantity = targetQuantity,
    )

    private suspend fun amendDeployment(
        deploymentId: String,
        openOrders: List<WorkingOrder>,
        targetQuantity: Int,
    ): TouchTurnBracketAmendResult {
        val execution = executionManager ?: return TouchTurnBracketAmendResult.Failed("Execution not available")
        val deployment = deploymentRepository.deployments.value.find { it.id == deploymentId }
            ?: return TouchTurnBracketAmendResult.Skipped("Deployment not found")
        if (!deployment.isTouchTurn) {
            return TouchTurnBracketAmendResult.Skipped("Not a Touch Turn deployment")
        }
        if (deployment.status != DeploymentStatus.RUNNING && deployment.status != DeploymentStatus.STOPPED) {
            return TouchTurnBracketAmendResult.Skipped("Session not active")
        }
        if (targetQuantity <= 0) {
            return TouchTurnBracketAmendResult.Skipped("Quantity must be positive")
        }
        val symbolOrders = SymbolMarkets.openOrdersForDeployment(deployment, openOrders)
        val entryOrder = symbolOrders.firstOrNull { it.parentOrderId == 0 && it.remaining > 0 }
            ?: return TouchTurnBracketAmendResult.Skipped("Entry bracket not eligible for amend")
        val brokerQty = LiquidityAllocatorMapper.brokerEntryQuantity(entryOrder)
        if (targetQuantity <= brokerQty) {
            return TouchTurnBracketAmendResult.Skipped(
                "Target quantity must exceed current broker quantity $brokerQty",
            )
        }
        val resizeRequest = LiquidityAllocatorMapper.buildResizeRequest(
            deployment = deployment,
            openOrders = openOrders,
            newQuantity = targetQuantity,
        ) ?: return TouchTurnBracketAmendResult.Failed("Could not build resize request")

        val resizeResult = execution.resizeTouchTurnBracket(resizeRequest)
        if (resizeResult.isFailure) {
            return TouchTurnBracketAmendResult.Failed(
                resizeResult.exceptionOrNull()?.message ?: "Broker resize failed",
            )
        }

        deploymentRepository.update(deploymentId) { current ->
            current.withAmendedBracketQuantity(targetQuantity)
        }
        return TouchTurnBracketAmendResult.Success(targetQuantity)
    }

    private suspend fun amendWatchlistPlan(
        watchlistId: String,
        entryId: String,
        planId: String,
        openOrders: List<WorkingOrder>,
        targetQuantity: Int,
    ): TouchTurnBracketAmendResult {
        val execution = executionManager ?: return TouchTurnBracketAmendResult.Failed("Execution not available")
        val repository = watchlistRepository
            ?: return TouchTurnBracketAmendResult.Failed("Watchlist not available")
        if (targetQuantity <= 0) {
            return TouchTurnBracketAmendResult.Skipped("Quantity must be positive")
        }
        val context = findWatchlistPlanContext(watchlistId, entryId, planId, repository)
            ?: return TouchTurnBracketAmendResult.Skipped("Watchlist plan not found")
        val plan = context.plan
        if (!plan.hasPlacedOrder || plan.placedOrderIds.isEmpty()) {
            return TouchTurnBracketAmendResult.Skipped("Plan has no working bracket")
        }
        if (TouchTurnOrderRole.ENTRY in plan.executedBracketLegs) {
            return TouchTurnBracketAmendResult.Skipped("Entry already filled")
        }
        val groupOrders = openOrders.filter { order ->
            order.orderId in plan.placedOrderIds || order.parentOrderId in plan.placedOrderIds
        }
        val entryOrder = groupOrders.firstOrNull { it.parentOrderId == 0 && it.remaining > 0 }
            ?: return TouchTurnBracketAmendResult.Skipped("Entry bracket not eligible for amend")
        if (entryOrder.filled > 0) {
            return TouchTurnBracketAmendResult.Skipped("Entry already partially filled")
        }
        val orderSizeRules = context.entry.instrument?.orderSizeRules()
            ?: InstrumentOrderSizeRules.DEFAULT
        val brokerQty = LiquidityAllocatorMapper.brokerEntryQuantity(entryOrder)
        if (targetQuantity <= brokerQty) {
            return TouchTurnBracketAmendResult.Skipped(
                "Target quantity must exceed current broker quantity $brokerQty",
            )
        }
        val orderIds = TouchTurnBracketOrderIds.fromAckOrderIds(plan.placedOrderIds)
            ?: LiquidityAllocatorMapper.resolveBracketOrderIdsFromOrders(groupOrders)
            ?: return TouchTurnBracketAmendResult.Failed("Could not resolve bracket order ids")
        val newPlan = WatchlistBracketOrderPlanner.buildTouchTurnPlan(
            symbol = context.entry.symbol,
            currencyCode = context.entry.currencyCode,
            instrument = context.entry.instrument,
            side = plan.side,
            entryPrice = plan.entryPrice!!,
            stopPrice = plan.stopPrice!!,
            targetPrice = plan.targetPrice!!,
            quantity = targetQuantity,
            options = WatchlistBracketOrderPlanner.optionsFromPlan(plan),
        ).getOrElse { error ->
            return TouchTurnBracketAmendResult.Failed(
                error.message ?: "Could not build bracket plan",
            )
        }
        val resizeRequest = TouchTurnBracketResizeRequest(
            symbol = context.entry.symbol,
            currencyCode = context.entry.currencyCode,
            instrument = context.entry.instrument,
            orderIds = orderIds,
            plan = newPlan,
        )
        val resizeResult = execution.resizeTouchTurnBracket(resizeRequest)
        if (resizeResult.isFailure) {
            return TouchTurnBracketAmendResult.Failed(
                resizeResult.exceptionOrNull()?.message ?: "Broker resize failed",
            )
        }
        repository.updateWatchlist(watchlistId) { watchlist ->
            watchlist.copy(
                entries = watchlist.entries.map { entry ->
                    if (entry.id != entryId) entry
                    else entry.copy(
                        tradePlans = entry.tradePlans.map { tradePlan ->
                            if (tradePlan.id != planId) tradePlan
                            else tradePlan.withAmendedQuantity(targetQuantity)
                        }
                    )
                }
            )
        }
        return TouchTurnBracketAmendResult.Success(targetQuantity)
    }

    private suspend fun amendOpenBracket(
        symbolKey: String,
        parentOrderId: Int,
        openOrders: List<WorkingOrder>,
        targetQuantity: Int,
    ): TouchTurnBracketAmendResult {
        val execution = executionManager ?: return TouchTurnBracketAmendResult.Failed("Execution not available")
        if (targetQuantity <= 0) {
            return TouchTurnBracketAmendResult.Skipped("Quantity must be positive")
        }
        val symbolOrders = openOrders.filter { order ->
            SymbolMarkets.symbolsMatch(symbolKey, order.symbol)
        }
        val entryOrder = symbolOrders.firstOrNull { it.orderId == parentOrderId && it.remaining > 0 }
            ?: symbolOrders.firstOrNull { it.parentOrderId == 0 && it.remaining > 0 }
            ?: return TouchTurnBracketAmendResult.Skipped("Entry bracket not eligible for amend")
        if (entryOrder.filled > 0) {
            return TouchTurnBracketAmendResult.Skipped("Entry already partially filled")
        }
        val brokerQty = LiquidityAllocatorMapper.brokerEntryQuantity(entryOrder)
        if (targetQuantity <= brokerQty) {
            return TouchTurnBracketAmendResult.Skipped(
                "Target quantity must exceed current broker quantity $brokerQty",
            )
        }
        val orderIds = LiquidityAllocatorMapper.resolveBracketOrderIdsFromOrders(symbolOrders)
            ?: return TouchTurnBracketAmendResult.Failed("Could not resolve bracket order ids")
        val bracketOrderIds = orderIds.allIds.toSet()
        val bracketOrders = symbolOrders.filter { order ->
            order.orderId in bracketOrderIds ||
                order.parentOrderId in bracketOrderIds
        }
        val newPlan = buildPlanFromOpenOrders(
            bracketOrders = bracketOrders.ifEmpty { symbolOrders },
            orderIds = orderIds,
            targetQuantity = targetQuantity,
        ) ?: return TouchTurnBracketAmendResult.Failed("Could not build bracket plan from open orders")
        val resizeRequest = TouchTurnBracketResizeRequest(
            symbol = entryOrder.symbol,
            currencyCode = entryOrder.currency,
            instrument = null,
            orderIds = orderIds,
            plan = newPlan,
        )
        val resizeResult = execution.resizeTouchTurnBracket(resizeRequest)
        if (resizeResult.isFailure) {
            return TouchTurnBracketAmendResult.Failed(
                resizeResult.exceptionOrNull()?.message ?: "Broker resize failed",
            )
        }
        return TouchTurnBracketAmendResult.Success(targetQuantity)
    }

    private fun buildPlanFromOpenOrders(
        bracketOrders: List<WorkingOrder>,
        orderIds: TouchTurnBracketOrderIds,
        targetQuantity: Int,
    ): TouchTurnOrderPlan? {
        val entry = bracketOrders.firstOrNull { it.orderId == orderIds.parentOrderId } ?: return null
        val takeProfit = bracketOrders.firstOrNull { it.orderId == orderIds.takeProfitOrderId } ?: return null
        val stop = bracketOrders.firstOrNull { it.orderId == orderIds.stopLossOrderId } ?: return null
        val entryPrice = entry.limitPrice ?: entry.stopPrice ?: return null
        val stopPrice = stop.stopPrice ?: stop.limitPrice ?: return null
        val targetPrice = takeProfit.limitPrice ?: return null
        val side = if (entry.action.equals("BUY", ignoreCase = true)) TradeSide.LONG else TradeSide.SHORT
        val hasAdjustable = orderIds.adjustableStopOrderId != null
        return WatchlistBracketOrderPlanner.buildTouchTurnPlan(
            symbol = entry.symbol,
            currencyCode = entry.currency,
            instrument = null,
            side = side,
            entryPrice = entryPrice,
            stopPrice = stopPrice,
            targetPrice = targetPrice,
            quantity = targetQuantity,
            options = WatchlistBracketOrderPlanner.BracketOrderOptions(
                stopEntry = entry.orderType.equals("STP", ignoreCase = true) ||
                    entry.orderType.equals("STP LMT", ignoreCase = true),
                adjustableTrailingStop = hasAdjustable,
            ),
        ).getOrNull()
    }

    private fun findWatchlistPlanContext(
        watchlistId: String,
        entryId: String,
        planId: String,
        repository: WatchlistRepository,
    ): WatchlistPlanOrderContext? {
        val watchlist = repository.watchlists.value.find { it.id == watchlistId } ?: return null
        val entry = watchlist.entries.find { it.id == entryId } ?: return null
        val plan = entry.tradePlans.find { it.id == planId } ?: return null
        return WatchlistPlanOrderContext(
            watchlistId = watchlistId,
            entry = entry,
            plan = plan,
        )
    }
}

private fun StrategyDeployment.withAmendedBracketQuantity(quantity: Int): StrategyDeployment {
    val session = touchTurnSession ?: return this
    return copy(touchTurnSession = session.copy(plannedQuantity = quantity))
}
