package daytrader.presentation.liquidity

import daytrader.broker.SymbolMarkets
import daytrader.domain.DeploymentStatus
import daytrader.domain.InstrumentOrderSizeRules
import daytrader.domain.LiquidityBucketLogic
import daytrader.domain.orderSizeRules
import daytrader.domain.LiquidityBucketState
import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnAdjustableStop
import daytrader.domain.TouchTurnBracketOrderIds
import daytrader.domain.TouchTurnBracketResizeRequest
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnPlannedOrder
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnTradeSide
import daytrader.domain.inProgressSession
import daytrader.domain.isTouchTurn
import daytrader.domain.rollups
import daytrader.domain.rollupsForConfiguration
import daytrader.gateway.LiveQuote
import daytrader.gateway.WorkingOrder
import daytrader.presentation.Formatters
import daytrader.presentation.strategies.SessionRollupCache
import daytrader.presentation.strategies.TouchTurnQuoteStripFormat
import kotlin.math.abs

object LiquidityAllocatorMapper {
    fun buildUiState(
        deployments: List<StrategyDeployment>,
        openOrders: List<WorkingOrder>,
        quotes: Map<String, LiveQuote>,
        bucketState: LiquidityBucketState,
        sessionDate: String,
        selectedCurrency: String,
        allocations: Map<String, Int>,
        applyingDeploymentIds: Set<String>,
        applyErrors: Map<String, String>,
        sessionRollupCache: SessionRollupCache? = null,
    ): LiquidityAllocatorUiState {
        val currency = LiquidityBucketLogic.normalizeCurrency(selectedCurrency)
        val bucket = LiquidityBucketLogic.rollBucketForDate(
            LiquidityBucketLogic.bucketForCurrency(bucketState, currency),
            sessionDate
        )
        val allocatedTotal = allocations.values.sum()
        val rows = buildRows(
            deployments = deployments,
            openOrders = openOrders,
            quotes = quotes,
            selectedCurrency = currency,
            allocations = allocations,
            applyingDeploymentIds = applyingDeploymentIds,
            applyErrors = applyErrors,
            sessionRollupCache = sessionRollupCache,
        )
        val currencyOptions = buildCurrencyOptions(deployments, bucketState, sessionDate)
        return LiquidityAllocatorUiState(
            sessionDate = sessionDate,
            selectedCurrency = currency,
            currencyOptions = currencyOptions,
            availableLiquidity = bucket.available,
            allocatedPending = allocatedTotal,
            remainingLiquidity = (bucket.available - allocatedTotal).coerceAtLeast(0),
            creditCount = bucket.credits.size,
            canClearLiquidity = bucket.available > 0 || bucket.credits.isNotEmpty(),
            rows = rows,
            lastUpdatedEpochMs = 0L,
        )
    }

    fun buildRows(
        deployments: List<StrategyDeployment>,
        openOrders: List<WorkingOrder>,
        quotes: Map<String, LiveQuote>,
        selectedCurrency: String,
        allocations: Map<String, Int> = emptyMap(),
        applyingDeploymentIds: Set<String> = emptySet(),
        applyErrors: Map<String, String> = emptyMap(),
        sessionRollupCache: SessionRollupCache? = null,
    ): List<LiquidityAllocatorRowUi> {
        val currency = LiquidityBucketLogic.normalizeCurrency(selectedCurrency)
        return deployments.mapNotNull { deployment ->
            toRow(
                deployment = deployment,
                openOrders = openOrders,
                quotes = quotes,
                currency = currency,
                allocationDollars = allocations[deployment.id] ?: 0,
                isApplying = deployment.id in applyingDeploymentIds,
                applyError = applyErrors[deployment.id],
                sessionRollupCache = sessionRollupCache,
            )
        }.sortedBy { it.symbol }
    }

    fun buildRowForDeployment(
        deployment: StrategyDeployment,
        openOrders: List<WorkingOrder>,
        quotes: Map<String, LiveQuote>,
        selectedCurrency: String,
        allocationDollars: Int,
        sessionRollupCache: SessionRollupCache? = null,
    ): LiquidityAllocatorRowUi? {
        val currency = LiquidityBucketLogic.normalizeCurrency(selectedCurrency)
        return toRow(
            deployment = deployment,
            openOrders = openOrders,
            quotes = quotes,
            currency = currency,
            allocationDollars = allocationDollars,
            isApplying = false,
            applyError = null,
            sessionRollupCache = sessionRollupCache,
        )
    }

