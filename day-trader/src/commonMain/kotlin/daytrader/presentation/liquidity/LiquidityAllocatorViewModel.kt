package daytrader.presentation.liquidity

import daytrader.broker.SymbolMarkets
import daytrader.data.LiquidityBucketRepository
import daytrader.data.OpenOrderRepository
import daytrader.data.StrategyDeploymentRepository
import daytrader.domain.DeploymentStatus
import daytrader.domain.LiquidityBucketLogic
import daytrader.domain.StrategyDeployment
import daytrader.domain.isTouchTurn
import daytrader.execution.ExecutionManager
import daytrader.gateway.BrokerGateway
import daytrader.gateway.LiveQuote
import daytrader.gateway.WorkingOrder
import daytrader.platform.currentSessionDateIso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import daytrader.presentation.strategies.SessionRollupCache
import daytrader.presentation.navigation.AppScreen
import daytrader.presentation.ui.UiCoroutineScopes
import daytrader.presentation.ui.launchUiAction
import daytrader.presentation.ui.safeUiEmit
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@OptIn(FlowPreview::class)
class LiquidityAllocatorViewModel(
    private val deploymentRepository: StrategyDeploymentRepository,
    private val openOrderRepository: OpenOrderRepository,
    private val liquidityBucketRepository: LiquidityBucketRepository,
    private val brokerGateway: BrokerGateway?,
    private val executionManager: ExecutionManager?,
    private val skipQuoteUiRefresh: () -> Boolean = { false },
    scope: CoroutineScope = UiCoroutineScopes.forScreen(AppScreen.LIQUIDITY, "LiquidityAllocatorViewModel"),
) {
    private val scope = scope
    private val _uiState = MutableStateFlow(LiquidityAllocatorUiState())
    val uiState: StateFlow<LiquidityAllocatorUiState> = _uiState.asStateFlow()

    private var selectedCurrency = "USD"
    private val allocations = mutableMapOf<String, Int>()
    private val applyingDeploymentIds = mutableSetOf<String>()
    private val applyErrors = mutableMapOf<String, String>()

    private var latestDeployments: List<StrategyDeployment> = emptyList()
    private var latestOpenOrders: List<WorkingOrder> = emptyList()
    private var latestQuotes: Map<String, LiveQuote> = emptyMap()
    private var latestBucketState = daytrader.domain.LiquidityBucketState()
    private val sessionRollupCache = SessionRollupCache()

    init {
        combine(
            deploymentRepository.deployments,
            openOrderRepository.openOrders,
            liquidityBucketRepository.state
        ) { deployments, openOrders, bucketState ->
            Triple(deployments, openOrders, bucketState)
        }.onEach { (deployments, openOrders, bucketState) ->
            latestDeployments = deployments
            latestOpenOrders = openOrders
            latestBucketState = bucketState
            sessionRollupCache.clear()
            refreshQuotes(deployments)
            publishUi()
        }.launchIn(scope)

        brokerGateway?.quotes?.let { quotesFlow ->
            quotesFlow
                .onEach { latestQuotes = it }
                .launchIn(scope)
            quotesFlow
                .sample(QUOTE_UI_REFRESH_INTERVAL_MS.milliseconds)
                .onEach {
                    if (!skipQuoteUiRefresh()) {
                        publishUi()
                    }
                }
                .launchIn(scope)
        }
    }

    fun onCurrencySelected(currencyCode: String) {
        selectedCurrency = LiquidityBucketLogic.normalizeCurrency(currencyCode)
        allocations.clear()
        applyErrors.clear()
        publishUi()
    }

    fun onAllocationChanged(deploymentId: String, dollars: Int) {
        val sanitized = dollars.coerceAtLeast(0)
        if (sanitized == 0) {
            allocations.remove(deploymentId)
        } else {
            allocations[deploymentId] = sanitized
        }
        applyErrors.remove(deploymentId)
        publishUi()
    }

    fun distributeEvenly() {
        val context = distributionContext() ?: return
        val perRow = context.available / context.rows.size
        val remainder = context.available % context.rows.size
        applyDistribution(
            context.rows.mapIndexed { index, row ->
                row.deploymentId to (perRow + if (index == 0) remainder else 0)
            }.toMap()
        )
    }

    fun distributeByWinRate() {
        val context = distributionContext() ?: return
        applyDistribution(
            distributeLiquidityByBayesianWinRate(
                rows = context.rows.map { row ->
                    row.deploymentId to (row.winDays to row.lossDays)
                },
                available = context.available,
            )
        )
    }

    private data class DistributionContext(
        val available: Int,
        val rows: List<LiquidityAllocatorRowUi>,
    )

    private fun distributionContext(): DistributionContext? {
        val sessionDate = currentSessionDateIso()
        val currency = LiquidityBucketLogic.normalizeCurrency(selectedCurrency)
        val bucket = LiquidityBucketLogic.rollBucketForDate(
            LiquidityBucketLogic.bucketForCurrency(latestBucketState, currency),
            sessionDate
        )
        val rows = LiquidityAllocatorMapper.buildRows(
            deployments = latestDeployments,
            openOrders = latestOpenOrders,
            quotes = latestQuotes,
            selectedCurrency = currency,
            sessionRollupCache = sessionRollupCache,
        )
        if (rows.isEmpty() || bucket.available <= 0) return null
        return DistributionContext(available = bucket.available, rows = rows)
    }

    private fun applyDistribution(distribution: Map<String, Int>) {
        allocations.clear()
        distribution.forEach { (deploymentId, amount) ->
            if (amount > 0) allocations[deploymentId] = amount
        }
        applyErrors.clear()
        publishUi()
    }

    fun applyRow(deploymentId: String) {
        val allocation = allocations[deploymentId] ?: return
        if (allocation <= 0) return
        scope.launchUiAction(AppScreen.LIQUIDITY, "applyRow") { applyInternal(deploymentId, allocation) }
    }

    fun applyAll() {
        val pending = allocations.filterValues { it > 0 }
        if (pending.isEmpty()) return
        scope.launchUiAction(AppScreen.LIQUIDITY, "applyAll") {
            pending.forEach { (deploymentId, amount) ->
                applyInternal(deploymentId, amount)
            }
        }
    }

    private suspend fun applyInternal(deploymentId: String, allocationDollars: Int) {
        val execution = executionManager ?: run {
            setApplyError(deploymentId, "Execution not available")
            return
        }
        val deployment = latestDeployments.find { it.id == deploymentId } ?: return
        if (!deployment.isTouchTurn || deployment.status != DeploymentStatus.RUNNING) {
            setApplyError(deploymentId, "Session no longer active")
            return
        }
        val row = LiquidityAllocatorMapper.buildRowForDeployment(
            deployment = deployment,
            openOrders = latestOpenOrders,
            quotes = latestQuotes,
            selectedCurrency = selectedCurrency,
            allocationDollars = allocationDollars,
            sessionRollupCache = sessionRollupCache,
        ) ?: run {
            setApplyError(deploymentId, "Entry order no longer eligible")
            return
        }
        val resizeRequest = LiquidityAllocatorMapper.buildResizeRequest(
            deployment = deployment,
            openOrders = latestOpenOrders,
            newQuantity = row.previewQuantity
        ) ?: run {
            setApplyError(deploymentId, "Could not build resize request")
            return
        }
        applyingDeploymentIds.add(deploymentId)
        applyErrors.remove(deploymentId)
        publishUi()

        val sessionDate = deployment.touchTurnSession?.sessionDate ?: currentSessionDateIso()
        val debitResult = liquidityBucketRepository.debitAllocation(
            currencyCode = deployment.currencyCode,
            sessionDate = sessionDate,
            deploymentId = deploymentId,
            symbol = deployment.symbol,
            amount = allocationDollars
        )
        if (debitResult.isFailure) {
            applyingDeploymentIds.remove(deploymentId)
            setApplyError(deploymentId, debitResult.exceptionOrNull()?.message ?: "Debit failed")
            return
        }

        val resizeResult = execution.resizeTouchTurnBracket(resizeRequest)
        applyingDeploymentIds.remove(deploymentId)
        if (resizeResult.isFailure) {
            liquidityBucketRepository.creditNoTradeSession(
                sessionId = "refund-${deploymentId}-${System.currentTimeMillis()}",
                deploymentId = deploymentId,
                symbol = deployment.symbol,
                currencyCode = deployment.currencyCode,
                sessionDate = sessionDate,
                maxDollars = allocationDollars,
                touchTurn = deployment.touchTurnSession?.copy(ordersPlacedForSession = false),
                creditedAtEpochMs = System.currentTimeMillis()
            )
            setApplyError(deploymentId, resizeResult.exceptionOrNull()?.message ?: "Resize failed")
            return
        }

        deploymentRepository.update(deploymentId) { current ->
            current.withTouchTurnBracketQuantity(row.previewQuantity)
        }
        allocations.remove(deploymentId)
        applyErrors.remove(deploymentId)
        liquidityBucketRepository.flushPersistence()
        publishUi()
    }

    private fun setApplyError(deploymentId: String, message: String) {
        applyErrors[deploymentId] = message
        publishUi()
    }

    private fun refreshQuotes(deployments: List<StrategyDeployment>) {
        val gateway = brokerGateway ?: return
        deployments
            .filter { it.isTouchTurn && it.status == DeploymentStatus.RUNNING }
            .forEach { deployment ->
                val norm = SymbolMarkets.normalizeSymbol(deployment.symbol)
                if (latestQuotes[norm] == null) {
                    latestQuotes = gateway.quotes.value
                }
            }
    }

    private fun publishUi() {
        safeUiEmit(AppScreen.LIQUIDITY, "publishUi") {
            val sessionDate = currentSessionDateIso()
            val built = LiquidityAllocatorMapper.buildUiState(
                deployments = latestDeployments,
                openOrders = latestOpenOrders,
                quotes = latestQuotes,
                bucketState = latestBucketState,
                sessionDate = sessionDate,
                selectedCurrency = selectedCurrency,
                allocations = allocations.toMap(),
                applyingDeploymentIds = applyingDeploymentIds.toSet(),
                applyErrors = applyErrors.toMap(),
                sessionRollupCache = sessionRollupCache,
            )
            if (built.currencyOptions.isNotEmpty() &&
                built.currencyOptions.none { it.currencyCode == selectedCurrency }
            ) {
                selectedCurrency = built.currencyOptions.first().currencyCode
            }
            val next = built.copy(selectedCurrency = selectedCurrency)
            val prev = _uiState.value
            if (next.copy(lastUpdatedEpochMs = 0L) == prev.copy(lastUpdatedEpochMs = 0L)) return@safeUiEmit
            _uiState.value = next.copy(lastUpdatedEpochMs = System.currentTimeMillis())
        }
    }

    private companion object {
        const val QUOTE_UI_REFRESH_INTERVAL_MS = 100L
    }
}

private fun StrategyDeployment.withTouchTurnBracketQuantity(quantity: Int): StrategyDeployment {
    val session = touchTurnSession ?: return this
    return copy(touchTurnSession = session.copy(plannedQuantity = quantity))
}