    private fun buildCurrencyOptions(
        deployments: List<StrategyDeployment>,
        bucketState: LiquidityBucketState,
        sessionDate: String
    ): List<LiquidityCurrencyOptionUi> {
        val fromDeployments = deployments.map { LiquidityBucketLogic.normalizeCurrency(it.currencyCode) }
        val fromBuckets = bucketState.buckets.values
            .filter { LiquidityBucketLogic.rollBucketForDate(it, sessionDate).available > 0 }
            .map { it.currencyCode }
        return (fromDeployments + fromBuckets).distinct().sorted().map { code ->
            val bucket = LiquidityBucketLogic.rollBucketForDate(
                LiquidityBucketLogic.bucketForCurrency(bucketState, code),
                sessionDate
            )
            LiquidityCurrencyOptionUi(currencyCode = code, available = bucket.available)
        }
    }

    private fun toRow(
        deployment: StrategyDeployment,
        openOrders: List<WorkingOrder>,
        quotes: Map<String, LiveQuote>,
        currency: String,
        allocationDollars: Int,
        isApplying: Boolean,
        applyError: String?,
        sessionRollupCache: SessionRollupCache? = null,
    ): LiquidityAllocatorRowUi? {
        if (!deployment.isTouchTurn) return null
        if (LiquidityBucketLogic.normalizeCurrency(deployment.currencyCode) != currency) return null
        if (deployment.status != DeploymentStatus.RUNNING) return null
        val session = deployment.touchTurnSession ?: return null
        if (!session.ordersPlacedForSession) return null
        if (session.milestones.positionOpenedAt != null) return null
        val bracket = session.plannedBracket ?: return null
        val symbolOrders = SymbolMarkets.openOrdersForDeployment(deployment, openOrders)
        val entryOrder = symbolOrders.firstOrNull { it.parentOrderId == 0 && it.remaining > 0 } ?: return null
        if (entryOrder.filled > 0) return null

        val quote = quotes[SymbolMarkets.normalizeSymbol(deployment.symbol)]
        val bid = quote?.bid
        val ask = quote?.ask
        val last = quote?.last
        val invertTradeSide = session.rules.invertTradeSide
        val fillGap = entryFillGap(bracket.side, bracket.entry, bid, ask, invertTradeSide)
        val touchable = when {
            bid != null && ask != null ->
                TouchTurnLogic.liveEntryTouchable(
                    setup = bracket.toSetup(session),
                    bid = bid,
                    ask = ask,
                    invertTradeSide = invertTradeSide
                )
            else -> null
        }
        val closedSessions = deployment.sessionHistory.filter {
            it.status == daytrader.domain.SessionStatus.CLOSED
        }
        val configRollup = sessionRollupCache?.rollupsForDeploymentConfiguration(
            deployment = deployment,
            closedSessions = closedSessions,
            asOfSessionDate = session.sessionDate,
        ) ?: closedSessions.rollupsForConfiguration(session.sessionDate, deployment)
        val additionalQty = if (allocationDollars > 0) {
            TouchTurnOrderPlanner.suggestedQuantity(
                maxDollars = allocationDollars,
                entryPrice = bracket.entry,
                orderSizeRules = deployment.instrument?.orderSizeRules() ?: InstrumentOrderSizeRules.DEFAULT
            ) ?: 0
        } else {
            0
        }
        val previewQty = entryOrder.quantity + additionalQty
        val riskPerShare = abs(bracket.entry - bracket.stopLoss)

        return LiquidityAllocatorRowUi(
            deploymentId = deployment.id,
            sessionId = deployment.inProgressSession()?.id.orEmpty(),
            symbol = deployment.symbol,
            companyName = deployment.companyName,
            currencyCode = currency,
            sideLabel = TouchTurnLogic.tradeSideLabel(bracket.side),
            entryPrice = bracket.entry,
            entryPriceLabel = Formatters.price(bracket.entry),
            currentQuantity = entryOrder.quantity,
            allocationDollars = allocationDollars,
            previewQuantity = previewQty,
            previewNotionalLabel = Formatters.money(bracket.entry * previewQty, currency),
            previewRiskAtStopLabel = Formatters.money(riskPerShare * previewQty, currency),
            distanceToEntryLabel = fillGap?.let {
                TouchTurnQuoteStripFormat.gapLabel(it, currency, deployment.instrument?.primaryExch)
            } ?: "—",
            distanceToEntry = fillGap,
            entryTouchable = touchable,
            winRateLabel = Formatters.winRate(configRollup.winDays, configRollup.lossDays),
            winRateSampleSize = configRollup.tradedDays,
            winDays = configRollup.winDays,
            lossDays = configRollup.lossDays,
            bracketOrderIds = session.bracketOrderIds,
            isApplying = isApplying,
            applyError = applyError
        )
    }

    fun buildResizeRequest(
        deployment: StrategyDeployment,
        openOrders: List<WorkingOrder>,
        newQuantity: Int
    ): TouchTurnBracketResizeRequest? {
        val session = deployment.touchTurnSession ?: return null
        val bracket = session.plannedBracket ?: return null
        val orderIds = resolveBracketOrderIds(deployment, openOrders) ?: return null
        val plan = buildPlanForQuantity(deployment, bracket, newQuantity, session.rules) ?: return null
        return TouchTurnBracketResizeRequest(
            symbol = deployment.symbol,
            currencyCode = deployment.currencyCode,
            instrument = deployment.instrument,
            orderIds = orderIds,
            plan = plan
        )
    }

    fun resolveBracketOrderIds(
        deployment: StrategyDeployment,
        openOrders: List<WorkingOrder>
    ): TouchTurnBracketOrderIds? {
        deployment.touchTurnSession?.bracketOrderIds?.let { return it }
        val symbolOrders = SymbolMarkets.openOrdersForDeployment(deployment, openOrders)
        val entry = symbolOrders.firstOrNull { it.parentOrderId == 0 } ?: return null
        val children = symbolOrders.filter { it.parentOrderId == entry.orderId }
        val takeProfit = children.firstOrNull { it.orderType.equals("LMT", ignoreCase = true) }
        val stop = children.firstOrNull {
            it.orderType.equals("STP", ignoreCase = true) ||
                it.orderType.equals("TRAIL", ignoreCase = true)
        } ?: children.firstOrNull { it.orderId != takeProfit?.orderId }
        if (takeProfit == null || stop == null) return null
        val adjustable = children.firstOrNull { it.isTrailAdjustment }
        return TouchTurnBracketOrderIds(
            parentOrderId = entry.orderId,
            takeProfitOrderId = takeProfit.orderId,
            stopLossOrderId = stop.orderId,
            adjustableStopOrderId = adjustable?.orderId
        )
    }

    private fun buildPlanForQuantity(
        deployment: StrategyDeployment,
        bracket: daytrader.domain.TouchTurnPlannedBracket,
        quantity: Int,
        rules: TouchTurnRuleConfig
    ): TouchTurnOrderPlan? {
        if (quantity <= 0) return null
        val exitAction = when (bracket.side) {
            TouchTurnTradeSide.SHORT -> "BUY"
            TouchTurnTradeSide.LONG -> "SELL"
        }
        val entryAction = when (bracket.side) {
            TouchTurnTradeSide.SHORT -> "SELL"
            TouchTurnTradeSide.LONG -> "BUY"
        }
        val adjustableStop = rules.computeAdjustableStop(
            entry = bracket.entry,
            stopLoss = bracket.stopLoss,
            takeProfit = bracket.takeProfit
        )
        return TouchTurnOrderPlan(
            symbol = deployment.symbol,
            currencyCode = deployment.currencyCode,
            instrument = deployment.instrument,
            side = bracket.side,
            quantity = quantity,
            orders = listOf(
                TouchTurnPlannedOrder(
                    role = TouchTurnOrderRole.ENTRY,
                    action = entryAction,
                    orderType = TouchTurnOrderPlanner.entryOrderType(rules),
                    quantity = quantity,
                    price = bracket.entry
                ),
                TouchTurnPlannedOrder(
                    role = TouchTurnOrderRole.TAKE_PROFIT,
                    action = exitAction,
                    orderType = "LMT",
                    quantity = quantity,
                    price = bracket.takeProfit
                ),
                TouchTurnPlannedOrder(
                    role = TouchTurnOrderRole.STOP_LOSS,
                    action = exitAction,
                    orderType = "STP",
                    quantity = quantity,
                    price = bracket.stopLoss,
                    trailTriggerPrice = adjustableStop?.triggerPrice ?: bracket.trailTriggerPrice,
                    trailArmStopPrice = adjustableStop?.armStopPrice
                )
            )
        )
    }

    private fun entryFillGap(
        side: TouchTurnTradeSide,
        entry: Double,
        bid: Double?,
        ask: Double?,
        invertTradeSide: Boolean
    ): Double? = if (invertTradeSide) {
        when (side) {
            TouchTurnTradeSide.LONG -> ask?.let { (entry - it).coerceAtLeast(0.0) }
            TouchTurnTradeSide.SHORT -> bid?.let { (it - entry).coerceAtLeast(0.0) }
        }
    } else {
        when (side) {
            TouchTurnTradeSide.LONG -> ask?.let { (it - entry).coerceAtLeast(0.0) }
            TouchTurnTradeSide.SHORT -> bid?.let { (entry - it).coerceAtLeast(0.0) }
        }
    }

    private fun daytrader.domain.TouchTurnPlannedBracket.toSetup(
        session: daytrader.domain.TouchTurnSessionContext
    ) = daytrader.domain.TouchTurnBracketSetup(
        range = session.setup?.range ?: abs(takeProfit - entry),
        rangeThreshold = session.rangeThreshold,
        isLiquidityCandle = true,
        candleColor = session.setup?.candleColor ?: daytrader.domain.FirstCandleColor.GREEN,
        side = side,
        entry = entry,
        stopLoss = stopLoss,
        takeProfit = takeProfit
    )
}
